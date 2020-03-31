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

import java.io.{ IOException, InputStream }

import org.apache.commons.net.ftp.{ FTP, FTPClient => JFTPClient, FTPSClient => JFTPSClient }
import zio.blocking.{ Blocking, effectBlocking }
import zio.ftp.FtpSettings.UnsecureFtpSettings
import zio.stream.{ Stream, ZStream, ZStreamChunk }
import zio.{ Task, ZIO, ZManaged }

/**
 * Unsecure Ftp client wrapper
 *
 * All ftp methods exposed are lift into ZIO or ZStream, which required a Blocking Environment
 * since the underlying java client only provide blocking methods.
 *
 */
final private class UnsecureFtp(unsafeClient: JFTPClient) extends FtpClient[JFTPClient] {

  def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]] =
    execute(c => Option(c.mlistFile(path))).map(_.map(FtpResource(_)))

  def readFile(path: String, chunkSize: Int = 2048): ZStreamChunk[Blocking, IOException, Byte] =
    ZStreamChunk(for {
      is <- ZStream.fromEffect(
             execute(c => Option(c.retrieveFileStream(path)))
               .flatMap(
                 _.fold[ZIO[Any, InvalidPathError, InputStream]](
                   ZIO.fail(InvalidPathError(s"File does not exist $path"))
                 )(
                   ZIO.succeed(_)
                 )
               )
           )

      safeIs <- ZStream.managed(ZManaged.fromAutoCloseable(Task(is))).mapError(e => new IOException(e.getMessage, e))

      data <- ZStream.fromInputStream(safeIs, chunkSize).chunks
    } yield data)

  def rm(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.deleteFile(path))
      .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot delete file : $path"))
      .unit

  def rmdir(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.removeDirectory(path))
      .filterOrFail(b => b)(InvalidPathError(s"Path is invalid. Cannot delete directory : $path"))
      .unit

  def mkdir(path: String): ZIO[Blocking, IOException, Unit] =
    execute(_.makeDirectory(path))
      .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot create directory : $path"))
      .unit

  def ls(path: String): ZStream[Blocking, IOException, FtpResource] =
    ZStream
      .fromEffect(execute(_.listFiles(path).toList))
      .flatMap(Stream.fromIterable(_))
      .map(FtpResource(_, Some(path)))

  def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource] =
    ZStream
      .fromEffect(execute(_.listFiles(path).toList))
      .flatMap(Stream.fromIterable(_))
      .flatMap { f =>
        if (f.isDirectory) {
          val dirPath = Option(path).filter(_.endsWith("/")).fold(s"$path/${f.getName}")(p => s"$p${f.getName}")
          lsDescendant(dirPath)
        } else
          Stream(FtpResource(f, Some(path)))
      }

  def upload[R <: Blocking](path: String, source: ZStreamChunk[R, Throwable, Byte]): ZIO[R, IOException, Unit] =
    source.toInputStream
      .mapError(new IOException(_))
      .use(is =>
        execute(_.storeFile(path, is))
          .filterOrFail(identity)(InvalidPathError(s"Path is invalid. Cannot upload data to : $path"))
          .unit
      )

  override def execute[T](f: JFTPClient => T): ZIO[Blocking, IOException, T] =
    effectBlocking(f(unsafeClient)).refineToOrDie[IOException]
}

object UnsecureFtp {

  def connect(settings: UnsecureFtpSettings): ZManaged[Blocking, ConnectionError, FtpClient[JFTPClient]] =
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
        new UnsecureFtp(ftpClient) -> success
      }.mapError(e => ConnectionError(e.getMessage, e))
        .filterOrFail(_._2)(ConnectionError(s"Fail to connect to server ${settings.host}:${settings.port}"))
        .map(_._1)
    )(client => client.execute(_.logout()).ignore >>= (_ => client.execute(_.disconnect()).ignore))
}
