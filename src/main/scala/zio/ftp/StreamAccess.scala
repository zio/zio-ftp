package zio.ftp

import zio.ZIO
import zio.stream.ZStream

//TODO need to be removed
//use ZIO 1.0.0-RC18 when release
object StreamAccess {

  final class B[R](private val dummy: Boolean = true) extends AnyVal {

    def apply[E, A](f: R => ZStream[R, E, A]): ZStream[R, E, A] =
      ZStream.unwrap(ZIO.access(f))
  }

  final def accessM[R]: B[R] = new B[R]
}
