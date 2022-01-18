package zio.ftp

import org.apache.commons.net.ftp.FTPClient
import zio.test.Assertion._
import zio.test._

import java.io.InputStream
import scala.util.Random

object UnsecureDownloadFinalizeSpec extends DefaultRunnableSpec {

  private def createFtpclient(success: Boolean) = {
    val client = new FTPClient {
      override def retrieveFileStream(remote: String): InputStream = {
        val it = Random.alphanumeric.take(5000).map(_.toByte).iterator
        () => if (it.hasNext) it.next().toInt else -1
      }

      override def completePendingCommand(): Boolean = success
    }

    new UnsecureFtp(client)
  }

  private def hasIncompleteMsg(a: Assertion[String]) =
    hasField("file transfer incomplete message", (e: FileTransferIncompleteError) => e.message, a)

  final val spec = suite("Download finalizer")(
    testM("complete pending command gets called") {
      val ftpClient = createFtpclient(true)
      for {
        bytes <- ftpClient.readFile("/a/b/c.txt").runCollect
      } yield assert(bytes)(hasSize(equalTo(5000)))
    },
    testM("completion failure is exposed on error channel") {
      val ftpClient = createFtpclient(false)
      for {
        exit <- ftpClient.readFile("/a/b/c.txt").runCollect.run
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
