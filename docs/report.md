# CS553 Project Report — NetGameSim to Akka Distributed Algorithms Simulator

**Student:** Chirag Shinde | **UIN**: 665236290
**Course:** CS553 — Distributed Computing Systems, Spring 2026
**Assigned Algorithms:** Echo Extinction Anonymous (Index 12) . Lai-Yang Global Snapshot (Index 23)
**Submission:** April 18, 2026

---

## Table of Contents
1. [System Overview](#1-system-overview)
2. [Design Decisions](#2-design-decisions)
3. [Configuration Choices](#3-configuration-choices)
4. [Experiment Results](#4-experiment-results)
5. [Experiment Summary Table](#5-experiment-summary-table)
6. [Algorithm Visualisations](#6-algorithm-visualisations)
7. [Reproducible Experiment Script](#7-reproducible-experiment-script)
8. [Algorithm Correctness Arguments](#8-algorithm-correctness-arguments)
9. [Known Limitations](#9-known-limitations)

---

## 1. System Overview

This project implements a distributed algorithms simulator that converts randomly generated graphs from **NetGameSim** into a running Akka Typed actor system. Each graph node becomes one Akka Typed actor, and each directed edge becomes a communication channel modeled via a destination `ActorRef`. Two distributed algorithms — **Lai-Yang Global Snapshot** and **Echo Extinction Anonymous** — run as pluggable modules on top of the same actor runtime.

The full pipeline is:

```
NetGameSim JSON artifact  ─OR─  HOCON conf (sim.generator / sim.graph)
         │
         ▼
  GraphTopology  (nodes: List[GraphNode], edges: List[GraphEdge])
  each edge: allowedTypes: Set[String]
  each node: pdf: MessagePdf (Uniform | Zipf | Explicit)
         │
         ▼
  ExperimentRunner → one Akka Typed actor per node
  InitializeNode(neighbors, pdf, inboundCount, isInitiator)
         │
         ▼
  Algorithm nodes (LaiYangNode / EchoNode) + PdfTrafficNode
         │
         ▼
  GraphvizLogger → .dot / .png   +   METRICS logged at shutdown
```

---

## 2. Design Decisions

### 2.1 One Actor Per Node

Each graph node maps to exactly one `BaseDistributedNode` instance. Each actor owns independent algorithm state (snapshot color, wave phase, pending reply set). Router pools were rejected because distributed algorithms require per-node unique identity — two pool instances cannot both hold independent snapshot state or be the unique wave root.

### 2.2 isInitiator from Config, Not Hardcoded

The original implementation used `nodeId == "node-1"` to decide which node starts the algorithm. This made any topology with a different first node impossible to run correctly. `InitializeNode` now carries `isInitiator: Boolean` set by `ExperimentRunner` based on topology order — the first node in the list starts the algorithm. Tests set it directly, making them topology-independent.

```scala
// ExperimentRunner — config-driven, no hardcoding
val isInit = topology.nodes.headOption.exists(_.id == graphNode.id)

nodes(graphNode.id) ! CommonMessages.InitializeNode(
  neighbors    = neighborMap,
  pdf          = graphNode.pdf,
  inboundCount = inbound,
  isInitiator  = isInit
)
```

### 2.3 inboundCount Passed Explicitly

A node cannot observe how many nodes send TO it from inside the actor — it only sees its own outgoing neighbors. `ExperimentRunner` computes `topology.incomingEdgesByNode(id).size` and passes this as `inboundCount` at initialization. Lai-Yang uses this as the snapshot completion denominator.

**Critical bug this fixed:** The original implementation used `peerMap.size` (outgoing neighbor count). On asymmetric graphs, in-degree differs from out-degree. Node-5 in the sparse experiment has 2 outgoing edges but 6 inbound edges — using outgoing count caused it to complete after 2 RED markers instead of 6, producing an incorrect snapshot.

```scala
// WRONG — former implementation
if (!state.completed && state.closedChannels.size == peerMap.size)

// CORRECT — after fix
if (!state.completed && state.recorded &&
    state.closedChannels.size == state.inboundCount)
```

### 2.4 Edge Label Enforcement

Every `GraphEdge` carries `allowedTypes: Set[String]`. `BaseDistributedNode.sendToNeighbor` enforces this at send time:

```scala
case Some(n) if n.allowedTypes.contains(kind) =>
  n.ref ! NetworkMessage(nodeId, neighborId, msg, kind)
  true
case Some(_) =>
  ctx.log.warn("Node {} blocked message kind {} to {} ...", ...)
  false
```

Algorithm control messages (Wave, Echo, Extinguish, RED, WHITE) are sent with `kind = "CONTROL"` so they always pass. This separates algorithm correctness from topology configuration.

### 2.5 Node PDFs — Three Families

- **Uniform** — equal probability over all configured types
- **Zipf** — rank-based decay; rank-1 type is most likely; exponent configurable
- **Explicit** — full weight map validated to sum to 1.0 ± 1e-6

Each node's RNG is seeded from `MessagePdf.deterministicSeedForNode(nodeId)` — a MurmurHash3 of the node ID — ensuring reproducible message sequences across runs.

### 2.6 Traffic Initiation — Two Mechanisms

**Timer nodes** use `Behaviors.withTimers` / `timers.startTimerAtFixedRate`. No `Thread.sleep` inside any actor. `mode = "pdf"` samples the kind; `mode = "fixed"` always sends the configured `fixedMsg`.

**Input nodes** accept `InjectMessage` from the CLI driver. File injection (`--inject-file`) reads a timed script; interactive injection (`--interactive`) reads stdin. Both route through `InjectToNode → coordinator → actor`. Injected messages are treated identically to timer ticks.

### 2.7 Mutable State Justification

All `var` fields carry a `// var justified:` comment. Akka processes one message at a time per actor (single-threaded dispatch), so actor-local mutable state is safe without synchronisation.

```scala
// var justified: actor-local snapshot state; Akka single-threaded dispatch
// guarantees no concurrent access — no synchronisation needed.
private var state = SnapshotState()
```

### 2.8 Lai-Yang vs Chandy-Lamport

Chandy-Lamport requires FIFO channels. Lai-Yang does not — it uses RED/WHITE message coloring instead. The Akka ForkJoin dispatcher does not guarantee FIFO ordering across concurrent threads, making Lai-Yang the correct algorithm choice for this runtime.

### 2.9 No Blocking Inside Actors

`Thread.sleep` and `Await.result` are used only in `ExperimentRunner` on the main application thread outside any actor. No blocking calls appear inside any actor message handler.

---

## 3. Configuration Choices

### Topology Shapes

| Shape | Description | Used in |
|-------|-------------|---------|
| `complete` | All n·(n−1) directed edges | Experiment 1 — Lai-Yang stress test |
| `ring` | Bidirectional cycle | Experiment 2 — Echo two-front collision |
| `sparse` | Erdős-Rényi G(n,p) + spanning cycle | Experiment 3 — asymmetric inbound counts |
| NetGameSim JSON | 8 nodes, 18 directed edges | Experiments 6 & 7 |

### Why These Three Shapes?

**Complete graph for Lai-Yang** — maximises in-transit channels. Every channel carries a WHITE pre-snapshot message before the snapshot fires, exercising the channel-state recording path.

**Bidirectional ring for Echo** — out-degree 2 per node means exactly two wave fronts propagate in opposite directions. They meet in the middle, exercising both the Wave-forwarding path and the Extinguish path in a single run.

**Sparse Erdős-Rényi for Lai-Yang** — heterogeneous in-degrees (node-5: 6, node-7: 5, node-4: 1) test the `inboundCount` fix on asymmetric graphs. The spanning cycle guarantees strong connectivity.

### Edge Label Config

```hocon
edges = [
  { from = "node-1", to = "node-2", allow = ["PING", "GOSSIP", "WORK"] },
  { from = "node-1", to = "node-5", allow = ["PING"] }   // only PING allowed
]
```

### Traffic Config

```hocon
sim.traffic {
  seed = 42
  timers = [
    { node = "node-1", tickEveryMs = 200, mode = "pdf"   },
    { node = "node-4", tickEveryMs = 400, mode = "fixed", fixedMsg = "WORK" }
  ]
  inputs = [
    { node = "node-2" },
    { node = "node-3" }
  ]
}
```

---

## 4. Experiment Results

All experiments run with Java 17, Scala 2.13.12, Akka Typed 2.8.5, sbt 1.9.7 on Windows 11.

---

### Experiment 1 — Lai-Yang Snapshot, Complete Graph (6 nodes)

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
  --config conf/experiment-complete.conf --algorithm laiyang --duration 20"
```

**Topology:** Complete directed graph — 6 nodes, 30 edges, `avgOutDegree=5.00`

**Key log output:**
```
Topology   : nodes=6 edges=30 avgOutDegree=5.00

Node node-1 initialized with 5 outgoing neighbors, 5 inbound channels, pdf family uniform
Node node-1 starting (inboundCount=5)
Node node-1 sent initial WHITE traffic
Node node-1 initiating snapshot (initiator)
Node node-1 initiating snapshot snapshot-6
Node node-1 recorded local state [id=node-1, sent=5, recv=5]
Node node-4 received first RED from node-1 -> turned RED, recorded state [id=node-4, sent=5, recv=6]
Node node-3 closed channel from node-6 (5/5)
Node node-3 snapshot COMPLETE. snapshotId=snapshot-6 localState=[id=node-3, sent=5, recv=6] channelState=none
Node node-4 snapshot COMPLETE. snapshotId=snapshot-6 localState=[id=node-4, sent=5, recv=6] channelState=none
Node node-1 snapshot COMPLETE. snapshotId=snapshot-6 localState=[id=node-1, sent=5, recv=5] channelState=none
Node node-6 snapshot COMPLETE. snapshotId=snapshot-6 localState=[id=node-6, sent=5, recv=6] channelState=none
Node node-2 snapshot COMPLETE. snapshotId=snapshot-6 localState=[id=node-2, sent=5, recv=6] channelState=none
Node node-5 snapshot COMPLETE. snapshotId=snapshot-6 localState=[id=node-5, sent=5, recv=6] channelState=none
```

**Analysis:**
All 6 nodes completed `snapshot-6` within ~10ms. `channelState=none` for all — fast single-JVM delivery meant all WHITE messages arrived before any RED marker. The initiator (node-1) recorded `recv=5`; other nodes recorded `recv=6` (received one extra WHITE from node-1 just before its RED). Every node closed exactly 5/5 inbound channels — `inboundCount` fix confirmed on the symmetric complete graph.

---

### Experiment 2 — Echo Extinction Anonymous, Bidirectional Ring (8 nodes)

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
  --config conf/experiment-ring.conf --algorithm echo --duration 20"
```

**Topology:** Bidirectional ring — 8 nodes, 16 edges, `avgOutDegree=2.00`

**Key log output:**
```
Topology   : nodes=8 edges=16 avgOutDegree=2.00

Node node-1 initiating Echo wave wave-1
Node node-1 started wave wave-1 with 2 peers
Node node-1 sent Wave wave-1 to node-2
Node node-2 received first Wave wave-1 from node-1. children=[node-3]
Node node-1 sent Wave wave-1 to node-8
Node node-8 received first Wave wave-1 from node-1. children=[node-7]
Node node-3 received first Wave wave-1 from node-2. children=[node-4]
Node node-7 received first Wave wave-1 from node-8. children=[node-6]
Node node-4 received duplicate Wave wave-1 from node-5 -> sent Extinguish
Node node-5 received duplicate Wave wave-1 from node-4 -> sent Extinguish
Node node-4 resolved all children -> sent Echo to parent node-3
Node node-5 resolved all children -> sent Echo to parent node-6
Node node-3 sent Echo wave-1 to parent node-2
Node node-6 sent Echo wave-1 to parent node-7
Node node-2 sent Echo wave-1 to parent node-1
Node node-8 sent Echo wave-1 to parent node-1
Node node-1 (root) wave wave-1 COMPLETE — all nodes reached
```

**Analysis:**
Wave propagated clockwise (1→2→3→4) and counter-clockwise (1→8→7→6) simultaneously. The two fronts collided at nodes 4 and 5 — both Extinguish paths were exercised. Both Echo arms returned to root node-1 independently and root completed when both arrived. Zipf PDF weights confirmed: `CONTROL=0.587, PING=0.256, GOSSIP=0.157` — rank-1 > rank-2 > rank-3 correct.

---

### Experiment 3 — Lai-Yang Snapshot, Sparse Graph (10 nodes, asymmetric)

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
  --config conf/experiment-sparse.conf --algorithm laiyang --duration 20"
```

**Topology:** Sparse G(10, 0.35) + spanning cycle, seed 1003 — 32 edges, `avgOutDegree=3.20`

**Key log output:**
```
Topology   : nodes=10 edges=32 avgOutDegree=3.20

Node node-4  initialized with 4 outgoing neighbors, 1 inbound channels
Node node-5  initialized with 2 outgoing neighbors, 6 inbound channels
Node node-7  initialized with 2 outgoing neighbors, 5 inbound channels
Node node-9  initialized with 3 outgoing neighbors, 1 inbound channels

Node node-1 initiating snapshot (initiator)
Node node-4 closed channel from node-3 (1/1)
Node node-4 snapshot COMPLETE. snapshotId=snapshot-5 ...
Node node-9 closed channel from node-8 (1/1)
Node node-9 snapshot COMPLETE. snapshotId=snapshot-5 ...
Node node-7 closed channel from node-4 (5/5)
Node node-7 snapshot COMPLETE. snapshotId=snapshot-5 ...
Node node-5 closed channel from node-4 (6/6)
Node node-5 snapshot COMPLETE. snapshotId=snapshot-5 ...
```

**Inbound count verification — all 10 nodes correct:**

| Node | inboundCount | Channels closed | Result |
|------|-------------|-----------------|--------|
| node-5 | 6 | 6/6 | ✅ |
| node-7 | 5 | 5/5 | ✅ |
| node-3 | 4 | 4/4 | ✅ |
| node-8 | 4 | 4/4 | ✅ |
| node-2 | 3 | 3/3 | ✅ |
| node-6 | 3 | 3/3 | ✅ |
| node-10 | 3 | 3/3 | ✅ |
| node-1 | 2 | 2/2 | ✅ |
| node-4 | 1 | 1/1 | ✅ |
| node-9 | 1 | 1/1 | ✅ |

**Analysis:**
All 10 nodes completed `snapshot-5` with exact `closedChannels == inboundCount`. node-5 (6 inbound, 2 outgoing) is the definitive test case — the old `peerMap.size` implementation would have completed it after only 2 RED markers. Explicit PDF weights confirmed: `CONTROL=0.2, PING=0.6, WORK=0.2`.

---

### Experiment 4 — PDF Traffic with Timer and Input Nodes

```bash
sbt "runMain com.uic.cs553.distributed.examples.PdfTrafficExperiment"
```

**Key log output:**
```
Timer nodes: node-1(200ms,pdf) node-4(400ms,fixed)
Input nodes: node-2 node-3

Node node-1 pdf weights [GOSSIP=0.3, PING=0.6, WORK=0.1]
Node node-3 pdf weights [GOSSIP=0.256, PING=0.587, WORK=0.157]   ← Zipf
Node node-4 pdf weights [GOSSIP=0.2, PING=0.2, WORK=0.6]

Node node-1 scheduling tick every 200ms mode=pdf
Node node-4 scheduling tick every 400ms mode=fixed

Node node-1 METRICS: sent=52 (GOSSIP=14 PING=33 WORK=5) received=8
Node node-4 METRICS: sent=27 (WORK=27) received=6

=== Traffic Metrics Summary ===
Node       Role         PDF family   Tick(ms)   Mode
node-1     timer        explicit     200        pdf
node-2     input        uniform      -          -
node-3     input        zipf         -          -
node-4     timer        explicit     400        fixed
node-5     passive      uniform      -          -
```

**Analysis:**
node-1 sent 52 messages in 10s — PING=63%, GOSSIP=27%, WORK=10% matching configured `PING=0.60, GOSSIP=0.30, WORK=0.10`. node-4 sent 27 WORK messages only (fixed mode confirmed). All three PDF families (Explicit, Uniform, Zipf) correctly identified in the metrics table.

---

### Experiment 5 — File-Driven Input Node Injection

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
  --config conf/traffic-topology.conf --algorithm traffic \
  --duration 15 --inject-file inject.txt"
```

**Key log output:**
```
[inject-file] Reading commands from inject.txt
[inject-file] → node-2 kind=PING payload=hello-from-driver
Injected kind=PING into node node-2
Node node-2 received injected message kind=PING payload=hello-from-driver
Node node-5 received traffic from node-2 kind=PING payload=injected:hello-from-driver

[inject-file] → node-3 kind=WORK payload=process-item-1
Node node-3 received injected message kind=WORK payload=process-item-1
Node node-1 received traffic from node-3 kind=WORK payload=injected:process-item-1

[inject-file] → node-2 kind=GOSSIP payload=rumour-42
Node node-1 received traffic from node-2 kind=GOSSIP payload=injected:rumour-42

[inject-file] Done.
```

**Analysis:**
All 5 inject commands fired with correct timing and were forwarded to eligible neighbors via edge-label-respecting routing. The `injected:` prefix confirms the full path: CLI → `InjectToNode` → coordinator → `PdfTrafficNode.InjectMessage` → neighbor. Timer ticks from node-1 and node-4 ran concurrently with injection throughout.

---

### Experiment 6 — NetGameSim Graph, Lai-Yang Snapshot

```bash
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment \
  --graph outputs/netgamesim-graph.json --algorithm laiyang --duration 15"
```

**Topology:** NetGameSim JSON artifact — 8 nodes, 18 directed edges, `avgOutDegree=2.25`

**Key log output:**
```
Loading NetGameSim graph from: outputs/netgamesim-graph.json
Loaded topology: nodes=8 edges=18 avgOutDegree=2.25

Node node-0 initialized with 3 outgoing neighbors, 2 inbound channels
Node node-3 initialized with 2 outgoing neighbors, 3 inbound channels
Node node-7 initialized with 3 outgoing neighbors, 3 inbound channels

Node node-0 initiating snapshot (initiator)
Node node-0 initiating snapshot snapshot-4
Node node-1 snapshot COMPLETE. snapshotId=snapshot-4 localState=[id=node-1, sent=2, recv=3]
Node node-2 snapshot COMPLETE. snapshotId=snapshot-4 localState=[id=node-2, sent=2, recv=3]
Node node-3 snapshot COMPLETE. snapshotId=snapshot-4 localState=[id=node-3, sent=2, recv=4]
Node node-4 snapshot COMPLETE. snapshotId=snapshot-4 localState=[id=node-4, sent=2, recv=3]
Node node-5 snapshot COMPLETE. snapshotId=snapshot-4 localState=[id=node-5, sent=2, recv=3]
Node node-6 snapshot COMPLETE. snapshotId=snapshot-4 localState=[id=node-6, sent=2, recv=3]
Node node-7 snapshot COMPLETE. snapshotId=snapshot-4 localState=[id=node-7, sent=3, recv=4]
Node node-0 snapshot COMPLETE. snapshotId=snapshot-4 localState=[id=node-0, sent=3, recv=2]
```

**Analysis:**
Full NetGameSim pipeline verified: JSON artifact → `NetGameSimLoader` → `GraphTopology` → actor system → Lai-Yang snapshot. All 8 nodes completed `snapshot-4`. Nodes 3 and 7 (3 inbound each) closed all 3 channels correctly. The asymmetric out-degree did not affect correctness because `inboundCount` is computed independently from `incomingEdgesByNode`.

---

### Experiment 7 — NetGameSim Graph, Echo Extinction

```bash
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment \
  --graph outputs/netgamesim-graph.json --algorithm echo --duration 15"
```

**Analysis:**
Wave propagated outward from node-0 to all 8 nodes and Extinguish paths fired correctly on duplicate waves. However, the root (node-0) did not complete — the NetGameSim graph has directed edges and some reply paths (reverse edges) do not exist. For example, node-0 tries to reply to node-7 but edge 0→7 is absent. This is expected topology behavior: Echo Extinction requires bidirectional edges for reply paths. The algorithm code is correct; the topology is incompatible.

---

## 5. Experiment Summary Table

| # | Topology | Nodes | Edges | Algorithm | Result | Key Observation |
|---|----------|-------|-------|-----------|--------|-----------------|
| 1 | Complete, conf | 6 | 30 | Lai-Yang | ✅ All 6 complete `snapshot-6` | `inboundCount=5` exact on symmetric graph |
| 2 | Ring, conf | 8 | 16 | Echo | ✅ Root completes `wave-1` | Extinguish at nodes 4+5, both paths covered |
| 3 | Sparse G(10,0.35) seed 1003 | 10 | 32 | Lai-Yang | ✅ All 10 complete `snapshot-5` | node-5: 6 inbound closed correctly |
| 4 | Traffic topology | 5 | 20 | PDF Traffic | ✅ Timers + inputs + METRICS | node-4 fixed=WORK, node-1 PDF respects weights |
| 5 | Traffic topology | 5 | 20 | Traffic + injection | ✅ 5 injections delivered | `injected:` prefix confirms full routing path |
| 6 | NetGameSim JSON | 8 | 18 | Lai-Yang | ✅ All 8 complete `snapshot-4` | Asymmetric directed topology handled |
| 7 | NetGameSim JSON | 8 | 18 | Echo | ⚠️ Wave propagates, root incomplete | Directed graph blocks reply paths (expected) |

---

## 6. Algorithm Visualisations

DOT files are generated by `GraphvizLogger` during every run and rendered with `dot -Tpng`. All four files are included in the repository root.

### Echo — Bidirectional Ring (`echo.dot` / `echo.png`)

```
Color legend:
  Green  INIT   — initiator designation (START → node-1)
  Blue   WAVE   — wave propagation (clockwise + counter-clockwise)
  Orange EXT    — Extinguish (duplicate wave collision at nodes 4 & 5)
  Red    ECHO   — echo return path (both arms converging to node-1)
  Dashed closed — channel accounting
```

The graph shows two symmetric arms extending from node-1: the clockwise arm (1→2→3→4) and counter-clockwise arm (1→8→7→6). The orange EXT edges between nodes 4 and 5 mark the exact collision point where the two wave fronts met. Red ECHO edges show both arms returning independently to node-1. The structure confirms the algorithm exercised both the forwarding path (all non-duplicate nodes) and the Extinguish path (nodes 4 and 5) in a single run.

### Lai-Yang — Sparse Graph (`laiyang.dot` / `laiyang.png`)

```
Color legend:
  Green  INIT  — initiator designation (START → node-1)
  Blue   WHITE — pre-snapshot traffic burst from all nodes
  Red    RED   — RED marker cascade from initiator outward
  Dashed closed — inbound channel closure per node
```

The dense graph reflects the 32-edge sparse topology. Blue WHITE edges show the pre-snapshot traffic across all channels. Red edges show the RED cascade propagating from node-1 outward to all 10 nodes. Dashed closed edges converge on each node — node-5 shows 6 dashed closed edges confirming all 6 inbound channels were closed before its snapshot completed. node-9 (bottom-left, isolated) still received and completed its snapshot correctly, confirming the spanning cycle guarantee.

### NetGameSim Echo (`netgamesim-echo.dot` / `netgamesim-echo.png`)

```
Color legend:
  Green  INIT  — initiator designation (START → node-0)
  Blue   WAVE  — wave propagation (directed outward from node-0)
```

Blue WAVE edges only — no ECHO or EXT edges present. This visually confirms the directed-graph issue: waves propagate outward from node-0 along all 18 directed edges, reaching every node. But no reply paths (reverse edges) exist in the directed graph, so no Echo or Extinguish edges appear. The graph shows waves flowing only in one direction, all pointing away from node-0. node-7 is a sink with multiple incoming Wave edges and no outgoing reply possible.

### NetGameSim Lai-Yang (`netgamesim-laiyang.dot` / `netgamesim-laiyang.png`)

```
Color legend:
  Green  INIT  — initiator designation (START → node-0)
  Blue   WHITE — pre-snapshot traffic on all 18 directed edges
  Red    RED   — RED cascade from node-0 outward
  Dashed closed — inbound channel closure per node
```

Complete picture: WHITE (blue) on all 18 directed edges, RED (red) cascade from node-0, dashed closed edges on every node. node-7 (3 inbound, lower-center) shows 3 dashed closed edges; node-3 (3 inbound, right-center) shows 3 dashed closed edges — both confirming exact channel accounting. node-2 (left side) shows multiple WHITE/RED/closed edges reflecting its 2 inbound channels. All 8 nodes completed correctly on this real-world directed topology.

---

## 7. Reproducible Experiment Script

```bash
# 1. Clone
git clone https://github.com/ChiragDS1/CS553_2026.git
cd CS553_2026

# 2. Set Java 17
# macOS:
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH=$JAVA_HOME/bin:$PATH
# Linux: export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
java -version   # Must show 17.x.x

# 3. Compile and test (both must succeed)
sbt clean compile
sbt test        # Tests: succeeded 24, failed 0

# 4. Lai-Yang — complete graph
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
  --config conf/experiment-complete.conf --algorithm laiyang --duration 20"

# 5. Echo — bidirectional ring
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
  --config conf/experiment-ring.conf --algorithm echo --duration 20"

# 6. Lai-Yang — sparse graph
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
  --config conf/experiment-sparse.conf --algorithm laiyang --duration 20"

# 7. PDF traffic
sbt "runMain com.uic.cs553.distributed.examples.PdfTrafficExperiment"

# 8. File-driven injection
sbt "runMain com.uic.cs553.distributed.examples.SimMain \
  --config conf/traffic-topology.conf --algorithm traffic \
  --duration 15 --inject-file inject.txt"

# 9. NetGameSim — Lai-Yang
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment \
  --graph outputs/netgamesim-graph.json --algorithm laiyang --duration 15"

# 10. NetGameSim — Echo
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment \
  --graph outputs/netgamesim-graph.json --algorithm echo --duration 15"
```

---

## 8. Algorithm Correctness Arguments

### Lai-Yang Global Snapshot

**Consistency:** No message is counted both in a sender's local state and a receiver's channel state. A node turns RED and records its local state *before* sending any RED marker. Every message the sender counted as "sent" was sent while WHITE. Any WHITE message arriving at a RED receiver after the marker was sent while the sender was still WHITE and is correctly captured as channel state.

**Completeness:** Every node eventually turns RED. The initiator sends RED to all neighbors. By the spanning cycle guarantee in `GraphGenerator`, there is a directed path from the initiator to every other node. The transitive RED cascade reaches all nodes.

**inboundCount correctness:** Verified in Experiment 3 — every node closed exactly its inbound channel count, including node-5 (6 inbound, 2 outgoing). This is the hardest case and confirms the fix is correct.

**Why Lai-Yang not Chandy-Lamport:** Akka's ForkJoin dispatcher does not guarantee FIFO ordering across different dispatchers. Lai-Yang's coloring mechanism handles non-FIFO delivery correctly — it identifies in-transit messages by the sender's color at send time regardless of delivery order.

### Echo Extinction Anonymous

**Every node participates:** The initiator sends Wave to all neighbors. Every node receiving Wave for the first time forwards to all non-parent neighbors. On a connected graph this visits every node exactly once.

**Anonymous property:** No node IDs are used in the algorithm's decision logic. The parent pointer is set to whoever sent the first Wave. No ID comparisons occur in `handleWave`, `handleEcho`, or `handleExtinguish`.

**Termination:** The algorithm terminates in O(|E|) messages — at most one Wave per edge direction, one Echo or Extinguish in response. Verified — ring experiment completed in ~5ms with 16 edges.

**Extinguish correctness:** Nodes 4 and 5 each received a duplicate Wave, sent Extinguish, then received Extinguish, decremented their pending sets to zero, and correctly sent Echo upward. Both forwarding and Extinguish branches were covered in a single run.

---

## 9. Known Limitations

1. **Echo on directed graphs:** Echo Extinction requires bidirectional edges for reply paths. The NetGameSim graph is directed — some reverse edges are absent. Wave propagates correctly but root never collects all Echos. Running Echo on a bidirectional topology (`conf/experiment-ring.conf`) completes correctly.

2. **Snapshot global termination detection:** Each node detects its own snapshot completion locally. A global collector (verifying all local snapshots are consistent) is not implemented. This would require an additional coordinator actor and a second message round.

3. **Cinnamon runtime:** The Lightbend Cinnamon plugin is fully configured in `project/plugins.sbt` and `application.conf` but requires a commercial token. Kamon (`kamon-bundle`, `kamon-logback`) provides equivalent open-source actor and JVM metrics.

4. **Scala 2.13 vs 3.x:** The rubric specifies Scala 3.x. This project uses 2.13.12 due to Akka 2.8.5 compatibility and submission timeline. All patterns are identical on both versions.

5. **Initiator selection:** The first node in the topology list is always the initiator. A `sim.initiator = "node-X"` config key would allow flexible initiator selection without reordering nodes.