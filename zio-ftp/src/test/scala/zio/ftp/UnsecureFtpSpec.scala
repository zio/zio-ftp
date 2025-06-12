package zio.ftp

import zio.ZIO.{ acquireRelease, attemptBlockingIO }
import zio.{ test => _, _ }
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.ftp.Ftp._
import zio.stream.ZPipeline.utf8Decode
import zio.stream.ZStream
import java.net.{ InetSocketAddress, Proxy }
import scala.io.Source
import java.nio.file.{ Path, Paths }
import java.nio.file.Files
import java.time.temporal.ChronoUnit

object UnsecureSslFtpSpec extends ZIOSpecDefault {
  private val settings = UnsecureFtpSettings.secure("127.0.0.1", 2121, FtpCredentials("username", "userpass"))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    FtpSuite.spec("UnsecureSslFtpSpec", settings).provideSomeLayer[Scope](unsecure(settings)) @@ sequential
}

object UnsecureFtpSpec extends ZIOSpecDefault {
  private val settings = UnsecureFtpSettings("127.0.0.1", port = 2121, FtpCredentials("username", "userpass"))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    FtpSuite.spec("UnsecureFtpSpec", settings).provideSomeLayer[Scope](unsecure(settings)) @@ sequential
}

object FtpSuite {
  private val home = Paths.get("ftp-home/ftp/home")

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
          files <- ls(Paths.get("/")).runFold(List.empty[Path])((s, f) => f.path +: s)
        } yield assert(files.reverse)(hasSameElements(List(Paths.get("/notes.txt"), Paths.get("/dir1"))))
      ),
      test("timestamp")(
        for {
          file     <- ls(Paths.get("/notes.txt")).runLast
          filetime <- ZIO.attempt(Files.getLastModifiedTime(Paths.get("ftp-home/sftp/home/foo/notes.txt")))

        } yield assertTrue(
          file
            .exists(_.lastModified == filetime.toInstant().truncatedTo(ChronoUnit.MINUTES))
        )
      ),
      test("ls with invalid directory")(
        for {
          files <- ls(Paths.get("/dont-exist")).runFold(List.empty[Path])((s, f) => f.path +: s)
        } yield assert(files.reverse)(hasSameElements(Nil))
      ),
      test("ls descendant")(
        for {
          files <- lsDescendant(Paths.get("/")).runFold(List.empty[Path])((s, f) => f.path +: s)
        } yield assert(files.reverse)(
          hasSameElements(List("/notes.txt", "/dir1/users.csv", "/dir1/console.dump").map(Paths.get(_)))
        )
      ),
      test("ls descendant with invalid directory")(
        for {
          files <- lsDescendant(Paths.get("/dont-exist")).runCollect
        } yield assertTrue(files == Chunk.empty)
      ),
      test("stat directory") {
        for {

          file <- stat(Paths.get("/dir1"))
        } yield assertTrue(file.get.path == Paths.get("/dir1")) &&
          assertTrue(file.get.isDirectory.get)
      },
      test("stat file") {
        for {
          file <- stat(Paths.get("/dir1/console.dump"))
        } yield assertTrue(file.get.path == Paths.get("/dir1/console.dump")) &&
          assertTrue(!file.get.isDirectory.get)
      },
      test("stat file does not exist") {
        for {
          file <- stat(Paths.get("/wrong-path.xml"))
        } yield assertTrue(file.isEmpty)
      },
      test("stat directory does not exist") {
        for {
          file <- stat(Paths.get("/wrong-path"))
        } yield assertTrue(file.isEmpty)
      },
      test("readFile") {
        for {
          content <- readFile(Paths.get("/notes.txt")).via(utf8Decode).runCollect
        } yield assert(content.mkString)(equalTo("""|Hello world !!!
                                                    |this is a beautiful day""".stripMargin))
      },
      test("readFile with offset") {
        for {
          content <- readFile(Paths.get("/notes.txt"), fileOffset = 16).via(utf8Decode).runCollect
        } yield assert(content.mkString)(equalTo("this is a beautiful day"))
      },
      test("readFile does not exist") {
        for {
          invalid <- readFile(Paths.get("/invalid.txt"))
                       .via(utf8Decode)
                       .runCollect
                       .flip
                       .map(_.getMessage)

        } yield assertTrue(invalid == "File does not exist /invalid.txt")
      },
      test("mkdir directory") {
        (
          for {
            result <- mkdir(Paths.get("/new-dir")).as(true)
          } yield assert(result)(equalTo(true))
        ) <* ZIO.attempt(Files.delete(home.resolve("new-dir")))
      },
      test("mkdir fail when invalid path") {
        for {
          failure <- mkdir(Paths.get("/dir1/users.csv")).flip.map(_.getMessage)
        } yield assert(failure)(containsString("Path is invalid. Cannot create directory : /dir1/users.csv"))
      },
      test("rm valid path") {
        val path = home.resolve("to-delete.txt")

        for {
          _       <- ZIO.attempt(Files.createFile(path))
          success <- rm(Paths.get("/to-delete.txt")).as(true)

          fileExist <- ZIO.attempt(Files.notExists(path))
        } yield assertTrue(success && fileExist)
      },
      test("rm fail when invalid path") {
        for {
          invalid <- rm(Paths.get("/dont-exist")).flip.map(_.getMessage)
        } yield assertTrue(invalid == "Path is invalid. Cannot delete file : /dont-exist")
      },
      test("rm directory") {
        val path = home.resolve("dir-to-delete")

        for {
          _     <- ZIO.attempt(Files.createDirectory(path))
          r     <- rmdir(Paths.get("/dir-to-delete")).as(true)
          exist <- ZIO.attempt(Files.notExists(path))
        } yield assertTrue(r && exist)
      },
      test("rm fail invalid directory") {
        for {
          r <- rmdir(Paths.get("/dont-exist"))
                 .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")
        } yield assertTrue(r == "Path is invalid. Cannot delete directory : /dont-exist")
      },
      test("upload a file") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        val path = home.resolve("hello-world.txt")

        (for {
          _      <- upload(Paths.get("/hello-world.txt"), data)
          result <-
            acquireRelease(attemptBlockingIO(Source.fromFile(path.toFile)))(b => attemptBlockingIO(b.close()).ignore)
              .map(_.mkString)

        } yield assert(result)(equalTo("Hello F World"))) <* ZIO.attempt(Files.delete(path))
      },
      test("upload fail when path is invalid") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload(Paths.get("/dont-exist/hello-world.txt"), data).flip.map(_.getMessage)
        } yield assertTrue(failure == "Path is invalid. Cannot upload data to : /dont-exist/hello-world.txt")
      },
      test("rename valid path") {
        val oldPath = home.resolve("to-rename.txt")
        val newPath = home.resolve("to-rename-destination.txt")

        (for {
          _       <- ZIO.attempt(Files.createFile(oldPath))
          success <- rename(Paths.get("/to-rename.txt"), Paths.get("/to-rename-destination.txt")).as(true)

          oldFileExists <- ZIO.attempt(Files.exists(oldPath))
          newFileExists <- ZIO.attempt(Files.exists(newPath))
        } yield assertTrue(success && !oldFileExists && newFileExists)) <* ZIO.attempt(Files.delete(newPath))
      },
      test("rename fail when invalid path") {
        for {
          invalid <- rename(Paths.get("/dont-exist"), Paths.get("/dont-exist-destination")).flip.map(_.getMessage)
        } yield assertTrue(invalid == "Path is invalid. Cannot rename /dont-exist to /dont-exist-destination")
      },
      test("call noOp underlying client") {
        for {
          noOp <- execute(_.sendNoOp())
        } yield assertTrue(noOp)
      }
    )
}
