package zio.ftp

import zio.ZIO.{ acquireRelease, attempt, attemptBlockingIO }
import zio._
import zio.ftp.SFtp._
import zio.stream.ZPipeline.utf8Decode
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._

import java.net.{ InetSocketAddress, Proxy }
import java.nio.file.{ Files, Paths }
import java.time.{ Duration => JDuration }
import scala.io.Source
import scala.util.chaining._

object Load

object SecureFtpSpec extends ZIOSpecDefault {
  val settings = SecureFtpSettings("127.0.0.1", port = 2222, FtpCredentials("foo", "foo"))

  val home = Paths.get("ftp-home/sftp/home/foo")

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SecureFtpSpec")(
      test("invalid credentials")(
        for {
          succeed <- SecureFtp
                       .connect(settings.copy(credentials = FtpCredentials("test", "test")))
                       .flip
                       .map(_.getMessage)
        } yield assertTrue(succeed.contains("Fail to connect to server"))
      ),
      test("invalid proxy")(
        for {
          failure <- SecureFtp
                       .connect(
                         settings.copy(proxy = Some(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("invalid", 9999))))
                       )
                       .flip
                       .map(_.getMessage)
        } yield assertTrue(failure.contains("Fail to connect to server"))
      ),
      test("valid credentials")(
        for {
          succeed <- SecureFtp.connect(settings).as(true)
        } yield assertTrue(succeed)
      ),
      test("connect with ssh key file") {
        for {
          privatekey <- acquireRelease(
                          attemptBlockingIO(io.Source.fromFile(Load.getClass.getResource("/ssh_host_rsa_key").toURI))
                        )(b => attemptBlockingIO(b.close()).ignore)
                          .map(_.mkString)

          settings    = SecureFtpSettings("127.0.0.1", 3333, FtpCredentials("fooz", ""), RawKeySftpIdentity(privatekey))
          succeed    <- SecureFtp.connect(settings).as(true)
        } yield assertTrue(succeed)
      },
      test("connect with ssh key") {
        for {
          privatekey <- ZIO.succeed(Paths.get(Load.getClass.getResource("/ssh_host_rsa_key").toURI))
          settings    = SecureFtpSettings(
                          "127.0.0.1",
                          3333,
                          FtpCredentials("fooz", ""),
                          KeyFileSftpIdentity(privatekey, None)
                        )
          succeed    <- SecureFtp.connect(settings).as(true)
        } yield assertTrue(succeed)
      },
      test("ls")(
        for {
          files    <- ls(Paths.get("/")).runCollect
          filetime <- ZIO.attempt(Files.getLastModifiedTime(Paths.get("ftp-home/ftp/home/notes.txt")))
        } yield assertTrue(
          files.map(_.path).toSet == Set(Paths.get("/notes.txt"), Paths.get("/dir1")) && files
            .find(_.path == Paths.get("/notes.txt"))
            .is(_.some)
            .pipe(r => JDuration.between(r.lastModified, filetime.toInstant()).abs.toMillis < 10000)
        )
      ),
      test("ls with invalid directory")(
        for {
          files <- ls(Paths.get("/dont-exist")).runCollect
        } yield assert(files.map(_.path))(hasSameElements(Nil))
      ),
      test("ls descendant")(
        for {
          files <- lsDescendant(Paths.get("/")).runCollect
        } yield assert(files.map(_.path.toString))(
          hasSameElements(List("/notes.txt", "/dir1/users.csv", "/dir1/console.dump"))
        )
      ),
      test("ls descendant with invalid directory")(
        for {
          files <- lsDescendant(Paths.get("/dont-exist")).runCollect
        } yield assert(files.map(_.path))(hasSameElements(Nil))
      ),
      test("stat file") {
        for {
          file <- stat(Paths.get("/dir1/users.csv"))
        } yield assertTrue(file.get.path == Paths.get("/dir1/users.csv")) &&
          assertTrue(file.get.isDirectory.isEmpty)
      },
      test("stat directory") {
        for {
          file <- stat(Paths.get("/dir1"))
        } yield assertTrue(file.get.path == Paths.get("/dir1")) &&
          assertTrue(file.get.isDirectory.isEmpty)
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
          content <- readFile(Paths.get("/notes.txt"))
                       .via(utf8Decode)
                       .runCollect
        } yield assertTrue(
          content.mkString("") ==
            """|Hello world !!!
               |this is a beautiful day""".stripMargin
        )
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
                       .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)

        } yield assertTrue(invalid == "No such file")
      },
      test("mkdir directory") {
        (for {
          result <- mkdir(Paths.get("/dir1/new-dir")).as(true)
        } yield assertTrue(result)) <* attempt(Files.delete(home.resolve("dir1/new-dir")))
      },
      test("mkdir fail when invalid path") {
        for {
          failure <- mkdir(Paths.get("/dir1/users.csv")).flip.map(_.getMessage)
        } yield assert(failure)(containsString("/dir1/users.csv exists but is not a directory"))
      },
      test("rm valid path") {
        val path = home.resolve("dir1/to-delete.txt")
        Files.createFile(path)

        for {
          success   <- rm(Paths.get("/dir1/to-delete.txt")).as(true)
          fileExist <- attempt(Files.notExists(path))
        } yield assertTrue(success && fileExist)
      },
      test("rm fail when invalid path") {
        for {
          invalid <- rm(Paths.get("/dont-exist")).flip.map(_.getMessage)
        } yield assertTrue(invalid == "No such file")
      },
      test("rm directory") {
        val path = home.resolve("dir1/dir-to-delete")
        Files.createDirectory(path)

        for {
          r     <- rmdir(Paths.get("/dir1/dir-to-delete")).as(true)
          exist <- attempt(Files.notExists(path))
        } yield assertTrue(r && exist)
      },
      test("rm fail invalid directory") {
        for {
          r <- rmdir(Paths.get("/dont-exist")).flip.map(_.getMessage)
        } yield assertTrue(r == "No such file")
      },
      test("upload a file") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))
        val path = home.resolve("dir1/hello-world.txt")

        (
          for {
            _      <- upload(Paths.get("/dir1/hello-world.txt"), data)
            result <- acquireRelease(attemptBlockingIO(Source.fromFile(path.toFile)))(b =>
                        attemptBlockingIO(b.close()).ignore
                      ).map(_.mkString)

          } yield assert(result)(equalTo("Hello F World"))
        ) <* attempt(Files.delete(path))
      },
      test("upload fail when path is invalid") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload(Paths.get("/dont-exist/hello-world.txt"), data).flip.map(_.getMessage)
        } yield assertTrue(failure == "No such file")
      },
      test("call version() underlying client") {
        for {
          version <- execute(_.version())
        } yield assert(version.toString)(isNonEmptyString)
      },
      test("rename valid path") {
        val oldPath = home.resolve("dir1/to-rename.txt")
        val newPath = home.resolve("dir1/to-rename-destination.txt")
        Files.createFile(oldPath)

        (
          for {
            success       <- rename(Paths.get("/dir1/to-rename.txt"), Paths.get("/dir1/to-rename-destination.txt")).as(true)
            oldFileExists <- attempt(Files.exists(oldPath))
            newFileExists <- attempt(Files.exists(newPath))
          } yield assertTrue(success && !oldFileExists && newFileExists)
        ) <* attempt(Files.delete(newPath))
      },
      test("rename fail when invalid path") {
        for {
          invalid <- rename(Paths.get("/dont-exist"), Paths.get("dont-exist-destination")).flip.map(_.getMessage)
        } yield assertTrue(invalid == "No such file")
      }
    ).provideSomeLayerShared[Scope](secure(settings))
}
