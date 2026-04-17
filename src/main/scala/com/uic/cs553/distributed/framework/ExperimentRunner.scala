package com.uic.cs553.distributed.framework

import java.io.File

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

import com.typesafe.config.{Config, ConfigFactory}

import com.uic.cs553.distributed.core.{
  GraphGenerator, GraphLoader, GraphNode, GraphTopology,
  TrafficConfig, UniformPdf
}
import com.uic.cs553.distributed.algorithms.traffic.PdfTrafficNode

// ---------------------------------------------------------------------------
// Coordinator actor
// ---------------------------------------------------------------------------

object DistributedSystemCoordinator {

  sealed trait CoordinatorMessage

  final case class CreateFromTopology(
      topology: GraphTopology,
      nodeFactory: GraphNode => DistributedNode,
      trafficConfig: TrafficConfig,
      replyTo: ActorRef[NodesCreated]
  ) extends CoordinatorMessage

  final case class InjectToNode(
      nodeId: String,
      kind: String,
      payload: String
  ) extends CoordinatorMessage

  final case class NodesCreated(
      nodes: Map[String, ActorRef[DistributedMessage]]
  ) extends CoordinatorMessage

  final case class StartAlgorithm() extends CoordinatorMessage
  final case class StopAlgorithm()  extends CoordinatorMessage

  final case class QueryStates(replyTo: ActorRef[SystemState]) extends CoordinatorMessage
  final case class SystemState(states: Map[String, Map[String, Any]]) extends CoordinatorMessage

  def apply(): Behavior[CoordinatorMessage] =
    Behaviors.setup { ctx =>

      // var justified: coordinator-local node map written once by
      // CreateFromTopology, then read-only; single-threaded actor dispatch.
      var nodes: Map[String, ActorRef[DistributedMessage]] = Map.empty

      Behaviors.receiveMessage {

        case CreateFromTopology(topology, nodeFactory, trafficConfig, replyTo) =>
          ctx.log.info(
            "Creating {} nodes and {} edges from topology ({})",
            topology.nodes.size,
            topology.edges.size,
            topology.summary
          )

          nodes = topology.nodes.map { graphNode =>
            val enriched: DistributedNode = nodeFactory(graphNode) match {
              case _: PdfTrafficNode =>
                new PdfTrafficNode(graphNode.id, trafficConfig.timerFor(graphNode.id))
              case other => other
            }
            graphNode.id -> ctx.spawn(enriched.behavior(), graphNode.id)
          }.toMap

          topology.nodes.foreach { graphNode =>
            val neighborMap = topology.outgoingEdgesByNode(graphNode.id).map { edge =>
              edge.to -> NeighborRef(
                id           = edge.to,
                ref          = nodes(edge.to),
                allowedTypes = edge.allowedTypes
              )
            }.toMap

            // inboundCount = edges pointing INTO this node.
            // Must use incoming, not outgoing — they differ on asymmetric graphs
            // and LaiYangNode uses this to know when to complete the snapshot.
            val inbound = topology.incomingEdgesByNode(graphNode.id).size

            // isInitiator: first node in the topology list starts the algorithm.
            // Replaces the hardcoded nodeId == "node-1" check in algorithm nodes.
            val isInit = topology.nodes.headOption.exists(_.id == graphNode.id)

            nodes(graphNode.id) ! CommonMessages.InitializeNode(
              neighbors    = neighborMap,
              pdf          = graphNode.pdf,
              inboundCount = inbound,
              isInitiator  = isInit
            )
          }

          replyTo ! NodesCreated(nodes)
          Behaviors.same

        case InjectToNode(nodeId, kind, payload) =>
          nodes.get(nodeId) match {
            case Some(ref) =>
              import com.uic.cs553.distributed.algorithms.traffic.TrafficPayload
              ref ! TrafficPayload.InjectMessage(kind, payload)
              ctx.log.info("Injected kind={} into node {}", kind, nodeId)
            case None =>
              ctx.log.warn("InjectToNode: unknown node {}", nodeId)
          }
          Behaviors.same

        case StartAlgorithm() =>
          ctx.log.info("Starting algorithm on {} nodes", nodes.size)
          nodes.values.foreach(_ ! CommonMessages.Start())
          Behaviors.same

        case StopAlgorithm() =>
          ctx.log.info("Stopping algorithm on {} nodes", nodes.size)
          nodes.values.foreach(_ ! CommonMessages.Stop())
          Behaviors.same

        case QueryStates(replyTo) =>
          replyTo ! SystemState(Map.empty)
          Behaviors.same
      }
    }
}

// ---------------------------------------------------------------------------
// ExperimentRunner
// ---------------------------------------------------------------------------

object ExperimentRunner {

  // ---------------------------------------------------------------------------
  // Primary entry points
  // ---------------------------------------------------------------------------

  /** Run an experiment from an external .conf file path. */
  def runFromConfigFile(
      algorithmName: String,
      configFilePath: String,
      nodeFactory: GraphNode => DistributedNode,
      durationSeconds: Int = 30
  ): Unit = {
    val config        = ConfigFactory.parseFile(new File(configFilePath)).resolve()
    val topology      = resolveTopology(config)
    val trafficConfig = TrafficConfig.fromConfig(config)
    runTopologyExperiment(algorithmName, topology, trafficConfig, nodeFactory, durationSeconds)
  }

  /** Run an experiment from a classpath resource (src/main/resources). */
  def runFromResource(
      algorithmName: String,
      resourceName: String,
      nodeFactory: GraphNode => DistributedNode,
      durationSeconds: Int = 30
  ): Unit = {
    val config        = ConfigFactory.load(resourceName)
    val topology      = resolveTopology(config)
    val trafficConfig = TrafficConfig.fromConfig(config)
    runTopologyExperiment(algorithmName, topology, trafficConfig, nodeFactory, durationSeconds)
  }

  /** Run with an already-resolved Config object. */
  def runFromResolvedConfig(
      algorithmName: String,
      config: Config,
      nodeFactory: GraphNode => DistributedNode,
      durationSeconds: Int = 30
  ): Unit = {
    val topology      = resolveTopology(config)
    val trafficConfig = TrafficConfig.fromConfig(config)
    runTopologyExperiment(algorithmName, topology, trafficConfig, nodeFactory, durationSeconds)
  }

  /** Legacy: run from a topology file (no traffic config). */
  def runFromTopologyFile(
      algorithmName: String,
      topologyFilePath: String,
      nodeFactory: GraphNode => DistributedNode,
      durationSeconds: Int = 30
  ): Unit = {
    GraphLoader.loadFromFile(topologyFilePath) match {
      case Right(topology) =>
        runTopologyExperiment(
          algorithmName, topology, TrafficConfig.empty, nodeFactory, durationSeconds
        )
      case Left(errors) =>
        throw new IllegalArgumentException(
          s"Failed to load topology from $topologyFilePath:\n${errors.mkString("\n")}"
        )
    }
  }

  /** Legacy shim: create a complete topology from nodeCount. */
  def runExperiment(
      algorithmName: String,
      nodeCount: Int,
      nodeFactory: String => DistributedNode,
      durationSeconds: Int = 30
  ): Unit = {
    val topology = GraphTopology.complete(
      nodeCount           = nodeCount,
      defaultAllowedTypes = Set("CONTROL"),
      defaultPdf          = UniformPdf(List("CONTROL"))
    )
    runTopologyExperiment(
      algorithmName,
      topology,
      TrafficConfig.empty,
      gn => nodeFactory(gn.id),
      durationSeconds
    )
  }

  // ---------------------------------------------------------------------------
  // Core experiment runner
  // ---------------------------------------------------------------------------

  def runTopologyExperiment(
      algorithmName: String,
      topology: GraphTopology,
      trafficConfig: TrafficConfig = TrafficConfig.empty,
      nodeFactory: GraphNode => DistributedNode,
      durationSeconds: Int = 30
  ): Unit = {

    implicit val system: ActorSystem[DistributedSystemCoordinator.CoordinatorMessage] =
      ActorSystem(DistributedSystemCoordinator(), "DistributedSystem")

    println(s"=== Starting $algorithmName Experiment ===")
    println(s"Topology   : ${topology.summary}")
    val timerStr = trafficConfig.timers
      .map(t => s"${t.nodeId}(${t.tickEveryMs}ms,${t.mode})").mkString(" ")
    val inputStr = trafficConfig.inputs.map(_.nodeId).mkString(" ")
    println(s"Timer nodes: ${if (timerStr.isEmpty) "none" else timerStr}")
    println(s"Input nodes: ${if (inputStr.isEmpty) "none" else inputStr}")
    println(s"Duration   : ${durationSeconds}s")
    println("=" * 50)

    try {
      import akka.actor.typed.scaladsl.AskPattern._
      import akka.util.Timeout
      implicit val timeout: Timeout = 5.seconds

      val nodesFuture: Future[DistributedSystemCoordinator.NodesCreated] =
        system.ask(replyTo =>
          DistributedSystemCoordinator.CreateFromTopology(
            topology, nodeFactory, trafficConfig, replyTo
          )
        )

      Await.result(nodesFuture, 10.seconds)
      system ! DistributedSystemCoordinator.StartAlgorithm()

      println(s"System running for ${durationSeconds}s ...")
      Thread.sleep(durationSeconds * 1000L)

      system ! DistributedSystemCoordinator.StopAlgorithm()
      Thread.sleep(500)

      printMetricsSummary(topology, trafficConfig)
      println("\n=== Experiment Complete ===")

    } finally {
      Thread.sleep(500)
      system.terminate()
      Await.result(system.whenTerminated, 10.seconds)
    }
  }

  // ---------------------------------------------------------------------------
  // Injection variant — exposes ActorSystem ref for CLI injection
  // ---------------------------------------------------------------------------

  def runTopologyExperimentWithRef(
      algorithmName: String,
      topology: GraphTopology,
      trafficConfig: TrafficConfig = TrafficConfig.empty,
      nodeFactory: GraphNode => DistributedNode,
      durationSeconds: Int = 30,
      onSystemReady: ActorSystem[DistributedSystemCoordinator.CoordinatorMessage] => Unit =
        _ => ()
  ): Unit = {

    implicit val system: ActorSystem[DistributedSystemCoordinator.CoordinatorMessage] =
      ActorSystem(DistributedSystemCoordinator(), "DistributedSystem")

    println(s"=== Starting $algorithmName Experiment (with injection) ===")
    println(s"Topology   : ${topology.summary}")
    println(s"Duration   : ${durationSeconds}s")
    println("=" * 50)

    try {
      import akka.actor.typed.scaladsl.AskPattern._
      import akka.util.Timeout
      implicit val timeout: Timeout = 5.seconds

      val nodesFuture: Future[DistributedSystemCoordinator.NodesCreated] =
        system.ask(replyTo =>
          DistributedSystemCoordinator.CreateFromTopology(
            topology, nodeFactory, trafficConfig, replyTo
          )
        )

      Await.result(nodesFuture, 10.seconds)
      system ! DistributedSystemCoordinator.StartAlgorithm()

      onSystemReady(system)

      println(s"System running for ${durationSeconds}s ...")
      Thread.sleep(durationSeconds * 1000L)

      system ! DistributedSystemCoordinator.StopAlgorithm()
      Thread.sleep(500)

      printMetricsSummary(topology, trafficConfig)
      println("\n=== Experiment Complete ===")

    } finally {
      Thread.sleep(500)
      system.terminate()
      Await.result(system.whenTerminated, 10.seconds)
    }
  }

  // ---------------------------------------------------------------------------
  // Public topology resolver (used by SimMain for injection setup)
  // ---------------------------------------------------------------------------

  def resolveTopologyPublic(config: Config): GraphTopology =
    resolveTopology(config)

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  private def resolveTopology(config: Config): GraphTopology = {
    val hasExplicitNodes =
      config.hasPath("sim.graph.nodes") ||
      (config.hasPath("sim.graph") && config.getConfig("sim.graph").hasPath("nodes"))

    if (hasExplicitNodes) {
      GraphLoader.loadFromConfig(config) match {
        case Right(t)     => t
        case Left(errors) =>
          throw new IllegalArgumentException(
            s"Invalid topology config:\n${errors.mkString("\n")}"
          )
      }
    } else if (config.hasPath("sim.generator")) {
      GraphGenerator.fromConfig(config) match {
        case Right(t)     => t
        case Left(errors) =>
          throw new IllegalArgumentException(
            s"Graph generator config errors:\n${errors.mkString("\n")}"
          )
      }
    } else {
      throw new IllegalArgumentException(
        "Config must contain either 'sim.graph.nodes' or 'sim.generator'."
      )
    }
  }

  private def printMetricsSummary(
      topology: GraphTopology,
      trafficConfig: TrafficConfig
  ): Unit = {
    println("\n=== Traffic Metrics Summary ===")
    println(f"${"Node"}%-10s ${"Role"}%-12s ${"PDF family"}%-12s ${"Tick(ms)"}%-10s ${"Mode"}%-8s")
    println("-" * 56)

    topology.nodes.foreach { n =>
      val isTimer = trafficConfig.timers.exists(_.nodeId == n.id)
      val isInput = trafficConfig.inputs.exists(_.nodeId == n.id)
      val role    = if (isTimer) "timer" else if (isInput) "input" else "passive"

      val timerCfg  = trafficConfig.timers.find(_.nodeId == n.id)
      val tickStr   = timerCfg.map(_.tickEveryMs.toString).getOrElse("-")
      val modeStr   = timerCfg.map(_.mode).getOrElse("-")
      val pdfFamily = n.pdf.family

      println(f"${n.id}%-10s $role%-12s $pdfFamily%-12s $tickStr%-10s $modeStr%-8s")
    }

    println("\nPer-node sent/received counts: search logs for 'METRICS'")
    println("=" * 56)
  }
}