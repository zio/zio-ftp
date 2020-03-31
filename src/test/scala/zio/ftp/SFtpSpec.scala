package zio.ftp

import java.net.{ InetSocketAddress, Proxy }
import java.nio.file.{ Files, Paths }

import zio._
import zio.blocking.Blocking
import zio.stream.{ ZSink, ZStreamChunk }
import zio.test.Assertion._
import zio.test._
import scala.io.Source
import SFtp._
object Load

object SFtpTest extends DefaultRunnableSpec {
  val settings = SecureFtpSettings("127.0.0.1", port = 2222, FtpCredentials("foo", "foo"))

  val home = Paths.get("ftp-home/sftp/home/foo/work")

  val sftp = Blocking.live >>> secure(settings).mapError(TestFailure.die(_))

  override def spec =
    suite("SFtpSpec")(
      testM("invalid credentials")(
        for {
          succeed <- SecureFtp
                      .connect(settings.copy(credentials = FtpCredentials("test", "test")))
                      .use(_ => IO.succeed(""))
                      .foldCause(_.failureOption.map(_.getMessage).mkString, identity)
        } yield assert(succeed)(containsString("Fail to connect to server"))
      ),
      testM("invalid proxy")(
        for {
          failure <- SecureFtp
                      .connect(
                        settings.copy(proxy = Some(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("invalid", 9999))))
                      )
                      .use(_ => IO.succeed(""))
                      .foldCause(_.failureOption.map(_.getMessage).mkString, identity)
        } yield assert(failure)(containsString("Fail to connect to server"))
      ),
      testM("valid credentials")(
        for {
          succeed <- SecureFtp.connect(settings).use(_ => IO.succeed(true))
        } yield assert(succeed)(equalTo(true))
      ),
      testM("connect with ssh key file") {
        for {
          privatekey <- Managed
                         .fromAutoCloseable(
                           IO(io.Source.fromFile(Load.getClass.getResource("/ssh_host_rsa_key").toURI))
                         )
                         .use(b => Task(b.mkString))
          settings = SecureFtpSettings("127.0.0.1", 3333, FtpCredentials("fooz", ""), RawKeySftpIdentity(privatekey))
          succeed  <- SecureFtp.connect(settings).use(_ => IO.succeed(true))
        } yield assert(succeed)(equalTo(true))
      },
      testM("connect with ssh key") {
        for {
          privatekey <- Task(Paths.get(Load.getClass.getResource("/ssh_host_rsa_key").toURI))
          settings = SecureFtpSettings(
            "127.0.0.1",
            3333,
            FtpCredentials("fooz", ""),
            KeyFileSftpIdentity(privatekey, None)
          )
          succeed <- SecureFtp.connect(settings).use(_ => IO.succeed(true))
        } yield assert(succeed)(equalTo(true))
      },
      testM("ls")(
        for {
          files <- ls("/").runCollect
        } yield assert(files.map(_.path))(hasSameElements(List("/empty.txt", "/work")))
      ),
      testM("ls with invalid directory")(
        for {
          files <- ls("/dont-exist").runCollect
        } yield assert(files.map(_.path))(hasSameElements(Nil))
      ),
      testM("ls descendant")(
        for {
          files <- lsDescendant("/").runCollect
        } yield assert(files.map(_.path))(
          hasSameElements(List("/empty.txt", "/work/notes.txt", "/work/dir1/users.csv", "/work/dir1/console.dump"))
        )
      ),
      testM("ls descendant with invalid directory")(
        for {
          files <- lsDescendant("/dont-exist").runCollect
        } yield assert(files.map(_.path))(hasSameElements(Nil))
      ),
      testM("stat file") {
        for {
          file <- stat("/work/dir1/users.csv")
        } yield assert(file.map(f => f.path -> f.isDirectory))(equalTo(Some("/work/dir1/users.csv" -> None)))
      },
      testM("stat directory") {
        for {
          file <- stat("/work/dir1")
        } yield assert(file.map(f => f.path -> f.isDirectory))(equalTo(Some("/work/dir1" -> None)))
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
          content <- readFile("/work/notes.txt").run(ZSink.utf8DecodeChunk)
        } yield assert(content)(equalTo("""|Hello world !!!
                                           |this is a beautiful day""".stripMargin))
      },
      testM("readFile does not exist") {
        for {
          invalid <- readFile("/work/invalid.txt")
                      .run(ZSink.utf8DecodeChunk)
                      .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)

        } yield assert(invalid)(equalTo("No such file"))
      },
      testM("mkdir directory") {
        (for {
          result <- mkdir("/work/new-dir").map(_ => true)
        } yield assert(result)(equalTo(true)))
          .tap(_ => Task(Files.delete(home.resolve("new-dir"))))
      },
      testM("mkdir fail when invalid path") {
        for {
          failure <- mkdir("/work/dir1/users.csv")
                      .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield {
          assert(failure)(containsString("/work/dir1/users.csv exists but is not a directory"))
        }
      },
      testM("rm valid path") {
        val path = home.resolve("to-delete.txt")
        Files.createFile(path)

        for {
          success <- rm("/work/to-delete.txt")
                      .foldCause(_ => false, _ => true)

          fileExist <- Task(Files.notExists(path))
        } yield assert(success && fileExist)(equalTo(true))
      },
      testM("rm fail when invalid path") {
        for {
          invalid <- rm("/work/dont-exist")
                      .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assert(invalid)(equalTo("No such file"))
      },
      testM("rm directory") {
        val path = home.resolve("dir-to-delete")
        Files.createDirectory(path)

        for {
          r     <- rmdir("/work/dir-to-delete").map(_ => true)
          exist <- Task(Files.notExists(path))
        } yield assert(r && exist)(equalTo(true))
      },
      testM("rm fail invalid directory") {
        for {
          r <- rmdir("/work/dont-exist")
                .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assert(r)(equalTo("No such file"))
      },
      testM("upload a file") {
        val data = ZStreamChunk.fromChunks(Chunk.fromArray("Hello F World".getBytes))
        val path = home.resolve("hello-world.txt")

        (for {
          _ <- upload("/work/hello-world.txt", data)
          result <- Managed
                     .make(Task(Source.fromFile(path.toFile)))(s => UIO(s.close))
                     .use(b => Task(b.mkString))
        } yield assert(result)(equalTo("Hello F World"))).tap(_ => Task(Files.delete(path)))
      },
      testM("upload fail when path is invalid") {
        val data = ZStreamChunk.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- upload("/work/dont-exist/hello-world.txt", data)
                      .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")

        } yield assert(failure)(equalTo("No such file"))
      },
      testM("call version() underlying client") {
        for {
          version <- execute(_.version())
        } yield assert(version.toString)(isNonEmptyString)
      }
    ).provideCustomLayerShared(sftp)
}
