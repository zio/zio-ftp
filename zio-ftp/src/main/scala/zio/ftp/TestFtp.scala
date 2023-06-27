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
import java.nio.file.attribute.PosixFilePermission

import zio.nio.file.{ Path => ZPath }
import zio.nio.file.Files
import zio.stream.{ ZSink, ZStream }
import zio.{ Cause, Scope, ZIO }

object TestFtp {

  def create(root: ZPath): FtpAccessors[Unit] =
    new FtpAccessors[Unit] {
      override def execute[T](f: Unit => T): ZIO[Any, IOException, T] = ZIO.succeed(f((): Unit))

      override def stat(path: String): ZIO[Any, IOException, Option[FtpResource]] = {
        val p = root / ZPath(path).elements.mkString("/")
        Files
          .exists(p)
          .flatMap {
            case true  => get(p).map(Option(_))
            case false => ZIO.succeed(Option.empty[FtpResource])
          }
          .refineToOrDie[IOException]
      }

      override def readFile(path: String, chunkSize: Int): ZStream[Any, IOException, Byte] =
        ZStream
          .fromZIO(Files.readAllBytes(root / ZPath(path).elements.mkString("/")))
          .catchAll {
            case _: NoSuchFileException => ZStream.fail(InvalidPathError(s"File does not exist $path"))
            case err                    => ZStream.fail(err)
          }
          .flatMap(ZStream.fromChunk(_))

      override def rm(path: String): ZIO[Any, IOException, Unit] =
        Files
          .delete(root / ZPath(path).elements.mkString("/"))
          .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot delete : $path", err)))

      override def rmdir(path: String): ZIO[Any, IOException, Unit] =
        rm(path)

      override def mkdir(path: String) = // : ZIO[Any, IOException, Unit] =
        Files
          .createDirectories(root / ZPath(path).elements.mkString("/"))
          .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot create directory : $path", err)))

      override def ls(path: String): ZStream[Any, IOException, FtpResource] =
        Files
          .list(root / ZPath(path).elements.mkString("/"))
          .catchAll {
            case _: NoSuchFileException => ZStream.empty
            case err                    => ZStream.fail(new IOException(err))
          }
          .mapZIO(get)

      private def get(p: ZPath): ZIO[Any, IOException, FtpResource] =
        (for {
          permissions  <- Files.getPosixFilePermissions(p).mapErrorCause(_.untraced).catchSomeCause {
                            //Windows don't support this operations
                            case Cause.Die(_: UnsupportedOperationException, _) =>
                              ZIO.succeed(Set.empty[PosixFilePermission])
                          }
          isDir        <- Files.isDirectory(p).map(Some(_))
          lastModified <- Files.getLastModifiedTime(p).map(_.toMillis)
          size         <- Files.size(p)
        } yield FtpResource(root.relativize(p).elements.mkString("/", "/", ""), size, lastModified, permissions, isDir))
          .mapError(new IOException(_))

      override def lsDescendant(path: String): ZStream[Any, IOException, FtpResource] =
        Files
          .find(root / ZPath(path).elements.mkString("/"))((_, attr) => attr.isRegularFile)
          .catchAll {
            case _: NoSuchFileException => ZStream.empty
            case err                    => ZStream.fail(new IOException(err))
          }
          .mapZIO(get)

      override def upload[R](
        path: String,
        source: ZStream[R, Throwable, Byte]
      ): ZIO[R with Scope, IOException, Unit] = {
        val file = (root / ZPath(path).elements.mkString("/")).toFile

        ZIO
          .fromAutoCloseable(ZIO.attempt(new FileOutputStream(file)))
          .flatMap { out =>
            source
              .run(ZSink.fromOutputStream(out))
              .unit
          }
          .refineToOrDie[IOException]
          .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot upload data to : $path", err)))
      }

      override def rename(oldPath: String, newPath: String): ZIO[Any, IOException, Unit] =
        Files
          .move(root / ZPath(oldPath).elements.mkString("/"), root / ZPath(newPath).elements.mkString("/"))
          .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot rename $oldPath to $newPath", err)))
    }
}
