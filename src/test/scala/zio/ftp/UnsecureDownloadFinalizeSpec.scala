package zio.ftp

import org.apache.commons.net.ftp.FTPClient
import zio._
import zio.clock.Clock
import zio.duration._
import zio.test.Assertion._
import zio.test._

import java.io.InputStream
import scala.util.Random

object UnsecureDownloadFinalizeSpec extends DefaultRunnableSpec {

  private def createFtpclient(success: Boolean) =
    for {
      clock <- ZIO.service[Clock.Service]
    } yield {
      val client = new FTPClient {
        override def retrieveFileStream(remote: String): InputStream = {
          val it = Random.alphanumeric.take(5000).map(_.toByte).iterator
          () => if (it.hasNext) it.next().toInt else -1
        }

        override def completePendingCommand(): Boolean = success
      }

      new UnsecureFtp(client, Some(5.seconds), clock)
    }

  private def readFile(success: Boolean) =
    for {
      ftpClient <- createFtpclient(success)
      bytes     <- ftpClient.readFile("/a/b/c.txt").runCollect
    } yield bytes

  private def hasIncompleteMsg(a: Assertion[String]) =
    hasField("file transfer incomplete message", (e: FileTransferIncompleteError) => e.message, a)

  final val spec = suite("Download finalizer")(
    testM("complete pending command gets called") {
      for {
        bytes <- readFile(success = true)
      } yield assert(bytes)(hasSize(equalTo(5000)))
    },
    testM("completion failure is exposed on error channel") {
      for {
        exit <- readFile(success = false).run
      } yield assert(exit)(
        fails(
          isSubtype[FileTransferIncompleteError](
            hasIncompleteMsg(startsWithString("Cannot finalize the file transfer") && containsString("/a/b/c.txt"))
          )
        )
      )
    }
  )
}
