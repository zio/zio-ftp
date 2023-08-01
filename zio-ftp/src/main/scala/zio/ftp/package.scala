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
  //Alias Unsecure Ftp dependency
  type Ftp     = FtpAccessors[UnsecureFtp.Client]
  // Alias Secure Ftp dependency
  type SFtp    = FtpAccessors[SecureFtp.Client]
  // Only for testing purpose
  type StubFtp = FtpAccessors[Unit]

  object Ftp {

    def execute[T](f: UnsecureFtp.Client => T): ZIO[Ftp, IOException, T] =
      ZIO.serviceWithZIO(_.execute(f))

    def stat(path: String): ZIO[Ftp, IOException, Option[FtpResource]] =
      ZIO.serviceWithZIO(_.stat(path))

    def rm(path: String): ZIO[Ftp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rm(path))

    def rmdir(path: String): ZIO[Ftp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rmdir(path))

    def mkdir(path: String): ZIO[Ftp, IOException, Unit] =
      ZIO.serviceWithZIO(_.mkdir(path))

    def ls(path: String): ZStream[Ftp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.ls(path))

    def lsDescendant(path: String): ZStream[Ftp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.lsDescendant(path))

    def upload[R](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[R & Scope with Ftp, IOException, Unit] =
      for {
        ftp <- ZIO.service[Ftp]
        _   <- ftp.upload(path, source)
      } yield ()

    def readFile(path: String, chunkSize: Int = 2048): ZStream[Ftp, IOException, Byte] =
      ZStream.serviceWithStream(_.readFile(path, chunkSize))
  }

  object SFtp {

    def execute[T](f: SecureFtp.Client => T): ZIO[SFtp, IOException, T] =
      ZIO.serviceWithZIO(_.execute(f))

    def stat(path: String): ZIO[SFtp, IOException, Option[FtpResource]] =
      ZIO.serviceWithZIO(_.stat(path))

    def rm(path: String): ZIO[SFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rm(path))

    def rmdir(path: String): ZIO[SFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rmdir(path))

    def mkdir(path: String): ZIO[SFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.mkdir(path))

    def ls(path: String): ZStream[SFtp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.ls(path))

    def lsDescendant(path: String): ZStream[SFtp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.lsDescendant(path))

    def upload[R](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[SFtp with R with Scope, IOException, Unit] =
      for {
        ftp <- ZIO.service[SFtp]
        _   <- ftp.upload(path, source)
      } yield ()

    def readFile(path: String, chunkSize: Int = 2048): ZStream[SFtp, IOException, Byte] =
      ZStream.serviceWithStream(_.readFile(path, chunkSize))
  }

  object StubFtp {

    def execute[T](f: Unit => T): ZIO[StubFtp, IOException, T] =
      ZIO.serviceWithZIO(_.execute(f))

    def stat(path: String): ZIO[StubFtp, IOException, Option[FtpResource]] =
      ZIO.serviceWithZIO(_.stat(path))

    def rm(path: String): ZIO[StubFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rm(path))

    def rmdir(path: String): ZIO[StubFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.rmdir(path))

    def mkdir(path: String): ZIO[StubFtp, IOException, Unit] =
      ZIO.serviceWithZIO(_.mkdir(path))

    def ls(path: String): ZStream[StubFtp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.ls(path))

    def lsDescendant(path: String): ZStream[StubFtp, IOException, FtpResource] =
      ZStream.serviceWithStream(_.lsDescendant(path))

    def upload[R](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[StubFtp with R with Scope, IOException, Unit] =
      for {
        ftp <- ZIO.service[StubFtp]
        _   <- ftp.upload(path, source)
      } yield ()

    def readFile(path: String, chunkSize: Int = 2048): ZStream[StubFtp, IOException, Byte] =
      ZStream.serviceWithStream(_.readFile(path, chunkSize))
  }

  def unsecure(settings: UnsecureFtpSettings): ZLayer[Any, ConnectionError, Ftp] =
    ZLayer.scoped(UnsecureFtp.connect(settings))

  def secure(settings: SecureFtpSettings): ZLayer[Any, ConnectionError, SFtp] =
    ZLayer.scoped(SecureFtp.connect(settings))

  def stub(path: ZPath): Layer[Any, StubFtp] =
    ZLayer.succeed(TestFtp.create(path))
}
