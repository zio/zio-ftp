package zio.ftp

import java.io.IOException

import zio.ZIO
import zio.blocking.Blocking
import zio.stream.ZStream

trait FtpAccessors[+A] {

  /**
   * Lift unsafe operation that does blocking IO into a pure value. If the operation failed an error will be emitted
   *
   * @param f unsafe function to lift
   * @tparam T return value
   */
  def execute[T](f: A => T): ZIO[Blocking, IOException, T]

  /**
   * Check existence of a file. If the file doesn't exist, the operation will succeed by returning an empty ftp resource
   * If the operation failed an error will be emitted
   *
   * @param path absolute path of the file
   */
  def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]]

  /**
   * Read a file by using stream. If the operation failed, an error will be emitted
   *
   * @param path absolute path of a file
   * @param chunkSize default chunk size is 2048 bytes
   */
  def readFile(path: String, chunkSize: Int = 2048): ZStream[Blocking, IOException, Byte]

  /**
   * Delete a file on a server. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the file
   */
  def rm(path: String): ZIO[Blocking, IOException, Unit]

  /**
   * Delete a directory. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the directory
   * @return
   */
  def rmdir(path: String): ZIO[Blocking, IOException, Unit]

  /**
   * Create a directory. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the directory
   */
  def mkdir(path: String): ZIO[Blocking, IOException, Unit]

  /**
   * List of files / directories. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the directory
   */
  def ls(path: String): ZStream[Blocking, IOException, FtpResource]

  /**
   * List of files from a base directory recursively. If the operation failed, an error will be emitted
   *
   * @param path absolute path of the directory
   */
  def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource]

  /**
   * Save a data stream. If the operation failed, an error will be emitted
   *
   * @param path absolute path of file to store
   * @param source data stream to store
   * @tparam R environment of the specified stream source, required to extend Blocking
   */
  def upload[R <: Blocking](path: String, source: ZStream[R, Throwable, Byte]): ZIO[R, IOException, Unit]

  /**
   * Rename a file on a server. If the operation failed, an error will be emitted
   *
   * @param oldPath absolute current path of the file
   * @param newPath absolute new path of the file
   */
  def rename(oldPath: String, newPath: String): ZIO[Blocking, IOException, Unit]

}
