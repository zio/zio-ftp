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
import net.schmizz.sshj.sftp.{ SFTPClient => JSFTPClient, _ }
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.apache.commons.net.DefaultSocketFactory
import zio.blocking.{ Blocking, effectBlocking }
import zio.ftp.FtpSettings.{ KeyFileSftpIdentity, RawKeySftpIdentity, SecureFtpSettings, SftpIdentity }
import zio.stream.{ Stream, ZSink, ZStream, ZStreamChunk }
import zio.{ Chunk, URIO, ZIO, ZManaged }

import scala.jdk.CollectionConverters._

/**
 * Secure Ftp client wrapper
 *
 * All ftp methods exposed are lift into ZIO or ZStream, which required a Blocking Environment
 * since the underlying java client only provide blocking methods.
 */
final private class SecureFtp(unsafeClient: JSFTPClient) extends FtpClient[JSFTPClient] {

  def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]] =
    execute(c => Option(c.statExistence(path)).map(FtpResource(path, _)))

  def readFile(path: String, chunkSize: Int): ZStreamChunk[Blocking, IOException, Byte] =
    ZStreamChunk(for {
      remoteFile <- ZStream.fromEffect(
                     execute(_.open(path, util.EnumSet.of(OpenMode.READ)))
                   )

      is: java.io.InputStream = new remoteFile.ReadAheadRemoteFileInputStream(64) {

        override def close(): Unit =
          try {
            super.close()
          } finally {
            remoteFile.close()
          }
      }

      input <- Stream.fromInputStream(is, chunkSize).chunks
    } yield input)

  def rm(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.rm(path))

  def rmdir(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.rmdir(path))

  def mkdir(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.mkdirs(path))

  def ls(path: String): ZStream[Blocking, IOException, FtpResource] =
    ZStream
      .fromEffect(
        execute(_.ls(path).asScala)
          .catchSome {
            case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE =>
              ZIO.succeed(scala.collection.mutable.Buffer.empty[RemoteResourceInfo])
          }
      )
      .flatMap(Stream.fromIterable)
      .map(FtpResource(_))

  def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource] =
    ZStream
      .fromEffect(
        execute(_.ls(path).asScala)
          .catchSome {
            case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE =>
              ZIO.succeed(scala.collection.mutable.Buffer.empty[RemoteResourceInfo])
          }
      )
      .flatMap(Stream.fromIterable)
      .flatMap { f =>
        if (f.isDirectory) lsDescendant(f.getPath)
        else Stream(FtpResource(f))
      }

  def upload[R <: Blocking](path: String, source: ZStreamChunk[R, Throwable, Byte]): ZIO[R, IOException, Unit] =
    for {
      remoteFile <- execute(_.open(path, util.EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)))

      os: java.io.OutputStream = new remoteFile.RemoteFileOutputStream() {

        override def close(): Unit =
          try {
            remoteFile.close()
          } finally {
            super.close()
          }
      }
      _ <- source.run(ZSink.fromOutputStream(os)).mapError(new IOException(_))
    } yield ()

  override def execute[T](f: JSFTPClient => T): ZIO[Blocking, IOException, T] =
    effectBlocking(f(unsafeClient)).refineToOrDie[IOException]
}

object SecureFtp {

  def connect(settings: SecureFtpSettings): ZManaged[Blocking, ConnectionError, FtpClient[JSFTPClient]] = {
    val ssh = new SSHClient(settings.sshConfig)
    import settings._
    ZManaged.make(
      effectBlocking {
        settings.proxy.foreach(p => ssh.setSocketFactory(new DefaultSocketFactory(p)))

        if (!strictHostKeyChecking)
          ssh.addHostKeyVerifier(new PromiscuousVerifier)
        else
          knownHosts.map(new File(_)).foreach(ssh.loadKnownHosts)

        ssh.connect(host, port)

        if (settings.credentials.password != "" && sftpIdentity.isEmpty)
          ssh.authPassword(settings.credentials.username, settings.credentials.password)

        sftpIdentity.foreach(setIdentity(_, settings.credentials.username)(ssh))

        new SecureFtp(ssh.newSFTPClient())
      }.mapError(ConnectionError(s"Fail to connect to server ${settings.host}:${settings.port}", _))
    )(
      cli =>
        cli.execute(_.close()).ignore >>= (
          _ => effectBlocking(ssh.disconnect()).whenM(URIO(ssh.isConnected)).ignore
        )
    )
  }

  private[this] def setIdentity(identity: SftpIdentity, username: String)(ssh: SSHClient): Unit = {
    def bats(chunk: Chunk[Byte]): String = new String(chunk.toArray, "UTF-8")

    def initKey(f: OpenSSHKeyFile => Unit): Unit = {
      val key = new OpenSSHKeyFile
      f(key)
      ssh.authPublickey(username, key)
    }

    val passphrase =
      identity.privateKeyFilePassphrase.map(pass => PasswordUtils.createOneOff(bats(pass).toCharArray)).orNull

    identity match {
      case id: RawKeySftpIdentity =>
        initKey(_.init(bats(id.privateKey), id.publicKey.map(bats).orNull, passphrase))
      case id: KeyFileSftpIdentity =>
        initKey(_.init(new File(id.privateKey), passphrase))
    }
  }
}
