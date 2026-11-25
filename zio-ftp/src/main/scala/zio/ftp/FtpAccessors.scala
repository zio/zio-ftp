package zio.ftp

import java.io.IOException
import zio.ZIO
import zio.stream.ZStream

/**
 * Operations on an FTP or SFTP server.
 *
 * The type parameter `A` is the client type that `execute` passes to its function: `UnsecureFtp.Client` for FTP, `SecureFtp.Client` for SFTP, and `Unit` for the test stub.
 *
 * Each operation returns a `ZIO` or a `ZStream` whose error type is `IOException`. An exception that is not an `IOException` becomes a defect, not a failure.
 */
trait FtpAccessors[+A] {

  /**
   * Runs `f` on the client as a blocking effect. An `IOException` thrown by `f` becomes a failure, and any other exception becomes a defect. The test stub runs `f` with `ZIO.succeed`, so every exception that `f` throws becomes a defect.
   *
   * @param f unsafe function to lift
   * @tparam T return value
   */
  def execute[T](f: A => T): ZIO[Any, IOException, T]

  /**
   * Returns the resource at `path`, or `None` when the path does not exist. On FTP, the result is also `None` whenever the server does not answer the `MLST` command with a positive completion reply, for example when the server does not support `MLST`. On SFTP the returned resource has `isDirectory` set to `None`.
   * If the operation failed an error will be emitted
   *
   * @param path absolute path of the file
   */
  def stat(path: String): ZIO[Any, IOException, Option[FtpResource]]

  /**
   * Read a file by using stream. If the operation failed, an error will be emitted
   *
   * @param path absolute path of a file
   * @param chunkSize default chunk size is 2048 bytes
   * @param fileOffset optional initial offset in bytes
   */
  def readFile(path: String, chunkSize: Int = 2048, fileOffset: Long = 0): ZStream[Any, IOException, Byte]

  /**
   * Delete a file on a server. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the file
   */
  def rm(path: String): ZIO[Any, IOException, Unit]

  /**
   * Delete a directory. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the directory
   */
  def rmdir(path: String): ZIO[Any, IOException, Unit]

  /**
   * Create a directory. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the directory
   */
  def mkdir(path: String): ZIO[Any, IOException, Unit]

  /**
   * List of files / directories. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the directory
   */
  def ls(path: String): ZStream[Any, IOException, FtpResource]

  /**
   * Lists the files below a base directory, recursively. Directories are not emitted.
   * If the operation failed, an error will be emitted
   *
   * @param path absolute path of the directory
   */
  def lsDescendant(path: String): ZStream[Any, IOException, FtpResource]

  /**
   * Save a data stream. If the operation failed, an error will be emitted
   *
   * @param path absolute path of file to store
   * @param source data stream to store
   * @tparam R environment required by the specified stream source. The upload itself needs no extra environment
   */
  def upload[R](path: String, source: ZStream[R, Throwable, Byte]): ZIO[R, IOException, Unit]

  /**
   * Renames a file/directory. If the operation failed, an error will be emitted
   *
   * @param oldPath absolute path of the file/directory to rename
   * @param newPath absolute path of the file/directory destination.
   */
  def rename(oldPath: String, newPath: String): ZIO[Any, IOException, Unit]

}
