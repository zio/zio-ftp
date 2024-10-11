package zio.ftp

import org.apache.commons.net.ftp.FTPClient
import zio.test.Assertion._
import zio.test._

import java.io.{ IOException, InputStream }
import scala.util.Random

object UnsecureFtpReadFileSpec extends ZIOSpecDefault {

  private def createFtpclient(errorOrsuccess: Either[Exception, Boolean]) =
    UnsecureFtp.unsafe(new FTPClient {

      override def retrieveFileStream(remote: String): InputStream = {
        val it = Random.alphanumeric.take(5000).map(_.toByte).iterator
        () => if (it.hasNext) it.next().toInt else -1
      }

      override def completePendingCommand(): Boolean =
        errorOrsuccess match {
          case Left(err)   => throw err
          case Right(flag) => flag
        }
    })

  private def hasIncompleteMsg(a: Assertion[String]) =
    hasField("file transfer incomplete message", (e: Exception) => e.getMessage, a)

  override def spec =
    suite("UnsecureFtp - ReadFile complete pending")(
      test("succeed") {
        val ftpClient = createFtpclient(Right(true))
        for {
          bytes <- ftpClient.readFile("/a/b/c.txt").runCollect
        } yield assert(bytes)(hasSize(equalTo(5000)))
      },
      test("fail to complete") {
        val ftpClient = createFtpclient(Right(false))
        for {
          exit <- ftpClient.readFile("/a/b/c.txt").runCollect.exit
        } yield assert(exit)(
          fails(
            isSubtype[FileTransferIncompleteError](
              hasIncompleteMsg(startsWithString("Cannot finalize the file transfer") && containsString("/a/b/c.txt"))
            )
          )
        )
      },
      test("error occur") {
        val ftpClient = createFtpclient(Left(new IOException("Boom")))
        for {
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
