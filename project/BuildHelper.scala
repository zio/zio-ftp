import sbt.*
import sbt.Keys.*

object BuildHelper {

  def stdSettings(prjName: String) =
    Seq(
      name := s"$prjName",
      crossScalaVersions := Seq(Scala212, Scala213, Scala3),
      ThisBuild / scalaVersion := Scala213,
      scalacOptions := stdOptions ++ extraOptions(scalaVersion.value),
      incOptions ~= (_.withLogRecompileOnMacro(false))
    )

  final val Scala212 = "2.12.18"
  final val Scala213 = "2.13.15"
  final val Scala3   = "3.3.3" // LTS

  final private val stdOptions = Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings"
  )

  final private val std2xOptions = Seq(
    "-explaintypes",
    "-Yrangepos",
    "-language:higherKinds",
    "-language:existentials",
    "-Xlint:_,-type-parameter-shadow",
    "-Xsource:2.13",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard"
  )

  private def extraOptions(scalaVersion: String) =
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((3, 2))  =>
        Seq.empty
      case Some((2, 13)) =>
        Seq(
          "-Wunused:imports",
          "-Wvalue-discard",
          "-Wunused:patvars",
          "-Wunused:privates",
          "-Wunused:params",
          "-Wvalue-discard",
          "-Wdead-code"
        ) ++ std2xOptions
      case Some((2, 12)) =>
        Seq(
          "-opt-warnings",
          "-Ywarn-extra-implicit",
          "-Ywarn-unused:_,imports",
          "-Ywarn-unused:imports",
          "-opt:l:inline",
          "-opt-inline-from:<source>",
          "-Xfuture",
          "-Ypartial-unification",
          "-Ywarn-nullary-override",
          "-Yno-adapted-args",
          "-Ywarn-infer-any",
          "-Ywarn-inaccessible",
          "-Ywarn-nullary-unit",
          "-Ywarn-unused-import"
        ) ++ std2xOptions
      case _             => Seq.empty
    }
}
