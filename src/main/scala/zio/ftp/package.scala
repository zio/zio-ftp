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

import zio.blocking.Blocking
import zio.nio.file.{ Path => ZPath }
import zio.stream.ZStream

package object ftp {
  //Alias Unsecure Ftp dependency
  type Ftp     = Has[FtpAccessors[UnsecureFtp.Client]]
  // Alias Secure Ftp dependency
  type SFtp    = Has[FtpAccessors[SecureFtp.Client]]
  // Only for testing purpose
  type StubFtp = Has[FtpAccessors[Unit]]

  object Ftp {

    def execute[T](f: UnsecureFtp.Client => T): ZIO[Ftp with Blocking, IOException, T] =
      ZIO.accessM(_.get.execute(f))

    def stat(path: String): ZIO[Ftp with Blocking, IOException, Option[FtpResource]] =
      ZIO.accessM(_.get.stat(path))

    def rm(path: String): ZIO[Ftp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.rm(path))

    def rmdir(path: String): ZIO[Ftp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.rmdir(path))

    def mkdir(path: String): ZIO[Ftp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.mkdir(path))

    def ls(path: String): ZStream[Ftp with Blocking, IOException, FtpResource] =
      ZStream.accessStream(_.get.ls(path))

    def lsDescendant(path: String): ZStream[Ftp with Blocking, IOException, FtpResource] =
      ZStream.accessStream(_.get.lsDescendant(path))

    def upload[R <: Blocking](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[Ftp with R, IOException, Unit] =
      ZIO.accessM(_.get.upload(path, source))

    def readFile(path: String, chunkSize: Int = 2048): ZStream[Ftp with Blocking, IOException, Byte] =
      ZStream.accessStream(_.get.readFile(path, chunkSize))

    def rename(oldPath: String, newPath: String): ZIO[Ftp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.rename(oldPath, newPath))
  }

  object SFtp {

    def execute[T](f: SecureFtp.Client => T): ZIO[SFtp with Blocking, IOException, T] =
      ZIO.accessM(_.get.execute(f))

    def stat(path: String): ZIO[SFtp with Blocking, IOException, Option[FtpResource]] =
      ZIO.accessM(_.get.stat(path))

    def rm(path: String): ZIO[SFtp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.rm(path))

    def rmdir(path: String): ZIO[SFtp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.rmdir(path))

    def mkdir(path: String): ZIO[SFtp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.mkdir(path))

    def ls(path: String): ZStream[SFtp with Blocking, IOException, FtpResource] =
      ZStream.accessStream(_.get.ls(path))

    def lsDescendant(path: String): ZStream[SFtp with Blocking, IOException, FtpResource] =
      ZStream.accessStream(_.get.lsDescendant(path))

    def upload[R <: Blocking](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[SFtp with R, IOException, Unit] =
      ZIO.accessM(_.get.upload(path, source))

    def readFile(path: String, chunkSize: Int = 2048): ZStream[SFtp with Blocking, IOException, Byte] =
      ZStream.accessStream(_.get.readFile(path, chunkSize))

    def rename(oldPath: String, newPath: String): ZIO[SFtp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.rename(oldPath, newPath))
  }

  object StubFtp {

    def execute[T](f: Unit => T): ZIO[StubFtp with Blocking, IOException, T] =
      ZIO.accessM(_.get.execute(f))

    def stat(path: String): ZIO[StubFtp with Blocking, IOException, Option[FtpResource]] =
      ZIO.accessM(_.get.stat(path))

    def rm(path: String): ZIO[StubFtp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.rm(path))

    def rmdir(path: String): ZIO[StubFtp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.rmdir(path))

    def mkdir(path: String): ZIO[StubFtp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.mkdir(path))

    def ls(path: String): ZStream[StubFtp with Blocking, IOException, FtpResource] =
      ZStream.accessStream(_.get.ls(path))

    def lsDescendant(path: String): ZStream[StubFtp with Blocking, IOException, FtpResource] =
      ZStream.accessStream(_.get.lsDescendant(path))

    def upload[R <: Blocking](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[StubFtp with R, IOException, Unit] =
      ZIO.accessM(_.get.upload(path, source))

    def readFile(path: String, chunkSize: Int = 2048): ZStream[StubFtp with Blocking, IOException, Byte] =
      ZStream.accessStream(_.get.readFile(path, chunkSize))

    def rename(oldPath: String, newPath: String): ZIO[StubFtp with Blocking, IOException, Unit] =
      ZIO.accessM(_.get.rename(oldPath, newPath))
  }

  def unsecure(settings: UnsecureFtpSettings): ZLayer[Blocking, ConnectionError, Ftp] =
    ZLayer.fromManaged(UnsecureFtp.connect(settings))

  def secure(settings: SecureFtpSettings): ZLayer[Blocking, ConnectionError, SFtp] =
    ZLayer.fromManaged(SecureFtp.connect(settings))

  def stub(path: ZPath): Layer[Any, StubFtp] =
    ZLayer.succeed(TestFtp.create(path))

}
