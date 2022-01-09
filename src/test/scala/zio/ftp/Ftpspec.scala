package zio.ftp

import zio._
import zio.blocking.Blocking
import zio.ftp.Ftp._
import zio.ftp.UnsecureFtpSpec.{ suite, testM }
import zio.nio.core.file.{ Path => ZPath }
import zio.nio.file.Files
import zio.stream.{ ZStream, ZTransducer }
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import java.net.{ InetSocketAddress, Proxy }
import scala.io.Source

object FtpsTest extends DefaultRunnableSpec {
  val settings = UnsecureFtpSettings.secure("127.0.0.1", 2121, FtpCredentials("username", "userpass"))
  val ftp      = Blocking.live >>> unsecure(settings).mapError(TestFailure.die(_))

  override def spec =
    FtpSuite.spec("FtpsSpec", settings).provideCustomLayer(ftp) @@ sequential
}

object FtpTest extends DefaultRunnableSpec {
  val settings = UnsecureFtpSettings("127.0.0.1", port = 2121, FtpCredentials("username", "userpass"))
  val ftp      = Blocking.live >>> unsecure(settings).mapError(TestFailure.die(_))

  override def spec =
    FtpSuite.spec("FtpSpec", settings).provideCustomLayer(ftp) @@ sequential
}

object FtpSuite {
  val home = ZPath("ftp-home/ftp/home")

  def spec(
    labelSuite: String,
    settings: UnsecureFtpSettings
  ): Spec[Ftp with Blocking, TestFailure[Throwable], TestSuccess] =
    suite(labelSuite)(
      testM("invalid credentials")(
        for {
          failure <- UnsecureFtp
                       .connect(settings.copy(credentials = FtpCredentials("test", "test")))
                       .use(_ => IO.succeed(""))
                       .foldCause(_.failureOption.map(_.getMessage).mkString, s => s)
        } yield assert(failure)(containsString("Fail to connect to server"))
      ),
      testM("invalid proxy")(
        for {
          failure <- UnsecureFtp
                       .connect(
                         settings.copy(proxy = Some(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("invalid", 9999))))
                       )
                       .use(_ => IO.succeed(""))
                       .foldCause(_.failureOption.map(_.getMessage).mkString, s => s)
        } yield assert(failure)(containsString("invalid"))
      ),
      testM("valid credentials")(
        for {
          succeed <- UnsecureFtp.connect(settings).use(_ => IO.succeed(true))
        } yield assert(succeed)(isTrue)
      ),
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
                       .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)

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
          _       <- Files.createFile(path)
          success <- rm("/work/to-delete.txt").map(_ => true)

          fileExist <- Files.notExists(path)
        } yield assert(success && fileExist)(equalTo(true))
      },
      testM("rm fail when invalid path") {
        for {
          invalid <- rm("/dont-exist")
                       .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")

        } yield assert(invalid)(equalTo("Path is invalid. Cannot delete file : /dont-exist"))
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

        } yield assert(r)(equalTo("Path is invalid. Cannot delete directory : /dont-exist"))
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
      },
      testM("call noOp underlying client") {
        for {
          noOp <- execute(_.sendNoOp())
        } yield assert(noOp)(equalTo(true))
      }
    )
}
