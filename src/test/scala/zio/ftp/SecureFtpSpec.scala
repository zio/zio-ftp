package zio.ftp

import zio.ZIO.{ attempt, attemptBlockingIO }

import java.net.{ InetSocketAddress, Proxy }
import java.nio.file.{ Files, Paths }
import zio._
import zio.ftp.SFtp._
import zio.stream.ZPipeline.utf8Decode
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._

import scala.io.Source

object Load

object SFtpTest extends ZIOSpecDefault {
  val settings = SecureFtpSettings("127.0.0.1", port = 2222, FtpCredentials("foo", "foo"))

  val home = Paths.get("ftp-home/sftp/home/foo")

  val sftp = Scope.default >+> secure(settings).mapError(TestFailure.die(_))

  override def spec =
    suite("SecureFtpSpec")(
      test("invalid credentials")(
        for {
          succeed <- SecureFtp
                       .connect(settings.copy(credentials = FtpCredentials("test", "test")))
                       .map(_ => "")
                       .foldCause(_.failureOption.map(_.getMessage).mkString, identity)
        } yield assertTrue(succeed.contains("Fail to connect to server"))
      ),
      test("invalid proxy")(
        for {
          failure <- SecureFtp
                       .connect(
                         settings.copy(proxy = Some(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("invalid", 9999))))
                       )
                       .map(_ => "")
                       .foldCause(_.failureOption.map(_.getMessage).mkString, identity)
        } yield assertTrue(failure.contains("Fail to connect to server"))
      ),
      test("valid credentials")(
        for {
          succeed <- SecureFtp.connect(settings).map(_ => true)
        } yield assertTrue(succeed)
      ),
      test("connect with ssh key file") {
        for {
          privatekey <-
            ZIO.scoped(
              ZIO
                .fromAutoCloseable(
                  attemptBlockingIO(io.Source.fromFile(Load.getClass.getResource("/ssh_host_rsa_key").toURI))
                )
                .map(b => b.mkString)
            )
          settings    = SecureFtpSettings("127.0.0.1", 3333, FtpCredentials("fooz", ""), RawKeySftpIdentity(privatekey))
          succeed    <- SecureFtp.connect(settings).map(_ => true)
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
          succeed    <- SecureFtp.connect(settings).map(_ => true)
        } yield assertTrue(succeed)
      },
      test("ls")(
        for {
          files <- ls("/").runCollect
        } yield assert(files.map(_.path))(hasSameElements(List("/notes.txt", "/dir1")))
      ),
      test("ls with invalid directory")(
        for {
          files <- ls("/dont-exist").runCollect
        } yield assert(files.map(_.path))(hasSameElements(Nil))
      ),
      test("ls descendant")(
        for {
          files <- lsDescendant("/").runCollect
        } yield assert(files.map(_.path))(
          hasSameElements(List("/notes.txt", "/dir1/users.csv", "/dir1/console.dump"))
        )
      ),
      test("ls descendant with invalid directory")(
        for {
          files <- lsDescendant("/dont-exist").runCollect
        } yield assert(files.map(_.path))(hasSameElements(Nil))
      ),
      test("stat file") {
        for {
          file <- stat("/dir1/users.csv")
        } yield assertTrue(file.get.path == "/dir1/users.csv") &&
          assertTrue(file.get.isDirectory.isEmpty)
      },
      test("stat directory") {
        for {
          file <- stat("/dir1")
        } yield assertTrue(file.get.path == "/dir1") &&
          assertTrue(file.get.isDirectory.isEmpty)
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
          content <- readFile("/notes.txt")
                       .via(utf8Decode)
                       .runCollect
        } yield assertTrue(
          content.mkString ==
            """|Hello world !!!
               |this is a beautiful day""".stripMargin
        )
      },
      test("readFile does not exist") {
        for {
          invalid <- readFile("/invalid.txt")
                       .via(utf8Decode)
                       .runCollect
                       .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)

        } yield assertTrue(invalid == "No such file")
      },
      test("mkdir directory") {
        (for {
          result <- mkdir("/dir1/new-dir").map(_ => true)
        } yield assertTrue(result)) <* attempt(Files.delete(home.resolve("dir1/new-dir")))
      },
      test("mkdir fail when invalid path") {
        for {
          failure <- mkdir("/dir1/users.csv")
                       .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assert(failure)(containsString("/dir1/users.csv exists but is not a directory"))
      },
      test("rm valid path") {
        val path = home.resolve("dir1/to-delete.txt")
        Files.createFile(path)

        for {
          success   <- rm("/dir1/to-delete.txt")
                         .foldCause(_ => false, _ => true)

          fileExist <- attempt(Files.notExists(path))
        } yield assertTrue(success && fileExist)
      },
      test("rm fail when invalid path") {
        for {
          invalid <- rm("/dont-exist")
                       .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assertTrue(invalid == "No such file")
      },
      test("rm directory") {
        val path = home.resolve("dir1/dir-to-delete")
        Files.createDirectory(path)

        for {
          r     <- rmdir("/dir1/dir-to-delete").map(_ => true)
          exist <- attempt(Files.notExists(path))
        } yield assertTrue(r && exist)
      },
      test("rm fail invalid directory") {
        for {
          r <- rmdir("/dont-exist")
                 .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assertTrue(r == "No such file")
      },
      test("upload a file") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))
        val path = home.resolve("dir1/hello-world.txt")

        (
          for {
            _      <- upload("/dir1/hello-world.txt", data)
            result <- ZIO
                        .fromAutoCloseable(attemptBlockingIO(Source.fromFile(path.toFile)))
                        .map(_.mkString)
          } yield assert(result)(equalTo("Hello F World"))
        ) <* attempt(Files.delete(path))
      },
      test("upload fail when path is invalid") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload("/dont-exist/hello-world.txt", data)
                       .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assertTrue(failure == "No such file")
      },
      test("call version() underlying client") {
        for {
          version <- execute(_.version())
        } yield assert(version.toString)(isNonEmptyString)
      }
    ).provideCustomLayerShared(sftp)
}
