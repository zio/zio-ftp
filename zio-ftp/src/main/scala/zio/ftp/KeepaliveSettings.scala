package zio.ftp

/**
 * see the documentation for jdk.net.ExtendedSocketOptions for details about these settings
 */
final case class KeepaliveSettings(idle: Option[Int], interval: Option[Int], count: Option[Int])

/**
 * Factory values for `KeepaliveSettings`.
 */
object KeepaliveSettings {

  /**
   * Keepalive with `idle` 60, `interval` 10 and `count` 5. Each value is set on the socket only when the platform supports that option.
   */
  val default: KeepaliveSettings = KeepaliveSettings(Some(60), Some(10), Some(5))
}
