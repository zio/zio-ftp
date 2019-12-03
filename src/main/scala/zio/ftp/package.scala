package zio

import java.io.IOException

package object ftp {

  def taskIO[A](f: => A): ZIO[Any, IOException, A] = Task(f).refineOrDie {
    case e: IOException => e
  }
}
