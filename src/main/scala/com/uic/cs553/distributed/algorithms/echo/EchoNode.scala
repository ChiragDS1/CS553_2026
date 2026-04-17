package com.uic.cs553.distributed.algorithms.echo

import java.util.concurrent.atomic.AtomicLong

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

import com.uic.cs553.distributed.framework._
import com.uic.cs553.distributed.algorithms.echo.EchoPayload._
import com.uic.cs553.distributed.utils.GraphvizLogger

class EchoNode(override val nodeId: String) extends BaseDistributedNode(nodeId) {

  private val sequence = new AtomicLong(0L)
  // var justified: actor-local algorithm state; Akka processes one message at a
  // time per actor — single-threaded dispatch means no concurrent access.
  private var state = EchoState()

  override protected def onMessage(
      ctx: ActorContext[DistributedMessage],
      msg: DistributedMessage
  ): Behavior[DistributedMessage] = msg match {

    case CommonMessages.Start() =>
      ctx.log.info("Node {} starting", nodeId)
      // isInitiator is set from config via InitializeNode, replacing the
      // former hardcoded nodeId == "node-1" check so any node can initiate.
      if (getIsInitiator) {
        val waveId = s"wave-${sequence.incrementAndGet()}"
        ctx.log.info("Node {} initiating Echo wave {}", nodeId, waveId)
        GraphvizLogger.logEdge("START", nodeId, "INIT", "green")
        startWave(ctx, waveId)
      }
      Behaviors.same

    case NetworkMessage(from, to, payload: Wave, _) if to == nodeId =>
      handleWave(ctx, from, payload)
      Behaviors.same

    case NetworkMessage(from, to, payload: Echo, _) if to == nodeId =>
      handleEcho(ctx, from, payload)
      Behaviors.same

    case NetworkMessage(from, to, payload: Extinguish, _) if to == nodeId =>
      handleExtinguish(ctx, from, payload)
      Behaviors.same

    case CommonMessages.GetState(replyTo) =>
      replyTo ! CommonMessages.StateResponse(
        nodeId,
        Map(
          "parent"    -> state.parent.getOrElse("none"),
          "pending"   -> state.pendingReplies.toList.sorted.mkString(","),
          "completed" -> state.completed,
          "active"    -> state.active
        )
      )
      Behaviors.same

    case _ =>
      Behaviors.same
  }

  // ---------------------------------------------------------------------------
  // Wave initiation
  // ---------------------------------------------------------------------------

  private def startWave(ctx: ActorContext[DistributedMessage], waveId: String): Unit = {
    val peers = peerMap.keySet

    state = EchoState(
      parent         = None,
      seenWaves      = Set(waveId),
      pendingReplies = peers,
      completed      = false,
      active         = true
    )

    ctx.log.info("Node {} started wave {} with {} peers", nodeId, waveId, peers.size)

    if (peers.isEmpty) {
      state = state.copy(completed = true, active = false)
      ctx.log.info("Node {} completed wave {} immediately (no peers)", nodeId, waveId)
    } else {
      peers.foreach { peerId =>
        // Wave sent as kind=CONTROL so it passes edge-label enforcement on all
        // topologies regardless of application-level allowedTypes on the edge.
        val sent = sendToNeighbor(ctx, peerId, Wave(nodeId, waveId), kind = "CONTROL")
        if (sent) {
          GraphvizLogger.logEdge(nodeId, peerId, "WAVE", "blue")
          ctx.log.info("Node {} sent Wave {} to {}", nodeId, waveId, peerId)
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Wave handling
  // ---------------------------------------------------------------------------

  private def handleWave(
      ctx: ActorContext[DistributedMessage],
      from: String,
      msg: Wave
  ): Unit = {

    if (!state.seenWaves.contains(msg.waveId)) {
      // First time seeing this wave — forward to all children (peers except parent).
      val children = peerMap.keySet - from

      state = state.copy(
        parent         = Some(from),
        seenWaves      = state.seenWaves + msg.waveId,
        pendingReplies = children,
        completed      = false,
        active         = true
      )

      ctx.log.info(
        "Node {} received first Wave {} from {}. children={}",
        nodeId, msg.waveId, from,
        children.mkString("[", ", ", "]")
      )

      if (children.isEmpty) {
        // Leaf node — reply immediately.
        // Echo sent as kind=CONTROL for same reason as Wave above.
        val sent = sendToNeighbor(ctx, from, Echo(nodeId, msg.waveId), kind = "CONTROL")
        if (sent) GraphvizLogger.logEdge(nodeId, from, "ECHO", "red")
        state = state.copy(active = false)
        ctx.log.info("Node {} is leaf -> sent Echo to {}", nodeId, from)

      } else {
        children.foreach { child =>
          val sent = sendToNeighbor(ctx, child, Wave(nodeId, msg.waveId), kind = "CONTROL")
          if (sent) {
            GraphvizLogger.logEdge(nodeId, child, "WAVE", "blue")
            ctx.log.info("Node {} forwarded Wave {} to {}", nodeId, msg.waveId, child)
          }
        }
      }

    } else {
      // Duplicate wave — extinguish.
      // Extinguish sent as kind=CONTROL for same reason as Wave above.
      val sent = sendToNeighbor(ctx, from, Extinguish(nodeId, msg.waveId), kind = "CONTROL")
      if (sent) GraphvizLogger.logEdge(nodeId, from, "EXT", "orange")
      ctx.log.info(
        "Node {} received duplicate Wave {} from {} -> sent Extinguish",
        nodeId, msg.waveId, from
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Echo handling
  // ---------------------------------------------------------------------------

  private def handleEcho(
      ctx: ActorContext[DistributedMessage],
      from: String,
      msg: Echo
  ): Unit = {
    val updatedPending = state.pendingReplies - from
    state = state.copy(pendingReplies = updatedPending)
    GraphvizLogger.logClosedChannel(from, nodeId)

    ctx.log.info(
      "Node {} received Echo {} from {}. pending={}",
      nodeId, msg.waveId, from,
      updatedPending.mkString("[", ", ", "]")
    )

    if (updatedPending.isEmpty) {
      state.parent match {
        case Some(parent) =>
          val sent = sendToNeighbor(ctx, parent, Echo(nodeId, msg.waveId), kind = "CONTROL")
          if (sent) GraphvizLogger.logEdge(nodeId, parent, "ECHO", "red")
          state = state.copy(active = false)
          ctx.log.info("Node {} sent Echo {} to parent {}", nodeId, msg.waveId, parent)

        case None =>
          state = state.copy(completed = true, active = false)
          ctx.log.info(
            "Node {} (root) wave {} COMPLETE — all nodes reached",
            nodeId, msg.waveId
          )
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Extinguish handling
  // ---------------------------------------------------------------------------

  private def handleExtinguish(
      ctx: ActorContext[DistributedMessage],
      from: String,
      msg: Extinguish
  ): Unit = {
    val updatedPending = state.pendingReplies - from
    state = state.copy(pendingReplies = updatedPending)
    GraphvizLogger.logClosedChannel(from, nodeId)

    ctx.log.info(
      "Node {} received Extinguish {} from {}. pending={}",
      nodeId, msg.waveId, from,
      updatedPending.mkString("[", ", ", "]")
    )

    if (updatedPending.isEmpty && !state.completed) {
      state.parent match {
        case Some(parent) =>
          val sent = sendToNeighbor(ctx, parent, Echo(nodeId, msg.waveId), kind = "CONTROL")
          if (sent) GraphvizLogger.logEdge(nodeId, parent, "ECHO", "red")
          state = state.copy(active = false)
          ctx.log.info(
            "Node {} resolved all children -> sent Echo to parent {}",
            nodeId, parent
          )

        case None =>
          state = state.copy(completed = true, active = false)
          ctx.log.info(
            "Node {} (root) wave {} COMPLETE — all nodes reached",
            nodeId, msg.waveId
          )
      }
    }
  }

  private def peerMap: Map[String, ActorRef[DistributedMessage]] =
    getNeighbors.view.mapValues(_.ref).toMap
}