package zio.ftp

import zio.ZLayer
import zio.blocking.Blocking
import SFtp._

object App {
  val settings: SecureFtpSettings              = SecureFtpSettings("127.0.0.1", port = 2222, FtpCredentials("foo", "foo"))
  val sftp: ZLayer[Any, ConnectionError, SFtp] = Blocking.live >>> secure(settings)

  ls("test").provideCustomLayer(sftp ++ Blocking.live)
}
