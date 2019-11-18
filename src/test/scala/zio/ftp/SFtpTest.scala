
package zio.ftp

import java.nio.file.{Files, Path, Paths}

import zio.ftp.SFtp._
import zio.ftp.settings.FtpCredentials._
import zio.ftp.settings.SFtpSettings
import zio.stream.{ZSink, ZStream}
import zio.test.Assertion._
import zio.test._
import zio.{IO, Managed, Task, UIO}

import scala.io.Source

object SFtpTest extends BaseSFtpTest(
  SFtpSettings("127.0.0.1", port = 2222, credentials("foo", "foo")),
  Paths.get("ftp-home/sftp/home/foo/work")
)

abstract class BaseSFtpTest(settings: SFtpSettings , home: Path) extends DefaultRunnableSpec(
  suite("SFtp")(
    testM("invalid credentials")(
      for {
        succeed <- connect(settings.copy(credentials = credentials("test", "test"))).use(_ => IO.succeed(""))
          .foldCause(_.dieOption.map(_.getMessage).mkString, identity)
      } yield assert(succeed, containsString("Fail to connect to server"))
    ),

    testM("valid credentials")(
      for {
        succeed <- connect(settings).use(_ => IO.succeed(true))
      } yield assert(succeed, equalTo(true))
    ),

    testM("listFiles")(
      for {
        files <- connect(settings).use(listFiles("/")(_).runCollect)
      } yield assert(files.map(_.name), hasSameElements(List("notes.txt", "console.dump", "users.csv")))
    ),

    testM("stat file") {
      for {
        file <- connect(settings).use(stat("/work/dir1/users.csv"))
      } yield assert(file.map(_.name), equalTo(Some("users.csv")))
    },

    testM("stat file does not exist") {
      for{
        file <- connect(settings).use(stat("/wrong-path.xml"))
      } yield assert(file, equalTo(None))
    },

    testM("readFile") {
      for{
        content <-  connect(settings).use(
          readFile("/work/notes.txt")(_).chunkN(100).chunks.run(ZSink.utf8DecodeChunk)
        )
      } yield assert(content, equalTo("""|Hello world !!!
                                         |this is a beautiful day""".stripMargin))
    },

    testM("readFile does not exist") {
      for {
        invalid <- connect(settings).use(readFile("/work/invalid.txt")(_).chunkN(100).chunks.run(ZSink.utf8DecodeChunk))
          .foldCause(_.dieOption.map(_.getMessage).mkString, _.mkString)
      }yield assert(invalid, equalTo("File does not exist /work/invalid.txt"))
    },

    testM("mkdir directory") {
      (for {
        result <-  connect(settings).use(mkdirs("/work/new-dir")).map(_ => true)
      } yield assert(result, equalTo(true)))
        .tap( _ => Task(Files.delete(home.resolve("new-dir"))))
    },

    testM("mkdir fail when invalid path") {
      for {
        failure <- connect(settings).use(mkdirs("/work/dir1/users.csv"))
          .foldCause( _.dieOption.fold("")(_.getMessage), _ => "")
      } yield {
        assert(failure, containsString("Path is invalid. Cannot create directory : /work/dir1/users.csv"))
      }
    },

    testM("rm valid path") {
      val path = home.resolve("to-delete.txt")
      Files.createFile(path)

      for {
        success <- connect(settings).use(rm("/work/to-delete.txt"))
          .foldCause(_ => false, _ => true)

        fileExist <- Task(Files.notExists(path))
      } yield assert(success && fileExist, equalTo(true))
    },

    testM("rm fail when invalid path") {
      for {
        invalid <- connect(settings).use(rm("/work/dont-exist"))
          .foldCause( _.dieOption.fold("")(_.getMessage), _ => "")
      } yield assert(invalid, equalTo("Path is invalid. Cannot delete file : /work/dont-exist"))
    },

    testM("rm directory") {
      val path = home.resolve("dir-to-delete")
      Files.createDirectory(path)

      for {
        r <- connect(settings).use(rmdir("/work/dir-to-delete")).map(_ => true)
        exist <- Task(Files.notExists(path))
      } yield assert(r && exist, equalTo(true))
    },

    testM("rm fail invalid directory"){
      for {
        r <- connect(settings).use(rmdir("/work/dont-exist"))
          .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
      } yield assert(r, equalTo("Path is invalid. Cannot delete directory : /work/dont-exist"))
    },

    testM("upload a file") {
      val data = ZStream.fromIterable("Hello F World".getBytes.toSeq).chunkN(10).chunks
      val path = home.resolve("hello-world.txt")

      (for{
        _ <- connect(settings).use(upload("/work/hello-world.txt", data))
        result <- Managed.make(taskIO(Source.fromFile(path.toFile)))(s => UIO(s.close)).use(b => taskIO(b.mkString))
      } yield assert(result, equalTo("Hello F World"))).tap(_ => Task(Files.delete(path)))
    },

    testM("upload fail when path is invalid") {
      val data = ZStream.fromIterable("Hello F World".getBytes.toSeq).chunkN(10).chunks

      for {
        failure <- connect(settings).use(upload("/work/dont-exist/hello-world.txt", data))
          .foldCause(_.dieOption.fold("")(_.getMessage), _ => "")
      } yield assert(failure, equalTo("Path is invalid. Cannot upload data to : /work/dont-exist/hello-world.txt"))
    }
  )
)
