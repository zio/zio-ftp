package zio.ftp

import zio.{test => _, _}
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._

import zio.ftp.Ftp._
import zio.nio.core.file.{Path => ZPath}
import zio.nio.file.Files
import zio.stream.ZPipeline.utf8Decode
import zio.stream.ZStream

import java.net.{InetSocketAddress, Proxy}
import scala.io.Source

object FtpsTest extends ZIOSpecDefault {
  val settings = UnsecureFtpSettings.secure("127.0.0.1", 2121, FtpCredentials("username", "userpass"))

  val ftp = unsecure(settings).mapError(TestFailure.die(_))

  override def spec =
    FtpSuite.spec("FtpsSpec", settings).provideCustomLayer(ftp) @@ sequential
}

object FtpTest extends ZIOSpecDefault {
  val settings = UnsecureFtpSettings("127.0.0.1", port = 2121, FtpCredentials("username", "userpass"))
  val ftp      = unsecure(settings).mapError(TestFailure.die(_))

  override def spec =
    FtpSuite.spec("FtpSpec", settings).provideCustomLayer(ftp) @@ sequential
}

object FtpSuite {
  val home = ZPath("ftp-home/ftp/home")

  def spec(
    labelSuite: String,
    settings: UnsecureFtpSettings
  ): Spec[Ftp, TestFailure[Throwable], TestSuccess] =
    suite(labelSuite)(
      test("invalid credentials")(
        for {
          failure <- UnsecureFtp
                       .connect(settings.copy(credentials = FtpCredentials("test", "test")))
                       .use(_ => IO.succeed(""))
                       .foldCause(_.failureOption.map(_.getMessage).mkString, s => s)
        } yield assert(failure)(containsString("Fail to connect to server"))
      ),
      test("invalid proxy")(
        for {
          failure <- UnsecureFtp
                       .connect(
                         settings.copy(proxy = Some(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("invalid", 9999))))
                       )
                       .use(_ => IO.succeed(""))
                       .foldCause(_.failureOption.map(_.getMessage).mkString, s => s)
        } yield assertTrue(failure.contains("invalid"))
      ),
      test("valid credentials")(
        for {
          succeed <- UnsecureFtp.connect(settings).use(_ => IO.succeed(true))
        } yield assertTrue(succeed)
      ),
      test("ls ")(
        for {
          files <- ls("/").runFold(List.empty[String])((s, f) => f.path +: s)
        } yield assert(files.reverse)(hasSameElements(List("/empty.txt", "/work")))
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
          hasSameElements(List("/empty.txt", "/work/notes.txt", "/work/dir1/users.csv", "/work/dir1/console.dump"))
        )
      ),
      test("ls descendant with invalid directory")(
        for {
          files <- lsDescendant("/dont-exist").runCollect
        } yield assertTrue(files == Chunk.empty)
      ),
      test("stat directory") {
        for {
          file <- stat("/work/dir1")
        } yield
          assertTrue(file.get.path == "/work/dir1") &&
            assertTrue(file.get.isDirectory.get)
      },
      test("stat file") {
        for {
          file <- stat("/work/dir1/console.dump")
        } yield
          assertTrue(file.get.path == "/work/dir1/console.dump") &&
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
          content <- readFile("/work/notes.txt").via(utf8Decode).runCollect
        } yield assertTrue(content.mkString ==
          """|Hello world !!!
             |this is a beautiful day""".stripMargin)
      },
      test("readFile does not exist") {
        for {
          invalid <- readFile("/invalid.txt")
                       .via(utf8Decode)
                       .runCollect
                       .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)

        } yield assertTrue(invalid == "File does not exist /invalid.txt")
      },
      test("mkdir directory") {
        (
          for {
            result <- mkdir("/work/new-dir").as(true)
          } yield assertTrue(result)
        ) <* Files.delete(home / "work" / "new-dir")
      },
      test("mkdir fail when invalid path") {
        for {
          failure <- mkdir("/work/dir1/users.csv")
                       .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")

        } yield assertTrue(failure.contains("Path is invalid. Cannot create directory : /work/dir1/users.csv"))
      },
      test("rm valid path") {
        val path = home / "work" / "to-delete.txt"

        for {
          _       <- Files.createFile(path)
          success <- rm("/work/to-delete.txt").as(true)

          fileExist <- Files.notExists(path)
        } yield assertTrue(success && fileExist)
      },
      test("rm fail when invalid path") {
        for {
          invalid <- rm("/dont-exist")
                       .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")

        } yield assertTrue(invalid == "Path is invalid. Cannot delete file : /dont-exist")
      },
      test("rm directory") {
        val path = home / "work" / "dir-to-delete"

        for {
          _     <- Files.createDirectory(path)
          r     <- rmdir("/work/dir-to-delete").as(true)
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
        val path = home / "work" / "hello-world.txt"
        (
          for {
            _ <- upload("/work/hello-world.txt", data)
            result <- Managed
              .acquireReleaseWith(Task(Source.fromFile(path.toFile)))(s => UIO(s.close))
              .use(b => Task(b.mkString))
          } yield assertTrue(result == "Hello F World")
        ) <* Files.delete(path)
      },
      test("upload fail when path is invalid") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload("/dont-exist/hello-world.txt", data)
                       .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assertTrue(failure == "Path is invalid. Cannot upload data to : /dont-exist/hello-world.txt")
      },
      test("call noOp underlying client") {
        for {
          noOp <- execute(_.sendNoOp())
        } yield assertTrue(noOp)
      }
    )
}
