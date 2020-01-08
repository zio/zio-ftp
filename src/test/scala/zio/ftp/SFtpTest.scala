package zio.ftp

import java.nio.file.{ Files, Path, Paths }

import zio.blocking.Blocking
import zio.ftp.FtpClient._
import zio.ftp.FtpSettings._
import zio.stream.{ ZSink, ZStreamChunk }
import zio.test.Assertion._
import zio.test._
import zio._

import scala.io.Source

object SFtpTest
    extends BaseSFtpTest(
      SecureFtpSettings("127.0.0.1", port = 2222, FtpCredentials("foo", "foo")),
      Paths.get("ftp-home/sftp/home/foo/work")
    )

abstract class BaseSFtpTest(settings: SecureFtpSettings, home: Path)
    extends DefaultRunnableSpec(
      suite("SFtp")(
        testM("invalid credentials")(
          for {
            succeed <- connect(settings.copy(credentials = FtpCredentials("test", "test")))
                        .use(_ => IO.succeed(""))
                        .foldCause(_.failureOption.map(_.getMessage).mkString, identity)
          } yield assert(succeed, containsString("Fail to connect to server"))
        ),
        testM("valid credentials")(
          for {
            succeed <- connect(settings).use(_ => IO.succeed(true))
          } yield assert(succeed, equalTo(true))
        ),
        testM("ls")(
          for {
            files <- connect(settings).use(_.ls("/").runCollect)
          } yield assert(files.map(_.path), hasSameElements(List("/empty.txt", "/work")))
        ),
        testM("ls with invalid directory")(
          for {
            files <- connect(settings).use(_.ls("/dont-exist").runCollect)
          } yield assert(files.map(_.path), hasSameElements(Nil))
        ),
        testM("ls descendant")(
          for {
            files <- connect(settings).use(_.lsDescendant("/").runCollect)
          } yield assert(
            files.map(_.path),
            hasSameElements(List("/empty.txt", "/work/notes.txt", "/work/dir1/users.csv", "/work/dir1/console.dump"))
          )
        ),
        testM("ls descendant with invalid directory")(
          for {
            files <- connect(settings).use(_.lsDescendant("/dont-exist").runCollect)
          } yield assert(files.map(_.path), hasSameElements(Nil))
        ),
        testM("stat file") {
          for {
            file <- connect(settings).use(_.stat("/work/dir1/users.csv"))
          } yield assert(file.map(f => f.path -> f.isDirectory), equalTo(Some("/work/dir1/users.csv" -> None)))
        },
        testM("stat directory") {
          for {
            file <- connect(settings).use(_.stat("/work/dir1"))
          } yield assert(file.map(f => f.path -> f.isDirectory), equalTo(Some("/work/dir1" -> None)))
        },
        testM("stat file does not exist") {
          for {
            file <- connect(settings).use(_.stat("/wrong-path.xml"))
          } yield assert(file, equalTo(None))
        },
        testM("stat directory does not exist") {
          for {
            file <- connect(settings).use(_.stat("/wrong-path"))
          } yield assert(file, equalTo(None))
        },
        testM("readFile") {
          for {
            content <- connect(settings).use(_.readFile("/work/notes.txt").run(ZSink.utf8DecodeChunk))
          } yield assert(content, equalTo("""|Hello world !!!
                                             |this is a beautiful day""".stripMargin))
        },
        testM("readFile does not exist") {
          for {
            invalid <- connect(settings).use(
                        _.readFile("/work/invalid.txt")
                          .run(ZSink.utf8DecodeChunk)
                          .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)
                      )
          } yield assert(invalid, equalTo("No such file"))
        },
        testM("mkdir directory") {
          (for {
            result <- connect(settings).use(_.mkdir("/work/new-dir").map(_ => true))
          } yield assert(result, equalTo(true)))
            .tap(_ => Task(Files.delete(home.resolve("new-dir"))))
        },
        testM("mkdir fail when invalid path") {
          for {
            failure <- connect(settings).use(
                        _.mkdir("/work/dir1/users.csv")
                          .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")
                      )
          } yield {
            assert(failure, containsString("/work/dir1/users.csv exists but is not a directory"))
          }
        },
        testM("rm valid path") {
          val path = home.resolve("to-delete.txt")
          Files.createFile(path)

          for {
            success <- connect(settings).use(
                        _.rm("/work/to-delete.txt")
                          .foldCause(_ => false, _ => true)
                      )

            fileExist <- Task(Files.notExists(path))
          } yield assert(success && fileExist, equalTo(true))
        },
        testM("rm fail when invalid path") {
          for {
            invalid <- connect(settings).use(
                        _.rm("/work/dont-exist")
                          .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")
                      )
          } yield assert(invalid, equalTo("No such file"))
        },
        testM("rm directory") {
          val path = home.resolve("dir-to-delete")
          Files.createDirectory(path)

          for {
            r     <- connect(settings).use(_.rmdir("/work/dir-to-delete").map(_ => true))
            exist <- Task(Files.notExists(path))
          } yield assert(r && exist, equalTo(true))
        },
        testM("rm fail invalid directory") {
          for {
            r <- connect(settings).use(
                  _.rmdir("/work/dont-exist")
                    .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")
                )
          } yield assert(r, equalTo("No such file"))
        },
        testM("upload a file") {
          val data = ZStreamChunk.fromChunks(Chunk.fromArray("Hello F World".getBytes))
          val path = home.resolve("hello-world.txt")

          (for {
            _ <- connect(settings).use(_.upload("/work/hello-world.txt", data))
            result <- Managed
                       .make(Task(Source.fromFile(path.toFile)))(s => UIO(s.close))
                       .use(b => Task(b.mkString))
          } yield assert(result, equalTo("Hello F World"))).tap(_ => Task(Files.delete(path)))
        },
        testM("upload fail when path is invalid") {
          val data = ZStreamChunk.fromChunks(Chunk.fromArray("Hello F World".getBytes))

          for {
            failure <- connect(settings).use(
                        _.upload("/work/dont-exist/hello-world.txt", data)
                          .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")
                      )
          } yield assert(failure, equalTo("No such file"))
        },
        testM("call version() underlying client") {
          for {
            version <- connect(settings).use(
                        _.execute(_.version())
                      )
          } yield assert(version.toString, isNonEmptyString)
        }
      ).provideManagedShared(Managed.succeed(Blocking.Live))
    )
