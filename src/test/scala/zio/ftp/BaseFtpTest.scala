package zio.ftp

import java.nio.file.{ Files, Path, Paths }

import zio._
import zio.ftp.Ftp.{ connect, _ }
import zio.ftp.settings._
import zio.stream.{ ZSink, ZStream }
import zio.test.Assertion._
import zio.test._

import scala.io.Source

abstract class BaseFtpTest(labelSuite: String, settings: FtpSettings, home: Path = Paths.get("ftp-home/ftp/home/work"))
    extends DefaultRunnableSpec(
      suite(labelSuite)(
        suite("connect")(
          testM("invalid credentials")(
            for {
              failure <- connect(settings.copy(credentials = credentials("test", "test")))
                          .use(_ => IO.succeed(""))
                          .foldCause(_.dieOption.map(_.getMessage).mkString, identity)
            } yield assert(failure, containsString("Fail to connect to server"))
          ),
          testM("valid credentials")(
            for {
              succeed <- connect(settings).use(_ => IO.succeed(true))
            } yield assert(succeed, equalTo(true))
          )
        ),
        suite("commands")(
          testM("listFiles")(
            for {
              files <- listFiles("/").fold(List.empty[String])((s, f) => f.name +: s)
            } yield assert(files.reverse, hasSameElements(List("notes.txt", "console.dump", "users.csv")))
          ),
          testM("listFiles with invalid directory")(
            for {
              files <- listFiles("/dont-exist").runCollect
            } yield assert(files, equalTo(Nil))
          ),
          testM("stat file") {
            for {
              file <- stat("/work/dir1/console.dump")
            } yield assert(file.map(_.name), equalTo(Some("console.dump")))
          },
          testM("stat file does not exist") {
            for {
              file <- stat("/wrong-path.xml")
            } yield assert(file, equalTo(None))
          },
          testM("readFile") {
            for {
              content <- readFile("/work/notes.txt").chunkN(100).chunks.run(ZSink.utf8DecodeChunk)
            } yield assert(content, equalTo("""|Hello world !!!
                                               |this is a beautiful day""".stripMargin))
          }.provideManaged(connect(settings).orDie),
          testM("readFile does not exist") {
            for {
              invalid <- readFile("/invalid.txt")
                          .chunkN(100)
                          .chunks
                          .run(ZSink.utf8DecodeChunk)
                          .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)
            } yield assert(invalid, equalTo("File does not exist /invalid.txt"))
          }.provideManaged(connect(settings).orDie),
          testM("mkdir directory") {
            (for {
              result <- mkdir("/work/new-dir").map(_ => true)
            } yield assert(result, equalTo(true)))
              .tap(_ => Task(Files.delete(home.resolve("new-dir"))))
          },
          testM("mkdir fail when invalid path") {
            for {
              failure <- mkdir("/work/dir1/users.csv")
                          .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
            } yield {
              assert(failure, containsString("Path is invalid. Cannot create directory : /work/dir1/users.csv"))
            }
          },
          testM("rm valid path") {
            val path = home.resolve("to-delete.txt")
            Files.createFile(path)

            for {
              success <- rm("/work/to-delete.txt")
                          .foldCause(_ => false, _ => true)

              fileExist <- Task(Files.notExists(path))
            } yield assert(success && fileExist, equalTo(true))
          },
          testM("rm fail when invalid path") {
            for {
              invalid <- rm("/dont-exist")
                          .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
            } yield assert(invalid, equalTo("Path is invalid. Cannot delete file : /dont-exist"))
          },
          testM("rm directory") {
            val path = home.resolve("dir-to-delete")
            Files.createDirectory(path)

            for {
              r     <- rmdir("/work/dir-to-delete").map(_ => true)
              exist <- Task(Files.notExists(path))
            } yield assert(r && exist, equalTo(true))
          },
          testM("rm fail invalid directory") {
            for {
              r <- rmdir("/dont-exist")
                    .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
            } yield assert(r, equalTo("Path is invalid. Cannot delete directory : /dont-exist"))
          },
          testM("upload a file") {
            val data = ZStream.fromIterable("Hello F World".getBytes.toSeq).chunkN(10).chunks
            val path = home.resolve("hello-world.txt")

            (for {
              _ <- upload("/work/hello-world.txt", data)
              result <- Managed
                         .make(taskIO(Source.fromFile(path.toFile)))(s => UIO(s.close))
                         .use(b => taskIO(b.mkString))
            } yield assert(result, equalTo("Hello F World"))).tap(_ => Task(Files.delete(path)))
          },
          testM("upload fail when path is invalid") {
            val data = ZStream.fromIterable("Hello F World".getBytes.toSeq).chunkN(10).chunks

            for {
              failure <- upload("/dont-exist/hello-world.txt", data)
                          .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
            } yield assert(failure, equalTo("Path is invalid. Cannot upload data to : /dont-exist/hello-world.txt"))
          }
        ).provideManagedShared(connect(settings).orDie)
      )
    )
