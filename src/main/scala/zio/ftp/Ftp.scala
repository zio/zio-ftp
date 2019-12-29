package zio.ftp

import java.io.{ IOException, InputStream }
import java.nio.file.attribute.PosixFilePermission

import org.apache.commons.net.ftp.{ FTP, FTPFile, FTPFileFilter, FTPClient => JFTPClient, FTPSClient => JFTPSClient }
import zio.ftp.settings.FtpSettings
import zio.stream.{ Stream, ZStream, ZStreamChunk }
import zio.{ Chunk, Managed, UIO, ZIO }

object Ftp {

  trait FtpClient {
    private[ftp] val client: JFTPClient

    def mlistFile(path: String): FTPFile                              = client.mlistFile(path)
    def retrieveFileStream(path: String): InputStream                 = client.retrieveFileStream(path)
    def deleteFile(path: String): Boolean                             = client.deleteFile(path)
    def removeDirectory(path: String): Boolean                        = client.removeDirectory(path)
    def makeDirectory(path: String): Boolean                          = client.makeDirectory(path)
    def listFiles(path: String, filter: FTPFileFilter): List[FTPFile] = client.listFiles(path, filter).toList
    def storeFile(path: String, is: InputStream): Boolean             = client.storeFile(path, is)

    private[ftp] def logout(): Unit     = client.logout()
    private[ftp] def disconnect(): Unit = client.disconnect()
  }

  def connect(settings: FtpSettings): Managed[IOException, FtpClient] =
    Managed.make(
      taskIO {
        val ftpClient = if (settings.secure) new JFTPSClient() else new JFTPClient()
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
        success -> new FtpClient {
          val client = ftpClient
        }
      }.filterOrDie(_._1)(new IOException(s"Fail to connect to server ${settings.host}:${settings.port}"))
        .map(_._2)
    )(
      client =>
        UIO(client.logout())
          .flatMap { _ =>
            UIO(client.disconnect())
          }
    )

  def stat(path: String): ZIO[FtpClient, IOException, Option[FtpFile]] = ZIO.accessM[FtpClient] { client =>
    taskIO(
      Option(client.mlistFile(path)).map(FtpFileOps.apply)
    )
  }

  def readFile(path: String, chunkSize: Int = 2048): ZStream[FtpClient, IOException, Byte] =
    StreamAccess.accessM[FtpClient] { client =>
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
    }

  def rm(path: String): ZIO[FtpClient, IOException, Unit] = ZIO.accessM[FtpClient] { client =>
    taskIO(client.deleteFile(path))
      .filterOrDie(identity)(new IOException(s"Path is invalid. Cannot delete file : $path"))
      .unit
  }

  def rmdir(path: String): ZIO[FtpClient, IOException, Unit] = ZIO.accessM[FtpClient] { client =>
    taskIO(client.removeDirectory(path))
      .filterOrDie(b => b)(new IOException(s"Path is invalid. Cannot delete directory : $path"))
      .unit
  }

  def mkdir(path: String): ZIO[FtpClient, IOException, Unit] = ZIO.accessM[FtpClient] { client =>
    taskIO(client.makeDirectory(path))
      .filterOrDie(identity)(new IOException(s"Path is invalid. Cannot create directory : $path"))
      .unit
  }

  def listFiles(basePath: String, predicate: FtpFile => Boolean = _ => true): ZStream[FtpClient, Throwable, FtpFile] =
    StreamAccess.accessM[FtpClient] { client =>
      val rootPath = if (!basePath.isEmpty && basePath.head != '/') s"/$basePath" else basePath
      val filter = new FTPFileFilter {
        override def accept(file: FTPFile): Boolean = predicate(FtpFileOps(file))
      }

      ZStream
        .fromEffect(taskIO(client.listFiles(rootPath, filter)))
        .flatMap(Stream.fromIterable)
        .flatMap { f =>
          if (f.isDirectory)
            listFiles(s"$basePath/${f.getName}", predicate)
          else
            Stream(FtpFileOps(f))
        }
    }

  def upload(path: String, source: Stream[Throwable, Chunk[Byte]]): ZIO[FtpClient, Throwable, Unit] =
    ZIO.accessM[FtpClient] { client =>
      ZStreamChunk(source).toInputStream
        .use(
          is =>
            taskIO(client.storeFile(path, is))
              .filterOrDie(identity)(new IOException(s"Path is invalid. Cannot upload data to : $path"))
              .unit
        )
    }

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
