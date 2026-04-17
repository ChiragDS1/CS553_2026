package com.uic.cs553.distributed.algorithms.laiyang

import java.util.concurrent.atomic.AtomicLong

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

import com.uic.cs553.distributed.framework._
import com.uic.cs553.distributed.algorithms.laiyang.SnapshotPayload._
import com.uic.cs553.distributed.utils.GraphvizLogger

class LaiYangNode(override val nodeId: String) extends BaseDistributedNode(nodeId) {

  private val sequence = new AtomicLong(0L)
  // var justified: actor-local snapshot state; Akka single-threaded dispatch
  // guarantees no concurrent access — no synchronisation needed.
  private var state = SnapshotState()

  override protected def onMessage(
      ctx: ActorContext[DistributedMessage],
      msg: DistributedMessage
  ): Behavior[DistributedMessage] = {

    msg match {

      case CommonMessages.Start() =>
        // Snapshot state needs inboundCount from InitializeNode before any
        // completion check runs.
        state = state.copy(inboundCount = getInboundCount)
        ctx.log.info("Node {} starting (inboundCount={})", nodeId, state.inboundCount)

        sendInitialWhiteTraffic(ctx)

        // isInitiator is set from config via InitializeNode, replacing the
        // former hardcoded nodeId == "node-1" check.
        if (getIsInitiator) {
          ctx.log.info("Node {} initiating snapshot (initiator)", nodeId)
          GraphvizLogger.logEdge("START", nodeId, "INIT", "green")
          ctx.self ! StartSnapshot
        }

        Behaviors.same

      case StartSnapshot =>
        if (!state.recorded) {
          val sid = s"snapshot-${sequence.incrementAndGet()}"

          state = state.copy(
            color      = "red",
            recorded   = true,
            snapshotId = Some(sid),
            localState = buildLocalState(),
            completed  = false
          )

          ctx.log.info("Node {} initiating snapshot {}", nodeId, sid)
          ctx.log.info(
            "Node {} recorded local state {}",
            nodeId,
            state.localState.mkString("[", ", ", "]")
          )

          sendRedSnapshotMessages(ctx, sid)
          checkCompletion(ctx)
        }

        Behaviors.same

      case NetworkMessage(from, to, payload: AppMessage, _) if to == nodeId =>
        handleAppMessage(ctx, from, payload)
        Behaviors.same

      case CommonMessages.GetState(replyTo) =>
        replyTo ! CommonMessages.StateResponse(
          nodeId,
          Map(
            "color"               -> state.color,
            "recorded"            -> state.recorded,
            "snapshotId"          -> state.snapshotId.getOrElse("none"),
            "completed"           -> state.completed,
            "closedChannels"      -> state.closedChannels.toList.sorted.mkString(","),
            "channelState"        -> formatChannelState(),
            "appMessagesSent"     -> state.appMessagesSent,
            "appMessagesReceived" -> state.appMessagesReceived
          )
        )
        Behaviors.same

      case _ =>
        Behaviors.same
    }
  }

  private def handleAppMessage(
      ctx: ActorContext[DistributedMessage],
      from: String,
      msg: AppMessage
  ): Unit = {
    state = state.copy(appMessagesReceived = state.appMessagesReceived + 1)

    if (msg.color == "red") handleRedMessage(ctx, from, msg)
    else handleWhiteMessage(ctx, from, msg)
  }

  private def handleRedMessage(
      ctx: ActorContext[DistributedMessage],
      from: String,
      msg: AppMessage
  ): Unit = {

    if (state.color == "white") {
      state = state.copy(
        color          = "red",
        recorded       = true,
        snapshotId     = Some(msg.snapshotId),
        localState     = buildLocalState(),
        closedChannels = state.closedChannels + from,
        completed      = false
      )

      ctx.log.info(
        "Node {} received first RED from {} -> turned RED, recorded state {}",
        nodeId, from,
        state.localState.mkString("[", ", ", "]")
      )

      sendRedSnapshotMessages(ctx, msg.snapshotId)
      checkCompletion(ctx)
      return
    }

    if (!state.closedChannels.contains(from)) {
      state = state.copy(closedChannels = state.closedChannels + from)
      GraphvizLogger.logClosedChannel(from, nodeId)
      ctx.log.info(
        "Node {} closed channel from {} ({}/{})",
        nodeId, from,
        state.closedChannels.size,
        state.inboundCount
      )
    }

    checkCompletion(ctx)
  }

  private def handleWhiteMessage(
      ctx: ActorContext[DistributedMessage],
      from: String,
      msg: AppMessage
  ): Unit = {
    if (state.color == "white") {
      ctx.log.info("Node {} received WHITE message from {} before snapshot", nodeId, from)
      return
    }

    if (!state.closedChannels.contains(from)) {
      val prev = state.channelState.getOrElse(from, Nil)
      val rec  = s"white-msg-${msg.sequenceNo}"
      state = state.copy(channelState = state.channelState + (from -> (prev :+ rec)))
      ctx.log.info("Node {} recorded in-transit message from {}", nodeId, from)
    }
  }

  private def sendInitialWhiteTraffic(ctx: ActorContext[DistributedMessage]): Unit = {
    peerMap.keys.foreach { peerId =>
      val seq = sequence.incrementAndGet()
      // WHITE sent as kind=CONTROL so it passes edge-label enforcement on all
      // topologies regardless of the application-level allowedTypes.
      val sent = sendToNeighbor(
        ctx, peerId,
        AppMessage("init", "white", "pre", seq),
        kind = "CONTROL"
      )
      if (sent) {
        GraphvizLogger.logEdge(nodeId, peerId, "WHITE", "blue")
        state = state.copy(appMessagesSent = state.appMessagesSent + 1)
      }
    }
    ctx.log.info("Node {} sent initial WHITE traffic", nodeId)
  }

  private def sendRedSnapshotMessages(
      ctx: ActorContext[DistributedMessage],
      snapshotId: String
  ): Unit = {
    peerMap.keys.foreach { peerId =>
      val seq = sequence.incrementAndGet()
      // RED sent as kind=CONTROL — same reason as WHITE above.
      val sent = sendToNeighbor(
        ctx, peerId,
        AppMessage("snapshot", "red", snapshotId, seq),
        kind = "CONTROL"
      )
      if (sent) {
        GraphvizLogger.logEdge(nodeId, peerId, "RED", "red")
        state = state.copy(appMessagesSent = state.appMessagesSent + 1)
      }
    }
    ctx.log.info("Node {} broadcast RED messages for snapshot {}", nodeId, snapshotId)
  }

  /**
   * Snapshot completes when we have received a RED on every inbound channel.
   * Uses state.inboundCount (set from InitializeNode) NOT peerMap.size
   * (outgoing count) — those differ on asymmetric topologies.
   */
  private def checkCompletion(ctx: ActorContext[DistributedMessage]): Unit = {
    if (!state.completed && state.recorded &&
        state.closedChannels.size == state.inboundCount) {
      state = state.copy(completed = true)
      ctx.log.info(
        "Node {} snapshot COMPLETE. snapshotId={} localState={} channelState={}",
        nodeId,
        state.snapshotId.getOrElse("none"),
        state.localState.mkString("[", ", ", "]"),
        formatChannelState()
      )
    }
  }

  private def buildLocalState(): List[String] =
    List(
      s"id=$nodeId",
      s"sent=${state.appMessagesSent}",
      s"recv=${state.appMessagesReceived}"
    )

  private def formatChannelState(): String =
    if (state.channelState.isEmpty) "none"
    else state.channelState.map { case (k, v) => s"$k->$v" }.mkString(",")

  private def peerMap: Map[String, ActorRef[DistributedMessage]] =
    getNeighbors.view.mapValues(_.ref).toMap
}