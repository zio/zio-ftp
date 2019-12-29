package zio

import java.io.IOException

import zio.blocking.Blocking
import zio.macros.delegate.Mix

package object ftp {

  def taskIO[A](f: => A): ZIO[Any, IOException, A] = Task(f).refineOrDie {
    case e: IOException => e
  }

  def withBlocking[A](a: A)(implicit ev: Mix[A, Blocking]): A with Blocking = ev.mix(a, Blocking.Live)
}
