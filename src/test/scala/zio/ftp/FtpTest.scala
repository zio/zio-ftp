package zio.ftp

import java.net.{ InetSocketAddress, Proxy }
import java.nio.file.{ Files, Path, Paths }

import zio._
import zio.ftp.FtpClient.connect
import zio.ftp.FtpSettings._
import zio.stream.{ ZSink, ZStreamChunk }
import zio.test.Assertion._
import zio.test._

import scala.io.Source

object FtpsTest extends DefaultRunnableSpec {
  val settings = UnsecureFtpSettings.secure("127.0.0.1", 2121, FtpCredentials("username", "userpass"))
  override def spec = FtpSuite.spec("FtpsSpec", settings)
}

object FtpTest extends DefaultRunnableSpec {
  val settings = UnsecureFtpSettings("127.0.0.1", port = 2121, FtpCredentials("username", "userpass"))
  override def spec = FtpSuite.spec("FtpSpec", settings)
}

object FtpSuite {
  val home = Paths.get("ftp-home/ftp/home/work")

  def spec(labelSuite: String, settings: UnsecureFtpSettings) =
    suite(labelSuite)(
      testM("invalid credentials")(
        for {
          failure <- connect(settings.copy(credentials = FtpCredentials("test", "test")))
                      .use(_ => IO.succeed(""))
                      .foldCause(_.failureOption.map(_.getMessage).mkString, identity)
        } yield assert(failure)(containsString("Fail to connect to server"))
      ),
      testM("invalid proxy")(
        for {
          failure <- connect(
                      settings.copy(proxy = Some(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("invalid", 9999))))
                    ).use(_ => IO.succeed(""))
                      .foldCause(_.failureOption.map(_.getMessage).mkString, identity)
        } yield assert(failure)(containsString("invalid"))
      ),
      testM("valid credentials")(
        for {
          succeed <- connect(settings).use(_ => IO.succeed(true))
        } yield assert(succeed)(equalTo(true))
      ),
      testM("ls ")(
        for {
          files <- connect(settings).use(_.ls("/").fold(List.empty[String])((s, f) => f.path +: s))
        } yield assert(files.reverse)(hasSameElements(List("/empty.txt", "/work")))
      ),
      testM("ls with invalid directory")(
        for {
          files <- connect(settings).use(_.ls("/dont-exist").fold(List.empty[String])((s, f) => f.path +: s))
        } yield assert(files.reverse)(hasSameElements(Nil))
      ),
      testM("ls descendant")(
        for {
          files <- connect(settings).use(_.lsDescendant("/").fold(List.empty[String])((s, f) => f.path +: s))
        } yield assert(files.reverse)(
          hasSameElements(List("/empty.txt", "/work/notes.txt", "/work/dir1/users.csv", "/work/dir1/console.dump"))
        )
      ),
      testM("ls descendant with invalid directory")(
        for {
          files <- connect(settings).use(_.lsDescendant("/dont-exist").runCollect)
        } yield assert(files)(equalTo(Nil))
      ),
      testM("stat directory") {
        for {
          file <- connect(settings).use(_.stat("/work/dir1"))
        } yield assert(file.map(f => f.path -> f.isDirectory))(equalTo(Some("/work/dir1" -> Some(true))))
      },
      testM("stat file") {
        for {
          file <- connect(settings).use(_.stat("/work/dir1/console.dump"))
        } yield assert(file.map(f => f.path -> f.isDirectory))(equalTo(Some("/work/dir1/console.dump" -> Some(false))))
      },
      testM("stat file does not exist") {
        for {
          file <- connect(settings).use(_.stat("/wrong-path.xml"))
        } yield assert(file)(equalTo(None))
      },
      testM("stat directory does not exist") {
        for {
          file <- connect(settings).use(_.stat("/wrong-path"))
        } yield assert(file)(equalTo(None))
      },
      testM("readFile") {
        for {
          content <- connect(settings).use(_.readFile("/work/notes.txt").run(ZSink.utf8DecodeChunk))
        } yield assert(content)(equalTo("""|Hello world !!!
                                           |this is a beautiful day""".stripMargin))
      },
      testM("readFile does not exist") {
        for {
          invalid <- connect(settings).use(
                      _.readFile("/invalid.txt")
                        .run(ZSink.utf8DecodeChunk)
                        .foldCause(_.failureOption.map(_.getMessage).mkString, _.mkString)
                    )
        } yield assert(invalid)(equalTo("File does not exist /invalid.txt"))
      },
      testM("mkdir directory") {
        (for {
          result <- connect(settings).use(_.mkdir("/work/new-dir").map(_ => true))
        } yield assert(result)(equalTo(true)))
          .tap(_ => Task(Files.delete(home.resolve("new-dir"))))
      },
      testM("mkdir fail when invalid path") {
        for {
          failure <- connect(settings).use(
                      _.mkdir("/work/dir1/users.csv")
                        .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")
                    )
        } yield {
          assert(failure)(containsString("Path is invalid. Cannot create directory : /work/dir1/users.csv"))
        }
      },
      testM("rm valid path") {
        val path = home.resolve("to-delete.txt")
        Files.createFile(path)

        for {
          success <- connect(settings).use(
                      _.rm("/work/to-delete.txt")
                        .foldCause(_ => false, _ => true)
                    )

          fileExist <- Task(Files.notExists(path))
        } yield assert(success && fileExist)(equalTo(true))
      },
      testM("rm fail when invalid path") {
        for {
          invalid <- connect(settings).use(
                      _.rm("/dont-exist")
                        .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")
                    )
        } yield assert(invalid)(equalTo("Path is invalid. Cannot delete file : /dont-exist"))
      },
      testM("rm directory") {
        val path = home.resolve("dir-to-delete")
        Files.createDirectory(path)

        for {
          r     <- connect(settings).use(_.rmdir("/work/dir-to-delete").map(_ => true))
          exist <- Task(Files.notExists(path))
        } yield assert(r && exist)(equalTo(true))
      },
      testM("rm fail invalid directory") {
        for {
          r <- connect(settings).use(
                _.rmdir("/dont-exist")
                  .foldCause(_.failureOption.map(_.getMessage).getOrElse(""), _ => "")
              )
        } yield assert(r)(equalTo("Path is invalid. Cannot delete directory : /dont-exist"))
      },
      testM("upload a file") {
        val data = ZStreamChunk.fromChunks(Chunk.fromArray("Hello F World".getBytes))
        val path = home.resolve("hello-world.txt")

        (for {
          _ <- connect(settings).use(_.upload("/work/hello-world.txt", data))
          result <- Managed
                     .make(Task(Source.fromFile(path.toFile)))(s => UIO(s.close))
                     .use(b => Task(b.mkString))
        } yield assert(result)(equalTo("Hello F World"))).tap(_ => Task(Files.delete(path)))
      },
      testM("upload fail when path is invalid") {
        val data = ZStreamChunk.fromChunks(Chunk.fromArray("Hello F World".getBytes))

        for {
          failure <- connect(settings).use(
                      _.upload("/dont-exist/hello-world.txt", data)
                        .foldCause(_.failureOption.fold("")(_.getMessage), _ => "")
                    )
        } yield assert(failure)(equalTo("Path is invalid. Cannot upload data to : /dont-exist/hello-world.txt"))
      },
      testM("call noOp underlying client") {
        for {
          noOp <- connect(settings).use(
                   _.execute(_.sendNoOp())
                 )
        } yield assert(noOp)(equalTo(true))
      }
    )
}
