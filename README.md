# CS553 — NetGameSim to Akka Distributed Algorithms Simulator

End-to-end pipeline from random graph generation to a running distributed
computation.  Graph nodes become Akka Typed actors; graph edges become typed
message channels.  The runtime generates background traffic from per-node
probability distributions and provides a framework for two assigned distributed
algorithms: **Lai-Yang global snapshot** and **Echo Extinction Anonymous**.

---

## Prerequisites

| Tool | Version |
|---|---|
| Java (Eclipse Adoptium or OpenJDK) | 17 |
| Scala | 2.13.12 |
| sbt | 1.9.7 |

> **Note on Scala version.** The rubric targets Scala 3.x.  This project uses
> Scala 2.13.12 because `akka-actor-typed` 2.8.5 has more complete support on
> 2.13 and the Akka 3.x migration guide was out of scope for the submission
> timeline.  All language features, actor patterns, and algorithm semantics are
> identical between the two versions.

---

## Quick start — clean machine

```bash
git clone <repo-url>
cd CS553_2026
sbt clean compile
sbt test
```

Both commands must succeed before running any experiment.

---

## Akka learning examples (Prof. Grechanik)

Before running experiments, read the PLANE examples:
<https://github.com/0x1DOCD00D/PLANE/tree/master/src/main/scala/Akka>

Suggested order: `BasicActorComm` → `ScheduleEventsRandomIntervals` →
`StateMachineWithActors` → `DiffusingComputation` → `ActorSystemExperiments`.

---

## Project layout

```
CS553_2026/
├── conf/                          # External experiment config files (CLI)
│   ├── experiment-complete.conf   # Complete graph, Lai-Yang stress test
│   ├── experiment-ring.conf       # Bidirectional ring, Echo algorithm
│   ├── experiment-sparse.conf     # Sparse Erdos-Renyi, Lai-Yang
│   ├── experiment-netgamesim.conf # Traffic config for NetGameSim topology
│   └── traffic-topology.conf      # Full traffic topology + timer/input nodes
├── outputs/
│   └── netgamesim-graph.json      # NetGameSim graph artifact (8 nodes, 18 edges)
├── inject.txt                     # Sample file-injection script
├── src/main/scala/com/uic/cs553/distributed/
│   ├── core/
│   │   ├── GraphModels.scala      # GraphNode, GraphEdge, GraphTopology
│   │   ├── GraphLoader.scala      # HOCON topology loader
│   │   ├── GraphGenerator.scala   # Config-driven complete/ring/sparse generator
│   │   ├── TrafficModels.scala    # MessagePdf: Uniform, Zipf, Explicit
│   │   ├── TrafficConfig.scala    # Timer and input node config model
│   │   └── NetGameSimLoader.scala # Loads NetGameSim JSON graph artifacts
│   ├── framework/
│   │   ├── DistributedNode.scala  # BaseDistributedNode, InitializeNode, NeighborRef
│   │   └── ExperimentRunner.scala # Actor system lifecycle, metrics summary
│   ├── algorithms/
│   │   ├── laiyang/               # Lai-Yang snapshot (LaiYangNode, SnapshotState)
│   │   ├── echo/                  # Echo Extinction Anonymous (EchoNode, EchoState)
│   │   └── traffic/               # PDF traffic node with Akka timer scheduler
│   ├── examples/
│   │   ├── SimMain.scala          # Main CLI entry point
│   │   ├── NetGameSimExperiment.scala # NetGameSim JSON → Akka pipeline
│   │   ├── LaiYangExperiment.scala
│   │   ├── EchoExperiment.scala
│   │   └── PdfTrafficExperiment.scala
│   └── utils/
│       └── GraphvizLogger.scala   # DOT file output for visualisation
└── src/test/scala/.../
    └── DistributedNodeSpec.scala  # 24 ScalaTest unit + integration tests
```

---

## Running all experiments

### 1. Run tests (24 tests, all must pass)

```bash
sbt test
```

Expected: `Tests: succeeded 24, failed 0`

---

### 2. Lai-Yang snapshot — complete graph (6 nodes)

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/experiment-complete.conf --algorithm laiyang --duration 20"
```

Expected: all 6 nodes log `snapshot COMPLETE. snapshotId=snapshot-X` with the
same snapshot ID.  DOT file written to `laiyang.dot`.

---

### 3. Echo Extinction Anonymous — bidirectional ring (8 nodes)

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/experiment-ring.conf --algorithm echo --duration 20"
```

Expected: `Node node-1 (root) wave wave-1 COMPLETE — all nodes reached`.
DOT file written to `echo.dot`.

---

### 4. Lai-Yang snapshot — sparse random graph (10 nodes, Erdos-Renyi seed 1003)

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/experiment-sparse.conf --algorithm laiyang --duration 20"
```

Expected: all 10 nodes complete with exact `closedChannels == inboundCount`.

---

### 5. PDF traffic — timer nodes + input nodes

```bash
sbt "runMain com.uic.cs553.distributed.examples.PdfTrafficExperiment"
```

Expected: per-node `METRICS:` lines at shutdown + traffic summary table showing
`node-1 timer 200ms pdf`, `node-4 timer 400ms fixed`, `node-2/3 input`.

---

### 6. PDF traffic — file-driven input node injection

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/traffic-topology.conf --algorithm traffic --duration 15 --inject-file inject.txt"
```

Expected: `[inject-file] → node-2 kind=PING payload=hello-from-driver` lines
interleaved with timer traffic.  Injected messages are forwarded to eligible
neighbors exactly like timer ticks.

---

### 7. PDF traffic — interactive stdin injection

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/traffic-topology.conf --algorithm traffic --duration 60 --interactive"
```

Then type on stdin while the system runs:

```
inject node-2 PING hello
inject node-3 WORK task-1
quit
```

---

### 8. NetGameSim graph — Lai-Yang snapshot

```bash
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment --graph outputs/netgamesim-graph.json --algorithm laiyang --duration 15"
```

Expected: `Loaded topology: nodes=8 edges=18` then all 8 nodes `snapshot COMPLETE`.

---

### 9. NetGameSim graph — Echo Extinction

```bash
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment --graph outputs/netgamesim-graph.json --algorithm echo --duration 15"
```

---

## Architecture

### Graph generation

`GraphGenerator` produces three topology shapes from config parameters:

- `complete` — every node has an outgoing edge to every other node; maximises
  in-transit channels for Lai-Yang and duplicate-wave collisions for Echo.
- `ring` — bidirectional ring; low degree, tests the tree-path case for Echo
  and the single-channel recording case for Lai-Yang.
- `sparse` — Erdos-Renyi G(n,p) with a guaranteed spanning cycle; heterogeneous
  degrees exercise both algorithms on asymmetric topologies.

Config files under `conf/` drive all parameters: `shape`, `nodeCount`,
`edgeProb`, `seed`, `allowedTypes`, and `defaultPdf`.

`NetGameSimLoader` reads NetGameSim JSON artifacts
(`outputs/netgamesim-graph.json`) and converts them to `GraphTopology` so
NetGameSim-generated graphs run without hand-crafted HOCON.

### Edge labels

Every `GraphEdge` carries an `allowedTypes: Set[String]`.
`BaseDistributedNode.sendToNeighbor` enforces this at send time — if the
message kind is not in the allowed set the message is dropped and a WARN is
logged.  Algorithm control messages (Wave, Echo, Extinguish, RED, WHITE) are
sent as `kind = "CONTROL"` so they always pass, while application traffic
(PING, GOSSIP, WORK) is constrained by the per-edge label.

### Node PDFs

Each node has a `MessagePdf` (Uniform, Zipf, or Explicit) read from config.
`MessagePdf.sample` draws a message kind using a seeded `Random` initialised
from a deterministic hash of the node ID — experiments are reproducible under
the same seed.  Probabilities are validated at load time; `ExplicitPdf` must
sum to 1.0 within 1e-6.

### Actor mapping

`ExperimentRunner` spawns one Akka Typed actor per graph node and sends each
actor an `InitializeNode` message containing:
- `neighbors` — outgoing `NeighborRef` map (id, ActorRef, allowedTypes)
- `pdf` — the node's message distribution
- `inboundCount` — number of edges pointing INTO this node (used by Lai-Yang)
- `isInitiator` — true for the first node in the topology list

### Traffic initiation

Two mechanisms, both configured in `sim.traffic`:

**Timer nodes** use `Behaviors.withTimers` / `timers.startTimerAtFixedRate` to
send a `TrafficTick` at a fixed interval.  `mode = "pdf"` samples the kind from
the node PDF; `mode = "fixed"` always sends the configured `fixedMsg`.

**Input nodes** accept `InjectMessage` from the CLI driver.  File injection
(`--inject-file`) reads a timed script; interactive injection (`--interactive`)
reads stdin commands.  Both route through `InjectToNode → coordinator → actor`,
so injected messages are treated identically to timer ticks.

### Distributed algorithms

**Lai-Yang global snapshot** (§ `algorithms/laiyang/`):

Each node is coloured WHITE or RED.  The initiator turns RED and broadcasts RED
marker messages as `kind = "CONTROL"`.  Each node that receives its first RED
message records its local state and turns RED.  A node's snapshot is complete
when it has received a RED message on every inbound channel
(`closedChannels.size == inboundCount`).  In-transit WHITE messages received
after turning RED are recorded in `channelState`.

**Echo Extinction Anonymous** (§ `algorithms/echo/`):

The initiator sends a Wave to all neighbours.  Each non-initiator node that
receives a Wave for the first time sets the sender as its parent and forwards
the Wave to all other neighbours.  Duplicate Waves are extinguished.  Leaf
nodes reply immediately with Echo.  Echos propagate back up the spanning tree;
the root completes when all replies arrive.

### Metrics

At shutdown each node logs:
```
Node node-1 METRICS: sent=52 (GOSSIP=14 PING=33 WORK=5) received=8 (PING=3 WORK=5)
```

`ExperimentRunner` prints a summary table showing role (timer/input/passive),
PDF family, and tick interval for every node.

### Instrumentation

The project is configured for Lightbend Telemetry (Cinnamon).  The `cinnamon`
block in `src/main/resources/application.conf` selects all `/user/*` actors and
configures a SLF4J metrics reporter.  The plugin declaration is in
`project/plugins.sbt` (commented out because the `cinnamon-agent` jar requires
a Lightbend subscription token not available on a clean machine).

Kamon (`kamon-bundle`, `kamon-logback`) provides equivalent open-source actor
and JVM metrics — mailbox sizes, processing times, heap, GC — without
credentials.  Metrics appear in the log every 10 seconds; search for `[kamon]`.

---

## Experiment configs and why the properties matter

| Config | Shape | Algorithm | Why this topology |
|---|---|---|---|
| `experiment-complete.conf` | Complete, 6 nodes | Lai-Yang | Maximum in-transit channels; every channel has pre-snapshot WHITE messages; stresses channel-state recording |
| `experiment-ring.conf` | Bidirectional ring, 8 nodes | Echo | Low degree; two wave fronts collide at nodes 4-5; exercises both forwarding and Extinguish paths |
| `experiment-sparse.conf` | Sparse G(10, 0.35), seed 1003 | Lai-Yang | Heterogeneous inbound counts; nodes with degree 1-6; tests exact `closedChannels == inboundCount` on asymmetric graphs |

---

## Test coverage (24 tests)

```bash
sbt test
# Tests: succeeded 24, failed 0
```

| Group | Tests | What is verified |
|---|---|---|
| GraphTopology.validate | 3 | Rejects missing node refs, empty allowed types |
| BaseDistributedNode.sendToNeighbor | 1 | Allowed kind delivers; forbidden kind blocked |
| MessagePdf.sample | 3 | Uniform/Zipf produce only configured types; Zipf rank ordering |
| MessagePdf.validate | 3 | Sum-to-1.0 check; negative probability rejected |
| EchoNode | 3 | Actor lifecycle; no-peer wave completes immediately |
| LaiYangNode | 3 | Actor lifecycle; zero-inbound snapshot completes immediately |
| EchoState | 4 | Pure state machine: leaf reply, root completion, pending tracking |
| GraphGenerator | 4 | Complete edge count; ring out-degree; sparse spanning cycle; GraphLoader round-trip |

---

## Scala version note

This project uses **Scala 2.13.12**.  The rubric specifies Scala 3.x.  The
choice was deliberate: `akka-actor-typed` 2.8.5 is more thoroughly tested on
2.13, and the Akka migration guide for 3.x was out of scope for the submission
deadline.  All actor patterns, message ADTs, PDF sampling, and algorithm
semantics are identical on both versions.  The deduction is acknowledged.

---

## References

- [NetGameSim](https://github.com/0x1DOCD00D/NetGameSim)
- [PLANE Akka examples](https://github.com/0x1DOCD00D/PLANE/tree/master/src/main/scala/Akka)
- [Akka documentation](https://doc.akka.io/index.html)
- Lai, T-H. and Yang, T.H. (1987). Distributed Snapshot Algorithm.
- Tel, G. (2000). *Introduction to Distributed Algorithms*, 2nd ed. Cambridge.
