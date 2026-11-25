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

package zio

import java.io.IOException

import zio.nio.file.{ Path => ZPath }
import zio.stream.ZStream

package object ftp {

  /**
   * The `FtpAccessors` service of an FTP server, with `UnsecureFtp.Client` as the client type. Provide it with `unsecure`.
   *
   * {{{
   * import java.io.IOException
   * import zio._
   * import zio.ftp._
   *
   * val found: ZIO[Ftp, IOException, Option[FtpResource]] = Ftp.stat("/pub/report.txt")
   * }}}
   *
   * @see [[https://zio.dev/zio-ftp/ ZIO FTP]]
   */
  type Ftp = FtpAccessors[UnsecureFtp.Client]

  /**
   * The `FtpAccessors` service of an SFTP server, with `SecureFtp.Client` as the client type. Provide it with `secure`.
   *
   * {{{
   * import java.io.IOException
   * import zio._
   * import zio.ftp._
   *
   * val listing: ZIO[SFtp, IOException, Chunk[FtpResource]] = SFtp.ls("/home/user").runCollect
   * }}}
   *
   * @see [[https://zio.dev/zio-ftp/ ZIO FTP]]
   */
  type SFtp = FtpAccessors[SecureFtp.Client]

  /**
   * The `FtpAccessors` service of the test stub, with `Unit` as the client type. Provide it with `stub`.
   */
  type StubFtp = FtpAccessors[Unit]

  /**
   * Accessors for the `Ftp` service in the environment. Each method takes the service from the environment and calls the method of the same name on it.
   */
  object Ftp {

    /**
     * Runs `f` on the FTP client of the `Ftp` service and returns its result. The call blocks, so ZIO runs it on the blocking pool.
     */
    def execute[T](f: UnsecureFtp.Client => T): ZIO[Ftp, IOException, T] =
      ZIO.serviceWithZIO(_.execute(f))

    /**
     * Returns the resource at `path` through the `Ftp` service, or `None` when the path does not exist.
     */
    def stat(path: String): ZIO[Ftp, IOException, Option[FtpResource]] =
      ZIO.serviceWithZIO(_.stat(path))

    /**
     * Deletes the file at `path` through the `Ftp` service.
     */
    def rm(path: String): ZIO[Ftp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rm(path))

    /**
     * Deletes the directory at `path` through the `Ftp` service.
     */
    def rmdir(path: String): ZIO[Ftp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rmdir(path))

    /**
     * Creates the directory at `path` through the `Ftp` service.
     */
    def mkdir(path: String): ZIO[Ftp, IOException, Unit] =
      ZIO.serviceWithZIO(_.mkdir(path))

    /**
     * Lists the entries of the directory at `path` through the `Ftp` service.
     *
     * {{{
     * import java.io.IOException
     * import zio._
     * import zio.ftp._
     *
     * val names: ZIO[Ftp, IOException, Chunk[String]] = Ftp.ls("/pub").map(_.path).runCollect
     * }}}
     */
    def ls(path: String): ZStream[Ftp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.ls(path))

    /**
     * Lists the files below `path`, recursively, through the `Ftp` service.
     */
    def lsDescendant(path: String): ZStream[Ftp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.lsDescendant(path))

    /**
     * Writes `source` to `path` through the `Ftp` service. The environment `R` of `source` is added to the environment of the result.
     *
     * {{{
     * import java.io.IOException
     * import zio._
     * import zio.ftp._
     * import zio.stream._
     *
     * val sent: ZIO[Ftp, IOException, Unit] =
     *   Ftp.upload("/pub/hello.txt", ZStream.fromChunk(Chunk.fromArray("hello".getBytes("UTF-8"))))
     * }}}
     */
    def upload[R](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[R with Ftp, IOException, Unit] =
      for {
        ftp <- ZIO.service[Ftp]
        _   <- ftp.upload(path, source)
      } yield ()

    /**
     * Streams the file at `path` through the `Ftp` service, starting at byte `fileOffset` and reading `chunkSize` bytes per chunk.
     *
     * {{{
     * import java.io.IOException
     * import zio._
     * import zio.ftp._
     * import zio.stream._
     *
     * val bytes: ZStream[Ftp, IOException, Byte] = Ftp.readFile("/pub/report.txt", chunkSize = 4096)
     * }}}
     */
    def readFile(path: String, chunkSize: Int = 2048, fileOffset: Long = 0): ZStream[Ftp, IOException, Byte] =
      ZStream.serviceWithStream(_.readFile(path, chunkSize, fileOffset))

    /**
     * Renames or moves `oldPath` to `newPath` through the `Ftp` service.
     */
    def rename(oldPath: String, newPath: String): ZIO[Ftp, Exception, Unit] =
      ZIO.serviceWithZIO(_.rename(oldPath, newPath))
  }

  /**
   * Accessors for the `SFtp` service in the environment. Each method takes the service from the environment and calls the method of the same name on it.
   */
  object SFtp {

    /**
     * Runs `f` on the SFTP client of the `SFtp` service and returns its result. The call blocks, so ZIO runs it on the blocking pool.
     */
    def execute[T](f: SecureFtp.Client => T): ZIO[SFtp, IOException, T] =
      ZIO.serviceWithZIO(_.execute(f))

    /**
     * Returns the resource at `path` through the `SFtp` service, or `None` when the path does not exist.
     */
    def stat(path: String): ZIO[SFtp, IOException, Option[FtpResource]] =
      ZIO.serviceWithZIO(_.stat(path))

    /**
     * Deletes the file at `path` through the `SFtp` service.
     */
    def rm(path: String): ZIO[SFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rm(path))

    /**
     * Deletes the directory at `path` through the `SFtp` service.
     */
    def rmdir(path: String): ZIO[SFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rmdir(path))

    /**
     * Creates the directory at `path` through the `SFtp` service.
     */
    def mkdir(path: String): ZIO[SFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.mkdir(path))

    /**
     * Lists the entries of the directory at `path` through the `SFtp` service.
     */
    def ls(path: String): ZStream[SFtp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.ls(path))

    /**
     * Lists the files below `path`, recursively, through the `SFtp` service.
     */
    def lsDescendant(path: String): ZStream[SFtp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.lsDescendant(path))

    /**
     * Writes `source` to `path` through the `SFtp` service. The environment `R` of `source` is added to the environment of the result.
     */
    def upload[R](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[SFtp with R, IOException, Unit] =
      for {
        ftp <- ZIO.service[SFtp]
        _   <- ftp.upload(path, source)
      } yield ()

    /**
     * Streams the file at `path` through the `SFtp` service, starting at byte `fileOffset` and reading `chunkSize` bytes per chunk.
     */
    def readFile(path: String, chunkSize: Int = 2048, fileOffset: Long = 0): ZStream[SFtp, IOException, Byte] =
      ZStream.serviceWithStream(_.readFile(path, chunkSize, fileOffset))

    /**
     * Renames or moves `oldPath` to `newPath` through the `SFtp` service.
     */
    def rename(oldPath: String, newPath: String): ZIO[SFtp, Exception, Unit] =
      ZIO.serviceWithZIO(_.rename(oldPath, newPath))
  }

  /**
   * Accessors for the `StubFtp` service in the environment. Each method takes the service from the environment and calls the method of the same name on it.
   */
  object StubFtp {

    /**
     * Runs `f` with `()` as the client of the `StubFtp` service and returns its result. No IO is done.
     */
    def execute[T](f: Unit => T): ZIO[StubFtp, IOException, T] =
      ZIO.serviceWithZIO(_.execute(f))

    /**
     * Returns the resource at `path` through the `StubFtp` service, or `None` when the path does not exist.
     */
    def stat(path: String): ZIO[StubFtp, IOException, Option[FtpResource]] =
      ZIO.serviceWithZIO(_.stat(path))

    /**
     * Deletes the file at `path` through the `StubFtp` service.
     */
    def rm(path: String): ZIO[StubFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rm(path))

    /**
     * Deletes the directory at `path` through the `StubFtp` service.
     */
    def rmdir(path: String): ZIO[StubFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rmdir(path))

    /**
     * Creates the directory at `path` through the `StubFtp` service.
     */
    def mkdir(path: String): ZIO[StubFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.mkdir(path))

    /**
     * Lists the entries of the directory at `path` through the `StubFtp` service.
     */
    def ls(path: String): ZStream[StubFtp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.ls(path))

    /**
     * Lists the files below `path`, recursively, through the `StubFtp` service.
     */
    def lsDescendant(path: String): ZStream[StubFtp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.lsDescendant(path))

    /**
     * Writes `source` to `path` through the `StubFtp` service. The environment `R` of `source` is added to the environment of the result.
     */
    def upload[R](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[StubFtp with R, IOException, Unit] =
      for {
        ftp <- ZIO.service[StubFtp]
        _   <- ftp.upload(path, source)
      } yield ()

    /**
     * Streams the file at `path` through the `StubFtp` service, starting at byte `fileOffset` and reading `chunkSize` bytes per chunk.
     */
    def readFile(path: String, chunkSize: Int = 2048, fileOffset: Long = 0): ZStream[StubFtp, IOException, Byte] =
      ZStream.serviceWithStream(_.readFile(path, chunkSize, fileOffset))

    /**
     * Renames or moves `oldPath` to `newPath` through the `StubFtp` service.
     */
    def rename(oldPath: String, newPath: String): ZIO[StubFtp, Exception, Unit] =
      ZIO.serviceWithZIO(_.rename(oldPath, newPath))
  }

  /**
   * A layer that connects to an FTP server with `settings` and provides the `Ftp` service. The connection is closed when the layer's scope closes.
   *
   * {{{
   * import zio._
   * import zio.ftp._
   *
   * val settings = UnsecureFtpSettings("ftp.example.com", 21, PasswordCredentials("user", "secret"))
   *
   * val layer: ZLayer[Any, ConnectionError, Ftp] = unsecure(settings)
   * }}}
   */
  def unsecure(settings: UnsecureFtpSettings): ZLayer[Any, ConnectionError, Ftp] =
    ZLayer.scoped(UnsecureFtp.connect(settings))

  /**
   * A layer that connects to an SFTP server with `settings` and provides the `SFtp` service. The connection is closed when the layer's scope closes.
   *
   * {{{
   * import zio._
   * import zio.ftp._
   *
   * val settings = SecureFtpSettings("sftp.example.com", 22, PasswordCredentials("user", "secret"))
   *
   * val layer: ZLayer[Any, ConnectionError, SFtp] = secure(settings)
   * }}}
   */
  def secure(settings: SecureFtpSettings): ZLayer[Any, ConnectionError, SFtp] =
    ZLayer.scoped(SecureFtp.connect(settings))

  /**
   * A layer that provides the `StubFtp` service, backed by the local directory `path`. Each FTP path is resolved under `path`. See `TestFtp.create` for the differences from a real server.
   *
   * {{{
   * import zio._
   * import zio.ftp._
   * import zio.nio.file.{ Path => ZPath }
   *
   * val layer: Layer[Any, StubFtp] = stub(ZPath("/tmp/ftp-root"))
   * }}}
   */
  def stub(path: ZPath): Layer[Any, StubFtp] =
    ZLayer.succeed(TestFtp.create(path))
}
