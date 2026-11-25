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

/**
 * Secure Ftp client wrapper
 *
 * All ftp methods exposed are lifted into ZIO or ZStream. The underlying java client only provides
 * blocking methods, so each call runs with `ZIO.attemptBlockingIO`, on ZIO's blocking thread pool,
 * and needs no environment beyond `Any`.
 */
sealed abstract class SecureFtp(unsafeClient: Client) extends FtpAccessors[Client] {

  /**
   * Returns the resource at `path`, or `None` when the path does not exist.
   */
  def stat(path: String): ZIO[Any, IOException, Option[FtpResource]] =
    execute(c => Option(c.statExistence(path)).map(FtpResource(path, _)))

  /**
   * Streams the bytes of the file at `path`, starting at byte `fileOffset`. The stream reads up to `chunkSize` bytes per chunk. The file is opened for reading when the stream starts.
   */
  def readFile(path: String, chunkSize: Int, fileOffset: Long): ZStream[Any, IOException, Byte] =
    for {
      remoteFile             <- ZStream.fromZIO(
                                  execute(_.open(path, util.EnumSet.of(OpenMode.READ)))
                                )

      is: java.io.InputStream = new remoteFile.ReadAheadRemoteFileInputStream(64, fileOffset) {

                                  override def close(): Unit =
                                    try super.close()
                                    finally remoteFile.close()
                                }

      input <- ZStream.fromInputStreamZIO(ZIO.succeed(is), chunkSize)
    } yield input

  /**
   * Deletes the file at `path`.
   */
  def rm(path: String): ZIO[Any, IOException, Unit] =
    execute(_.rm(path))

  /**
   * Deletes the directory at `path`. The SFTP server refuses to delete a directory that is not empty.
   */
  def rmdir(path: String): ZIO[Any, IOException, Unit] =
    execute(_.rmdir(path))

  /**
   * Creates the directory at `path`, together with any missing parent directories.
   */
  def mkdir(path: String): ZIO[Any, IOException, Unit] =
    execute(_.mkdirs(path))

  /**
   * Lists the entries of the directory at `path`. A missing directory gives an empty stream.
   */
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

  /**
   * Renames or moves the file or directory at `oldPath` to `newPath`.
   */
  def rename(oldPath: String, newPath: String): ZIO[Any, IOException, Unit] =
    execute(_.rename(oldPath, newPath))

  /**
   * Lists the files below the directory at `path`, recursively. Directories are not emitted. A missing top-level directory gives an empty stream.
   */
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

  /**
   * Writes `source` to the file at `path`, creating the file when it does not exist.
   *
   * The file is not truncated before writing. When the existing file is longer than the new data, the bytes after the end of the new data remain in the file.
   */
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

/**
 * Factory methods for the SFTP client.
 */
object SecureFtp {

  /**
   * The underlying client: sshj's `SFTPClient`.
   */
  type Client = SFTPClient

  /**
   * Wraps an open `SFTPClient` as a `SecureFtp`. The wrapper does not close the client.
   */
  def unsafe(c: Client): SecureFtp =
    new SecureFtp(c) {}

  /**
   * Connects to the SFTP server in `settings` and authenticates with its credentials. The SSH connection is closed when the enclosing `Scope` closes.
   *
   * A failure to connect or to authenticate fails with a `ConnectionError`.
   *
   * When `proxy` is set, the TCP connection is opened through it. When `strictHostKeyChecking` is `false`, the server's host key is accepted without checking. When it is `true`, the keys in `knownHosts` are loaded and the server's host key is checked against them.
   */
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

        credentials match {
          case PasswordCredentials(username, password) => ssh.authPassword(username, password)
          case KeyCredentials(username, identity)      => setIdentity(identity, username)(ssh)
        }

        new SecureFtp(ssh.newSFTPClient()) {}
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
