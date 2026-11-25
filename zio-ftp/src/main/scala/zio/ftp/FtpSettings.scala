/*
 * Copyright 2017-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.ftp

import java.net.Proxy
import java.nio.file.Path
import net.schmizz.sshj.{ Config => SshConfig, DefaultConfig => DefaultSshConfig }
import zio._

/**
 * Credentials that authenticate a user on an FTP or SFTP server.
 *
 * The subtypes are `PasswordCredentials` and `KeyCredentials`.
 */
sealed trait FtpCredentials

/**
 * Basic credentials used during ftp authentication
 *
 * @param username identifier of the user in plain text
 * @param password secure secret of the user in plain text
 */
final case class PasswordCredentials(username: String, password: String) extends FtpCredentials

/**
 * Private key credentials used during SFTP authentication
 *
 * @param username identifier of the user in plain text
 * @param identity the private key, given as key text or as a key file path
 */
final case class KeyCredentials(username: String, identity: SftpIdentity) extends FtpCredentials

/**
 * Settings to connect to an SFTP server (SSH File Transfer Protocol)
 *
 * @param host hostname of ftp server (ipAddress / dnsName)
 * @param port port of communication used by the server
 * @param credentials auth credentials
 * @param strictHostKeyChecking when `true`, the server host key is checked against the keys in `knownHosts`. When `false`, the host key is accepted without checking
 * @param knownHosts path of the known hosts file. It is read only when `strictHostKeyChecking` is `true`
 * @param proxy optional proxy used to open the TCP connection
 * @param sshConfig configuration of ssh client
 */
final case class SecureFtpSettings(
  host: String,
  port: Int,
  credentials: FtpCredentials,
  strictHostKeyChecking: Boolean,
  knownHosts: Option[String],
  proxy: Option[Proxy],
  sshConfig: SshConfig
)

/**
 * Factory methods for `SecureFtpSettings`.
 */
object SecureFtpSettings {

  /**
   * Creates settings for `host` and `port` with `credentials`, without a proxy.
   *
   * It is the four-argument `apply` with `proxy` set to `None`.
   */
  def apply(host: String, port: Int, credentials: FtpCredentials): SecureFtpSettings =
    SecureFtpSettings.apply(host, port, credentials, None)

  /**
   * Creates settings for `host` and `port` with `credentials` and the optional `proxy`.
   *
   * `strictHostKeyChecking` is `false`, `knownHosts` is `None`, and `sshConfig` is a new `DefaultSshConfig`.
   */
  def apply(host: String, port: Int, credentials: FtpCredentials, proxy: Option[Proxy]): SecureFtpSettings =
    new SecureFtpSettings(
      host,
      port,
      credentials,
      strictHostKeyChecking = false,
      knownHosts = None,
      proxy = proxy,
      new DefaultSshConfig()
    )
}

/**
 * A key that authenticates a user over SFTP.
 *
 * The subtypes are `RawKeySftpIdentity`, which holds the key text, and `KeyFileSftpIdentity`, which holds the path to a key file.
 */
sealed trait SftpIdentity {

  /**
   * The type of `privateKey`: `String` for key text, `Path` for a key file.
   */
  type KeyType

  /**
   * The private key: the key text or the key file path, depending on `KeyType`.
   */
  val privateKey: KeyType

  /**
   * The passphrase that decrypts the private key, or `None` when the key is not encrypted.
   */
  val passphrase: Option[String]
}

/**
 * SFTP identity for authenticating using private/public key value
 *
 * @param privateKey private key value to use when connecting
 * @param passphrase password to use to decrypt private key
 * @param publicKey public key value to use when connecting
 */
final case class RawKeySftpIdentity(
  privateKey: String,
  passphrase: Option[String] = None,
  publicKey: Option[String] = None
) extends SftpIdentity {

  /**
   * The key is given as text.
   */
  type KeyType = String
}

/**
 * Factory methods for `RawKeySftpIdentity`.
 */
object RawKeySftpIdentity {

  /**
   * Creates an identity from the key text `privateKey`, with no passphrase and no public key.
   */
  def apply(privateKey: String): RawKeySftpIdentity =
    RawKeySftpIdentity(privateKey, None, None)

  /**
   * Creates an identity from the key text `privateKey` and the `passphrase` that decrypts it.
   */
  def apply(privateKey: String, passphrase: String): RawKeySftpIdentity =
    RawKeySftpIdentity(privateKey, Some(passphrase), None)
}

/**
 * SFTP identity for authenticating using private/public key file
 *
 * @param privateKey private key file to use when connecting
 * @param passphrase password to use to decrypt private key file
 */
final case class KeyFileSftpIdentity(privateKey: Path, passphrase: Option[String] = None) extends SftpIdentity {

  /**
   * The key is given as the path to a key file.
   */
  type KeyType = Path
}

/**
 * Factory methods for `KeyFileSftpIdentity`.
 */
object KeyFileSftpIdentity {

  /**
   * Creates an identity that reads the key from the file `privateKey`, with no passphrase.
   */
  def apply(privateKey: Path): KeyFileSftpIdentity =
    KeyFileSftpIdentity(privateKey, None)
}

/**
 * The data channel protection level of an FTPS connection. `s` is the code that the `PROT` command takes.
 */
sealed abstract class ProtectionLevel(val s: String)

/**
 * The four data channel protection levels, each with its `PROT` code.
 */
object ProtectionLevel {

  /**
   * No protection for the data channel, code `C`. `SslParams.default` uses this level.
   */
  case object Clear extends ProtectionLevel("C")

  /**
   * Protected data channel, code `P`.
   */
  case object Private extends ProtectionLevel("P")

  /**
   * Protected data channel, code `S`. Applies to the SSL protocol only.
   */
  case object Safe extends ProtectionLevel("S")

  /**
   * Encrypted data channel, code `E`. Applies to the SSL protocol only.
   */
  case object Confidential extends ProtectionLevel("E")
}

/**
 * FTPS settings: the TLS mode and the data channel protection level.
 *
 * @param isImplicit `true` for implicit FTPS (TLS from the start of the connection), `false` for explicit FTPS
 * @param pbzs protection buffer size, sent with the `PBSZ` command. `SslParams.default` uses 0
 * @param prot data channel protection level, sent with the `PROT` command. `SslParams.default` uses `ProtectionLevel.Clear`
 *
 * More information about these commands
 * https://enterprisedt.com/products/edtftpjssl/doc/manual/html/ftpscommands.html
 */
final case class SslParams(isImplicit: Boolean, pbzs: Long, prot: ProtectionLevel)

/**
 * Factory values for `SslParams`.
 */
object SslParams {

  /**
   * Explicit FTPS (`isImplicit` is `false`), protection buffer size 0, and data channel protection `ProtectionLevel.Clear`.
   */
  val default: SslParams = SslParams(isImplicit = false, 0L, ProtectionLevel.Clear)
}

/**
 * Settings to connect to an FTP server, or to an FTPS server when `sslParams` is defined
 *
 * @param host hostname of ftp server (ipAddress / dnsName)
 * @param port port of communication used by the server
 * @param credentials credentials (username and password)
 * @param binary `true` for the BINARY transfer type, `false` for ASCII. The `apply` and `secure` factory methods set `true`
 * @param passiveMode `true` for passive mode connections, `false` for active mode. The `apply` and `secure` factory methods set `true`
 * @param remoteVerificationEnabled `true` asks the client to check that the remote host of a data connection is the host of the control connection. The connect code sets the flag only when it is `true`, so `false` has no effect and the client keeps its own default, which is `true`
 * @param proxy An optional proxy to use when connecting with these settings
 * @param sslParams FTPS settings. When `None`, the connection is plain FTP. The `secure` factory method sets `SslParams.default`
 * @param dataTimeout Sets the timeout to use when reading from the data connection.
 * @param defaultTimeout Sets the default timeout for connections established with these settings,
 *                       specifically the timeout for the control connection.
 * @param keepalive if defined enables TCP keepalive with the provided settings
 * @param controlEncoding character encoding to be used by the FTP control connection, auto-detects UTF-8 if None
 */
final case class UnsecureFtpSettings(
  host: String,
  port: Int,
  credentials: PasswordCredentials,
  binary: Boolean,
  passiveMode: Boolean,
  remoteVerificationEnabled: Boolean,
  proxy: Option[Proxy],
  sslParams: Option[SslParams] = None,
  dataTimeout: Option[Duration] = Some(1.minute),
  defaultTimeout: Option[Duration] = Some(1.minute),
  keepalive: Option[KeepaliveSettings] = Some(KeepaliveSettings.default),
  controlEncoding: Option[String] = None
)

/**
 * Factory methods for `UnsecureFtpSettings`.
 */
object UnsecureFtpSettings {

  /**
   * Creates settings for `host` and `port` with `creds`.
   *
   * `binary` and `passiveMode` are `true`, `remoteVerificationEnabled` is `true`, `proxy` and `sslParams` are `None`. The other fields keep their constructor defaults: one-minute data and control timeouts, `KeepaliveSettings.default`, and no control encoding.
   */
  def apply(host: String, port: Int, creds: PasswordCredentials): UnsecureFtpSettings =
    new UnsecureFtpSettings(
      host,
      port,
      creds,
      binary = true,
      passiveMode = true,
      remoteVerificationEnabled = true,
      None,
      None
    )

  /**
   * Creates settings as `apply` does, but with `sslParams` set to `SslParams.default`, so the connection uses explicit FTPS.
   */
  def secure(host: String, port: Int, creds: PasswordCredentials): UnsecureFtpSettings =
    new UnsecureFtpSettings(
      host,
      port,
      creds,
      binary = true,
      passiveMode = true,
      remoteVerificationEnabled = true,
      None,
      Some(SslParams.default)
    )
}
