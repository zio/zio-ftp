package zio.ftp

import java.net.Proxy

import net.schmizz.sshj.{ Config => SshConfig, DefaultConfig => DefaultSshConfig }
import net.schmizz.sshj.sftp.{ SFTPClient => JSFTPClient }
import org.apache.commons.net.ftp.{ FTPClient => JFTPClient }
import zio.Chunk

sealed trait Settings[+A]

object Settings {
  case class FtpCredentials(username: String, password: String)

  final case class SFtpSettings(
    host: String,
    port: Int,
    credentials: FtpCredentials,
    strictHostKeyChecking: Boolean,
    knownHosts: Option[String],
    sftpIdentity: Option[SftpIdentity],
    sshConfig: SshConfig
  ) extends Settings[JSFTPClient]

  sealed trait SftpIdentity {
    type KeyType
    val privateKey: KeyType
    val privateKeyFilePassphrase: Option[Chunk[Byte]]
  }

  final case class RawKeySftpIdentity(
    privateKey: Chunk[Byte],
    privateKeyFilePassphrase: Option[Chunk[Byte]] = None,
    publicKey: Option[Chunk[Byte]] = None
  ) extends SftpIdentity {
    type KeyType = Chunk[Byte]
  }

  final case class KeyFileSftpIdentity(privateKey: String, privateKeyFilePassphrase: Option[Chunk[Byte]] = None)
      extends SftpIdentity {
    type KeyType = String
  }

  object SftpIdentity {

    def createRawSftpIdentity(privateKey: Chunk[Byte]): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey)

    def createRawSftpIdentity(privateKey: Chunk[Byte], privateKeyFilePassphrase: Chunk[Byte]): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey, Some(privateKeyFilePassphrase))

    def createRawSftpIdentity(
      privateKey: Chunk[Byte],
      privateKeyFilePassphrase: Chunk[Byte],
      publicKey: Chunk[Byte]
    ): RawKeySftpIdentity =
      RawKeySftpIdentity(privateKey, Some(privateKeyFilePassphrase), Some(publicKey))

    def createFileSftpIdentity(privateKey: String): KeyFileSftpIdentity =
      KeyFileSftpIdentity(privateKey)

    def createFileSftpIdentity(privateKey: String, privateKeyFilePassphrase: Chunk[Byte]): KeyFileSftpIdentity =
      KeyFileSftpIdentity(privateKey, Some(privateKeyFilePassphrase))
  }

  object SFtpSettings {

    def apply(host: String, port: Int, creds: FtpCredentials): SFtpSettings =
      new SFtpSettings(
        host,
        port,
        creds,
        strictHostKeyChecking = false,
        knownHosts = None,
        sftpIdentity = None,
        new DefaultSshConfig()
      )
  }

  final case class FtpSettings(
    host: String,
    port: Int,
    credentials: FtpCredentials,
    binary: Boolean,
    passiveMode: Boolean,
    proxy: Option[Proxy],
    secure: Boolean
  ) extends Settings[JFTPClient]

  object FtpSettings {

    def apply(host: String, port: Int, creds: FtpCredentials): FtpSettings =
      new FtpSettings(host, port, creds, true, true, None, false)

    def secure(host: String, port: Int, creds: FtpCredentials): FtpSettings =
      new FtpSettings(host, port, creds, true, true, None, true)
  }
}
