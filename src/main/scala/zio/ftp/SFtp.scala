package zio.ftp

import java.io.{ File, IOException }
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission._

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp._
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.xfer.FilePermission._
import zio.blocking.Blocking
import zio.ftp.settings.{ KeyFileSftpIdentity, RawKeySftpIdentity, SFtpSettings, SftpIdentity }
import zio.stream.{ Stream, ZSink, ZStream }
import zio.{ Chunk, Managed, ZIO }
import scala.jdk.CollectionConverters._

object SFtp {

  def connect(settings: SFtpSettings): Managed[IOException, SFTPClient] = {
    val ssh = new SSHClient(settings.sshConfig)

    Managed.make(taskIO {
      import settings._

      if (!strictHostKeyChecking)
        ssh.addHostKeyVerifier(new PromiscuousVerifier)
      else
        knownHosts.map(new File(_)).foreach(ssh.loadKnownHosts)

      ssh.connect(host, port)

      if (credentials.password != "" && sftpIdentity.isEmpty)
        ssh.authPassword(credentials.username, credentials.password)

      sftpIdentity.foreach(setIdentity(_, credentials.username)(ssh))

      ssh.newSFTPClient()
    }.orDieWith(ex => new IOException(s"Fail to connect to server ${settings.host}:${settings.port}", ex)))(
      client =>
        taskIO {
          client.close()
          if (ssh.isConnected) ssh.disconnect()
        }.either.unit
    )
  }

  private[this] def setIdentity(identity: SftpIdentity, username: String)(ssh: SSHClient): Unit = {
    def bats(array: Array[Byte]): String = new String(array, "UTF-8")

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

  def listFiles(basePath: String, predicate: FtpFile => Boolean = _ => true)(
    client: SFTPClient
  ): ZStream[Any, IOException, FtpFile] = {
    val path = if (!basePath.isEmpty && basePath.head != '/') s"/$basePath" else basePath
    val filter = new RemoteResourceFilter {
      override def accept(r: RemoteResourceInfo): Boolean = predicate(SftpFileOps(r))
    }

    ZStream
      .fromEffect(taskIO(client.ls(path, filter).asScala))
      .flatMap(Stream.fromIterable)
      .flatMap(f => if (f.isDirectory) listFiles(f.getPath)(client) else Stream(SftpFileOps(f)))
      .catchAll {
        case ex: SFTPException if ex.getStatusCode == Response.StatusCode.NO_SUCH_FILE => Stream.empty
        case other                                                                     => Stream.fail(other)
      }
  }

  def stat(path: String)(client: SFTPClient): ZIO[Any, IOException, Option[FtpFile]] =
    taskIO(client.stat(path)).either
      .map(r => r.map(r => SftpFileOps(path, r)).toOption)

  def readFile[E](path: String, chunkSize: Int = 2048)(client: SFTPClient): ZStream[Blocking, IOException, Byte] =
    for {
      remoteFile <- ZStream.fromEffect(
                     taskIO(client.open(path, java.util.EnumSet.of(OpenMode.READ)))
                       .orDieWith(ex => new IOException(s"File does not exist $path", ex))
                   )

      is: java.io.InputStream = new remoteFile.ReadAheadRemoteFileInputStream(64) {

        override def close(): Unit =
          try {
            super.close()
          } finally {
            remoteFile.close()
          }
      }

      input <- Stream.fromInputStream(is, chunkSize).chunks.flatMap(Stream.fromChunk)
    } yield input

  def rm(path: String)(client: SFTPClient): ZIO[Any, IOException, Unit] =
    taskIO(client.rm(path))
      .orDieWith(ex => new IOException(s"Path is invalid. Cannot delete file : $path", ex))

  def rmdir(path: String)(client: SFTPClient): ZIO[Any, IOException, Unit] =
    taskIO(client.rmdir(path))
      .orDieWith(ex => new IOException(s"Path is invalid. Cannot delete directory : $path", ex))

  def mkdirs(path: String)(client: SFTPClient): ZIO[Any, IOException, Unit] =
    taskIO(client.mkdirs(path))
      .orDieWith(ex => new IOException(s"Path is invalid. Cannot create directory : $path", ex))

  def upload(path: String, source: Stream[Throwable, Chunk[Byte]])(client: SFTPClient): ZIO[Blocking, Throwable, Unit] =
    for {
      remoteFile <- taskIO(client.open(path, java.util.EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)))
                     .orDieWith(ex => new IOException(s"Path is invalid. Cannot upload data to : $path", ex))

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

  object SftpFileOps {

    def apply(file: RemoteResourceInfo): FtpFile =
      FtpFile(
        file.getName,
        file.getPath,
        file.getAttributes.getSize,
        file.getAttributes.getMtime,
        posixFilePermissions(file.getAttributes)
      )

    def apply(path: String, attr: FileAttributes): FtpFile =
      FtpFile(
        path.substring(path.lastIndexOf("/") + 1, path.length),
        path,
        attr.getSize,
        attr.getMtime,
        posixFilePermissions(attr)
      )

    private val posixFilePermissions: FileAttributes => Set[PosixFilePermission] = { attr =>
      attr.getPermissions.asScala.collect {
        case USR_R => OWNER_READ
        case USR_W => OWNER_WRITE
        case USR_X => OWNER_EXECUTE
        case GRP_R => GROUP_READ
        case GRP_W => GROUP_WRITE
        case GRP_X => GROUP_EXECUTE
        case OTH_R => OTHERS_READ
        case OTH_W => OTHERS_WRITE
        case OTH_X => OTHERS_EXECUTE
      }.toSet
    }
  }
}
