package zio.ftp

import FtpSettings._

object FtpsTest
    extends BaseFtpTest("Ftps", UnsecureFtpSettings.secure("127.0.0.1", 2121, FtpCredentials("username", "userpass")))
