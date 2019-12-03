package zio.ftp

import settings.FtpCredentials.credentials
import settings.FtpSettings

object FtpTest extends BaseFtpTest("Ftp", FtpSettings("127.0.0.1", port = 2121, credentials("username", "userpass")))
