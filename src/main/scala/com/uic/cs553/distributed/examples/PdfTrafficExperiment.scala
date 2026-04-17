package com.uic.cs553.distributed.examples

import com.uic.cs553.distributed.framework.ExperimentRunner
import com.uic.cs553.distributed.algorithms.traffic.PdfTrafficNode

/**
 * Runs the PDF-driven background traffic demo.
 *
 * Usage (from sbt):
 *   runMain com.uic.cs553.distributed.examples.PdfTrafficExperiment
 *   runMain com.uic.cs553.distributed.examples.PdfTrafficExperiment --config conf/experiment-sparse.conf
 *   runMain com.uic.cs553.distributed.examples.PdfTrafficExperiment --config conf/experiment-ring.conf --duration 20
 *
 * If --config is omitted the bundled traffic-topology.conf resource is used.
 * If --duration is omitted the default is 10 seconds.
 */
object PdfTrafficExperiment {

  def main(args: Array[String]): Unit = {
    val parsed   = parseArgs(args)
    val config   = parsed.get("config")
    val duration = parsed.getOrElse("duration", "10").toInt

    config match {
      case Some(path) =>
        ExperimentRunner.runFromConfigFile(
          algorithmName   = "PDF Traffic Demo",
          configFilePath  = path,
          nodeFactory     = gn => new PdfTrafficNode(gn.id),
          durationSeconds = duration
        )
      case None =>
        ExperimentRunner.runFromResource(
          algorithmName   = "PDF Traffic Demo",
          resourceName    = "traffic-topology.conf",
          nodeFactory     = gn => new PdfTrafficNode(gn.id),
          durationSeconds = duration
        )
    }
  }

  private def parseArgs(args: Array[String]): Map[String, String] =
    args.sliding(2, 2).collect {
      case Array(k, v) if k.startsWith("--") => k.stripPrefix("--") -> v
    }.toMap
}