package zio.ftp

import org.apache.commons.net.ftp.FTPClient
import zio.test.Assertion._
import zio.test._

import java.io.InputStream
import scala.util.Random

object UnsecureDownloadFinalizeSpec extends ZIOSpecDefault {

  private def createFtpclient(success: => Boolean) = {
    val client = new FTPClient {
      override def retrieveFileStream(remote: String): InputStream = {
        val it = Random.alphanumeric.take(5000).map(_.toByte).iterator
        () => if (it.hasNext) it.next().toInt else -1
      }

      override def getReplyCode(): Int = 150

      override def completePendingCommand(): Boolean = success
    }

    new UnsecureFtp(client)
  }

  private def hasIncompleteMsg(a: Assertion[String]) =
    hasField("file transfer incomplete message", (e: FileTransferIncompleteError) => e.message, a)

  val FileSize = 5000

  override def spec =
    suite("Download finalizer")(
      test("complete pending command gets called") {
        var completePendingCommandWasCalled = false
        val ftpClient                       = createFtpclient {
          completePendingCommandWasCalled = true
          true
        }
        for {
          bytes <- ftpClient.readFile("/a/b/c.txt").runCollect
        } yield assert(bytes)(hasSize(equalTo(FileSize))) && assertTrue(completePendingCommandWasCalled)
      },
      test("completion failure is exposed on error channel") {
        val ftpClient = createFtpclient(false)
        for {
          _    <- ftpClient.readFile("/a/b/c.txt").runCollect.exit
          exit <- ftpClient.readFile("/a/b/c.txt").runCollect.exit
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
