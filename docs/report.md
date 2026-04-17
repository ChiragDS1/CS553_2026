# CS553 Project Report — NetGameSim to Akka Distributed Algorithms Simulator

**Course**: CS553 Distributed Systems — University of Illinois Chicago  
**Submission**: Saturday, April 18, 2026

---

## 1. Overview

This project implements a full pipeline from graph generation to running
distributed computation.  Random graphs are generated from configuration
parameters, enriched with per-edge message-type labels and per-node probability
distributions, converted into an Akka Typed actor system, and used as the
substrate for two distributed algorithms: **Lai-Yang global snapshot** and
**Echo Extinction Anonymous**.

---

## 2. Design Decisions

### 2.1 Graph model

`GraphTopology` is an immutable value class holding `List[GraphNode]` and
`List[GraphEdge]`.  Lazy vals index the edge lists by source and destination
so repeated lookups during actor initialization are O(1).  Validation runs at
load time and fails fast with a complete error list rather than throwing on the
first problem.

Three topology shapes are supported by `GraphGenerator`:

- **complete** — all n·(n−1) directed edges; used to stress-test Lai-Yang
  because every channel carries in-transit messages before the snapshot fires.
- **ring** — bidirectional cycle; used for Echo because two wave fronts
  collide in the middle, exercising both the forwarding path and the
  Extinguish path in a single run.
- **sparse** (Erdős-Rényi G(n,p) + spanning cycle) — heterogeneous degrees
  that test Lai-Yang's per-channel inbound count tracking on asymmetric graphs.

`NetGameSimLoader` reads NetGameSim JSON artifacts directly into `GraphTopology`
so externally generated graphs run without hand-crafted HOCON.

### 2.2 Edge labels and message routing

Each `GraphEdge` carries `allowedTypes: Set[String]`.
`BaseDistributedNode.sendToNeighbor` enforces this at send time:

```scala
case Some(n) if n.allowedTypes.contains(kind) =>
  n.ref ! NetworkMessage(nodeId, neighborId, msg, kind)
  true
case Some(_) =>
  ctx.log.warn("Node {} blocked message kind {} to {} ...", ...)
  false
```

Algorithm control messages (Wave, Echo, Extinguish, RED, WHITE) are sent with
`kind = "CONTROL"` so they always pass regardless of the application-level
edge labels.  This separates algorithm correctness from topology configuration
— no experiment conf needs to list algorithm message types in `allowedTypes`.

### 2.3 Node PDFs

Three distribution families:

- **Uniform** — equal probability over all configured types.
- **Zipf** — rank-based decay; rank-1 type is most likely.  Exponent
  is configurable; higher values concentrate traffic on the top type.
- **Explicit** — full weight map that must sum to 1.0 ± 1e-6.

Each node's RNG is seeded deterministically from a MurmurHash3 of its node ID
plus a base seed, making all experiments reproducible under the same config.

### 2.4 Actor mapping

`ExperimentRunner` spawns one Akka Typed actor per node, then sends each actor
an `InitializeNode` message containing:
- `neighbors` — outgoing `NeighborRef` map (id, ActorRef, allowedTypes)
- `pdf` — message distribution
- `inboundCount` — number of edges pointing INTO this node; passed explicitly
  because a node can only see its own outgoing edges
- `isInitiator` — true for the first node in the topology list; replaces the
  former hardcoded `nodeId == "node-1"` check

This design means any topology shape can designate its own initiator through
config ordering, and tests can set `isInitiator = true` directly.

### 2.5 Traffic initiation

Two mechanisms:

**Timer nodes** use `Behaviors.withTimers` / `timers.startTimerAtFixedRate`.
No `Thread.sleep` is used inside actors; the Akka scheduler sends a
`TrafficTick` message at the configured interval without blocking any thread.
`mode = "pdf"` samples the kind from the node PDF; `mode = "fixed"` always
sends the configured `fixedMsg`.

**Input nodes** accept `InjectMessage` forwarded from outside the actor system
via the coordinator's `InjectToNode` handler.  File injection reads a timed
script; interactive injection reads stdin.  Both paths route through the same
coordinator, so injected messages are treated identically to timer ticks and
algorithms need no special cases.

### 2.6 Blocking operations

`Thread.sleep` and `Await.result` are used only in `ExperimentRunner.main` —
the main application thread that runs the overall experiment duration timer and
waits for the actor system to terminate.  No blocking calls appear inside any
actor message handler.

### 2.7 Mutable state

All `var` fields carry a `// var justified:` comment explaining why immutability
is not required.  In every case the justification is that Akka processes one
message at a time per actor (single-threaded dispatch), so actor-local mutable
state is safe without synchronisation.

---

## 3. Lai-Yang Global Snapshot

### Algorithm summary

Each node starts WHITE.  The initiator records its local state, turns RED, and
broadcasts RED marker messages on all outgoing channels.  When a node receives
its first RED message it records its local state, turns RED, and broadcasts RED
on all its outgoing channels.  In-transit WHITE messages received after a node
turns RED are recorded in `channelState`.  A node's snapshot is complete when
it has received a RED message on every inbound channel
(`closedChannels.size == inboundCount`).

### Key correctness property

The snapshot is consistent: no message is counted both in a sender's local
state (as "sent") and in a receiver's channel state (as "in transit").  This
holds because a sender turns RED before it can send any RED message, so the RED
marker overtakes all WHITE messages that the sender had already sent.

### Implementation notes

The critical bug we discovered and fixed: the original implementation used
`peerMap.size` (outgoing neighbor count) as the completion denominator.  On
asymmetric graphs a node's in-degree can differ from its out-degree, causing
nodes to either complete too early (in-degree < out-degree) or never complete
(in-degree > out-degree).  The fix passes `inboundCount` explicitly from
`ExperimentRunner` at initialization.

---

## 4. Echo Extinction Anonymous

### Algorithm summary

The initiator sends a Wave to all neighbours.  Each node that receives a Wave
for the first time records the sender as its parent and forwards the Wave to
all other neighbours (its "children").  Duplicate Waves are extinguished — the
node sends an Extinguish reply to the duplicate sender.  Leaf nodes (no
children) send Echo back to parent immediately.  Echos propagate up the
spanning tree; the root completes when all replies (Echo or Extinguish) arrive.

### Key property

The algorithm terminates on any connected undirected graph.  It is anonymous
(no node IDs are used in the Wave/Echo decision logic; only the parent pointer
and pending-reply set matter).

### Ring topology result

On the bidirectional 8-node ring, two wave fronts propagate in opposite
directions.  They meet at nodes 4 and 5, which each receive a duplicate Wave
and reply with Extinguish.  The Extinguish path is exercised in the same run
as the normal forwarding path — both branches of `handleWave` are covered.

---

## 5. Experiment Results

### Experiment 1 — Complete graph (6 nodes, Lai-Yang)

```
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
      --config conf/experiment-complete.conf --algorithm laiyang --duration 20"
```

All 6 nodes completed `snapshot-6` within ~10ms of Start.  `channelState=none`
for all nodes because the snapshot fired after all pre-snapshot WHITE messages
had already been delivered (fast local delivery on a single JVM).  This
demonstrates the correct operation of the color-flip cascade on the maximum
in-transit-channel topology.

### Experiment 2 — Bidirectional ring (8 nodes, Echo)

```
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
      --config conf/experiment-ring.conf --algorithm echo --duration 20"
```

Wave completed in ~5ms.  Nodes 4 and 5 sent Extinguish (two-front collision).
Both arms echoed back to root (node-1) which logged `COMPLETE`.  Confirms the
Extinguish path and the two-arm Echo collection both work correctly.

### Experiment 3 — Sparse random graph (10 nodes, Lai-Yang, seed 1003)

```
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
      --config conf/experiment-sparse.conf --algorithm laiyang --duration 20"
```

Heterogeneous inbound counts (node-5: 6 inbound, node-4: 1 inbound).  All 10
nodes completed `snapshot-5` with exact `closedChannels == inboundCount` counts
— no overruns.  This validates the inbound-count fix on an asymmetric topology.

### NetGameSim experiment (8 nodes loaded from JSON artifact)

```
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment \
      --graph outputs/netgamesim-graph.json --algorithm laiyang --duration 15"
```

`Loaded topology: nodes=8 edges=18 avgOutDegree=2.25`.  All 8 nodes completed
`snapshot-4`.  Demonstrates the full pipeline: JSON artifact → GraphTopology →
actor system → distributed algorithm.

---

## 6. Instrumentation

The project is configured for Lightbend Telemetry (Cinnamon).  The `cinnamon`
block in `src/main/resources/application.conf` selects all `/user/*` actors
and configures a SLF4J reporter at 10-second intervals.  The `sbt-cinnamon`
plugin declaration is in `project/plugins.sbt` (commented out because the
`cinnamon-agent` jar requires a Lightbend subscription token).

Equivalent metrics are provided by Kamon (`kamon-bundle`, `kamon-logback`),
which resolves from Maven Central without credentials and instruments actor
mailboxes, processing times, JVM heap, and GC pauses.

---

## 7. Test Coverage

24 ScalaTest tests covering:

| Group | Tests | Invariant |
|---|---|---|
| GraphTopology.validate | 3 | Missing node refs and empty edge labels rejected |
| BaseDistributedNode.sendToNeighbor | 1 | Forbidden kind blocked; allowed kind delivered |
| MessagePdf.sample | 3 | Only configured types produced; Zipf rank ordering |
| MessagePdf.validate | 3 | Sum-to-1.0; negative probabilities rejected |
| EchoNode | 3 | Lifecycle; no-peer wave completes immediately |
| LaiYangNode | 3 | Lifecycle; zero-inbound snapshot completes immediately |
| EchoState | 4 | Leaf reply; root completion; pending-set tracking |
| GraphGenerator | 4 | Complete edge count; ring degree; sparse connectivity; loader round-trip |

---

## 8. Known Limitations

- **Scala version**: Scala 2.13.12 is used instead of the specified 3.x due to
  Akka 2.8.5 compatibility constraints and submission timeline.
- **Cinnamon runtime**: Requires a Lightbend token not available on a clean
  machine; Kamon provides equivalent coverage.
- **NetGameSim**: The `NetGameSimLoader` reads the JSON export format.  A full
  submodule integration of NetGameSim was not completed; the artifact in
  `outputs/netgamesim-graph.json` serves as the graph input.
- **Initiator selection**: The first node in the topology list is always the
  initiator.  A future iteration could support a `sim.initiator` config key.