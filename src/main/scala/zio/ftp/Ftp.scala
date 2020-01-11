package zio.ftp

import java.io.{File, FileOutputStream, IOException}

import zio.blocking.Blocking
import zio.nio.file.{Files, Path}
import zio.stream.{ZSink, ZStream, ZStreamChunk}
import zio.{ZIO, ZManaged}

trait Ftp {
  val ftp: Ftp.Service
}

object Ftp extends Serializable {

  trait Service {
    def connect[A](settings: FtpSettings[A]): ZManaged[Blocking, ConnectionError, FtpClient[A]]
  }

  trait Live extends Service {
    def connect[A](settings: FtpSettings[A]): ZManaged[Blocking, ConnectionError, FtpClient[A]] =
      FtpClient.connect(settings)
  }

//  trait Test extends Service {
//    def connect[A >: Mock](settings: FtpSettings[A]): ZManaged[Blocking, ConnectionError, FtpClient[A]] = {
//      val _ = settings
//      ZManaged.succeed(FtpFileSystem)
//    }
//  }

  object FtpFileSystem extends FtpClient[Unit] {
    override def execute[T](f: Unit => T): ZIO[Blocking, IOException, T] = ZIO.succeed(f(():Unit))

    override def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]] =
      Files
        .exists(Path(path))
        .flatMap {
          case true  => get(Path(path)).map(Option(_))
          case false => ZIO.succeed(Option.empty[FtpResource])
        }
        .refineToOrDie[IOException]

    override def readFile(path: String, chunkSize: Int): ZStreamChunk[Blocking, IOException, Byte] =
      ZStreamChunk(ZStream.fromEffect(Files.readAllBytes(Path(path))))

    override def rm(path: String): ZIO[Blocking, IOException, Unit] =
      Files.delete(Path(path))

    override def rmdir(path: String): ZIO[Blocking, IOException, Unit] =
      rm(path)

    override def mkdir(path: String): ZIO[Blocking, IOException, Unit] =
      Files.createDirectories(Path(path)).refineToOrDie[IOException]

    override def ls(path: String): ZStream[Blocking, IOException, FtpResource] =
      Files
        .list(Path(path))
        .mapM(get)
        .mapError(new IOException(_))

    private def get(p: Path): ZIO[Blocking, Exception, FtpResource] =
      for {
        permissions  <- Files.getPosixFilePermissions(p)
        isDir        <- Files.isDirectory(p).map(Some(_))
        lastModified <- Files.getLastModifiedTime(p).map(_.toMillis)
        size         <- Files.size(p)
      } yield FtpResource(p.toString, size, lastModified, permissions, isDir)

    override def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource] =
      Files
        .find(Path(path))((_, _) => true)
        .mapM(get)
        .mapError(new IOException(_))

    override def upload[R <: Blocking](
      path: String,
      source: ZStreamChunk[R, Throwable, Byte]
    ): ZIO[R, IOException, Unit] =
      source
        .run(ZSink.fromOutputStream(new FileOutputStream(new File(path))))
        .unit
        .refineToOrDie[IOException]
  }
}
