package zio.ftp

import settings._

object FtpsTest extends BaseFtpTest("Ftps", FtpSettings.secure("127.0.0.1", 2121, credentials("username", "userpass")))
