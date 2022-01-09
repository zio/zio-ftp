package zio.ftp

import org.apache.commons.net.ftp.FTPClient
import zio.test.Assertion._
import zio.test._

import java.io.InputStream
import scala.util.Random

object UnsecureFtpSpec extends DefaultRunnableSpec {

  final val spec = suite("UnsecureFtpSpec")(
    testM("readFile fail when completePendingCommand is false") {
      val ftpClient = new UnsecureFtp(new FTPClient {
        override def retrieveFileStream(remote: String): InputStream = {
          val it = Random.alphanumeric.take(1000).map(_.toByte).iterator
          () => if (it.hasNext) it.next().toInt else -1
        }
        override def completePendingCommand(): Boolean = false
      })

      assertM(ftpClient.readFile("/a/b/c.txt").runCollect.run)(
        fails(
          isSubtype[FileTransferIncompleteError](
            hasMessage(startsWithString("Cannot finalize the file transfer") && containsString("/a/b/c.txt"))
          )
        )
      )
    }
  )
}
