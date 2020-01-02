package zio.ftp

import Settings._

object FtpsTest
    extends BaseFtpTest("Ftps", FtpSettings.secure("127.0.0.1", 2121, FtpCredentials("username", "userpass")))
