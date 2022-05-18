package zio.ftp

import zio.ftp.StubFtp._
import zio.nio.file.{ Files, Path => ZPath }
import zio.stream.ZPipeline.utf8Decode
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._

import zio.{ Chunk, Scope, ZIO }

import scala.io.Source

object StubFtpSpec extends ZIOSpecDefault {
  val home = ZPath("ftp-home/stub/home")

  val stubFtp = Scope.default >+> stub(home).mapError(TestFailure.fail)

  override def spec =
    suite("StubFtpSpec")(
      test("ls")(
        for {
          files <- ls("/").runFold(List.empty[String])((s, f) => f.path +: s)
        } yield assert(files.reverse)(hasSameElements(List("/notes.txt", "/dir1")))
      ),
      test("ls with invalid directory")(
        for {
          files <- ls("/dont-exist").runFold(List.empty[String])((s, f) => f.path +: s)
        } yield assert(files.reverse)(hasSameElements(Nil))
      ),
      test("ls descendant")(
        for {
          files <- lsDescendant("/").runFold(List.empty[String])((s, f) => f.path +: s)
        } yield assert(files.reverse)(
          hasSameElements(List("/notes.txt", "/dir1/users.csv", "/dir1/console.dump"))
        )
      ),
      test("ls descendant with invalid directory")(
        for {
          files <- lsDescendant("/dont-exist").runCollect
        } yield assertTrue(files == Chunk.empty)
      ),
      test("stat directory") {
        for {

          file <- stat("/dir1")
        } yield assertTrue(file.get.path == "/dir1") &&
          assertTrue(file.get.isDirectory.get)
      },
      test("stat file") {
        for {
          file <- stat("/dir1/console.dump")
        } yield assertTrue(file.get.path == "/dir1/console.dump") &&
          assertTrue(!file.get.isDirectory.get)
      },
      test("stat file does not exist") {
        for {
          file <- stat("/wrong-path.xml")
        } yield assertTrue(file.isEmpty)
      },
      test("stat directory does not exist") {
        for {
          file <- stat("/wrong-path")
        } yield assertTrue(file.isEmpty)
      },
      test("readFile") {
        for {
          content <- readFile("/notes.txt").via(utf8Decode).runCollect
        } yield assert(content.mkString)(equalTo("""|Hello world !!!
                                                    |this is a beautiful day""".stripMargin))
      },
      test("readFile does not exist") {
        for {
          invalid <- readFile("/invalid.txt")
                       .via(utf8Decode)
                       .runCollect
                       .foldCause(e => e.failureOption.map(_.getMessage).mkString, _.mkString)

        } yield assertTrue(invalid == "File does not exist /invalid.txt")
      },
      test("mkdir directory") {
        (for {
          result <- mkdir("/new-dir").as(true)
        } yield assert(result)(equalTo(true))) <* Files.delete(home / "new-dir")
      },
      test("mkdir fail when invalid path") {
        for {
          failure <- mkdir("/dir1/users.csv")
                       .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")

        } yield assert(failure)(containsString("Path is invalid. Cannot create directory : /dir1/users.csv"))
      },
      test("rm valid path") {
        val path = home / "to-delete.txt"

        for {
          _         <- Files.createFile(path)
          success   <- rm("/to-delete.txt")
                         .foldCause(_ => false, _ => true)

          fileExist <- Files.notExists(path)
        } yield assertTrue(success && fileExist)
      },
      test("rm fail when invalid path") {
        for {
          invalid <- rm("/dont-exist")
                       .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")

        } yield assertTrue(invalid == "Path is invalid. Cannot delete : /dont-exist")
      },
      test("rm directory") {
        val path = home / "dir-to-delete"
        for {
          _     <- Files.createDirectory(path)
          r     <- rmdir("/dir-to-delete").as(true)
          exist <- Files.notExists(path)
        } yield assertTrue(r && exist)
      },
      test("rm fail invalid directory") {
        for {
          r <- rmdir("/dont-exist")
                 .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")

        } yield assertTrue(r == "Path is invalid. Cannot delete : /dont-exist")
      },
      test("upload a file") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))
        val path = home / "hello-world.txt"

        (for {
          _      <- upload("/hello-world.txt", data)
          result <- ZIO
                      .fromAutoCloseable(ZIO.attemptBlockingIO(Source.fromFile(path.toFile)))
                      .map(_.mkString)
        } yield assert(result)(equalTo("Hello F World"))) <* Files.delete(path)
      },
      test("upload fail when path is invalid") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload("/dont-exist/hello-world.txt", data)
                       .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assertTrue(failure == "Path is invalid. Cannot upload data to : /dont-exist/hello-world.txt")
      }
    ).provideCustomLayerShared(stubFtp)
}
