import sbt.*
import sbt.Keys.*

object BuildHelper {

  def stdSettings(prjName: String) =
    Seq(
      name := s"$prjName",
      crossScalaVersions := Seq(Scala211, Scala212, Scala213, Scala3),
      ThisBuild / scalaVersion := Scala213,
      scalacOptions := stdOptions ++ extraOptions(scalaVersion.value),
      incOptions ~= (_.withLogRecompileOnMacro(false))
    )

  final val Scala211       = "2.11.12"
  final val Scala212       = "2.12.16"
  final val Scala213       = "2.13.8"
  final val Scala3: String = "3.2.2"

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
      case Some((2, 11)) =>
        Seq(
          "-Ypartial-unification",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-infer-any",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit",
          "-Xexperimental",
          "-Ywarn-unused-import",
          "-Xfuture",
          "-Xsource:2.13",
          "-Xmax-classfile-name",
          "242"
        ) ++ std2xOptions
      case _             => Seq.empty
    }
}
