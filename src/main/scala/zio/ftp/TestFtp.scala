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

import java.io.{ FileOutputStream, IOException }
import java.nio.file.NoSuchFileException

import zio.blocking.Blocking
import zio.nio.core.file.{ Path => ZPath }
import zio.nio.file.Files
import zio.stream.{ ZSink, ZStream }
import zio.{ ZIO, ZManaged }

object TestFtp {

  def create(root: ZPath): FtpAccessors[Unit] = new FtpAccessors[Unit] {
    override def execute[T](f: Unit => T): ZIO[Blocking, IOException, T] = ZIO.succeed(f((): Unit))

    override def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]] = {
      val p = root / ZPath(path).elements.mkString("/")
      Files
        .exists(p)
        .flatMap {
          case true  => get(p).map(Option(_))
          case false => ZIO.succeed(Option.empty[FtpResource])
        }
        .refineToOrDie[IOException]
    }

    override def readFile(path: String, chunkSize: Int): ZStream[Blocking, IOException, Byte] =
      ZStream
        .fromEffect(Files.readAllBytes(root / ZPath(path).elements.mkString("/")))
        .catchAll {
          case _: NoSuchFileException => ZStream.fail(InvalidPathError(s"File does not exist $path"))
          case err                    => ZStream.fail(err)
        }
        .flatMap(ZStream.fromChunk(_))

    override def rm(path: String): ZIO[Blocking, IOException, Unit] =
      Files
        .delete(root / ZPath(path).elements.mkString("/"))
        .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot delete : $path", err)))

    override def rmdir(path: String): ZIO[Blocking, IOException, Unit] =
      rm(path)

    override def mkdir(path: String): ZIO[Blocking, IOException, Unit] =
      Files
        .createDirectories(root / ZPath(path).elements.mkString("/"))
        .refineToOrDie[IOException]
        .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot create directory : $path", err)))

    override def ls(path: String): ZStream[Blocking, IOException, FtpResource] =
      Files
        .list(root / ZPath(path).elements.mkString("/"))
        .catchAll {
          case _: NoSuchFileException => ZStream.empty
          case err                    => ZStream.fail(new IOException(err))
        }
        .mapM(get)

    private def get(p: ZPath): ZIO[Blocking, IOException, FtpResource] =
      (for {
        permissions  <- Files.getPosixFilePermissions(p)
        isDir        <- Files.isDirectory(p).map(Some(_))
        lastModified <- Files.getLastModifiedTime(p).map(_.toMillis)
        size         <- Files.size(p)
      } yield FtpResource(root.relativize(p).elements.mkString("/", "/", ""), size, lastModified, permissions, isDir))
        .mapError(new IOException(_))

    override def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource] =
      Files
        .find(root / ZPath(path).elements.mkString("/"))((_, attr) => attr.isRegularFile)
        .catchAll {
          case _: NoSuchFileException => ZStream.empty
          case err                    => ZStream.fail(new IOException(err))
        }
        .mapM(get)

    override def upload[R <: Blocking](
      path: String,
      source: ZStream[R, Throwable, Byte]
    ): ZIO[R, IOException, Unit] = {
      val file = (root / ZPath(path).elements.mkString("/")).toFile

      ZManaged
        .fromAutoCloseable(ZIO(new FileOutputStream(file)))
        .use { out =>
          source
            .run(ZSink.fromOutputStream(out))
            .unit
        }
        .refineToOrDie[IOException]
        .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot upload data to : $path", err)))
    }
  }
}
