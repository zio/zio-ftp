package zio.ftp

import java.io.{ File, IOException }
import java.util

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.{ SFTPClient => JSFTPClient, _ }
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import zio.blocking.{ Blocking, effectBlocking }
import zio.ftp.Settings.{ KeyFileSftpIdentity, RawKeySftpIdentity, SFtpSettings, SftpIdentity }
import zio.stream.{ Stream, ZSink, ZStream, ZStreamChunk }
import zio.{ Chunk, URIO, ZIO, ZManaged }

import scala.jdk.CollectionConverters._

final class SFtp(unsafeClient: JSFTPClient) extends FtpClient[JSFTPClient] {

  def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]] =
    effectBlocking(
      Option(unsafeClient.statExistence(path)).map(FtpResource(path, _))
    ).refineToOrDie[IOException]

  def readFile(path: String, chunkSize: Int): ZStreamChunk[Blocking, IOException, Byte] =
    ZStreamChunk(for {
      remoteFile <- ZStream.fromEffect(
                     execute(_.open(path, util.EnumSet.of(OpenMode.READ)))
                       .refineToOrDie[IOException]
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
      .refineToOrDie[IOException]

  def rmdir(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.rmdir(path))
      .refineToOrDie[IOException]

  def mkdir(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.mkdirs(path))
      .refineToOrDie[IOException]

  def ls(path: String): ZStream[Blocking, IOException, FtpResource] =
    ZStream
      .fromEffect(
        execute(_.ls(path).asScala)
          .catchSome {
            case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE =>
              ZIO.succeed(scala.collection.mutable.Buffer.empty[RemoteResourceInfo])
          }
          .refineToOrDie[IOException]
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
          .refineToOrDie[IOException]
      )
      .flatMap(Stream.fromIterable)
      .flatMap { f =>
        if (f.isDirectory) lsDescendant(f.getPath)
        else Stream(FtpResource(f))
      }

  def upload[R <: Blocking](path: String, source: ZStreamChunk[R, Throwable, Byte]): ZIO[R, Throwable, Unit] =
    for {
      remoteFile <- execute(_.open(path, util.EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)))
                     .refineToOrDie[IOException]

      os: java.io.OutputStream = new remoteFile.RemoteFileOutputStream() {

        override def close(): Unit =
          try {
            remoteFile.close()
          } finally {
            super.close()
          }
      }
      _ <- source.run(ZSink.fromOutputStream(os))
    } yield ()

  override def execute[T](f: JSFTPClient => T): ZIO[Blocking, Throwable, T] = effectBlocking(f(unsafeClient))
}

object SFtp {

  def connect(settings: SFtpSettings): ZManaged[Blocking, ConnectionError, FtpClient[JSFTPClient]] = {
    val ssh = new SSHClient(settings.sshConfig)
    import settings._
    ZManaged.make(
      effectBlocking {
        if (!strictHostKeyChecking)
          ssh.addHostKeyVerifier(new PromiscuousVerifier)
        else
          knownHosts.map(new File(_)).foreach(ssh.loadKnownHosts)

        ssh.connect(host, port)

        if (settings.credentials.password != "" && sftpIdentity.isEmpty)
          ssh.authPassword(settings.credentials.username, settings.credentials.password)

        sftpIdentity.foreach(setIdentity(_, settings.credentials.username)(ssh))

        new SFtp(ssh.newSFTPClient())
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
