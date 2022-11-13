---
id: index
title: "ZIO FTP"
---

ZIO FTP is a thin wrapper over (s)Ftp client for ZIO.

Setup
---

Support Scala 2.11 / 2.12 / 2.13

```
libraryDependencies += "dev.zio" %% "zio-ftp" % "@VERSION@"
```


How to use it ?
---

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

Support any commands ?
---

If you need a method which is not wrapped by the library, you can have access to underlying FTP client in a safe manner by using

```scala
import zio._

trait FtpAccessors[+A] {
  def execute[T](f: A => T): ZIO[Any, IOException, T]
} 
```

