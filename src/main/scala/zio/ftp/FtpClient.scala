package zio.ftp

import java.io.IOException

import zio.blocking.Blocking
import zio.ftp.Settings.{ FtpSettings, SFtpSettings }
import zio.stream.{ ZStream, ZStreamChunk }
import zio.{ ZIO, ZManaged }

trait FtpClient[+A] {
  def execute[T](f: A => T): ZIO[Blocking, Throwable, T]
  def stat(path: String): ZIO[Blocking, IOException, Option[FtpResource]]
  def readFile(path: String, chunkSize: Int = 2048): ZStreamChunk[Blocking, IOException, Byte]
  def rm(path: String): ZIO[Blocking, IOException, Unit]
  def rmdir(path: String): ZIO[Blocking, IOException, Unit]
  def mkdir(path: String): ZIO[Blocking, IOException, Unit]
  def ls(path: String): ZStream[Blocking, IOException, FtpResource]
  def lsDescendant(path: String): ZStream[Blocking, IOException, FtpResource]
  def upload[R <: Blocking](path: String, source: ZStreamChunk[R, Throwable, Byte]): ZIO[R, Throwable, Unit]
}

object FtpClient {

  def connect[A](settings: Settings[A]): ZManaged[Blocking, ConnectionError, FtpClient[A]] = settings match {
    case s: FtpSettings  => Ftp.connect(s)
    case s: SFtpSettings => SFtp.connect(s)
  }
}
