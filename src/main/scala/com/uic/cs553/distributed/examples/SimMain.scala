package com.uic.cs553.distributed.examples

import java.io.File

import scala.io.{Source, StdIn}
import scala.util.{Try, Using}

import com.typesafe.config.ConfigFactory
import com.uic.cs553.distributed.framework.{
  DistributedNode, DistributedSystemCoordinator, ExperimentRunner
}
import com.uic.cs553.distributed.core.{GraphNode, TrafficConfig}
import com.uic.cs553.distributed.algorithms.echo.EchoNode
import com.uic.cs553.distributed.algorithms.laiyang.LaiYangNode
import com.uic.cs553.distributed.algorithms.traffic.PdfTrafficNode
import com.uic.cs553.distributed.utils.GraphvizLogger

/**
 * Main CLI entry point for all experiments.
 *
 * Basic usage:
 *   sbt "runMain com.uic.cs553.distributed.examples.SimMain \
 *         --config conf/experiment-complete.conf \
 *         --algorithm laiyang \
 *         --duration 20"
 *
 * Input node injection — file mode (traffic algorithm only):
 *   sbt "runMain com.uic.cs553.distributed.examples.SimMain \
 *         --config conf/traffic-topology.conf \
 *         --algorithm traffic \
 *         --duration 15 \
 *         --inject-file inject.txt"
 *
 *   inject.txt format — one command per line:
 *     <delayMs> <nodeId> <kind> <payload>
 *     500  node-2  PING  hello-from-driver
 *     1000 node-3  WORK  process-item-1
 *
 * Input node injection — interactive mode (traffic algorithm only):
 *   sbt "runMain com.uic.cs553.distributed.examples.SimMain \
 *         --config conf/traffic-topology.conf \
 *         --algorithm traffic \
 *         --duration 60 \
 *         --interactive"
 *
 *   Then on stdin while running:
 *     inject node-2 PING hello
 *     quit
 *
 * Flags:
 *   --config        path to .conf file (required)
 *   --algorithm     laiyang | echo | traffic  (default: laiyang)
 *   --duration      seconds                   (default: 15)
 *   --inject-file   injection script path     (traffic only, optional)
 *   --interactive   read stdin commands       (traffic only, optional)
 */
object SimMain {

  def main(args: Array[String]): Unit = {
    val parsed      = parseArgs(args)
    val config      = parsed.getOrElse("config", "")
    val algo        = parsed.getOrElse("algorithm", "laiyang")
    val duration    = parsed.getOrElse("duration", "15").toInt
    val injectFile  = parsed.get("inject-file")
    val interactive = parsed.contains("interactive")

    if (config.isEmpty) { printUsage(); sys.exit(1) }

    algo.toLowerCase match {

      case "laiyang" =>
        val dot = "laiyang.dot"
        GraphvizLogger.init(dot)
        try {
          ExperimentRunner.runFromConfigFile(
            algorithmName   = "Lai-Yang Snapshot",
            configFilePath  = config,
            nodeFactory     = (gn: GraphNode) => new LaiYangNode(gn.id),
            durationSeconds = duration
          )
        } finally { GraphvizLogger.finish(); println(s"DOT written: $dot") }

      case "echo" =>
        val dot = "echo.dot"
        GraphvizLogger.init(dot)
        try {
          ExperimentRunner.runFromConfigFile(
            algorithmName   = "Echo Extinction Anonymous",
            configFilePath  = config,
            nodeFactory     = (gn: GraphNode) => new EchoNode(gn.id),
            durationSeconds = duration
          )
        } finally { GraphvizLogger.finish(); println(s"DOT written: $dot") }

      case "traffic" =>
        runTrafficWithInjection(config, duration, injectFile, interactive)

      case other =>
        System.err.println(s"Unknown algorithm '$other'. Choose: laiyang, echo, traffic")
        sys.exit(1)
    }
  }

  // ---------------------------------------------------------------------------
  // Traffic experiment with optional input node injection
  // ---------------------------------------------------------------------------

  /**
   * Runs the traffic experiment while optionally driving input nodes via
   * file injection or interactive stdin injection.
   *
   * The experiment runs in a background thread so the main thread can read
   * and dispatch injection commands concurrently.  A CountDownLatch ensures
   * injection only starts once the actor system is ready.
   *
   * Injection is forwarded via InjectToNode → coordinator → target actor,
   * which routes it as an InjectMessage — treated identically to a timer tick
   * so algorithms need no special cases.
   */
  private def runTrafficWithInjection(
      configPath: String,
      durationSeconds: Int,
      injectFile: Option[String],
      interactive: Boolean
  ): Unit = {

    // Load file config; merge with classpath traffic-topology.conf if the file
    // contains only sim.traffic but no graph definition.  This lets conf/ files
    // declare only the traffic block while the topology comes from resources.
    val fileConfig = ConfigFactory.parseFile(new File(configPath))
    val hasGraph   = fileConfig.hasPath("sim.graph") || fileConfig.hasPath("sim.generator")
    val hocon =
      if (hasGraph) fileConfig.resolve()
      else fileConfig.withFallback(ConfigFactory.load("traffic-topology.conf")).resolve()

    val topology      = ExperimentRunner.resolveTopologyPublic(hocon)
    val trafficConfig = TrafficConfig.fromConfig(hocon)

    // var justified: written once by the experiment thread before the latch
    // releases; read-only by the injection thread after that point.
    @volatile var systemRef: Option[
      akka.actor.typed.ActorSystem[DistributedSystemCoordinator.CoordinatorMessage]
    ] = None

    val ready = new java.util.concurrent.CountDownLatch(1)

    val expThread = new Thread(
      () =>
        ExperimentRunner.runTopologyExperimentWithRef(
          algorithmName   = "PDF Traffic Demo",
          topology        = topology,
          trafficConfig   = trafficConfig,
          nodeFactory     = (gn: GraphNode) => new PdfTrafficNode(gn.id),
          durationSeconds = durationSeconds,
          onSystemReady   = sys => { systemRef = Some(sys); ready.countDown() }
        ),
      "experiment-thread"
    )
    expThread.setDaemon(true)
    expThread.start()

    // Block until actors are initialised before sending any injection commands.
    ready.await(10, java.util.concurrent.TimeUnit.SECONDS)

    injectFile.foreach(runFileInjection(_, systemRef))
    if (interactive) runInteractiveInjection(systemRef)

    expThread.join()
  }

  // ---------------------------------------------------------------------------
  // File-driven injection
  // ---------------------------------------------------------------------------

  /**
   * Reads a plain-text injection script and dispatches each command.
   *
   * Format (one line per command, # lines are comments):
   *   <delayMs>  <nodeId>  <kind>  <payload>
   *
   * delayMs is the sleep time *before* this command is sent, relative to
   * the previous command (cumulative from injection start).
   */
  private def runFileInjection(
      filePath: String,
      sysRef: Option[akka.actor.typed.ActorSystem[DistributedSystemCoordinator.CoordinatorMessage]]
  ): Unit = {
    if (!new File(filePath).exists()) {
      System.err.println(s"[inject-file] File not found: $filePath")
      return
    }
    println(s"[inject-file] Reading commands from $filePath")

    Using(Source.fromFile(filePath)) { src =>
      src.getLines()
        .map(_.trim)
        .filterNot(l => l.isEmpty || l.startsWith("#"))
        .foreach { line =>
          val cols = line.split("\\s+", 4)
          if (cols.length >= 3) {
            val delayMs = Try(cols(0).toLong).getOrElse(0L)
            val nodeId  = cols(1)
            val kind    = cols(2)
            val payload = if (cols.length >= 4) cols(3) else "injected"

            if (delayMs > 0) Thread.sleep(delayMs)

            sysRef.foreach { sys =>
              sys ! DistributedSystemCoordinator.InjectToNode(nodeId, kind, payload)
              println(s"[inject-file] → $nodeId kind=$kind payload=$payload")
            }
          } else {
            System.err.println(s"[inject-file] Skipping malformed line: $line")
          }
        }
    }.recover { case e => System.err.println(s"[inject-file] Error: ${e.getMessage}") }

    println("[inject-file] Done.")
  }

  // ---------------------------------------------------------------------------
  // Interactive stdin injection
  // ---------------------------------------------------------------------------

  /**
   * Reads commands from stdin while the experiment is running.
   *
   * Commands:
   *   inject <nodeId> <kind> <payload>   send a message to an input node
   *   quit                               stop reading (experiment continues)
   *   help                               print reference
   */
  private def runInteractiveInjection(
      sysRef: Option[akka.actor.typed.ActorSystem[DistributedSystemCoordinator.CoordinatorMessage]]
  ): Unit = {
    println("\n[interactive] Injection mode active.")
    println("[interactive] Commands: inject <nodeId> <kind> <payload> | quit | help\n")

    var running = true
    while (running) {
      val line  = Option(StdIn.readLine("> ")).map(_.trim).getOrElse("quit")
      val parts = line.split("\\s+", 4)

      parts.headOption.map(_.toLowerCase) match {

        case Some("inject") if parts.length >= 3 =>
          val nodeId  = parts(1)
          val kind    = parts(2)
          val payload = if (parts.length >= 4) parts(3) else "injected"
          sysRef.foreach { sys =>
            sys ! DistributedSystemCoordinator.InjectToNode(nodeId, kind, payload)
            println(s"[interactive] Injected kind=$kind into $nodeId")
          }

        case Some("inject") =>
          println("[interactive] Usage: inject <nodeId> <kind> <payload>")

        case Some("quit") | Some("exit") =>
          println("[interactive] Exiting injection mode.")
          running = false

        case Some("help") =>
          println("  inject <nodeId> <kind> <payload>  send message to input node")
          println("  quit                               exit interactive mode")

        case Some("") | None => ()

        case Some(other) =>
          println(s"[interactive] Unknown command '$other'. Type 'help'.")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Arg parsing
  // ---------------------------------------------------------------------------

  /**
   * Parses --key value pairs and bare --flag tokens.
   * --interactive is a boolean flag (no following value).
   */
  private def parseArgs(args: Array[String]): Map[String, String] = {
    val booleanFlags = Set("interactive")
    val result       = scala.collection.mutable.Map.empty[String, String]
    val buf          = args.toList

    def loop(rem: List[String]): Unit = rem match {
      case Nil                                                      =>
      case key :: rest if key.startsWith("--") =>
        val k = key.stripPrefix("--")
        if (booleanFlags.contains(k)) {
          result(k) = "true"
          loop(rest)
        } else {
          rest match {
            case value :: tail if !value.startsWith("--") =>
              result(k) = value
              loop(tail)
            case _ =>
              result(k) = "true"
              loop(rest)
          }
        }
      case _ :: rest => loop(rest)
    }

    loop(buf)
    result.toMap
  }

  private def printUsage(): Unit =
    System.err.println(
      """Usage: SimMain --config <path.conf> [options]
        |
        |  --config        experiment config file (required)
        |  --algorithm     laiyang | echo | traffic  (default: laiyang)
        |  --duration      seconds to run             (default: 15)
        |
        |Input node injection (traffic only):
        |  --inject-file <path>   run injection script
        |  --interactive          read inject commands from stdin
        |
        |Injection script format (one line per command):
        |  <delayMs> <nodeId> <kind> <payload>
        |  500  node-2  PING  hello
        |""".stripMargin
    )
}