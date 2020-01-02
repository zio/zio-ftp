package zio.ftp

import java.io.{ IOException, InputStream }

import org.apache.commons.net.ftp.{ FTP, FTPClient => JFTPClient, FTPSClient => JFTPSClient }
import zio.blocking.{ Blocking, effectBlocking }
import zio.ftp.Settings.FtpSettings
import zio.stream.{ Stream, ZStream, ZStreamChunk }
import zio.{ IO, Task, ZIO, ZManaged }

final class Ftp(unsafeClient: JFTPClient) extends FtpClient[JFTPClient] {

  def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]] =
    effectBlocking(
      Option(unsafeClient.mlistFile(path)).map(FtpResource(_))
    ).refineToOrDie[IOException]

  def readFile(path: String, chunkSize: Int = 2048): ZStreamChunk[Blocking, IOException, Byte] =
    ZStreamChunk(for {
      is <- ZStream.fromEffect(
             effectBlocking(Option(unsafeClient.retrieveFileStream(path)))
               .refineToOrDie[IOException]
               .flatMap(
                 _.fold[ZIO[Any, InvalidPathError, InputStream]](
                   ZIO.fail(InvalidPathError(s"File does not exist $path"))
                 )(
                   ZIO.succeed
                 )
               )
           )
      data <- ZStream.fromInputStream(is, chunkSize).chunks
    } yield data)

  def rm(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.deleteFile(path))
      .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot delete file : $path"))
      .refineToOrDie[IOException]
      .unit

  def rmdir(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.removeDirectory(path))
      .filterOrFail(b => b)(InvalidPathError(s"Path is invalid. Cannot delete directory : $path"))
      .refineToOrDie[IOException]
      .unit

  def mkdir(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.makeDirectory(path))
      .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot create directory : $path"))
      .refineToOrDie[IOException]
      .unit

  def ls(path: String): ZStream[Blocking, IOException, FtpResource] =
    ZStream
      .fromEffect(execute(_.listFiles(path).toList).refineToOrDie[IOException])
      .flatMap(Stream.fromIterable)
      .map(FtpResource(_, Some(path)))

  def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource] =
    ZStream
      .fromEffect(execute(_.listFiles(path).toList).refineToOrDie[IOException])
      .flatMap(Stream.fromIterable)
      .flatMap { f =>
        if (f.isDirectory) {
          val dirPath = Option(path).filter(_.endsWith("/")).fold(s"$path/${f.getName}")(p => s"$p${f.getName}")
          lsDescendant(dirPath)
        } else
          Stream(FtpResource(f, Some(path)))
      }

  def upload[R <: Blocking](path: String, source: ZStreamChunk[R, Throwable, Byte]): ZIO[R, Throwable, Unit] =
    source.toInputStream
      .use(
        is =>
          execute(_.storeFile(path, is))
            .refineToOrDie[IOException]
            .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot upload data to : $path"))
            .unit
      )

  override def execute[T](f: JFTPClient => T): ZIO[Blocking, Throwable, T] = effectBlocking(f(unsafeClient))
}

object Ftp {

  def connect(settings: FtpSettings): ZManaged[Blocking, ConnectionError, FtpClient[JFTPClient]] =
    ZManaged.make(
      effectBlocking {
        val ftpClient = if (settings.secure) new JFTPSClient() else new JFTPClient()
        settings.proxy.foreach(ftpClient.setProxy)
        ftpClient.connect(settings.host, settings.port)

        val success = ftpClient.login(settings.credentials.username, settings.credentials.password)

        if (settings.binary) {
          ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
        }

        if (settings.passiveMode) {
          ftpClient.enterLocalPassiveMode()
        }
        new Ftp(ftpClient) -> success
      }.mapError(ConnectionError(_))
        .filterOrFail(_._2)(ConnectionError(s"Fail to connect to server ${settings.host}:${settings.port}"))
        .map(_._1)
    )(
      client =>
        client.execute(_.logout()).ignore >>= (
          _ => client.execute(_.disconnect()).ignore
        )
    )
}
