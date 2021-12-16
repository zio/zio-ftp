package zio.ftp

import java.net.{InetSocketAddress, Proxy}
import java.nio.file.{Files, Paths}
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

  val home = Paths.get("ftp-home/sftp/home/foo/work")

  val sftp = secure(settings).mapError(TestFailure.die(_))

  override def spec =
    suite("SFtpSpec")(
      test("invalid credentials")(
        for {
          succeed <- SecureFtp
                       .connect(settings.copy(credentials = FtpCredentials("test", "test")))
                       .use(_ => IO.succeed(""))
                       .foldCause(_.failureOption.map(_.getMessage).mkString, identity)
        } yield assertTrue(succeed.contains("Fail to connect to server"))
      ),
      test("invalid proxy")(
        for {
          failure <- SecureFtp
                       .connect(
                         settings.copy(proxy = Some(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("invalid", 9999))))
                       )
                       .use(_ => IO.succeed(""))
                       .foldCause(_.failureOption.map(_.getMessage).mkString, identity)
        } yield assertTrue(failure.contains("Fail to connect to server"))
      ),
      test("valid credentials")(
        for {
          succeed <- SecureFtp.connect(settings).use(_ => IO.succeed(true))
        } yield assertTrue(succeed)
      ),
      test("connect with ssh key file") {
        for {
          privatekey <- Managed
                          .acquireReleaseWith(
                            IO(io.Source.fromFile(Load.getClass.getResource("/ssh_host_rsa_key").toURI))
                          )(s => IO(s.close()).ignore)
                          .use(b => Task(b.mkString))
          settings    = SecureFtpSettings("127.0.0.1", 3333, FtpCredentials("fooz", ""), RawKeySftpIdentity(privatekey))
          succeed    <- SecureFtp.connect(settings).use(_ => IO.succeed(true))
        } yield assertTrue(succeed)
      },
      test("connect with ssh key") {
        for {
          privatekey <- Task(Paths.get(Load.getClass.getResource("/ssh_host_rsa_key").toURI))
          settings    = SecureFtpSettings(
                          "127.0.0.1",
                          3333,
                          FtpCredentials("fooz", ""),
                          KeyFileSftpIdentity(privatekey, None)
                        )
          succeed    <- SecureFtp.connect(settings).use(_ => IO.succeed(true))
        } yield assertTrue(succeed)
      },
      test("ls")(
        for {
          files <- ls("/").runCollect
        } yield assert(files.map(_.path))(hasSameElements(List("/empty.txt", "/work")))
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
          hasSameElements(List("/empty.txt", "/work/notes.txt", "/work/dir1/users.csv", "/work/dir1/console.dump"))
        )
      ),
      test("ls descendant with invalid directory")(
        for {
          files <- lsDescendant("/dont-exist").runCollect
        } yield assert(files.map(_.path))(hasSameElements(Nil))
      ),
      test("stat file") {
        for {
          file <- stat("/work/dir1/users.csv")
        } yield
          assertTrue(file.get.path == "/work/dir1/users.csv") &&
            assertTrue(file.get.isDirectory.isEmpty)

      },
      test("stat directory") {
        for {
          file <- stat("/work/dir1")
        } yield
          assertTrue(file.get.path == "/work/dir1") &&
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
          content <- readFile("/work/notes.txt")
                       .via(utf8Decode)
                       .runCollect
        } yield assertTrue(content.mkString ==
          """|Hello world !!!
             |this is a beautiful day""".stripMargin)
      },
      test("readFile does not exist") {
        for {
          invalid <- readFile("/work/invalid.txt")
                       .via(utf8Decode)
                       .runCollect
                       .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)

        } yield assertTrue(invalid == "No such file")
      },
      test("mkdir directory") {
        (
          for {
            result <- mkdir("/work/new-dir").as(true)
          } yield assertTrue(result)
        ) <* Task(Files.delete(home.resolve("new-dir")))
      },
      test("mkdir fail when invalid path") {
        for {
          failure <- mkdir("/work/dir1/users.csv")
                       .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assertTrue(failure.contains("/work/dir1/users.csv exists but is not a directory"))
      },
      test("rm valid path") {
        val path = home.resolve("to-delete.txt")
        Files.createFile(path)

        for {
          success   <- rm("/work/to-delete.txt")
                         .foldCause(_ => false, _ => true)

          fileExist <- Task(Files.notExists(path))
        } yield assertTrue(success && fileExist)
      },
      test("rm fail when invalid path") {
        for {
          invalid <- rm("/work/dont-exist")
                       .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assertTrue(invalid == "No such file")
      },
      test("rm directory") {
        val path = home.resolve("dir-to-delete")
        Files.createDirectory(path)

        for {
          r     <- rmdir("/work/dir-to-delete").as(true)
          exist <- Task(Files.notExists(path))
        } yield assertTrue(r && exist)
      },
      test("rm fail invalid directory") {
        for {
          r <- rmdir("/work/dont-exist")
                 .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assertTrue(r == "No such file")
      },
      test("upload a file") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))
        val path = home.resolve("hello-world.txt")

        (
          for {
            _ <- upload("/work/hello-world.txt", data)
            result <- Managed
              .acquireReleaseWith(Task(Source.fromFile(path.toFile)))(s => UIO(s.close))
              .use(b => Task(b.mkString))
          } yield assertTrue(result == "Hello F World")
        ) <* Task(Files.delete(path))
      },
      test("upload fail when path is invalid") {
        val data = ZStream.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload("/work/dont-exist/hello-world.txt", data)
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
