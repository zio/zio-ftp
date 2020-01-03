package zio.ftp
import FtpSettings._

object FtpTest
    extends BaseFtpTest("Ftp", UnsecureFtpSettings("127.0.0.1", port = 2121, FtpCredentials("username", "userpass")))
