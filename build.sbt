import BuildHelper._
import zio.sbt.githubactions.{ DependencyBot, Job, Step, Strategy }

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

ThisBuild / ciEnabledBranches := Seq("master")
ThisBuild / ciTargetJavaVersions := Seq("8", "11", "17")
ThisBuild / ciDefaultJavaVersion := "8"
ThisBuild / ciTargetScalaVersions := Map("zio-ftp" -> Seq("2.11.12", "2.12.15", "2.13.8"))
ThisBuild / ciUpdateReadmeJobs := Seq.empty
ThisBuild / ciPostReleaseJobs := Seq.empty
ThisBuild / ciDependencyUpdateBots := Seq(DependencyBot.Custom("scala-steward"), DependencyBot.Renovate)
inThisBuild(List(ciTestJobs := {
  val startContainers               = Step.SingleStep(
    name = "Start containers",
    run = Some(
      """chmod -R 777 ./ftp-home/
        |docker compose -f "docker-compose.yml" up -d --build
        |chmod -R 777 ./ftp-home/sftp/home/foo/dir1""".stripMargin
    )
  )
  def withContainers(job: Job): Job =
    job.copy(steps = job.steps.init ++ Seq(startContainers, job.steps.last))

  // Scala 2.11 does not run on Java 11+, so only Java 8 covers every Scala version
  val allScalaVersions = ciTestJobs.value.map(job =>
    withContainers(
      job.copy(strategy = job.strategy.map(s => s.copy(matrix = s.matrix.updated("java", List("8")))))
    )
  )
  val modernJvms       = allScalaVersions.map(job =>
    job.copy(
      id = "test-jvms",
      name = "Test JVMs",
      strategy = job.strategy.map(s =>
        s.copy(matrix = Map("java" -> List("11", "17"), "scala-project" -> List("++2.13.8 zio-ftp")))
      )
    )
  )
  allScalaVersions ++ modernJvms
}))
ThisBuild / ciCheckWebsiteBuildProcess := Seq(
  Step.SingleStep(name = "Check website build process", run = Some("sbt docs/docusaurusCreateSite"))
)

addCommandAlias("lint", "check")
addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

val zioVersion = "1.0.16"

lazy val `zio-ftp` = project
  .in(file("."))
  .settings(stdSettings("zio-ftp"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"                 %% "zio"                     % zioVersion,
      "dev.zio"                 %% "zio-streams"             % zioVersion,
      "dev.zio"                 %% "zio-nio"                 % "1.0.0-RC12",
      "com.hierynomus"           % "sshj"                    % "0.33.0",
      "commons-net"              % "commons-net"             % "3.8.0",
      "org.scala-lang.modules"  %% "scala-collection-compat" % "2.7.0",
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
    skip.in(publish) := true,
    moduleName := "zio-ftp-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion
    ),
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(`zio-ftp`),
    target in (ScalaUnidoc, unidoc) := (baseDirectory in LocalRootProject).value / "website" / "static" / "api",
    cleanFiles += (target in (ScalaUnidoc, unidoc)).value,
    docusaurusCreateSite := docusaurusCreateSite.dependsOn(unidoc in Compile).value,
    docusaurusPublishGhpages := docusaurusPublishGhpages.dependsOn(unidoc in Compile).value
  )
  .dependsOn(`zio-ftp`)
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)
