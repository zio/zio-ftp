import BuildHelper._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage := Some(url("https://zio.github.io/zio-ftp/")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer("jdegoes", "John De Goes", "john@degoes.net", url("http://degoes.net")),
      Developer("regis-leray", "Regis Leray", "regis.leray@gmail.com", url("https://github.com/regis-leray"))
    ),
    Test / fork := true,
    parallelExecution in Test := false,
    publishMavenStyle := true,
    pgpPassphrase := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc"),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/zio/zio-ftp/"),
        "scm:git:git@github.com:zio/zio-ftp.git"
      )
    )
  )
)

ThisBuild / publishTo := sonatypePublishToBundle.value

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

lazy val `zio-ftp` = project
  .in(file("."))
  .settings(
    skip in publish := true,
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
