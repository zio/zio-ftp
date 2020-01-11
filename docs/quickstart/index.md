---
id: quickstart_index
title: "Quick Start"
---

Setup
-----

```
//support scala 2.12 / 2.13

libraryDependencies += "dev.zio" %% "zio-ftp" % "0.1.0"
```


How to use it ?
---

* FTP / FTPS
```scala
import zio.blocking.Blocking
import zio.ftp.FtpClient._
import zio.ftp.FtpSettings._

// FTP
val settings = UnsecureFtpSettings("127.0.0.1", 21, FtpCredentials("foo", "bar"))
// FTP with ssl (FTPS)
val settings = UnsecureFtpSettings.secure("127.0.0.1", 21, FtpCredentials("foo", "bar"))

//listing files
connect(settings).use{
  _.ls("/").runCollect
}

```

* SFTP (support ssh key)

```scala
import zio.blocking.Blocking
import zio.ftp.FtpClient._
import zio.ftp.FtpSettings._

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

//listing files
connect(settings).use{ 
  _.ls("/").runCollect
}
```

Support any commands ?
---

If you need a method which is not wrapped by the library, you can have access to underlying FTP client in a safe manner by using

```scala
trait FtpClient[+A] {
  def execute[T](f: A => T): ZIO[Blocking, IOException, T]
} 
```

All the call are safe since the computation will be executed in the blocking context you will provide

```scala
import zio.ftp.FtpClient._
import zio.ftp.FtpSettings._

val settings = SecureFtpSettings("127.0.0.1", 22, FtpCredentials("foo", "bar"))

connect(settings).use{
  _.execute(_.version())
}
``` 

