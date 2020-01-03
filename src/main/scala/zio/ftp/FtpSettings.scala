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

sealed trait FtpSettings[+A]

object FtpSettings {
  final case class FtpCredentials(username: String, password: String)

  final case class SecureFtpSettings(
    host: String,
    port: Int,
    credentials: FtpCredentials,
    strictHostKeyChecking: Boolean,
    knownHosts: Option[String],
    sftpIdentity: Option[SftpIdentity],
    sshConfig: SshConfig
  ) extends FtpSettings[JSFTPClient]

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

  object SecureFtpSettings {

    def apply(host: String, port: Int, creds: FtpCredentials): SecureFtpSettings =
      new SecureFtpSettings(
        host,
        port,
        creds,
        strictHostKeyChecking = false,
        knownHosts = None,
        sftpIdentity = None,
        new DefaultSshConfig()
      )
  }

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
