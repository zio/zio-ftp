package zio.ftp

import zio.ZIO.{ acquireRelease, attemptBlockingIO }
import zio.ftp.StubFtp._
import zio.stream.ZPipeline.utf8Decode
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._
import zio.{ Chunk, Scope }

import scala.io.Source
import java.nio.file.{ Files, Path }
import zio.ZIO

object StubFtpSpec extends ZIOSpecDefault {
  val home = Path.of("ftp-home/stub/home")

  override def spec =
    suite("StubFtpSpec")(
      test("ls")(
        for {
          files <- ls(Path.of("/")).runFold(List.empty[Path])((s, f) => f.path +: s)
        } yield assert(files.reverse)(hasSameElements(List(Path.of("/notes.txt"), Path.of("/dir1"))))
      ),
      test("ls with invalid directory")(
        for {
          files <- ls(Path.of("/dont-exist")).runFold(List.empty[Path])((s, f) => f.path +: s)
        } yield assert(files.reverse)(hasSameElements(Nil))
      ),
      test("ls descendant")(
        for {
          files <- lsDescendant(Path.of("/")).runFold(List.empty[String])((s, f) => f.path.toString +: s)
        } yield assert(files)(
          hasSameElements(List("/notes.txt", "/dir1/users.csv", "/dir1/console.dump"))
        )
      ),
      test("ls descendant with invalid directory")(
        for {
          files <- lsDescendant(Path.of("/dont-exist")).runCollect
        } yield assertTrue(files == Chunk.empty)
      ),
      test("stat directory") {
        for {

          file <- stat(Path.of("/dir1"))
        } yield assertTrue(file.get.path == Path.of("/dir1")) &&
          assertTrue(file.get.isDirectory.get)
      },
      test("stat file") {
        for {
          file <- stat(Path.of("/dir1/console.dump"))
        } yield assertTrue(file.get.path == Path.of("/dir1/console.dump")) &&
          assertTrue(!file.get.isDirectory.get)
      },
      test("stat file does not exist") {
        for {
          file <- stat(Path.of("/wrong-path.xml"))
        } yield assertTrue(file.isEmpty)
      },
      test("stat directory does not exist") {
        for {
          file <- stat(Path.of("/wrong-path"))
        } yield assertTrue(file.isEmpty)
      },
      test("readFile") {
        for {
          content <- readFile(Path.of("/notes.txt")).via(utf8Decode).runCollect
        } yield assert(content.mkString)(equalTo("""|Hello world !!!
                                                    |this is a beautiful day""".stripMargin))
      },
      test("readFile with offset") {
        for {
          content <- readFile(Path.of("/notes.txt"), fileOffset = 16).via(utf8Decode).runCollect
        } yield assert(content.mkString)(equalTo("this is a beautiful day"))
      },
      test("readFile does not exist") {
        for {
          invalid <- readFile(Path.of("/invalid.txt"))
                       .via(utf8Decode)
                       .runCollect
                       .flip
                       .map(_.getMessage)

        } yield assertTrue(invalid == "File does not exist /invalid.txt")
      },
      test("mkdir directory") {
        (for {
          result <- mkdir(Path.of("/new-dir")).as(true)
        } yield assertTrue(result)) <* ZIO.attempt(Files.delete(home.resolve("new-dir")))
      },
      test("mkdir fail when invalid path") {
        for {
          failure <- mkdir(Path.of("/dir1/users.csv")).flip.map(_.getMessage)
        } yield assert(failure)(containsString("Path is invalid. Cannot create directory : /dir1/users.csv"))
      },
      test("rm valid path") {
        val path = home.resolve("to-delete.txt")

        for {
          _       <- ZIO.attempt(Files.createFile(path))
          success <- rm(Path.of("/to-delete.txt")).as(true)

          fileExist <- ZIO.attempt(Files.notExists(path))
        } yield assertTrue(success && fileExist)
      },
      test("rm fail when invalid path") {
        for {
          invalid <- rm(Path.of("/dont-exist")).flip.map(_.getMessage)
        } yield assertTrue(invalid == "Path is invalid. Cannot delete : /dont-exist")
      },
      test("rm directory") {
        val path = home.resolve("dir-to-delete")
        for {
          _     <- ZIO.attempt(Files.createDirectory(path))
          r     <- rmdir(Path.of("/dir-to-delete")).as(true)
          exist <- ZIO.attempt(Files.notExists(path))
        } yield assertTrue(r && exist)
      },
      test("rm fail invalid directory") {
        for {
          r <- rmdir(Path.of("/dont-exist")).flip.map(_.getMessage)
        } yield assertTrue(r == "Path is invalid. Cannot delete : /dont-exist")
      },
      test("upload a file") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))
        val path = home.resolve("hello-world.txt")

        (for {
          _      <- upload(Path.of("/hello-world.txt"), data)
          result <-
            acquireRelease(attemptBlockingIO(Source.fromFile(path.toFile)))(b => attemptBlockingIO(b.close()).ignore)
              .map(_.mkString)

        } yield assert(result)(equalTo("Hello F World"))) <* ZIO.attempt(Files.delete(path))
      },
      test("upload fail when path is invalid") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload(Path.of("/dont-exist/hello-world.txt"), data).flip.map(_.getMessage)

        } yield assertTrue(failure == "Path is invalid. Cannot upload data to : /dont-exist/hello-world.txt")
      },
      test("rename a file") {
        val oldPath = home.resolve("to-rename.txt")
        val newPath = home.resolve("to-rename-destination.txt")

        (for {
          _       <- ZIO.attempt(Files.createFile(oldPath))
          success <- rename(Path.of("/to-rename.txt"), Path.of("/to-rename-destination.txt")).as(true)

          oldFileExists <- ZIO.attempt(Files.exists(oldPath))
          newFileExists <- ZIO.attempt(Files.exists(newPath))
        } yield assertTrue(success && !oldFileExists && newFileExists)) <* ZIO.attempt(Files.delete(newPath))
      },
      test("rename a file fails when old path doesn't exist") {
        for {
          failure <- rename(Path.of("/dont-exist.txt"), Path.of("/dont-exist-destination.txt")).flip.map(_.getMessage)
        } yield assertTrue(failure == "Path is invalid. Cannot rename /dont-exist.txt to /dont-exist-destination.txt")
      }
    ).provideSomeLayerShared[Scope](stub(home))
}
