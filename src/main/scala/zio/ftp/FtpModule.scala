package zio.ftp

import java.io.IOException
import net.schmizz.sshj.sftp.{ SFTPClient => JSFTPClient }
import org.apache.commons.net.ftp.{ FTPClient => JFTPClient }
import zio.ZIO
import zio.blocking.Blocking
import zio.stream.{ ZStream, ZStreamChunk }

trait UnsecureFtpModule extends Serializable {
  val ftp: UnsecureFtpModule.Service
}

object UnsecureFtpModule extends Serializable {
  trait Service extends FtpClient[JFTPClient]

  trait Live extends UnsecureFtpModule {
    protected val client: FtpClient[JFTPClient]

    override val ftp: UnsecureFtpModule.Service = new UnsecureFtpModule.Service {
      override def execute[T](f: JFTPClient => T): ZIO[Blocking, IOException, T]       = client.execute(f)
      override def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]] = client.stat(path)

      override def readFile(path: String, chunkSize: Int): ZStreamChunk[Blocking, IOException, Byte] =
        client.readFile(path, chunkSize)
      override def rm(path: String): ZIO[Blocking, IOException, Unit]                      = client.rm(path)
      override def rmdir(path: String): ZIO[Blocking, IOException, Unit]                   = client.rmdir(path)
      override def mkdir(path: String): ZIO[Blocking, IOException, Unit]                   = client.mkdir(path)
      override def ls(path: String): ZStream[Blocking, IOException, FtpResource]           = client.ls(path)
      override def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource] = client.lsDescendant(path)

      override def upload[R <: Blocking](
        path: String,
        source: ZStreamChunk[R, Throwable, Byte]
      ): ZIO[R, IOException, Unit] = client.upload(path, source)
    }
  }
}

trait SecureFtpModule extends Serializable {
  val sftp: SecureFtpModule.Service
}

object SecureFtpModule extends Serializable {
  trait Service extends FtpClient[JSFTPClient]

  trait Live extends SecureFtpModule {
    protected val client: FtpClient[JSFTPClient]

    override val sftp: SecureFtpModule.Service = new SecureFtpModule.Service {
      override def execute[T](f: JSFTPClient => T): ZIO[Blocking, IOException, T]      = client.execute(f)
      override def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]] = client.stat(path)

      override def readFile(path: String, chunkSize: Int): ZStreamChunk[Blocking, IOException, Byte] =
        client.readFile(path, chunkSize)
      override def rm(path: String): ZIO[Blocking, IOException, Unit]                      = client.rm(path)
      override def rmdir(path: String): ZIO[Blocking, IOException, Unit]                   = client.rmdir(path)
      override def mkdir(path: String): ZIO[Blocking, IOException, Unit]                   = client.mkdir(path)
      override def ls(path: String): ZStream[Blocking, IOException, FtpResource]           = client.ls(path)
      override def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource] = client.lsDescendant(path)

      override def upload[R <: Blocking](
        path: String,
        source: ZStreamChunk[R, Throwable, Byte]
      ): ZIO[R, IOException, Unit] = client.upload(path, source)
    }
  }
}
