package zio.ftp

import java.io.IOException
import zio.ZIO
import zio.stream.ZStream

trait FtpAccessors[+A] {

  /**
   * Lift unsafe operation that does blocking IO into a pure value. If the operation failed an error will be emitted
   *
   * @param f unsafe function to lift
   * @tparam T return value
   */
  def execute[T](f: A => T): ZIO[Any, IOException, T]

  /**
   * Check existence of a file. If the file doesn't exist, the operation will succeed by returning an empty ftp resource
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
   */
  def readFile(path: String, chunkSize: Int = 2048): ZStream[Any, IOException, Byte]

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
   * @return
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
   * List of files from a base directory recursively. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the directory
   */
  def lsDescendant(path: String): ZStream[Any, IOException, FtpResource]

  /**
   * Save a data stream. If the operation failed, an error will be emitted
   *
   * @param path absolute path of file to store
   * @param source data stream to store
   * @tparam R environment of the specified stream source, required to extend Blocking
   */
  def upload[R](path: String, source: ZStream[R, Throwable, Byte]): ZIO[R, IOException, Unit]

}
