addSbtPlugin("org.scoverage"  % "sbt-scoverage"   % "1.9.3")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"    % "2.4.6")
addSbtPlugin("com.github.sbt" % "sbt-ci-release"  % "1.5.10")
addSbtPlugin("ch.epfl.scala"  % "sbt-bloop"       % "1.5.4")
addSbtPlugin("org.scalameta"  % "sbt-mdoc"        % "2.2.24")
addSbtPlugin("com.eed3si9n"   % "sbt-unidoc"      % "0.4.3")
addSbtPlugin("ch.epfl.scala"  % "sbt-scalafix"    % "0.9.29")
addSbtPlugin("dev.zio"        % "zio-sbt-website" % "0.0.0+80-e5b408eb-SNAPSHOT")

resolvers += Resolver.sonatypeRepo("public")
