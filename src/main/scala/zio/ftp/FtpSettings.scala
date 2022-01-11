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
import zio.duration.Duration

/**
 * Credential used during ftp authentication
 *
 * @param username identifier of the user in plain text
 * @param password secure secret of the user in plain text
 */
final case class FtpCredentials(username: String, password: String)

/**
 * Settings to connect to a secure Ftp server (Ftp over ssh)
 *
 * @param host hostname of ftp server (ipAddress / dnsName)
 * @param port port of communication used by the server
 * @param credentials auth credentials
 * @param strictHostKeyChecking sets whether to use strict host key checking.
 * @param knownHosts known hosts file to be used when connecting
 * @param sftpIdentity private/public key config to use when connecting
 * @param sshConfig configuration of ssh client
 */
final case class SecureFtpSettings(
  host: String,
  port: Int,
  credentials: FtpCredentials,
  sftpIdentity: Option[SftpIdentity],
  strictHostKeyChecking: Boolean,
  knownHosts: Option[String],
  proxy: Option[Proxy],
  sshConfig: SshConfig
)

object SecureFtpSettings {

  def apply(host: String, port: Int, credentials: FtpCredentials): SecureFtpSettings =
    new SecureFtpSettings(
      host,
      port,
      credentials,
      sftpIdentity = None,
      strictHostKeyChecking = false,
      knownHosts = None,
      proxy = None,
      new DefaultSshConfig()
    )

  def apply(host: String, port: Int, credentials: FtpCredentials, identity: SftpIdentity): SecureFtpSettings =
    new SecureFtpSettings(
      host,
      port,
      credentials,
      sftpIdentity = Some(identity),
      strictHostKeyChecking = false,
      knownHosts = None,
      proxy = None,
      new DefaultSshConfig()
    )
}

sealed trait SftpIdentity {
  type KeyType
  val privateKey: KeyType
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
  type KeyType = String
}

object RawKeySftpIdentity {

  def apply(privateKey: String): RawKeySftpIdentity =
    RawKeySftpIdentity(privateKey, None, None)

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
  type KeyType = Path
}

object KeyFileSftpIdentity {

  def apply(privateKey: Path): KeyFileSftpIdentity =
    KeyFileSftpIdentity(privateKey, None)
}

/**
 * Settings to connect unsecure Ftp server
 *
 * @param host hostname of ftp server (ipAddress / dnsName)
 * @param port port of communication used by the server
 * @param credentials credentials (username and password)
 * @param binary specifies the file transfer mode, BINARY or ASCII. Default is ASCII (false)
 * @param passiveMode specifies whether to use passive mode connections. Default is active mode (false)
 * @param proxy An optional proxy to use when connecting with these settings
 * @param secure Use FTP over SSL
 * @param dataTimeout Sets the timeout to use when reading from the data connection.
 */
final case class UnsecureFtpSettings(
  host: String,
  port: Int,
  credentials: FtpCredentials,
  binary: Boolean,
  passiveMode: Boolean,
  proxy: Option[Proxy],
  secure: Boolean,
  dataTimeout: Option[Duration] = None
)

object UnsecureFtpSettings {

  def apply(host: String, port: Int, creds: FtpCredentials): UnsecureFtpSettings =
    new UnsecureFtpSettings(host, port, creds, true, true, None, false)

  def secure(host: String, port: Int, creds: FtpCredentials): UnsecureFtpSettings =
    new UnsecureFtpSettings(host, port, creds, true, true, None, true)
}
