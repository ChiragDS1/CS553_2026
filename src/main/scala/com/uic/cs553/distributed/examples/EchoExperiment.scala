package com.uic.cs553.distributed.examples

import com.uic.cs553.distributed.framework.ExperimentRunner
import com.uic.cs553.distributed.algorithms.echo.EchoNode
import com.uic.cs553.distributed.utils.GraphvizLogger

/**
 * Runs the Echo Extinction Anonymous algorithm.
 *
 * Usage (from sbt):
 *   runMain com.uic.cs553.distributed.examples.EchoExperiment
 *   runMain com.uic.cs553.distributed.examples.EchoExperiment --config conf/experiment-ring.conf
 *   runMain com.uic.cs553.distributed.examples.EchoExperiment --config conf/experiment-sparse.conf --duration 20
 *
 * If --config is omitted the bundled topology.conf resource is used.
 * If --duration is omitted the default is 15 seconds.
 */
object EchoExperiment {

  def main(args: Array[String]): Unit = {
    val parsed   = parseArgs(args)
    val config   = parsed.get("config")
    val duration = parsed.getOrElse("duration", "15").toInt

    GraphvizLogger.init("echo.dot")

    try {
      config match {
        case Some(path) =>
          ExperimentRunner.runFromConfigFile(
            algorithmName   = "Echo Extinction Anonymous",
            configFilePath  = path,
            nodeFactory     = gn => new EchoNode(gn.id),
            durationSeconds = duration
          )
        case None =>
          ExperimentRunner.runFromResource(
            algorithmName   = "Echo Extinction Anonymous",
            resourceName    = "topology.conf",
            nodeFactory     = gn => new EchoNode(gn.id),
            durationSeconds = duration
          )
      }
    } finally {
      GraphvizLogger.finish()
      println("DOT file written: echo.dot")
    }
  }

  private def parseArgs(args: Array[String]): Map[String, String] =
    args.sliding(2, 2).collect {
      case Array(k, v) if k.startsWith("--") => k.stripPrefix("--") -> v
    }.toMap
}