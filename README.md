# ZIO with SFTP - FTP / FTPS

thin wrapper over ftp and sftp client, which wrap with ZIO libraries

[![Build Status](https://circleci.com/gh/zio/zio-ftp.svg?style=svg&circle-token=???)](https://circleci.com/gh/zio/zio-ftp)
[![codecov](https://codecov.io/gh/zio/zio-ftp/branch/master/graph/badge.svg)](https://codecov.io/gh/zio/zio-ftp)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.zio/zio-ftp_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Czio-ftp) 

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

import zio.ftp.Ftp._

// FTP
val settings = FtpSettings("127.0.0.1", 21, credentials("foo", "bar"))
// FTPS
val settings = FtpSettings.secure("127.0.0.1", 21, credentials("foo", "bar"))

connect(settings).use(listFiles("/")(_).runCollect)
```

* SFTP

```scala
import zio.ftp.SFtp._

val settings = SFtpSettings("127.0.0.1", 22, credentials("foo", "bar"))

connect(settings).use(listFiles("/")(_).runCollect)    
```