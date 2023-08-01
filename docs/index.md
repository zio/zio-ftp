---
id: index
title: "ZIO FTP"
---

[ZIO FTP](https://zio.dev) is a thin wrapper over (s)Ftp client for [ZIO](https://zio.dev).

@PROJECT_BADGES@

## Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-ftp" % "@VERSION@" 
```

## How to use it?

* Imports
```scala mdoc:silent
import zio.ftp._
```

* FTP
```scala mdoc:silent
// FTP
val unsecureSettings = UnsecureFtpSettings("127.0.0.1", 21, FtpCredentials("foo", "bar"))

//listing files
Ftp.ls("/").runCollect.provideLayer(unsecure(unsecureSettings))
```

* FTPS
```scala mdoc:silent
// FTPS
val secureSettings = SecureFtpSettings("127.0.0.1", 21, FtpCredentials("foo", "bar"))

//listing files
SFtp.ls("/").runCollect.provideLayer(secure(secureSettings))
```

* SFTP (support ssh key)

```scala mdoc:silent
val sftpSettings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

//listing files
SFtp.ls("/").runCollect.provideLayer(secure(sftpSettings))
```

## Example

First we need an FTP server, so let's create one:

```bash
docker run -d \
    -p 21:21 \
    -p 21000-21010:21000-21010 \
    -e USERS="one|1234" \
    -e ADDRESS=localhost \
    delfer/alpine-ftp-server
```

Now we can run the example:

```scala mdoc:compile-only
import zio._
import zio.stream._
import zio.ftp._
import zio.ftp.Ftp._

import java.io.IOException

object ZIOFTPExample extends ZIOAppDefault {

  private val settings =
    UnsecureFtpSettings("127.0.0.1", 21, FtpCredentials("one", "1234"))

  private val myApp: ZIO[Ftp, IOException, Unit] =
    for {
      _        <- Console.printLine("List of files at root directory:")
      resource <- ls("/").runCollect
      _        <- ZIO.foreach(resource)(e => Console.printLine(e.path))
      path      = "~/file.txt"
      _        <- upload(
                    path,
                    ZStream.fromChunk(
                      Chunk.fromArray("Hello, ZIO FTP!\nHello, World!".getBytes)
                    )
                  )
      file     <- readFile(path)
                    .via(ZPipeline.utf8Decode)
                    .runCollect
      _        <- Console.printLine(s"Content of $path file:")
      _        <- Console.printLine(file.fold("")(_ + _))
    } yield ()

  override def run = myApp.provideSomeLayer(unsecure(settings))
}
```

## Support any commands?

If you need a method which is not wrapped by the library, you can have access to underlying FTP client in a safe manner by using

```scala
import zio._

trait FtpAccessors[+A] {
  def execute[T](f: A => T): ZIO[Any, IOException, T]
} 
```
