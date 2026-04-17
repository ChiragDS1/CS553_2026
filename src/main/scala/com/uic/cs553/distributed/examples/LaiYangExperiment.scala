package com.uic.cs553.distributed.examples

import com.uic.cs553.distributed.framework.ExperimentRunner
import com.uic.cs553.distributed.algorithms.laiyang.LaiYangNode
import com.uic.cs553.distributed.utils.GraphvizLogger

/**
 * Runs the Lai-Yang snapshot algorithm.
 *
 * Usage (from sbt):
 *   runMain com.uic.cs553.distributed.examples.LaiYangExperiment
 *   runMain com.uic.cs553.distributed.examples.LaiYangExperiment --config conf/experiment-complete.conf
 *   runMain com.uic.cs553.distributed.examples.LaiYangExperiment --config conf/experiment-sparse.conf --duration 20
 *
 * If --config is omitted the bundled topology.conf resource is used.
 * If --duration is omitted the default is 15 seconds.
 */
object LaiYangExperiment {

  def main(args: Array[String]): Unit = {
    val parsed   = parseArgs(args)
    val config   = parsed.get("config")
    val duration = parsed.getOrElse("duration", "15").toInt

    GraphvizLogger.init("laiyang.dot")

    try {
      config match {
        case Some(path) =>
          ExperimentRunner.runFromConfigFile(
            algorithmName   = "Lai-Yang Snapshot",
            configFilePath  = path,
            nodeFactory     = gn => new LaiYangNode(gn.id),
            durationSeconds = duration
          )
        case None =>
          ExperimentRunner.runFromResource(
            algorithmName   = "Lai-Yang Snapshot",
            resourceName    = "topology.conf",
            nodeFactory     = gn => new LaiYangNode(gn.id),
            durationSeconds = duration
          )
      }
    } finally {
      GraphvizLogger.finish()
      println("DOT file written: laiyang.dot")
    }
  }

  private def parseArgs(args: Array[String]): Map[String, String] =
    args.sliding(2, 2).collect {
      case Array(k, v) if k.startsWith("--") => k.stripPrefix("--") -> v
    }.toMap
}