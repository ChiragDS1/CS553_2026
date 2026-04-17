package com.uic.cs553.distributed.framework

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

import scala.util.Random

import com.uic.cs553.distributed.core.{MessagePdf, UniformPdf}

trait DistributedMessage

trait DistributedNode {
  def nodeId: String
  def behavior(): Behavior[DistributedMessage]
}

final case class NeighborRef(
    id: String,
    ref: ActorRef[DistributedMessage],
    allowedTypes: Set[String]
)

object CommonMessages {

  /**
   * Sent by ExperimentRunner to each actor after spawning.
   *
   * inboundCount — number of edges pointing INTO this node; used by
   *   LaiYangNode to know when all inbound channels have been closed.
   *
   * isInitiator — when true this node starts the algorithm wave/snapshot
   *   on receipt of Start().  Replaces the former hardcoded
   *   nodeId == "node-1" check so any node can be designated the initiator
   *   via configuration (topology node order).
   */
  final case class InitializeNode(
      neighbors: Map[String, NeighborRef],
      pdf: MessagePdf,
      inboundCount: Int = 0,
      isInitiator: Boolean = false
  ) extends DistributedMessage

  final case class Start()                                              extends DistributedMessage
  final case class Stop()                                               extends DistributedMessage
  final case class TrafficTick()                                        extends DistributedMessage
  final case class GetState(replyTo: ActorRef[StateResponse])          extends DistributedMessage
  final case class StateResponse(nodeId: String, state: Map[String, Any]) extends DistributedMessage
}

final case class NetworkMessage(
    from: String,
    to: String,
    payload: DistributedMessage,
    kind: String = "CONTROL"
) extends DistributedMessage

abstract class BaseDistributedNode(val nodeId: String) extends DistributedNode {

  // var justified: actor-local initialization state set once by InitializeNode
  // and then read-only for the actor's lifetime; Akka single-threaded dispatch
  // guarantees no concurrent writes are possible.
  private var neighbors: Map[String, NeighborRef] = Map.empty
  private var localPdf: MessagePdf                = UniformPdf(List("CONTROL"))
  private var rng: Random                         = new Random(0L)
  private var localInboundCount: Int              = 0
  private var localIsInitiator: Boolean           = false

  // ---------------------------------------------------------------------------
  // Protected accessors
  // ---------------------------------------------------------------------------

  protected def getPeers: Set[ActorRef[DistributedMessage]] =
    neighbors.values.map(_.ref).toSet

  protected def getNeighbors: Map[String, NeighborRef] =
    neighbors

  protected def getPdf: MessagePdf =
    localPdf

  protected def getRng: Random =
    rng

  protected def pdfWeights: Map[String, Double] =
    MessagePdf.weights(localPdf)

  /** The number of nodes that send TO this node (inbound edge count). */
  protected def getInboundCount: Int =
    localInboundCount

  /** True if this node was designated the algorithm initiator in config. */
  protected def getIsInitiator: Boolean =
    localIsInitiator

  protected def allowedTypesTo(neighborId: String): Set[String] =
    neighbors.get(neighborId).map(_.allowedTypes).getOrElse(Set.empty)

  protected def canSendTo(neighborId: String, kind: String): Boolean =
    allowedTypesTo(neighborId).contains(kind)

  protected def eligibleNeighborIds(kind: String): List[String] =
    neighbors.values.collect { case n if n.allowedTypes.contains(kind) => n.id }.toList.sorted

  protected def sampleMessageKind(): String =
    MessagePdf.sample(localPdf, rng)

  protected def chooseEligibleNeighbor(kind: String): Option[String] = {
    val eligible = eligibleNeighborIds(kind)
    if (eligible.isEmpty) None
    else Some(eligible(rng.nextInt(eligible.size)))
  }

  // ---------------------------------------------------------------------------
  // Messaging helpers
  // ---------------------------------------------------------------------------

  protected def sendToNeighbor(
      ctx: ActorContext[DistributedMessage],
      neighborId: String,
      msg: DistributedMessage,
      kind: String = "CONTROL"
  ): Boolean =
    neighbors.get(neighborId) match {
      case Some(n) if n.allowedTypes.contains(kind) =>
        n.ref ! NetworkMessage(nodeId, neighborId, msg, kind)
        true

      case Some(_) =>
        ctx.log.warn(
          "Node {} blocked message kind {} to {} because edge does not allow it. Allowed={}",
          nodeId, kind, neighborId,
          allowedTypesTo(neighborId).toList.sorted.mkString("[", ", ", "]")
        )
        false

      case None =>
        ctx.log.warn(
          "Node {} blocked message kind {} to {} because neighbor does not exist",
          nodeId, kind, neighborId
        )
        false
    }

  protected def broadcast(
      ctx: ActorContext[DistributedMessage],
      msg: DistributedMessage,
      kind: String = "CONTROL"
  ): Int =
    neighbors.values.foldLeft(0) { (sent, n) =>
      if (n.allowedTypes.contains(kind)) {
        n.ref ! NetworkMessage(nodeId, n.id, msg, kind)
        sent + 1
      } else {
        ctx.log.warn(
          "Node {} skipped broadcast of kind {} to {} because edge does not allow it. Allowed={}",
          nodeId, kind, n.id,
          n.allowedTypes.toList.sorted.mkString("[", ", ", "]")
        )
        sent
      }
    }

  protected def sendSampledTraffic(
      ctx: ActorContext[DistributedMessage],
      payloadFactory: String => DistributedMessage
  ): Boolean = {
    val kind = sampleMessageKind()
    chooseEligibleNeighbor(kind) match {
      case Some(neighborId) =>
        sendToNeighbor(ctx, neighborId, payloadFactory(kind), kind)
      case None =>
        ctx.log.warn(
          "Node {} sampled kind {} from pdf but has no eligible outgoing edge for it",
          nodeId, kind
        )
        false
    }
  }

  // ---------------------------------------------------------------------------
  // Initialization
  // ---------------------------------------------------------------------------

  protected def onInitialize(
      ctx: ActorContext[DistributedMessage],
      neighborMap: Map[String, NeighborRef],
      pdf: MessagePdf,
      inboundCount: Int,
      isInitiator: Boolean
  ): Behavior[DistributedMessage] = {
    neighbors        = neighborMap
    localPdf         = pdf
    localInboundCount = inboundCount
    localIsInitiator  = isInitiator
    rng              = new Random(MessagePdf.deterministicSeedForNode(nodeId))

    ctx.log.info(
      "Node {} initialized with {} outgoing neighbors, {} inbound channels, pdf family {}",
      nodeId, neighbors.size, inboundCount, pdf.family
    )
    ctx.log.info(
      "Node {} pdf weights {}",
      nodeId,
      pdfWeights.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString("[", ", ", "]")
    )

    Behaviors.same
  }

  // ---------------------------------------------------------------------------
  // Abstract hook — implemented by each algorithm node
  // ---------------------------------------------------------------------------

  protected def onMessage(
      ctx: ActorContext[DistributedMessage],
      msg: DistributedMessage
  ): Behavior[DistributedMessage]

  // ---------------------------------------------------------------------------
  // Behavior wiring
  // ---------------------------------------------------------------------------

  override def behavior(): Behavior[DistributedMessage] =
    Behaviors.setup { ctx =>
      Behaviors.receiveMessage {
        case CommonMessages.InitializeNode(neighborMap, pdf, inboundCount, isInitiator) =>
          onInitialize(ctx, neighborMap, pdf, inboundCount, isInitiator)

        case CommonMessages.Stop() =>
          ctx.log.info("Node {} stopping", nodeId)
          Behaviors.stopped

        case msg =>
          onMessage(ctx, msg)
      }
    }
}