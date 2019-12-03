package zio.ftp

import java.nio.file.{ Files, Path, Paths }

import zio._
import zio.ftp.Ftp.{ connect, _ }
import zio.ftp.settings.FtpCredentials._
import zio.ftp.settings.FtpSettings
import zio.stream.{ ZSink, ZStream }
import zio.test.Assertion._
import zio.test._

import scala.io.Source

abstract class BaseFtpTest(labelSuite: String, settings: FtpSettings, home: Path = Paths.get("ftp-home/ftp/home"))
    extends DefaultRunnableSpec(
      suite(labelSuite)(
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
        ),
        testM("listFiles")(
          for {
            files <- connect(settings).use(listFiles("/")(_).runCollect)
          } yield assert(files.map(_.name), hasSameElements(List("notes.txt", "console.dump", "users.csv")))
        ),
        testM("stat file") {
          for {
            file <- connect(settings).use(stat("/dir1/users.csv"))
          } yield assert(file.map(_.name), equalTo(Some("users.csv")))
        },
        testM("stat file does not exist") {
          for {
            file <- connect(settings).use(stat("/wrong-path.xml"))
          } yield assert(file, equalTo(None))
        },
        testM("readFile") {
          for {
            content <- connect(settings).use(
                        readFile("/notes.txt")(_).chunkN(100).chunks.run(ZSink.utf8DecodeChunk)
                      )
          } yield assert(content, equalTo("""|Hello world !!!
                                             |this is a beautiful day""".stripMargin))
        },
        testM("readFile does not exist") {
          for {
            invalid <- connect(settings)
                        .use(readFile("/invalid.txt")(_).chunkN(100).chunks.run(ZSink.utf8DecodeChunk))
                        .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)
          } yield assert(invalid, equalTo("File does not exist /invalid.txt"))
        },
        testM("mkdir directory") {
          (for {
            result <- connect(settings).use(mkdir("/new-dir")).map(_ => true)
          } yield assert(result, equalTo(true)))
            .tap(_ => Task(Files.delete(home.resolve("new-dir"))))
        },
        testM("mkdir fail when invalid path") {
          for {
            failure <- connect(settings)
                        .use(mkdir("/dir1/users.csv"))
                        .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
          } yield {
            assert(failure, containsString("Path is invalid. Cannot create directory : /dir1/users.csv"))
          }
        },
        testM("rm valid path") {
          val path = home.resolve("to-delete.txt")
          Files.createFile(path)

          for {
            success <- connect(settings)
                        .use(rm("/to-delete.txt"))
                        .foldCause(_ => false, _ => true)

            fileExist <- Task(Files.notExists(path))
          } yield assert(success && fileExist, equalTo(true))
        },
        testM("rm fail when invalid path") {
          for {
            invalid <- connect(settings)
                        .use(rm("/dont-exist"))
                        .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
          } yield assert(invalid, equalTo("Path is invalid. Cannot delete file : /dont-exist"))
        },
        testM("rm directory") {
          val path = home.resolve("dir-to-delete")
          Files.createDirectory(path)

          for {
            r     <- connect(settings).use(rmdir("/dir-to-delete")).map(_ => true)
            exist <- Task(Files.notExists(path))
          } yield assert(r && exist, equalTo(true))
        },
        testM("rm fail invalid directory") {
          for {
            r <- connect(settings)
                  .use(rmdir("/dont-exist"))
                  .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
          } yield assert(r, equalTo("Path is invalid. Cannot delete directory : /dont-exist"))
        },
        testM("upload a file") {
          val data = ZStream.fromIterable("Hello F World".getBytes.toSeq).chunkN(10).chunks
          val path = home.resolve("hello-world.txt")

          (for {
            _      <- connect(settings).use(upload("/hello-world.txt", data))
            result <- Managed.make(taskIO(Source.fromFile(path.toFile)))(s => UIO(s.close)).use(b => taskIO(b.mkString))
          } yield assert(result, equalTo("Hello F World"))).tap(_ => Task(Files.delete(path)))
        },
        testM("upload fail when path is invalid") {
          val data = ZStream.fromIterable("Hello F World".getBytes.toSeq).chunkN(10).chunks

          for {
            failure <- connect(settings)
                        .use(upload("/dont-exist/hello-world.txt", data))
                        .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
          } yield assert(failure, equalTo("Path is invalid. Cannot upload data to : /dont-exist/hello-world.txt"))
        }
      )
    )
