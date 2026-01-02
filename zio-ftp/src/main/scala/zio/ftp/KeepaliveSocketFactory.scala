package zio.ftp

import java.net.Socket
import javax.net.SocketFactory
import java.net.InetAddress

private class KeepaliveSocketFactory(underlying: SocketFactory, keepaliveSettings: KeepaliveSettings)
    extends SocketFactory {

  private def cfg(socket: Socket): Socket = {
    socket.setKeepAlive(true)
    keepaliveSettings.idle.foreach(socket.setOption[Integer](jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE, _))
    keepaliveSettings.interval.foreach(socket.setOption[Integer](jdk.net.ExtendedSocketOptions.TCP_KEEPINTERVAL, _))
    keepaliveSettings.count.foreach(socket.setOption[Integer](jdk.net.ExtendedSocketOptions.TCP_KEEPCOUNT, _))
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
