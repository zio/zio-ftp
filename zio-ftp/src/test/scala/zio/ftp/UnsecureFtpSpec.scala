package zio.ftp

import zio.ZIO.{ acquireRelease, attemptBlockingIO }
import zio.{ test => _, _ }
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.ftp.Ftp._
import zio.nio.file.{ Path => ZPath }
import zio.nio.file.Files
import zio.stream.ZPipeline.utf8Decode
import zio.stream.ZStream
import java.net.{ InetSocketAddress, Proxy }
import scala.io.Source

object UnsecureSslFtpSpec extends ZIOSpecDefault {
  val settings = UnsecureFtpSettings.secure("127.0.0.1", 2121, FtpCredentials("username", "userpass"))

  override def spec =
    FtpSuite.spec("UnsecureSslFtpSpec", settings).provideSomeLayer[Scope](unsecure(settings)) @@ sequential
}

object UnsecureFtpSpec extends ZIOSpecDefault {
  val settings = UnsecureFtpSettings("127.0.0.1", port = 2121, FtpCredentials("username", "userpass"))

  override def spec =
    FtpSuite.spec("UnsecureFtpSpec", settings).provideSomeLayer[Scope](unsecure(settings)) @@ sequential
}

object FtpSuite {
  val home = ZPath("ftp-home/ftp/home")

  def spec(labelSuite: String, settings: UnsecureFtpSettings) =
    suite(labelSuite)(
      test("invalid credentials")(
        for {
          failure <- UnsecureFtp
                       .connect(settings.copy(credentials = FtpCredentials("test", "test")))
                       .flip
                       .map(_.getMessage)
        } yield assert(failure)(containsString("Fail to connect to server"))
      ),
      test("invalid proxy")(
        for {
          failure <- UnsecureFtp
                       .connect(
                         settings.copy(proxy = Some(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("invalid", 9999))))
                       )
                       .flip
                       .map(_.getMessage)
        } yield assertTrue(failure.contains("invalid"))
      ),
      test("valid credentials")(
        for {
          succeed <- UnsecureFtp.connect(settings).as(true)
        } yield assertTrue(succeed)
      ),
      test("ls ")(
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
      test("readFile with offset") {
        for {
          content <- readFile("/notes.txt", fileOffset = 16).via(utf8Decode).runCollect
        } yield assert(content.mkString)(equalTo("this is a beautiful day"))
      },
      test("readFile, not trying to read beyond the end") {
        for {
          size    <- stat("/notes.txt").someOrFail("ouch")
          content <- readFile("/notes.txt").take(size.size).via(utf8Decode).runCollect
          size2   <- stat("/notes.txt") // DO NOT REMOVE THIS LINE!!!
          // It is here to ensure that the connection is still usable after `readFile`,
          // which is not the case if `completePendingCommand` isn't called
        } yield assert(content.mkString)(
          equalTo("""|Hello world !!!
                     |this is a beautiful day""".stripMargin)
        ) && assertTrue(size2.nonEmpty)
      },
      test("readFile does not exist") {
        for {
          invalid <- readFile("/invalid.txt")
                       .via(utf8Decode)
                       .runCollect
                       .flip
                       .map(_.getMessage)

        } yield assertTrue(invalid == "550 Can't open /invalid.txt: No such file or directory")
      },
      test("mkdir directory") {
        (
          for {
            result <- mkdir("/new-dir").as(true)
          } yield assert(result)(equalTo(true))
        ) <* Files.delete(home / "new-dir")
      },
      test("mkdir fail when invalid path") {
        for {
          failure <- mkdir("/dir1/users.csv").flip.map(_.getMessage)
        } yield assert(failure)(containsString("Path is invalid. Cannot create directory : /dir1/users.csv"))
      },
      test("rm valid path") {
        val path = home / "to-delete.txt"

        for {
          _       <- Files.createFile(path)
          success <- rm("/to-delete.txt").as(true)

          fileExist <- Files.notExists(path)
        } yield assertTrue(success && fileExist)
      },
      test("rm fail when invalid path") {
        for {
          invalid <- rm("/dont-exist").flip.map(_.getMessage)
        } yield assertTrue(invalid == "Path is invalid. Cannot delete file : /dont-exist")
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
        } yield assertTrue(r == "Path is invalid. Cannot delete directory : /dont-exist")
      },
      test("upload a file") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        val path = home / "hello-world.txt"

        (for {
          _      <- upload("/hello-world.txt", data)
          result <-
            acquireRelease(attemptBlockingIO(Source.fromFile(path.toFile)))(b => attemptBlockingIO(b.close()).ignore)
              .map(_.mkString)

        } yield assert(result)(equalTo("Hello F World"))) <* Files.delete(path)
      },
      test("upload fail when path is invalid") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload("/dont-exist/hello-world.txt", data).flip.map(_.getMessage)
        } yield assertTrue(failure == "Path is invalid. Cannot upload data to : /dont-exist/hello-world.txt")
      },
      test("rename valid path") {
        val oldPath = home / "to-rename.txt"
        val newPath = home / "to-rename-destination.txt"

        (for {
          _       <- Files.createFile(oldPath)
          success <- rename("/to-rename.txt", "/to-rename-destination.txt").as(true)

          oldFileExists <- Files.exists(oldPath)
          newFileExists <- Files.exists(newPath)
        } yield assertTrue(success && !oldFileExists && newFileExists)) <* Files.delete(newPath)
      },
      test("rename fail when invalid path") {
        for {
          invalid <- rename("/dont-exist", "/dont-exist-destination").flip.map(_.getMessage)
        } yield assertTrue(invalid == "Path is invalid. Cannot rename /dont-exist to /dont-exist-destination")
      },
      test("call noOp underlying client") {
        for {
          noOp <- execute(_.sendNoOp())
        } yield assertTrue(noOp)
      }
    )
}
