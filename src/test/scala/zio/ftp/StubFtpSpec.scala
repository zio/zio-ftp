package zio.ftp

import zio.blocking.Blocking
import zio.ftp.StubFtp._
import zio.nio.file.{ Path => ZPath }
import zio.nio.file.Files
import zio.stream.{ ZStream, ZTransducer }
import zio.test.Assertion._
import zio.test._
import zio.{ Chunk, Managed, Task, UIO }

import scala.io.Source

object StubFtpSpec extends DefaultRunnableSpec {
  val home = ZPath("ftp-home/stub/home")

  val stubFtp = stub(home).mapError(TestFailure.fail(_))

  override def spec =
    suite("TestFtp")(
      testM("ls ")(
        for {
          files <- ls("/").fold(List.empty[String])((s, f) => f.path +: s)
        } yield assert(files.reverse)(hasSameElements(List("/empty.txt", "/work")))
      ),
      testM("ls with invalid directory")(
        for {
          files <- ls("/dont-exist").fold(List.empty[String])((s, f) => f.path +: s)
        } yield assert(files.reverse)(hasSameElements(Nil))
      ),
      testM("ls descendant")(
        for {
          files <- lsDescendant("/").fold(List.empty[String])((s, f) => f.path +: s)
        } yield assert(files.reverse)(
          hasSameElements(List("/empty.txt", "/work/notes.txt", "/work/dir1/users.csv", "/work/dir1/console.dump"))
        )
      ),
      testM("ls descendant with invalid directory")(
        for {
          files <- lsDescendant("/dont-exist").runCollect
        } yield assert(files)(equalTo(Chunk.empty))
      ),
      testM("stat directory") {
        for {
          file <- stat("/work/dir1")
        } yield assert(file.map(f => f.path -> f.isDirectory))(equalTo(Some("/work/dir1" -> Some(true))))
      },
      testM("stat file") {
        for {
          file <- stat("/work/dir1/console.dump")
        } yield assert(file.map(f => f.path -> f.isDirectory))(equalTo(Some("/work/dir1/console.dump" -> Some(false))))
      },
      testM("stat file does not exist") {
        for {
          file <- stat("/wrong-path.xml")
        } yield assert(file)(equalTo(None))
      },
      testM("stat directory does not exist") {
        for {
          file <- stat("/wrong-path")
        } yield assert(file)(equalTo(None))
      },
      testM("readFile") {
        for {
          content <- readFile("/work/notes.txt").transduce(ZTransducer.utf8Decode).runCollect
        } yield assert(content.mkString)(equalTo("""|Hello world !!!
                                                    |this is a beautiful day""".stripMargin))
      },
      testM("readFile does not exist") {
        for {
          invalid <- readFile("/invalid.txt")
                       .transduce(ZTransducer.utf8Decode)
                       .runCollect
                       .foldCause(e => e.failureOption.map(_.getMessage).mkString, _.mkString)

        } yield assert(invalid)(equalTo("File does not exist /invalid.txt"))
      },
      testM("mkdir directory") {
        (for {
          result <- mkdir("/work/new-dir").map(_ => true)
        } yield assert(result)(equalTo(true)))
          .tap(_ => Files.delete(home / "work" / "new-dir"))
      },
      testM("mkdir fail when invalid path") {
        for {
          failure <- mkdir("/work/dir1/users.csv")
                       .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")

        } yield assert(failure)(containsString("Path is invalid. Cannot create directory : /work/dir1/users.csv"))
      },
      testM("rm valid path") {
        val path = home / "work" / "to-delete.txt"

        for {
          _         <- Files.createFile(path)
          success   <- rm("/work/to-delete.txt")
                         .foldCause(_ => false, _ => true)

          fileExist <- Files.notExists(path)
        } yield assert(success && fileExist)(equalTo(true))
      },
      testM("rm fail when invalid path") {
        for {
          invalid <- rm("/dont-exist")
                       .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")

        } yield assert(invalid)(equalTo("Path is invalid. Cannot delete : /dont-exist"))
      },
      testM("rm directory") {
        val path = home / "work" / "dir-to-delete"

        for {
          _     <- Files.createDirectory(path)
          r     <- rmdir("/work/dir-to-delete").map(_ => true)
          exist <- Files.notExists(path)
        } yield assert(r && exist)(equalTo(true))
      },
      testM("rm fail invalid directory") {
        for {
          r <- rmdir("/dont-exist")
                 .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")

        } yield assert(r)(equalTo("Path is invalid. Cannot delete : /dont-exist"))
      },
      testM("upload a file") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))
        val path = home / "work" / "hello-world.txt"

        (for {
          _      <- upload("/work/hello-world.txt", data)
          result <- Managed
                      .make(Task(Source.fromFile(path.toFile)))(s => UIO(s.close))
                      .use(b => Task(b.mkString))
        } yield assert(result)(equalTo("Hello F World"))).tap(_ => Files.delete(path))
      },
      testM("upload fail when path is invalid") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload("/dont-exist/hello-world.txt", data)
                       .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assert(failure)(equalTo("Path is invalid. Cannot upload data to : /dont-exist/hello-world.txt"))
      }
    ).provideCustomLayerShared(stubFtp ++ Blocking.live)
}
