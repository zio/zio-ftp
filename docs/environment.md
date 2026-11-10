---
id: environment
title: "Environment"
---

ZIO FTP keeps its connection in the ZIO environment, so each operation states in its type which connection it needs. Nothing connects until you provide a layer, and the layer closes the connection when its scope ends.

## Two Environment Types

Plain FTP operations require `Ftp`, and FTP over SSH (SFTP) operations require `SFtp`. Both aliases live in the `zio.ftp` package object:

```scala
type Ftp  = FtpAccessors[UnsecureFtp.Client]
type SFtp = FtpAccessors[SecureFtp.Client]
```

The `FtpAccessors` trait is the common contract. Its methods need nothing from the environment, because the service is the object they belong to. The `Ftp` and `SFtp` helpers take that service from the environment and forward the call to it:

```scala
object Ftp {
  def ls(path: String): ZStream[Ftp, IOException, FtpResource]
}
```

## Providing the Environment

Use `unsecure` for plain FTP and `secure` for SFTP. Each returns a layer that connects when it is built, fails with a `ConnectionError` if the server refuses the login, and disconnects when its scope closes:

```scala
package object ftp {
  def unsecure(settings: UnsecureFtpSettings): ZLayer[Any, ConnectionError, Ftp]
  def secure(settings: SecureFtpSettings): ZLayer[Any, ConnectionError, SFtp]
}
```

To remove `Ftp` from an effect's requirements, pass the layer to `ZIO#provide`:

```scala mdoc:compile-only
import zio._
import zio.ftp._
import zio.ftp.Ftp._

val settings = UnsecureFtpSettings("127.0.0.1", 21, PasswordCredentials("one", "1234"))

val listing = ls("/").runCollect.provide(unsecure(settings))
```

For SFTP, the same pattern applies with `secure` and the `SFtp` accessors:

```scala mdoc:compile-only
import zio.ftp._

val sftpSettings = SecureFtpSettings("127.0.0.1", 22, PasswordCredentials("one", "1234"))

val sftpListing = SFtp.ls("/").runCollect.provide(secure(sftpSettings))
```

## Running Java Calls Safely

The FTP and SFTP Java clients only offer blocking methods. ZIO FTP runs every call to the Java client through `FtpAccessors#execute`, which wraps the call in `ZIO.attemptBlockingIO`. That moves the call onto ZIO's blocking thread pool, so the main pool keeps running other fibers. In the plain FTP client, `UnsecureFtp#execute` is implemented as:

```scala
override def execute[T](f: Client => T): ZIO[Any, IOException, T] =
  attemptBlockingIO(f(unsafeClient))
```

You don't provide any extra layer for this. ZIO 2 dropped the old default-services environment, so every function in this library requires only `Ftp` or `SFtp`. If you call a Java client method that the library doesn't wrap, use `FtpAccessors#execute` so it runs on the blocking pool too.

## Example

The following program runs as a `ZIOAppDefault`. It provides `Ftp` once, at the edge of the program, and prints the root directory listing. It needs a running FTP server, so it is compile-only:

```scala mdoc:compile-only
import zio._
import zio.ftp._
import zio.ftp.Ftp._

import java.io.IOException

object EnvironmentExample extends ZIOAppDefault {

  private val settings =
    UnsecureFtpSettings("127.0.0.1", 21, PasswordCredentials("one", "1234"))

  private val program: ZIO[Ftp, IOException, Unit] =
    for {
      files <- ls("/").runCollect
      _     <- ZIO.foreachDiscard(files)(file => Console.printLine(file.path))
    } yield ()

  override def run = program.provide(unsecure(settings))
}
```

## Further Reading

For the ZIO 1 to ZIO 2 changes to the environment and default services, see the [ZIO 2.x migration guide](https://zio.dev/guides/migrate/zio-2.x-migration-guide). For how ZIO runs synchronous code that waits on the operating system, see [Creating Effects](https://zio.dev/overview/creating-effects/#blocking-synchronous-code).
