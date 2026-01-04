package zio.ftp

import java.net.Socket
import javax.net.SocketFactory
import java.net.InetAddress
import jdk.net.ExtendedSocketOptions.{ TCP_KEEPCOUNT, TCP_KEEPIDLE, TCP_KEEPINTERVAL }
import java.net.SocketOption
import zio.ZIO
import zio.Unsafe

private class KeepaliveSocketFactory(
  underlying: SocketFactory,
  keepaliveSettings: KeepaliveSettings,
  runtime: zio.Runtime[Any]
) extends SocketFactory {

  private def cfg(socket: Socket): Socket = {
    socket.setKeepAlive(true)
    Seq[(SocketOption[Integer], Option[Int])](
      TCP_KEEPIDLE     -> keepaliveSettings.idle,
      TCP_KEEPINTERVAL -> keepaliveSettings.interval,
      TCP_KEEPCOUNT    -> keepaliveSettings.count
    ).foreach {
      case (option, valueOpt) =>
        valueOpt.foreach { value =>
          if (socket.supportedOptions().contains(option))
            socket.setOption[Integer](option, value)
          else
            Unsafe.unsafe { implicit u =>
              runtime.unsafe.run(
                ZIO.logWarning(s"Socket option $option is not supported on this platform and cannot be set.")
              )
            }
        }
    }
    socket
  }

  override def createSocket(): Socket =
    cfg(underlying.createSocket())

  override def createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
    cfg(underlying.createSocket(host, port, localHost, localPort))

  override def createSocket(host: InetAddress, port: Int): Socket =
    cfg(underlying.createSocket(host, port))

  override def createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
    cfg(underlying.createSocket(address, port, localAddress, localPort))

  override def createSocket(host: String, port: Int): Socket =
    cfg(underlying.createSocket(host, port))

}
