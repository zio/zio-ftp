---
id: quickstart_index
title: "Quick Start"
---

Setup
-----

```
//support scala 2.12 / 2.13

libraryDependencies += "dev.zio" %% "zio-ftp" % "0.3.0"
```


How to use it ?
---

* FTP / FTPS
```scala
import zio.blocking.Blocking
import zio.ftp._
import zio.ftp.Ftp._

// FTP
val settings = UnsecureFtpSettings("127.0.0.1", 21, FtpCredentials("foo", "bar"))
// FTP with ssl (FTPS)
val settings = UnsecureFtpSettings.secure("127.0.0.1", 21, FtpCredentials("foo", "bar"))

//listing files
ls("/").runCollect.provideLayer(
  unsecure(settings) ++ Blocking.live
)
```

* SFTP (support ssh key)

```scala
import zio.blocking.Blocking
import zio.ftp._
import zio.ftp.SFtp._

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

//listing files
ls("/").runCollect.provideLayer(
  unsecure(settings) ++ Blocking.live
)
```

Support any commands ?
---

If you need a method which is not wrapped by the library, you can have access to underlying FTP client in a safe manner by using

```scala
import zio.blocking.Blocking
import zio._

trait FtpAccessors[+A] {
  def execute[T](f: A => T): ZIO[Blocking, IOException, T]
} 
```

All the call are safe since the computation will be executed in the blocking context you will provide

```scala
import zio.ftp._
import zio.ftp.Ftp._
import zio.blocking.Blocking

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

execute(_.noop()).provideLayer(
  unsecure(settings) ++ Blocking.live
)
``` 

