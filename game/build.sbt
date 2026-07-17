import org.scalajs.linker.interface.ModuleKind

val scala3 = "3.3.4"

inThisBuild(Seq(
  scalaVersion := scala3,
  organization := "game",
  scalacOptions += "-Wunused:all"
))

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("shared"))
  .settings(
    libraryDependencies += "com.lihaoyi" %%% "upickle" % "3.3.1"
  )

lazy val client = project
  .in(file("client"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared.js)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.NoModule) },
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.0"
    )
  )

lazy val server = project
  .in(file("server"))
  .dependsOn(shared.jvm)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s"    %% "http4s-ember-server" % "0.23.30",
      "org.http4s"    %% "http4s-dsl"          % "0.23.30",
      "org.typelevel" %% "cats-effect"         % "3.5.7",
      "org.slf4j"      % "slf4j-simple"        % "2.0.16"
    ),
    fork := true,
    run / envVars := Map(
      "SCALA_PORT" -> sys.env.getOrElse("SCALA_PORT", "8081")
    )
  )
