/*
 * Copyright 2017-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.ftp

import java.io.{ File, IOException }
import java.util
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.{ SFTPClient, _ }
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.apache.commons.net.DefaultSocketFactory
import zio.ftp.SecureFtp.Client
import zio.stream.{ ZSink, ZStream }
import zio._

import scala.jdk.CollectionConverters._
import zio.ZIO.{ acquireRelease, attemptBlockingIO, fromAutoCloseable, scoped }
import java.io.InputStream

/**
 * Secure Ftp client wrapper
 *
 * All ftp methods exposed are lift into ZIO or ZStream, which required a Blocking Environment
 * since the underlying java client only provide blocking methods.
 */
final private class SecureFtp(unsafeClient: Client) extends FtpAccessors[Client] {

  def stat(path: String): ZIO[Any, IOException, Option[FtpResource]] =
    execute(c => Option(c.statExistence(path)).map(FtpResource(path, _)))

  override def readFileInputStream(path: String, fileOffset: Long): ZIO[Scope, IOException, InputStream] =
    ZIO.fromAutoCloseable(execute(_.open(path, util.EnumSet.of(OpenMode.READ)))).flatMap { remoteFile =>
      ZIO.fromAutoCloseable(ZIO.attemptBlockingIO(new remoteFile.ReadAheadRemoteFileInputStream(64, fileOffset)))
    }

  def readFile(path: String, chunkSize: Int, fileOffset: Long): ZStream[Any, IOException, Byte] =
    ZStream.fromInputStreamScoped(readFileInputStream(path, fileOffset))

  def rm(path: String): ZIO[Any, IOException, Unit] =
    execute(_.rm(path))

  def rmdir(path: String): ZIO[Any, IOException, Unit] =
    execute(_.rmdir(path))

  def mkdir(path: String): ZIO[Any, IOException, Unit] =
    execute(_.mkdirs(path))

  def ls(path: String): ZStream[Any, IOException, FtpResource] =
    ZStream
      .fromZIO(
        execute(_.ls(path).asScala)
          .catchSome {
            case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE =>
              ZIO.succeed(scala.collection.mutable.Buffer.empty[RemoteResourceInfo])
          }
      )
      .flatMap(ZStream.fromIterable(_))
      .map(FtpResource.fromResource)

  def rename(oldPath: String, newPath: String): ZIO[Any, IOException, Unit] =
    execute(_.rename(oldPath, newPath))

  def lsDescendant(path: String): ZStream[Any, IOException, FtpResource] =
    ZStream
      .fromZIO(
        execute(_.ls(path).asScala)
          .catchSome {
            case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE =>
              ZIO.succeed(scala.collection.mutable.Buffer.empty[RemoteResourceInfo])
          }
      )
      .flatMap(ZStream.fromIterable(_))
      .flatMap { f =>
        if (f.isDirectory) lsDescendant(f.getPath)
        else ZStream.succeed(FtpResource.fromResource(f))
      }

  def upload[R](path: String, source: ZStream[R, Throwable, Byte]): ZIO[R, IOException, Unit] =
    for {
      remoteFile <- execute(_.open(path, util.EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)))
      _          <- scoped[R] {
                      fromAutoCloseable(ZIO.succeed(new remoteFile.RemoteFileOutputStream() {
                        override def close(): Unit =
                          try remoteFile.close()
                          finally super.close()
                      }))
                        .flatMap(os => source.run(ZSink.fromOutputStream(os)).mapError(new IOException(_)))
                    }
    } yield ()

  override def execute[T](f: Client => T): ZIO[Any, IOException, T] =
    attemptBlockingIO(f(unsafeClient))
}

object SecureFtp {
  type Client = SFTPClient

  def connect(settings: SecureFtpSettings): ZIO[Scope, ConnectionError, FtpAccessors[Client]] = {
    val ssh = new SSHClient(settings.sshConfig)
    import settings._

    acquireRelease(
      attemptBlockingIO {
        settings.proxy.foreach(p => ssh.setSocketFactory(new DefaultSocketFactory(p)))

        if (!strictHostKeyChecking)
          ssh.addHostKeyVerifier(new PromiscuousVerifier)
        else
          knownHosts.map(new File(_)).foreach(ssh.loadKnownHosts)

        ssh.connect(host, port)

        sftpIdentity
          .fold(ssh.authPassword(credentials.username, credentials.password))(
            setIdentity(_, credentials.username)(ssh)
          )

        new SecureFtp(ssh.newSFTPClient())
      }.mapError(ConnectionError(s"Fail to connect to server ${settings.host}:${settings.port}", _))
    )(
      _.execute(_.close()).ignore
        .flatMap(_ => attemptBlockingIO(ssh.disconnect()).whenZIO(ZIO.attempt(ssh.isConnected)).ignore)
    )
  }

  private[this] def setIdentity(identity: SftpIdentity, username: String)(ssh: SSHClient): Unit = {
    def bats(array: Array[Byte]): String = new String(array, "UTF-8")

    val passphrase =
      identity.passphrase.map(pass => PasswordUtils.createOneOff(bats(pass.getBytes).toCharArray)).orNull

    val keyProvider = identity match {
      case id: RawKeySftpIdentity  =>
        ssh.loadKeys(bats(id.privateKey.getBytes), id.publicKey.map(p => bats(p.getBytes)).orNull, passphrase)
      case id: KeyFileSftpIdentity =>
        ssh.loadKeys(id.privateKey.toString, passphrase)
    }
    ssh.authPublickey(username, keyProvider)
  }
}
