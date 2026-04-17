package com.uic.cs553.distributed.framework

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt
import scala.util.Random

import com.uic.cs553.distributed.algorithms.echo.{EchoNode, EchoState}
import com.uic.cs553.distributed.algorithms.laiyang.{LaiYangNode, SnapshotState}
import com.uic.cs553.distributed.core._

/**
 * Integration and unit tests for the CS553 distributed algorithms simulator.
 *
 * Coverage:
 *   1. GraphTopology validation rejects bad configs
 *   2. Edge label enforcement — only allowed kinds are delivered
 *   3. PDF sampling produces only valid message types
 *   4. Explicit PDF probabilities must sum to 1.0
 *   5. EchoNode actor spawns and initialises without errors
 *   6. LaiYangNode actor spawns and initialises without errors
 *   7. EchoState models leaf and root completion correctly (pure logic)
 *   8. GraphGenerator produces correct topology shapes
 */
class DistributedNodeSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Eventually {

  /**
   * Default patience for Eventually: retry every 50ms for up to 3 seconds.
   * Required for tests that query actor state after async self-messages —
   * e.g. Start() enqueues StartSnapshot internally before the actor
   * processes GetState, so a fixed sleep would race.
   */
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(
      timeout  = Span(3, Seconds),
      interval = Span(50, Millis)
    )

  // -------------------------------------------------------------------------
  // 1. GraphTopology validation
  // -------------------------------------------------------------------------

  "GraphTopology.validate" must {

    "accept a well-formed topology" in {
      val topo = GraphTopology.complete(
        nodeCount           = 3,
        defaultAllowedTypes = Set("CONTROL"),
        defaultPdf          = UniformPdf(List("CONTROL"))
      )
      topo.validate() match {
        case Right(_)   => // pass
        case Left(errs) => fail(s"Expected valid topology but got errors: $errs")
      }
    }

    "reject a topology with an edge referencing a missing node" in {
      val nodes = List(GraphNode("node-1"), GraphNode("node-2"))
      val edges = List(
        GraphEdge("node-1", "node-2",  Set("CONTROL")),
        GraphEdge("node-1", "node-99", Set("CONTROL"))  // node-99 does not exist
      )
      GraphTopology(nodes, edges).validate() match {
        case Left(errs) =>
          assert(errs.exists(_.contains("node-99")),
            s"Expected error about node-99 but got: $errs")
        case Right(_) =>
          fail("Expected validation to fail for missing node reference")
      }
    }

    "reject a topology with an edge that has no allowed message types" in {
      val nodes = List(GraphNode("node-1"), GraphNode("node-2"))
      val edges = List(GraphEdge("node-1", "node-2", Set.empty))
      GraphTopology(nodes, edges).validate() match {
        case Left(errs) =>
          assert(errs.exists(_.contains("no allowed message types")),
            s"Expected error about empty allowed types but got: $errs")
        case Right(_) =>
          fail("Expected validation to fail for empty allowed types")
      }
    }
  }

  // -------------------------------------------------------------------------
  // 2. Edge label enforcement
  // -------------------------------------------------------------------------

  "BaseDistributedNode.sendToNeighbor" must {

    "only deliver messages whose kind is in the edge allowed types" in {
      // Use a test-only node subclass that calls sendToNeighbor directly,
      // avoiding dependency on any specific algorithm node version.
      // This tests the BaseDistributedNode enforcement logic in isolation.

      val probe = TestProbe[DistributedMessage]()

      // Minimal test node: on Start() tries to send two kinds —
      //   "CONTROL" (allowed)  → must reach the probe
      //   "GOSSIP"  (forbidden) → must be blocked
      class TestNode(id: String) extends BaseDistributedNode(id) {
        override protected def onMessage(
            ctx: akka.actor.typed.scaladsl.ActorContext[DistributedMessage],
            msg: DistributedMessage
        ): akka.actor.typed.Behavior[DistributedMessage] = msg match {
          case CommonMessages.Start() =>
            // CONTROL is allowed on the edge — should deliver
            sendToNeighbor(ctx, "probe-neighbor",
              NetworkMessage(id, "probe-neighbor", CommonMessages.Start(), "CONTROL"),
              kind = "CONTROL")
            // GOSSIP is NOT allowed — should be blocked
            sendToNeighbor(ctx, "probe-neighbor",
              NetworkMessage(id, "probe-neighbor", CommonMessages.Start(), "GOSSIP"),
              kind = "GOSSIP")
            Behaviors.same
          case _ => Behaviors.same
        }
      }

      val fwdRef  = spawn(
        Behaviors.receiveMessage[DistributedMessage] { msg => probe.ref ! msg; Behaviors.same },
        "probe-neighbor"
      )
      val neighbor = NeighborRef("probe-neighbor", fwdRef, Set("CONTROL"))  // only CONTROL allowed
      val nodeRef  = spawn(new TestNode("edge-test-direct").behavior(), "edge-test-direct")

      nodeRef ! CommonMessages.InitializeNode(
        neighbors    = Map("probe-neighbor" -> neighbor),
        pdf          = UniformPdf(List("CONTROL")),
        inboundCount = 0
      )
      nodeRef ! CommonMessages.Start()

      // Only the CONTROL message must arrive — GOSSIP must be blocked.
      val received = probe.receiveMessage(1.second)
      assert(received.isInstanceOf[NetworkMessage],
        s"Expected NetworkMessage but got $received")
      assert(received.asInstanceOf[NetworkMessage].kind == "CONTROL",
        s"Expected kind=CONTROL but got ${received.asInstanceOf[NetworkMessage].kind}")

      // No second message should arrive (GOSSIP was blocked).
      probe.expectNoMessage(200.millis)
    }
  }

  // -------------------------------------------------------------------------
  // 3. PDF sampling only produces configured message types
  // -------------------------------------------------------------------------

  "MessagePdf.sample" must {

    "only produce message types listed in a UniformPdf" in {
      val pdf     = UniformPdf(List("PING", "GOSSIP", "WORK"))
      val rng     = new Random(42L)
      val samples = (1 to 200).map(_ => MessagePdf.sample(pdf, rng)).toSet
      assert(samples.subsetOf(Set("PING", "GOSSIP", "WORK")),
        s"Sample produced unexpected type(s): ${samples -- Set("PING","GOSSIP","WORK")}")
    }

    "only produce message types listed in a ZipfPdf" in {
      val pdf     = ZipfPdf(List("CONTROL", "PING"), exponent = 1.5)
      val rng     = new Random(99L)
      val samples = (1 to 200).map(_ => MessagePdf.sample(pdf, rng)).toSet
      assert(samples.subsetOf(Set("CONTROL", "PING")),
        s"ZipfPdf produced unexpected type(s): ${samples -- Set("CONTROL","PING")}")
    }

    "respect Zipf distribution so rank-1 type is sampled more than rank-2" in {
      val pdf    = ZipfPdf(List("CONTROL", "PING", "GOSSIP"), exponent = 1.2)
      val rng    = new Random(7L)
      val counts = (1 to 1000)
        .map(_ => MessagePdf.sample(pdf, rng))
        .groupBy(identity)
        .view.mapValues(_.size)
        .toMap

      val controlCount = counts.getOrElse("CONTROL", 0)
      val pingCount    = counts.getOrElse("PING",    0)
      val gossipCount  = counts.getOrElse("GOSSIP",  0)

      assert(controlCount > pingCount,
        s"Expected CONTROL($controlCount) > PING($pingCount) for Zipf")
      assert(pingCount > gossipCount,
        s"Expected PING($pingCount) > GOSSIP($gossipCount) for Zipf")
    }
  }

  // -------------------------------------------------------------------------
  // 4. ExplicitPdf validation
  // -------------------------------------------------------------------------

  "MessagePdf.validate" must {

    "accept a valid ExplicitPdf that sums to 1.0" in {
      val pdf = ExplicitPdf(Map("PING" -> 0.6, "GOSSIP" -> 0.3, "WORK" -> 0.1))
      MessagePdf.validate(pdf) match {
        case Right(_)   => // pass
        case Left(errs) => fail(s"Expected valid pdf but got: $errs")
      }
    }

    "reject an ExplicitPdf whose probabilities do not sum to 1.0" in {
      val pdf = ExplicitPdf(Map("PING" -> 0.5, "GOSSIP" -> 0.3))  // sums to 0.8
      MessagePdf.validate(pdf) match {
        case Left(errs) =>
          assert(errs.exists(_.contains("sum")),
            s"Expected sum error but got: $errs")
        case Right(_) =>
          fail("Expected validation to fail for pdf that does not sum to 1.0")
      }
    }

    "reject an ExplicitPdf with a negative probability" in {
      val pdf = ExplicitPdf(Map("PING" -> 1.1, "GOSSIP" -> -0.1))
      MessagePdf.validate(pdf) match {
        case Left(errs) =>
          assert(errs.exists(_.contains("Negative")),
            s"Expected negative-probability error but got: $errs")
        case Right(_) =>
          fail("Expected validation to fail for negative probability")
      }
    }
  }

  // -------------------------------------------------------------------------
  // 5. EchoNode actor lifecycle
  // -------------------------------------------------------------------------

  "EchoNode" must {

    "spawn successfully and report its nodeId" in {
      val node = new EchoNode("echo-test-1")
      node.nodeId shouldBe "echo-test-1"
    }

    "accept InitializeNode and Start without crashing" in {
      val probe   = TestProbe[DistributedMessage]()
      val nodeRef = spawn(new EchoNode("echo-test-2").behavior(), "echo-lifecycle")

      nodeRef ! CommonMessages.InitializeNode(
        neighbors    = Map.empty,
        pdf          = UniformPdf(List("CONTROL")),
        inboundCount = 0
      )
      nodeRef ! CommonMessages.Start()

      // No neighbors so no messages expected, but actor must stay alive.
      probe.expectNoMessage(200.millis)
      nodeRef should not be null
    }

    "immediately complete a wave when it has no peers" in {
      val stateProbe = TestProbe[DistributedMessage]()
      // isInitiator=true triggers startWave() on Start().
      // A root node with no peers completes immediately.
      val nodeRef = spawn(new EchoNode("node-1").behavior(), "echo-no-peers")

      nodeRef ! CommonMessages.InitializeNode(
        neighbors    = Map.empty,
        pdf          = UniformPdf(List("CONTROL")),
        inboundCount = 0,
        isInitiator  = true   // replaces former nodeId == "node-1" check
      )
      nodeRef ! CommonMessages.Start()

      // startWave() sets completed=true synchronously when peers is empty,
      // but it runs inside the actor's message handler so we still poll.
      eventually {
        nodeRef ! CommonMessages.GetState(stateProbe.ref)
        val response = stateProbe.receiveMessage(500.millis)
        val sr       = response.asInstanceOf[CommonMessages.StateResponse]
        assert(sr.state("completed") == true,
          s"Expected completed=true but got ${sr.state("completed")}")
      }
    }
  }

  // -------------------------------------------------------------------------
  // 6. LaiYangNode actor lifecycle
  // -------------------------------------------------------------------------

  "LaiYangNode" must {

    "spawn successfully and report its nodeId" in {
      val node = new LaiYangNode("ly-test-1")
      node.nodeId shouldBe "ly-test-1"
    }

    "accept InitializeNode and Start without crashing" in {
      val probe   = TestProbe[DistributedMessage]()
      val nodeRef = spawn(new LaiYangNode("ly-test-2").behavior(), "ly-lifecycle")

      nodeRef ! CommonMessages.InitializeNode(
        neighbors    = Map.empty,
        pdf          = UniformPdf(List("CONTROL")),
        inboundCount = 0
      )
      nodeRef ! CommonMessages.Start()

      probe.expectNoMessage(200.millis)
      nodeRef should not be null
    }

    "complete snapshot immediately when inboundCount is 0" in {
      val stateProbe = TestProbe[DistributedMessage]()
      // isInitiator=true triggers ctx.self ! StartSnapshot on Start().
      // With inboundCount=0 the snapshot completes immediately.
      val nodeRef = spawn(new LaiYangNode("node-1").behavior(), "ly-no-inbound")

      nodeRef ! CommonMessages.InitializeNode(
        neighbors    = Map.empty,
        pdf          = UniformPdf(List("CONTROL")),
        inboundCount = 0,
        isInitiator  = true   // replaces former nodeId == "node-1" check
      )
      nodeRef ! CommonMessages.Start()

      // Start() enqueues StartSnapshot via ctx.self — poll until processed.
      eventually {
        nodeRef ! CommonMessages.GetState(stateProbe.ref)
        val response = stateProbe.receiveMessage(500.millis)
        val sr       = response.asInstanceOf[CommonMessages.StateResponse]
        assert(sr.state("completed") == true,
          s"Expected completed=true but got ${sr.state}")
      }
    }
  }

  // -------------------------------------------------------------------------
  // 7. EchoState unit tests (pure logic, no actors)
  // -------------------------------------------------------------------------

  "EchoState" must {

    "start with default values" in {
      val s = EchoState()
      assert(s.parent.isEmpty)
      assert(s.seenWaves.isEmpty)
      assert(s.pendingReplies.isEmpty)
      assert(!s.completed)
      assert(!s.active)
    }

    "model a leaf node that has replied" in {
      val s = EchoState(
        parent         = Some("node-1"),
        seenWaves      = Set("wave-1"),
        pendingReplies = Set.empty,   // leaf sent Echo immediately
        completed      = false,
        active         = false
      )
      assert(s.parent.contains("node-1"))
      assert(s.pendingReplies.isEmpty)
      assert(!s.active)
    }

    "model a root node that has collected all replies" in {
      val s = EchoState(
        parent         = None,        // root has no parent
        seenWaves      = Set("wave-1"),
        pendingReplies = Set.empty,   // all children replied
        completed      = true,
        active         = false
      )
      assert(s.parent.isEmpty)
      assert(s.completed)
    }

    "track pending replies correctly as Echos arrive" in {
      val initial = EchoState(
        parent         = None,
        seenWaves      = Set("wave-1"),
        pendingReplies = Set("node-2", "node-3", "node-4"),
        completed      = false,
        active         = true
      )
      val afterFirst  = initial.copy(pendingReplies = initial.pendingReplies - "node-2")
      val afterSecond = afterFirst.copy(pendingReplies = afterFirst.pendingReplies - "node-3")
      val afterThird  = afterSecond.copy(
        pendingReplies = afterSecond.pendingReplies - "node-4",
        completed      = true,
        active         = false
      )

      assert(afterFirst.pendingReplies  == Set("node-3", "node-4"))
      assert(afterSecond.pendingReplies == Set("node-4"))
      assert(afterThird.pendingReplies.isEmpty)
      assert(afterThird.completed)
    }
  }

  // -------------------------------------------------------------------------
  // 8. GraphGenerator topology shapes
  // -------------------------------------------------------------------------

  "GraphGenerator" must {

    "produce a complete graph with n*(n-1) edges" in {
      val n    = 5
      val topo = GraphTopology.complete(n, Set("CONTROL"), UniformPdf(List("CONTROL")))
      assert(topo.nodes.size == n,
        s"Expected $n nodes but got ${topo.nodes.size}")
      assert(topo.edges.size == n * (n - 1),
        s"Expected ${n*(n-1)} edges but got ${topo.edges.size}")
      topo.validate() match {
        case Right(_)   => // pass
        case Left(errs) => fail(s"Complete graph failed validation: $errs")
      }
    }

    "produce a ring graph with exactly n edges and out-degree 1" in {
      val n    = 6
      val topo = GraphTopology.ring(n, Set("CONTROL"), UniformPdf(List("CONTROL")))
      assert(topo.nodes.size == n, s"Expected $n nodes")
      assert(topo.edges.size == n,
        s"Expected $n edges in ring but got ${topo.edges.size}")
      topo.nodes.foreach { node =>
        val out = topo.outgoingEdgesByNode(node.id).size
        assert(out == 1,
          s"Ring node ${node.id} should have out-degree 1 but has $out")
      }
    }

    "produce a sparse graph that is strongly connected via the spanning cycle" in {
      val rng  = new Random(1003L)
      val topo = GraphGenerator.sparse(
        nodeCount    = 8,
        edgeProb     = 0.3,
        allowedTypes = Set("CONTROL"),
        defaultPdf   = UniformPdf(List("CONTROL")),
        rng          = rng
      )
      assert(topo.nodes.size == 8)
      topo.nodes.foreach { node =>
        val out = topo.outgoingEdgesByNode(node.id).size
        assert(out >= 1,
          s"Sparse node ${node.id} has no outgoing edge — spanning cycle guarantee failed")
      }
      topo.validate() match {
        case Right(_)   => // pass
        case Left(errs) => fail(s"Sparse graph failed validation: $errs")
      }
    }

    "pass GraphLoader round-trip for all three PDF families" in {
      import com.typesafe.config.ConfigFactory
      val confStr =
        """
          |sim.graph {
          |  nodes = [
          |    { id = "a", pdf { family = "uniform",  messageTypes = ["PING"] } },
          |    { id = "b", pdf { family = "zipf",     messageTypes = ["PING","GOSSIP"], exponent = 1.2 } },
          |    { id = "c", pdf { family = "explicit", weights { PING = 0.7, GOSSIP = 0.3 } } }
          |  ]
          |  edges = [
          |    { from = "a", to = "b", allow = ["PING"] },
          |    { from = "b", to = "c", allow = ["PING","GOSSIP"] },
          |    { from = "c", to = "a", allow = ["PING"] }
          |  ]
          |}
        """.stripMargin
      val config = ConfigFactory.parseString(confStr)
      GraphLoader.loadFromConfig(config) match {
        case Right(topo) =>
          assert(topo.nodes.size == 3)
          assert(topo.edges.size == 3)
          assert(topo.nodeMap("a").pdf.family == "uniform")
          assert(topo.nodeMap("b").pdf.family == "zipf")
          assert(topo.nodeMap("c").pdf.family == "explicit")
        case Left(errs) =>
          fail(s"GraphLoader failed: $errs")
      }
    }
  }
}