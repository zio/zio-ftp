package zio.ftp
import Settings._

object FtpTest extends BaseFtpTest("Ftp", FtpSettings("127.0.0.1", port = 2121, FtpCredentials("username", "userpass")))
