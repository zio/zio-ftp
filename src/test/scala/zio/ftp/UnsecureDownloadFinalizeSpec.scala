package zio.ftp

import org.apache.commons.net.ftp.FTPClient
import zio._
import zio.ftp.UnsecureFtp.Client
import zio.test.Assertion._
import zio.test._

import java.io.InputStream
import scala.util.Random

object UnsecureDownloadFinalizeSpec extends DefaultRunnableSpec {
  private val fakeFileName = "/a/b/c.txt"

  private def createFtp(
    completeCallCount: Ref[Int],
    rt: Runtime[Any],
    failComplete: Boolean,
    fileSize: Int = 5000
  ) = {
    val errorFinalizerClient = new FTPClient {

      override def retrieveFileStream(remote: String): InputStream = {
        val it = Random.alphanumeric.take(fileSize).map(_.toByte).iterator
        () => if (it.hasNext) it.next().toInt else -1
      }

      override def completePendingCommand(): Boolean =
        rt.unsafeRun(completeCallCount.update(_ + 1).as(!failComplete))
    }

    new UnsecureFtp(errorFinalizerClient)
  }

  private def createFtpZio(fileSize: Int = 5000, failComplete: Boolean = true) =
    for {
      ref <- Ref.make(0)
      rt  <- ZIO.runtime[Any]
    } yield createFtp(ref, rt, failComplete, fileSize)

  private def callDownload(ftp: FtpAccessors[Client]) =
    ftp.readFile(fakeFileName).runCollect

  private def hasIncompleteMsg(a: Assertion[String]) =
    hasField("file transfer incomplete message", (e: FileTransferIncompleteError) => e.message, a)

  final val spec = suite("Download finalizer")(
    testM("complete pending command gets called") {
      for {
        ref           <- Ref.make(0)
        rt            <- ZIO.runtime[Any]
        ftp            = createFtp(ref, rt, failComplete = false)
        bytes         <- callDownload(ftp)
        completeCount <- ref.get
      } yield assertTrue(completeCount == 1) && assert(bytes)(hasSize(equalTo(5000)))
    },
    testM("completion failure is exposed on error channel") {
      for {
        ftp  <- createFtpZio()
        exit <- callDownload(ftp).run
      } yield assert(exit)(
        fails(
          isSubtype[FileTransferIncompleteError](
            hasIncompleteMsg(startsWithString("Cannot finalize the file transfer") && containsString(fakeFileName))
          )
        )
      )
    }
  )
}
