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

import java.io.IOException

import zio.blocking.Blocking
import zio.ftp.FtpSettings.{ SecureFtpSettings, UnsecureFtpSettings }
import zio.stream.{ ZStream, ZStreamChunk }
import zio.{ ZIO, ZManaged }

/**
 * Based trait for Ftp Wrapper
 *
 * It is required to provide an Blocking Environment for each command.
 *
 * @tparam A Ftp client type
 */
trait FtpClient[+A] {
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
  def readFile(path: String, chunkSize: Int = 2048): ZStreamChunk[Blocking, IOException, Byte]

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
  def upload[R <: Blocking](path: String, source: ZStreamChunk[R, Throwable, Byte]): ZIO[R, IOException, Unit]
}

object FtpClient {

  /**
   * Create a safe resource FtpClient. If the operation failed, an ConnectionError will be emitted
   *
   * @param settings ftp connection settings
   * @tparam A
   */
  def connect[A](settings: FtpSettings[A]): ZManaged[Blocking, ConnectionError, FtpClient[A]] = settings match {
    case s: UnsecureFtpSettings => Ftp.connect(s)
    case s: SecureFtpSettings   => SFtp.connect(s)
  }
}
