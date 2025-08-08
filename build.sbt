val scala3Version = "3.3.6"

lazy val root = project
  .in(file("."))
  .settings(
    name := "mcp-git-ollama-server",
    version := "0.1.0",
    scalaVersion := scala3Version,
    Compile / run / fork := true,
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case x                   => (assembly / assemblyMergeStrategy).value(x)
    },
    libraryDependencies ++= Seq(
      // MCP Protocol
      "org.typelevel" %% "cats-core" % "2.10.0",
      "org.typelevel" %% "cats-effect" % "3.5.2",
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-generic" % "0.14.6",
      "io.circe" %% "circe-parser" % "0.14.6",

      // HTTP Client for Ollama
      "org.http4s" %% "http4s-ember-client" % "0.23.23",
      "org.http4s" %% "http4s-circe" % "0.23.23",
      "org.http4s" %% "http4s-dsl" % "0.23.23",

      // Process execution for Git (built into Scala 3 standard library)
      // "org.scala-lang.modules" %% "scala-sys-process" % "1.0.0", // Not needed for Scala 3

      // Logging
      "org.typelevel" %% "log4cats-slf4j" % "2.6.0",
      "ch.qos.logback" % "logback-classic" % "1.4.11",

      // Configuration
      "com.typesafe" % "config" % "1.4.3",

      // Testing
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test
    )
  )
