import BuildHelper._
import zio.sbt.WebsiteUtils
import zio.sbt.githubactions.{ DependencyBot, Job, Step }

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

ThisBuild / ciEnabledBranches := Seq("series/2.x")
ThisBuild / ciDefaultJavaVersion := "21"
ThisBuild / ciTargetJavaVersions := Seq("21")
ThisBuild / ciTargetScalaVersions := Map("zio-ftp" -> Seq(Scala212, Scala213, Scala3))
ThisBuild / ciPublishSnapshots := true
// Not part of today's CI: no bot workflows that need extra secrets, no dependabot config.
ThisBuild / ciEnableScalaSteward := false
ThisBuild / ciEnableDependabot := false
ThisBuild / ciEnableRegenerateWorkflows := false
ThisBuild / ciDependencyUpdateBots := Seq(DependencyBot.Custom("scala-steward"), DependencyBot.Renovate)
ThisBuild / ciCheckWebsiteBuildProcess := Seq(
  Step.SingleStep(name = "Check Document Generation", run = Some("sbt docs/compileDocs"))
)
// The tests need the FTP, FTPS and SFTP servers from docker-compose.yml.
inThisBuild(List(ciTestJobs := {
  val startContainers               = Step.SingleStep(
    name = "Start containers",
    run = Some(
      """chmod -R 777 ./zio-ftp/ftp-home/
        |docker compose -f "docker-compose.yml" up -d --build
        |chmod -R 777 ./zio-ftp/ftp-home/sftp/home/foo/dir1""".stripMargin
    )
  )
  def withContainers(job: Job): Job =
    job.copy(steps = job.steps.init ++ Seq(startContainers, job.steps.last))

  // Every Scala version on Java 21, then the default Scala version on the other supported JVMs.
  val allScalaVersions = ciTestJobs.value.map(withContainers)
  val otherJvms        = allScalaVersions.map(job =>
    job.copy(
      id = "test-jvms",
      name = "Test JVMs",
      strategy = job.strategy.map(s =>
        s.copy(matrix = Map("java" -> List("17"), "scala-project" -> List(s"++$Scala213 zio-ftp")))
      )
    )
  )
  allScalaVersions ++ otherJvms
}))

addCommandAlias("lint", "check")
addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
addCommandAlias("fix", "; all compile:scalafix test:scalafix; all scalafmtSbt scalafmtAll")

val zioVersion = "2.1.11"

lazy val root =
  project
    .in(file("."))
    .settings(
      publish / skip := true
    )
    .aggregate(`zio-ftp`, docs)

lazy val `zio-ftp` = project
  .in(file("zio-ftp"))
  .settings(stdSettings("zio-ftp"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"                 %% "zio"                     % zioVersion,
      "dev.zio"                 %% "zio-streams"             % zioVersion,
      //TODO remove dependency
      ("dev.zio"                %% "zio-nio"                 % "2.0.2").exclude("org.scala-lang.modules", "scala-collection-compat_2.13"),
      "com.hierynomus"           % "sshj"                    % "0.40.0",
      "commons-net"              % "commons-net"             % "3.11.0",
      "org.scala-lang.modules"  %% "scala-collection-compat" % "2.13.0",
      "org.apache.logging.log4j" % "log4j-api"               % "2.24.1"   % Test,
      "org.apache.logging.log4j" % "log4j-core"              % "2.24.1"   % Test,
      "org.apache.logging.log4j" % "log4j-slf4j-impl"        % "2.24.1"   % Test,
      "dev.zio"                 %% "zio-test"                % zioVersion % Test,
      "dev.zio"                 %% "zio-test-sbt"            % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val docs = project
  .in(file("zio-ftp-docs"))
  .settings(
    publish / skip := true,
    moduleName := "zio-ftp-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    libraryDependencies ++= Seq("dev.zio" %% "zio" % zioVersion),
    scalaVersion := Scala213,
    crossScalaVersions := Seq(Scala212, Scala213, Scala3),
    projectName := "ZIO FTP",
    mainModuleName := (`zio-ftp` / moduleName).value,
    projectStage := ProjectStage.ProductionReady,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(`zio-ftp`),
    // The generated snapshot badge reads artifact-level maven-metadata.xml, which the Central
    // snapshot repo does not publish, so it always shows "not found". Swap only that badge for
    // a static one. The @PROJECT_BADGES@ placeholder in docs/index.md stays as it is.
    mdocVariables := {
      val generated      = mdocVariables.value("PROJECT_BADGES")
      val liveSnapshot   = WebsiteUtils.snapshotBadge(organization.value, badgeArtifactId.value)
      val staticSnapshot =
        "[![Sonatype Snapshots](https://img.shields.io/badge/Sonatype%20Snapshot-published-blue)]" +
          "(https://central.sonatype.com/repository/maven-snapshots/dev/zio/zio-ftp_2.13/)"
      mdocVariables.value.updated("PROJECT_BADGES", generated.replace(liveSnapshot, staticSnapshot))
    }
  )
  .dependsOn(`zio-ftp`)
  .enablePlugins(WebsitePlugin)
