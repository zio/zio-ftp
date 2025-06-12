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

import java.nio.file.{ Path }
import java.nio.file.Files
import zio.stream.{ ZSink, ZStream }
import scala.jdk.CollectionConverters._
import zio.{ Cause, ZIO }

object TestFtp {

  def create(root: Path): FtpAccessors[Unit] =
    new FtpAccessors[Unit] {
      override def execute[T](f: Unit => T): ZIO[Any, IOException, T] = ZIO.succeed(f((): Unit))

      override def stat(path: String): ZIO[Any, IOException, Option[FtpResource]] = {
        val p = root.resolve(path)
        ZIO
          .attempt(
            Files
              .exists(p)
          )
          .flatMap {
            case true  => get(p).map(Option(_))
            case false => ZIO.succeed(Option.empty[FtpResource])
          }
          .refineToOrDie[IOException]
      }

      override def readFile(path: String, chunkSize: Int, fileOffset: Long): ZStream[Any, IOException, Byte] = {
        val a: ZStream[Any, IOException, Byte] = ZStream
          .fromInputStreamScoped(ZIO.fromAutoCloseable(ZIO.attemptBlockingIO(Files.newInputStream(root.resolve(path)))))
        a
          .catchAll {
            case _: NoSuchFileException => ZStream.fail(InvalidPathError(s"File does not exist $path"))
            case err                    => ZStream.fail(err)
          }
          .drop(fileOffset.toInt)
      }

      override def rm(path: String): ZIO[Any, IOException, Unit] =
        ZIO
          .attemptBlockingIO(
            Files
              .delete(root.resolve(path))
          )
          .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot delete : $path", err)))

      override def rmdir(path: String): ZIO[Any, IOException, Unit] =
        rm(path)

      override def mkdir(path: String) = // : ZIO[Any, IOException, Unit] =
        ZIO
          .attemptBlockingIO(
            Files
              .createDirectories(root.resolve(path))
          )
          .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot create directory : $path", err)))
          .unit

      override def ls(path: String): ZStream[Any, IOException, FtpResource] =
        ZStream
          .fromJavaStreamScoped[Any, Path](
            ZIO.fromAutoCloseable(
              ZIO.attemptBlockingIO(
                Files
                  .list(root.resolve(path))
              )
            )
          )
          .catchAll {
            case _: NoSuchFileException => ZStream.empty
            case err                    => ZStream.fail(new IOException(err))
          }
          .mapZIO(get)

      private def get(p: Path): ZIO[Any, IOException, FtpResource] =
        (for {
          permissions  <-
            ZIO.attempt(Files.getPosixFilePermissions(p).asScala.toSet).mapErrorCause(_.untraced).catchSomeCause {
              //Windows don't support this operation
              case Cause.Die(_: UnsupportedOperationException, _) =>
                ZIO.succeed(Set.empty[PosixFilePermission])
            }
          isDir        <- ZIO.attempt(Files.isDirectory(p)).map(Some(_))
          lastModified <- ZIO.attempt(Files.getLastModifiedTime(p)).map(_.toInstant())
          size         <- ZIO.attempt(Files.size(p))
        } yield FtpResource(root.relativize(p).toString, size, lastModified, permissions, isDir))
          .mapError(new IOException(_))

      override def lsDescendant(path: String): ZStream[Any, IOException, FtpResource] =
        ZStream
          .fromJavaStreamScoped[Any, Path](
            ZIO.fromAutoCloseable(
              ZIO.attempt(
                Files
                  .find(root.resolve(path), Int.MaxValue, (_, attr) => attr.isRegularFile)
              )
            )
          )
          .catchAll {
            case _: NoSuchFileException => ZStream.empty
            case err                    => ZStream.fail(new IOException(err))
          }
          .mapZIO(get)

      override def upload[R](
        path: String,
        source: ZStream[R, Throwable, Byte]
      ): ZIO[R, IOException, Unit] = {
        val file = (root.resolve(path)).toFile

        ZIO.scoped[R] {
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
      }

      override def rename(oldPath: String, newPath: String): ZIO[Any, IOException, Unit] =
        ZIO
          .attempt(
            Files
              .move(root.resolve(oldPath), root.resolve(newPath)): Unit
          )
          .catchAll(err => ZIO.fail(new IOException(s"Path is invalid. Cannot rename $oldPath to $newPath", err)))
    }
}
