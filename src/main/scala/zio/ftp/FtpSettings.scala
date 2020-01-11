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

import net.schmizz.sshj.{ Config => SshConfig, DefaultConfig => DefaultSshConfig }
import net.schmizz.sshj.sftp.{ SFTPClient => JSFTPClient }
import org.apache.commons.net.ftp.{ FTPClient => JFTPClient }
import zio.Chunk

/**
 * Base trait Ftp Settings
 *
 * @tparam A Ftp client type
 */
sealed trait FtpSettings[+A]

object FtpSettings {
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
    strictHostKeyChecking: Boolean,
    knownHosts: Option[String],
    sftpIdentity: Option[SftpIdentity],
    proxy: Option[Proxy],
    sshConfig: SshConfig
  ) extends FtpSettings[JSFTPClient]

  object SecureFtpSettings {

    def apply(host: String, port: Int, creds: FtpCredentials): SecureFtpSettings =
      new SecureFtpSettings(
        host,
        port,
        creds,
        strictHostKeyChecking = false,
        knownHosts = None,
        sftpIdentity = None,
        proxy = None,
        new DefaultSshConfig()
      )
  }

  sealed trait SftpIdentity {
    type KeyType
    val privateKey: KeyType
    val privateKeyFilePassphrase: Option[Chunk[Byte]]
  }

  /**
   * SFTP identity for authenticating using private/public key value
   *
   * @param privateKey private key value to use when connecting
   * @param privateKeyFilePassphrase password to use to decrypt private key
   * @param publicKey public key value to use when connecting
   */
  final case class RawKeySftpIdentity(
    privateKey: Chunk[Byte],
    privateKeyFilePassphrase: Option[Chunk[Byte]] = None,
    publicKey: Option[Chunk[Byte]] = None
  ) extends SftpIdentity {
    type KeyType = Chunk[Byte]
  }

  /**
   * SFTP identity for authenticating using private/public key file
   *
   * @param privateKey private key file to use when connecting
   * @param privateKeyFilePassphrase password to use to decrypt private key file
   */
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

  /**
   * Settings to connect unsecure Ftp server
   *
   * @param host hostname of ftp server (ipAddress / dnsName)
   * @param port port of communication used by the server
   * @param credentials credentials (username and password)
   * @param binary specifies the file transfer mode, BINARY or ASCII. Default is ASCII (false)
   * @param passiveMode specifies whether to use passive mode connections. Default is active mode (false)
   * @param proxy An optional proxy to use when connecting with these settings
   */
  final case class UnsecureFtpSettings(
    host: String,
    port: Int,
    credentials: FtpCredentials,
    binary: Boolean,
    passiveMode: Boolean,
    proxy: Option[Proxy],
    secure: Boolean
  ) extends FtpSettings[JFTPClient]

  object UnsecureFtpSettings {

    def apply(host: String, port: Int, creds: FtpCredentials): UnsecureFtpSettings =
      new UnsecureFtpSettings(host, port, creds, true, true, None, false)

    def secure(host: String, port: Int, creds: FtpCredentials): UnsecureFtpSettings =
      new UnsecureFtpSettings(host, port, creds, true, true, None, true)
  }
}
