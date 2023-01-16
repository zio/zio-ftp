import BuildHelper._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage := Some(url("https://zio.dev/zio-ftp/")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer("jdegoes", "John De Goes", "john@degoes.net", url("http://degoes.net")),
      Developer("regis-leray", "Regis Leray", "regis.leray@gmail.com", url("https://github.com/regis-leray"))
    ),
    Test / fork := true,
    Test / parallelExecution := false,
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

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
addCommandAlias("fix", "; all compile:scalafix test:scalafix; all scalafmtSbt scalafmtAll")

val zioVersion = "2.0.0"

lazy val root =
  project.in(file(".")).settings(publish / skip := false).aggregate(`zio-ftp`, docs)

lazy val `zio-ftp` = project
  .in(file("zio-ftp"))
  .settings(stdSettings("zio-ftp"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"                 %% "zio"                     % zioVersion,
      "dev.zio"                 %% "zio-streams"             % zioVersion,
      "dev.zio"                 %% "zio-nio"                 % "2.0.0",
      "com.hierynomus"           % "sshj"                    % "0.34.0",
      "commons-net"              % "commons-net"             % "3.8.0",
      "org.scala-lang.modules"  %% "scala-collection-compat" % "2.8.1",
      "org.apache.logging.log4j" % "log4j-api"               % "2.13.1"   % Test,
      "org.apache.logging.log4j" % "log4j-core"              % "2.13.1"   % Test,
      "org.apache.logging.log4j" % "log4j-slf4j-impl"        % "2.13.1"   % Test,
      "dev.zio"                 %% "zio-test"                % zioVersion % Test,
      "dev.zio"                 %% "zio-test-sbt"            % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val docs = project
  .in(file("zio-ftp-docs"))
  .settings(
    moduleName := "zio-ftp-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    test / aggregate := false,
    scalaVersion := Scala213,
    crossScalaVersions := Seq(Scala212, Scala213),
    projectName := "ZIO FTP",
    mainModuleName := (`zio-ftp` / moduleName).value,
    projectStage := ProjectStage.ProductionReady,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(`zio-ftp`),
    docsPublishBranch := "series/2.x"
  )
  .dependsOn(`zio-ftp`)
  .enablePlugins(WebsitePlugin)
