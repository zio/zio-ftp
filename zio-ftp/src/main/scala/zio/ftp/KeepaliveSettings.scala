package zio.ftp

final case class KeepaliveSettings(idle: Option[Int], interval: Option[Int], count: Option[Int])

object KeepaliveSettings {
  val default: KeepaliveSettings = KeepaliveSettings(Some(60), Some(10), Some(5))
}
