package com.uic.cs553.distributed.algorithms.traffic

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}

import scala.concurrent.duration._

import com.uic.cs553.distributed.framework._
import com.uic.cs553.distributed.core.TimerNodeConfig

/**
 * Application messages produced by PDF-driven traffic nodes.
 */
object TrafficPayload {
  /** A sampled application message travelling over a channel. */
  final case class AppTraffic(kindName: String, payload: String) extends DistributedMessage

  /** Injected externally by the driver into an input node. */
  final case class InjectMessage(kind: String, payload: String) extends DistributedMessage
}

/**
 * PDF-driven traffic node — implements both timer and input node roles.
 *
 * Timer node: when timerCfg is Some, the Akka TimerScheduler fires
 *   a TrafficTick at the configured interval.  On each tick:
 *     mode="pdf"   → samples kind from the node PDF, picks an eligible
 *                    neighbor, and sends AppTraffic.
 *     mode="fixed" → always sends the configured fixedMsg kind.
 *
 * Input node: when the driver injects an InjectMessage, the node
 *   forwards it to an eligible neighbor exactly like a tick.
 *
 * Metrics: sentCount, receivedCount, and per-kind counters are
 *   accumulated and logged at Stop time and available via GetState.
 */
class PdfTrafficNode(
    override val nodeId: String,
    timerCfg: Option[TimerNodeConfig] = None
) extends BaseDistributedNode(nodeId) {

  import TrafficPayload._

  // Unique key for the Akka timer so it can be cancelled on Stop.
  private val TimerKey = s"traffic-tick-$nodeId"

  // var justified: actor-local counters updated on each message; Akka's single-
  // threaded actor model prevents concurrent access so vars are safe here.
  private var sentCount: Int     = 0
  private var receivedCount: Int = 0
  private var sentByKind: Map[String, Int]     = Map.empty
  private var receivedByKind: Map[String, Int] = Map.empty

  override def behavior(): Behavior[DistributedMessage] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        Behaviors.receiveMessage {
          case CommonMessages.InitializeNode(neighborMap, pdf, inboundCount, isInitiator) =>
            onInitialize(ctx, neighborMap, pdf, inboundCount, isInitiator)

          case CommonMessages.Stop() =>
            timers.cancelAll()
            logMetricsSummary(ctx)
            ctx.log.info("Node {} stopping", nodeId)
            Behaviors.stopped

          case msg =>
            onMessageWithTimers(ctx, timers, msg)
        }
      }
    }

  // Separate handler that has access to the TimerScheduler.
  private def onMessageWithTimers(
      ctx: ActorContext[DistributedMessage],
      timers: TimerScheduler[DistributedMessage],
      msg: DistributedMessage
  ): Behavior[DistributedMessage] = msg match {

    case CommonMessages.Start() =>
      ctx.log.info("Node {} starting (role={})", nodeId,
        if (timerCfg.isDefined) "timer" else "passive")

      // Start the Akka scheduler if this is a timer node.
      timerCfg.foreach { cfg =>
        ctx.log.info("Node {} scheduling tick every {}ms mode={}",
          nodeId, cfg.tickEveryMs, cfg.mode)
        timers.startTimerAtFixedRate(
          TimerKey,
          CommonMessages.TrafficTick(),
          cfg.tickEveryMs.millis
        )
      }

      // Burst of initial messages on start regardless of timer role.
      (1 to 3).foreach { _ =>
        sendOneTick(ctx, fromTimer = false)
      }

      Behaviors.same

    // Periodic timer fires → send one sampled message.
    case CommonMessages.TrafficTick() =>
      sendOneTick(ctx, fromTimer = true)
      Behaviors.same

    // External injection from the driver → treat exactly like a tick.
    case InjectMessage(kind, payload) =>
      ctx.log.info("Node {} received injected message kind={} payload={}",
        nodeId, kind, payload)
      val sent = sendToEligibleNeighbor(ctx, kind,
        AppTraffic(kind, s"injected:$payload"))
      if (sent) recordSent(kind)
      Behaviors.same

    case NetworkMessage(from, to, payload: AppTraffic, kind) if to == nodeId =>
      receivedCount += 1
      recordReceived(kind)
      ctx.log.info(
        "Node {} received traffic from {} kind={} payload={} [recv={}]",
        nodeId, from, kind, payload.payload, receivedCount
      )
      Behaviors.same

    case CommonMessages.GetState(replyTo) =>
      replyTo ! CommonMessages.StateResponse(
        nodeId,
        Map(
          "sentCount"      -> sentCount,
          "receivedCount"  -> receivedCount,
          "sentByKind"     -> sentByKind.toSeq.sortBy(_._1).mkString(","),
          "receivedByKind" -> receivedByKind.toSeq.sortBy(_._1).mkString(","),
          "pdfFamily"      -> getPdf.family,
          "isTimerNode"    -> timerCfg.isDefined,
          "tickEveryMs"    -> timerCfg.map(_.tickEveryMs).getOrElse(0)
        )
      )
      Behaviors.same

    case _ =>
      Behaviors.same
  }

  // Required by BaseDistributedNode but not used — behavior() is overridden.
  override protected def onMessage(
      ctx: ActorContext[DistributedMessage],
      msg: DistributedMessage
  ): Behavior[DistributedMessage] = Behaviors.same

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private def sendOneTick(
      ctx: ActorContext[DistributedMessage],
      fromTimer: Boolean
  ): Unit = {
    val kind = timerCfg match {
      case Some(cfg) if cfg.mode == "fixed" => cfg.fixedMsg.getOrElse(sampleMessageKind())
      case _                                => sampleMessageKind()
    }

    val label = if (fromTimer) "tick" else "burst"
    val sent  = sendToEligibleNeighbor(ctx, kind,
      AppTraffic(kind, s"$label-$kind-from-$nodeId-${sentCount + 1}"))

    if (sent) recordSent(kind)
  }

  private def sendToEligibleNeighbor(
      ctx: ActorContext[DistributedMessage],
      kind: String,
      msg: DistributedMessage
  ): Boolean = {
    chooseEligibleNeighbor(kind) match {
      case Some(neighborId) =>
        sendToNeighbor(ctx, neighborId, msg, kind)
      case None =>
        ctx.log.warn("Node {} has no eligible neighbor for kind {}", nodeId, kind)
        false
    }
  }

  private def recordSent(kind: String): Unit = {
    sentCount += 1
    sentByKind = sentByKind.updated(kind, sentByKind.getOrElse(kind, 0) + 1)
  }

  private def recordReceived(kind: String): Unit = {
    receivedByKind = receivedByKind.updated(kind, receivedByKind.getOrElse(kind, 0) + 1)
  }

  private def logMetricsSummary(ctx: ActorContext[DistributedMessage]): Unit = {
    val sentDetail     = sentByKind.toSeq.sortBy(_._1).map { case (k,v) => s"$k=$v" }.mkString(" ")
    val receivedDetail = receivedByKind.toSeq.sortBy(_._1).map { case (k,v) => s"$k=$v" }.mkString(" ")
    ctx.log.info(
      "Node {} METRICS: sent={} ({}) received={} ({})",
      nodeId, sentCount, sentDetail, receivedCount, receivedDetail
    )
  }
}