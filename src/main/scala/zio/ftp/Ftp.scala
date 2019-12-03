package zio.ftp

import java.io.{ IOException, InputStream }
import java.nio.file.attribute.PosixFilePermission

import org.apache.commons.net.ftp._
import zio.blocking.Blocking
import zio.ftp.settings.FtpSettings
import zio.stream.{ Stream, ZStream, ZStreamChunk }
import zio.{ Chunk, Managed, ZIO }

object Ftp {

  def connect(settings: FtpSettings): Managed[IOException, FTPClient] =
    Managed.make(
      taskIO {
        val ftpClient = if (settings.secure) new FTPSClient() else new FTPClient()
        settings.proxy.foreach(ftpClient.setProxy)
        ftpClient.connect(settings.host, settings.port)
        settings.configureConnection(ftpClient)

        val success = ftpClient.login(settings.credentials.username, settings.credentials.password)

        if (settings.binary) {
          ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
        }

        if (settings.passiveMode) {
          ftpClient.enterLocalPassiveMode()
        }

        success -> ftpClient
      }.filterOrDie(_._1)(new IOException(s"Fail to connect to server ${settings.host}:${settings.port}"))
        .map(_._2)
    )(
      client =>
        taskIO {
          client.logout()
          client.disconnect()
        }.either.unit
    )

  def stat(path: String)(client: FTPClient): ZIO[Any, IOException, Option[FtpFile]] =
    taskIO(
      Option(client.mlistFile(path)).map(FtpFileOps.apply)
    )

  def readFile(path: String, chunkSize: Int = 2048)(client: FTPClient): ZStream[Blocking, IOException, Byte] =
    for {
      is <- ZStream.fromEffect(
             taskIO(Option(client.retrieveFileStream(path)))
               .flatMap(
                 _.fold[ZIO[Any, IOException, InputStream]](ZIO.fail(new IOException(s"File does not exist $path")))(
                   ZIO.succeed
                 )
               )
           )
      data <- ZStream.fromInputStream(is, chunkSize).chunks.flatMap(zio.stream.Stream.fromChunk)
    } yield data

  def rm(path: String)(client: FTPClient): ZIO[Any, IOException, Unit] =
    taskIO(client.deleteFile(path))
      .filterOrDie(identity)(new IOException(s"Path is invalid. Cannot delete file : $path"))
      .unit

  def rmdir(path: String)(client: FTPClient): ZIO[Any, IOException, Unit] =
    taskIO(client.removeDirectory(path))
      .filterOrDie(b => b)(new IOException(s"Path is invalid. Cannot delete directory : $path"))
      .unit

  def mkdir(path: String)(client: FTPClient): ZIO[Any, IOException, Unit] =
    taskIO(client.makeDirectory(path))
      .filterOrDie(identity)(new IOException(s"Path is invalid. Cannot create directory : $path"))
      .unit

  def listFiles(basePath: String, predicate: FtpFile => Boolean = _ => true)(
    client: FTPClient
  ): ZStream[Any, Throwable, FtpFile] = {
    val rootPath = if (!basePath.isEmpty && basePath.head != '/') s"/$basePath" else basePath
    val filter = new FTPFileFilter {
      override def accept(file: FTPFile): Boolean = predicate(FtpFileOps(file))
    }

    ZStream
      .fromEffect(taskIO(client.listFiles(rootPath, filter).toList))
      .flatMap(Stream.fromIterable(_))
      .flatMap { f =>
        if (f.isDirectory)
          listFiles(f.getName)(client)
        else
          Stream(FtpFileOps(f))
      }
  }

  def upload(path: String, source: Stream[Throwable, Chunk[Byte]])(client: FTPClient): ZIO[Any, Throwable, Unit] =
    ZStreamChunk(source).toInputStream
      .use(
        is =>
          taskIO(client.storeFile(path, is))
            .filterOrDie(identity)(new IOException(s"Path is invalid. Cannot upload data to : $path"))
            .unit
      )

  object FtpFileOps {

    def apply(f: FTPFile): FtpFile = {
      val fileName = f.getName.substring(f.getName.lastIndexOf("/") + 1, f.getName.length)
      FtpFile(fileName, f.getName, f.getSize, f.getTimestamp.getTimeInMillis, getPosixFilePermissions(f))
    }

    private def getPosixFilePermissions(file: FTPFile) =
      Map(
        PosixFilePermission.OWNER_READ     -> file.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION),
        PosixFilePermission.OWNER_WRITE    -> file.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION),
        PosixFilePermission.OWNER_EXECUTE  -> file.hasPermission(FTPFile.USER_ACCESS, FTPFile.EXECUTE_PERMISSION),
        PosixFilePermission.GROUP_READ     -> file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.READ_PERMISSION),
        PosixFilePermission.GROUP_WRITE    -> file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.WRITE_PERMISSION),
        PosixFilePermission.GROUP_EXECUTE  -> file.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.EXECUTE_PERMISSION),
        PosixFilePermission.OTHERS_READ    -> file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.READ_PERMISSION),
        PosixFilePermission.OTHERS_WRITE   -> file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.WRITE_PERMISSION),
        PosixFilePermission.OTHERS_EXECUTE -> file.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.EXECUTE_PERMISSION)
      ).collect {
        case (perm, true) => perm
      }.toSet
  }
}
