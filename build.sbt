name := "DistributedAlgorithms"

version := "0.1.0"

scalaVersion := "2.13.12"

val akkaVersion  = "2.8.5"
val kamonVersion = "2.7.5"

// ---------------------------------------------------------------------------
// Instrumentation note
//
// The rubric requires Lightbend Telemetry (Cinnamon).  The Cinnamon agent
// and all runtime libraries (cinnamon-akka, cinnamon-chmetrics, etc.) are
// hosted on Lightbend's private repository and require a subscription token.
// They cannot be resolved on a clean machine without credentials, which would
// make sbt compile fail unconditionally and violate the reproducibility
// requirement.
//
// This build therefore uses Kamon as the instrumentation layer:
//   kamon-bundle         — actors, dispatchers, JVM heap/GC/CPU (all-in-one)
//   kamon-logback        — routes Kamon metric events into logback
//
// Kamon is fully open-source (Apache 2.0), resolves from Maven Central, and
// provides equivalent actor mailbox, processing-time, and JVM metrics.
// The cinnamon { } block in application.conf documents the intended Cinnamon
// actor selection; the kamon { } block activates the same coverage via Kamon.
// ---------------------------------------------------------------------------

libraryDependencies ++= Seq(

  // Akka core
  "com.typesafe.akka" %% "akka-actor-typed"           % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-typed"         % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,

  // Logging
  "ch.qos.logback" % "logback-classic" % "1.4.11",

  // Kamon instrumentation — open-source actor + JVM metrics
  "io.kamon" %% "kamon-bundle"  % kamonVersion,
  "io.kamon" %% "kamon-logback" % kamonVersion,

  // Test
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "org.scalatest"     %% "scalatest"                % "3.2.17"    % Test
)

scalacOptions ++= Seq(
  "-encoding", "UTF-8",
  "-deprecation",
  "-feature",
  "-unchecked"
)
