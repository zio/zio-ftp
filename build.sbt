lazy val scala212 = "2.12.10"
lazy val scala213 = "2.13.0"

lazy val `zio-ftp` = project
  .in(file("."))
  .settings(
    organization := "dev.zio",
    name := "zio-ftp",
    description := "zio-ftp",
    Test / fork := true,
    parallelExecution in Test := false,
    homepage := Some(url("https://github.com/zio/zio-ftp")),
    scmInfo := Some(ScmInfo(url("https://github.com/zio/zio-ftp"), "git@github.com:zio/zio-ftp.git")),
    developers := List(
      Developer("jdegoes", "John De Goes", "john@degoes.net", url("http://degoes.net")),
      Developer("regis-leray", "Regis Leray", "regis.leray@gmail.com", url("https://github.com/regis-leray"))
    ),
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    publishMavenStyle := true,
    // crossScalaVersions must be set to Nil on the aggregating project
    crossScalaVersions := List(scala212, scala213),
    scalaVersion := scala212,
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xlint",
      "-Xfatal-warnings",
      "-language:higherKinds",
      "-language:postfixOps"
    ) ++ PartialFunction
      .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
        case Some((2, n)) if n < 13 => Seq("-Ypartial-unification")
      }
      .toList
      .flatten,
    publishTo := {
      if (isSnapshot.value)
        Some(Opts.resolver.sonatypeSnapshots)
      else
        Some(Opts.resolver.sonatypeStaging)
    },
    addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt"),
    addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
  )
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"                  %% "zio"                     % "1.0.0-RC17",
      "dev.zio"                  %% "zio-streams"             % "1.0.0-RC17",
      "com.hierynomus"           % "sshj"                     % "0.27.0",
      "commons-net"              % "commons-net"              % "3.6",
      "org.scala-lang.modules"   %% "scala-collection-compat" % "2.1.2",
      "org.apache.logging.log4j" % "log4j-api"                % "2.12.0" % Test,
      "org.apache.logging.log4j" % "log4j-core"               % "2.12.0" % Test,
      "org.apache.logging.log4j" % "log4j-slf4j-impl"         % "2.12.0" % Test,
      "dev.zio"                  %% "zio-test"                % "1.0.0-RC17" % Test,
      "dev.zio"                  %% "zio-test-sbt"            % "1.0.0-RC17" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
