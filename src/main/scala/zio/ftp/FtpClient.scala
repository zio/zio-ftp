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

trait FtpClient[+A] {
  def execute[T](f: A => T): ZIO[Blocking, IOException, T]
  def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]]
  def readFile(path: String, chunkSize: Int = 2048): ZStreamChunk[Blocking, IOException, Byte]
  def rm(path: String): ZIO[Blocking, IOException, Unit]
  def rmdir(path: String): ZIO[Blocking, IOException, Unit]
  def mkdir(path: String): ZIO[Blocking, IOException, Unit]
  def ls(path: String): ZStream[Blocking, IOException, FtpResource]
  def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource]
  def upload[R <: Blocking](path: String, source: ZStreamChunk[R, Throwable, Byte]): ZIO[R, IOException, Unit]
}

object FtpClient {

  def connect[A](settings: FtpSettings[A]): ZManaged[Blocking, ConnectionError, FtpClient[A]] = settings match {
    case s: UnsecureFtpSettings => Ftp.connect(s)
    case s: SecureFtpSettings   => SFtp.connect(s)
  }
}
