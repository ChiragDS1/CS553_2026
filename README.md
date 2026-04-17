# CS553 Distributed Algorithms Simulator
**Student:** Chirag Shinde | UIN : 665236290
**Course:** CS553 — Distributed Computing Systems, Spring 2026
**Assigned Algorithms:** Echo Extinction Anonymous (Index 12) . Lai-Yang Global Snapshot (Index 23)
**Submission:** April 18, 2026

---

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Architecture](#architecture)
5. [Key Source Files](#key-source-files)
6. [Configuration](#configuration)
7. [Building the Project](#building-the-project)
8. [Generating Graphs](#generating-graphs)
9. [Running Experiments](#running-experiments)
10. [All Run Commands](#all-run-commands)
11. [Running Tests](#running-tests)
12. [Algorithms](#algorithms)
13. [Metrics & Observability](#metrics--observability)
14. [Cinnamon Instrumentation](#cinnamon-instrumentation)
15. [Design Decisions](#design-decisions)

---

## Overview

This project implements an end-to-end distributed algorithms simulator that:

1. Loads randomly generated graphs from **NetGameSim** (JSON artifacts) or generates them from config
2. Enriches the graph with **edge labels** (which message types are allowed per channel) and **node PDFs** (traffic generation probabilities)
3. Converts each graph node into an **Akka Typed actor** — one actor per node, one ActorRef per directed edge
4. Runs configurable **background traffic** driven by per-node probability distributions (Uniform, Zipf, Explicit)
5. Executes two **distributed algorithms** (Lai-Yang Snapshot + Echo Extinction Anonymous) as pluggable modules on top of the same actor runtime
6. Produces **metrics, logs, DOT/PNG visualisations, and results** as observable output

> **Note on Scala version.** The rubric targets Scala 3.x. This project uses Scala 2.13.12 because
> `akka-actor-typed` 2.8.5 has more complete support on 2.13 and the Akka 3.x migration guide was
> out of scope for the submission timeline. All actor patterns, message ADTs, and algorithm semantics
> are identical on both versions. The deduction is acknowledged.

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (Eclipse Adoptium or OpenJDK) | 17 | Required for Akka 2.8.5 compatibility |
| Scala | 2.13.12 | Project language |
| sbt | 1.9.7 | Scala Build Tool |
| Git | Any | For cloning the repo |

### Verify Java 17

```bash
java -version
# Should show: openjdk version "17.x.x"
```

### Switch to Java 17 on macOS

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH=$JAVA_HOME/bin:$PATH
java -version  # confirm 17
```

---

## Project Structure

```
CS553_2026/
├── conf/                                    # External experiment config files (CLI)
│   ├── experiment-complete.conf             # Complete graph, 6 nodes — Lai-Yang stress test
│   ├── experiment-ring.conf                 # Bidirectional ring, 8 nodes — Echo algorithm
│   ├── experiment-sparse.conf               # Sparse Erdos-Renyi, 10 nodes — Lai-Yang
│   ├── experiment-netgamesim.conf           # Traffic config for NetGameSim topology
│   └── traffic-topology.conf                # Full traffic topology + timer/input nodes
│
├── outputs/
│   └── netgamesim-graph.json                # NetGameSim graph artifact (8 nodes, 18 edges)
│
├── inject.txt                               # Sample file-driven injection script
├── echo.dot / echo.png                      # GraphViz output — Echo experiment
├── laiyang.dot / laiyang.png                # GraphViz output — Lai-Yang experiment
├── netgamesim-echo.dot / netgamesim-echo.png
├── netgamesim-laiyang.dot / netgamesim-laiyang.png
│
├── docs/
│   └── report.md                            # Design decisions + experiment results
│
├── src/
│   ├── main/
│   │   ├── resources/
│   │   │   ├── application.conf             # Akka + Kamon + Cinnamon config
│   │   │   ├── logback.xml                  # Logging configuration
│   │   │   ├── topology.conf                # Resource-based topology (classic actors)
│   │   │   └── traffic-topology.conf        # Resource-based traffic topology
│   │   └── scala/com/uic/cs553/distributed/
│   │       ├── core/
│   │       │   ├── GraphModels.scala        # GraphNode, GraphEdge, GraphTopology
│   │       │   ├── GraphLoader.scala        # HOCON topology loader + file loader
│   │       │   ├── GraphGenerator.scala     # Config-driven complete/ring/sparse generator
│   │       │   ├── TrafficModels.scala      # MessagePdf: Uniform, Zipf, Explicit
│   │       │   ├── TrafficConfig.scala      # Timer and input node config model
│   │       │   └── NetGameSimLoader.scala   # Loads NetGameSim JSON graph artifacts
│   │       ├── framework/
│   │       │   ├── DistributedNode.scala    # BaseDistributedNode, InitializeNode, NeighborRef
│   │       │   └── ExperimentRunner.scala   # Actor system lifecycle, metrics summary
│   │       ├── algorithms/
│   │       │   ├── laiyang/
│   │       │   │   ├── LaiYangNode.scala    # Lai-Yang snapshot algorithm
│   │       │   │   ├── SnapshotState.scala  # Immutable snapshot state (inboundCount)
│   │       │   │   └── SnapshotMessages.scala
│   │       │   ├── echo/
│   │       │   │   ├── EchoNode.scala       # Echo Extinction Anonymous
│   │       │   │   ├── EchoState.scala      # Immutable echo state (pendingReplies)
│   │       │   │   └── EchoMessages.scala
│   │       │   └── traffic/
│   │       │       └── PdfTrafficNode.scala # PDF traffic node with Akka timer scheduler
│   │       ├── examples/
│   │       │   ├── SimMain.scala            # Main CLI entry point
│   │       │   ├── NetGameSimExperiment.scala # NetGameSim JSON → Akka pipeline
│   │       │   ├── LaiYangExperiment.scala
│   │       │   ├── EchoExperiment.scala
│   │       │   └── PdfTrafficExperiment.scala
│   │       └── utils/
│   │           └── GraphvizLogger.scala     # DOT file output for visualisation
│   └── test/
│       └── scala/com/uic/cs553/distributed/framework/
│           └── DistributedNodeSpec.scala    # 24 ScalaTest unit + integration tests
│
├── build.sbt                                # Build definition + Kamon dependencies
├── project/
│   └── plugins.sbt                          # sbt plugins (Cinnamon commented out)
├── .gitignore
└── README.md                                # This file
```

---

## Architecture

```
NetGameSim JSON artifact  ─────OR─────  HOCON conf (sim.generator)
         │                                        │
         ▼                                        ▼
  NetGameSimLoader                         GraphGenerator
         │                                        │
         └──────────────┬─────────────────────────┘
                        ▼
                  GraphTopology          ← nodes: List[GraphNode], edges: List[GraphEdge]
                        │                  each edge: allowedTypes: Set[String]
                        ▼                  each node: pdf: MessagePdf
               ExperimentRunner
                        │
                        ▼
              ActorSystem "DistributedSystem"
  ┌────────────────────────────────────────────────┐
  │  node-1 ──CONTROL──▶ node-2                    │
  │  node-3 ──CONTROL──▶ node-5                    │
  │  ... (one Akka Typed actor per node)           │
  └────────────────────────────────────────────────┘
                        │
                        ▼
            Algorithm nodes (Lai-Yang / Echo)
            + PdfTrafficNode (timer + injection)
                        │
                        ▼
              GraphvizLogger → .dot / .png
              METRICS logged at shutdown
```

### Key Design Principles

- **One actor per node** — `ExperimentRunner` spawns one `ActorRef` per `GraphNode`. Each actor owns independent algorithm state.
- **Edge labels enforced at send time** — `BaseDistributedNode.sendToNeighbor()` checks `allowedTypes` before every send. Forbidden messages are dropped and a WARN is logged.
- **Algorithm messages always pass** — Wave, Echo, Extinguish, RED, WHITE are sent as `kind = "CONTROL"` so they pass every edge regardless of application-level labels.
- **Config-driven** — all parameters (graph shape, node count, seed, PDFs, timers, duration, algorithm) come from `conf/` files. Nothing is hardcoded in source.
- **isInitiator from config** — the first node in the topology list starts the algorithm. Replaces any hardcoded `nodeId == "node-1"` check.
- **inboundCount explicit** — Lai-Yang completion uses the number of edges pointing INTO a node (passed at init), not outgoing neighbor count. Critical for asymmetric topologies.

---

## Key Source Files

### `DistributedNode.scala`
Base framework. Defines `InitializeNode` (neighbors, pdf, inboundCount, isInitiator), `sendToNeighbor` (edge-label enforcement), `getIsInitiator`, `getInboundCount`. All algorithm nodes extend `BaseDistributedNode`.

### `LaiYangNode.scala`
Lai-Yang global snapshot. WHITE/RED coloring. WHITE and RED marker messages sent as `kind = "CONTROL"`. Completion check: `closedChannels.size == state.inboundCount` (inbound, not outgoing). Uses immutable `SnapshotState` case class.

### `EchoNode.scala`
Echo Extinction Anonymous. Wave, Echo, Extinguish all sent as `kind = "CONTROL"`. Duplicate waves are extinguished. Root completes when all replies arrive. Uses immutable `EchoState` case class.

### `GraphGenerator.scala`
Generates `complete`, `ring`, or `sparse` (Erdős-Rényi G(n,p) + spanning cycle) topologies from config. Seed-controlled for reproducibility.

### `NetGameSimLoader.scala`
Reads NetGameSim JSON graph artifacts into `GraphTopology`. Converts integer node IDs to `node-N` strings. Assigns default `UniformPdf` and `allowedTypes` to all nodes/edges.

### `ExperimentRunner.scala`
Creates actor system, spawns one actor per node, sends `InitializeNode` with correct `inboundCount` (from `incomingEdgesByNode`) and `isInitiator` (first node in list), runs for configured duration, prints metrics summary.

### `PdfTrafficNode.scala`
PDF traffic node. Uses `Behaviors.withTimers` / `timers.startTimerAtFixedRate` — no `Thread.sleep` inside actors. Supports `mode = "pdf"` (sampled) and `mode = "fixed"`. Accepts `InjectMessage` from the coordinator.

---

## Configuration

All simulation parameters live in `conf/` files. Example `conf/traffic-topology.conf`:

```hocon
sim.graph {
  nodes = [
    { id = "node-1", pdf { family = "explicit", weights { PING = 0.60, GOSSIP = 0.30, WORK = 0.10 } } },
    { id = "node-2", pdf { family = "uniform",  messageTypes = ["PING", "GOSSIP", "WORK"] } },
    { id = "node-3", pdf { family = "zipf",     messageTypes = ["PING", "GOSSIP", "WORK"], exponent = 1.2 } }
  ]
  edges = [
    { from = "node-1", to = "node-2", allow = ["PING", "GOSSIP", "WORK"] },
    { from = "node-2", to = "node-3", allow = ["PING", "WORK"] }
  ]
}

sim.traffic {
  seed = 42
  timers = [
    { node = "node-1", tickEveryMs = 200, mode = "pdf" },
    { node = "node-4", tickEveryMs = 400, mode = "fixed", fixedMsg = "WORK" }
  ]
  inputs = [
    { node = "node-2" },
    { node = "node-3" }
  ]
}
```

For generator-based topologies (`conf/experiment-sparse.conf`):

```hocon
sim.generator {
  shape     = "sparse"
  nodeCount = 10
  edgeProb  = 0.35
  seed      = 1003
  defaultPdf { family = "explicit", weights { CONTROL = 0.2, PING = 0.6, WORK = 0.2 } }
  defaultAllowedTypes = ["CONTROL", "PING", "GOSSIP", "WORK"]
}
```

---

## Building the Project

### 1. Clone

```bash
git clone https://github.com/ChiragDS1/CS553_2026.git
cd CS553_2026
```

### 2. Compile

```bash
sbt clean compile
```

Expected: `[success] Total time: ...`

### 3. Run tests

```bash
sbt test
```

Expected: `Tests: succeeded 24, failed 0`

---

## Generating Graphs

Three topology shapes are supported by `GraphGenerator`:

| Shape | Config key | Description |
|-------|-----------|-------------|
| `complete` | `sim.generator.shape = "complete"` | All n·(n−1) directed edges |
| `ring` | `sim.generator.shape = "ring"` | Bidirectional cycle |
| `sparse` | `sim.generator.shape = "sparse"` | Erdős-Rényi G(n,p) + spanning cycle |

NetGameSim graphs are loaded from `outputs/netgamesim-graph.json` via `NetGameSimLoader`. The artifact is pre-generated and included in the repo (8 nodes, 18 directed edges).

To use a different NetGameSim artifact:
1. Run NetGameSim from `https://github.com/0x1DOCD00D/NetGameSim`
2. Export the graph as JSON
3. Place it in `outputs/` and pass `--graph outputs/your-graph.json`

---

## Running Experiments

### Experiment 1 — Lai-Yang snapshot, complete graph (6 nodes)

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/experiment-complete.conf --algorithm laiyang --duration 20"
```

**Expected output:**
- 6 actors initialized, each `inboundCount=5`
- All 6 nodes log `snapshot COMPLETE. snapshotId=snapshot-X` with the same ID
- `channelState=none` (fast JVM delivery, no in-transit WHITE captured)
- DOT file written to `laiyang.dot`

### Experiment 2 — Echo Extinction, bidirectional ring (8 nodes)

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/experiment-ring.conf --algorithm echo --duration 20"
```

**Expected output:**
- Wave propagates both clockwise and counter-clockwise
- Nodes 4 and 5 log `received duplicate Wave → sent Extinguish` (two-front collision)
- Root logs `Node node-1 (root) wave wave-1 COMPLETE — all nodes reached`
- DOT file written to `echo.dot`

### Experiment 3 — Lai-Yang snapshot, sparse random graph (10 nodes)

```bash
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/experiment-sparse.conf --algorithm laiyang --duration 20"
```

**Expected output:**
- Heterogeneous inbound counts (node-5: 6 inbound, node-7: 5 inbound, node-4: 1 inbound)
- All 10 nodes complete `snapshot-5` with exact `closedChannels == inboundCount`
- No overruns on asymmetric topology

### Experiment 4 — PDF traffic with timer and input nodes

```bash
sbt "runMain com.uic.cs553.distributed.examples.PdfTrafficExperiment"
```

**Expected output:**
- `node-1 timer explicit 200ms pdf`, `node-4 timer explicit 400ms fixed`
- `node-2 input uniform`, `node-3 input zipf`
- Per-node `METRICS:` lines at shutdown with kind breakdown

### Experiment 5 — NetGameSim graph, Lai-Yang snapshot

```bash
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment --graph outputs/netgamesim-graph.json --algorithm laiyang --duration 15"
```

**Expected output:**
- `Loaded topology: nodes=8 edges=18 avgOutDegree=2.25`
- All 8 nodes complete `snapshot-4`
- DOT file written to `netgamesim-laiyang.dot`

### Experiment 6 — NetGameSim graph, Echo Extinction

```bash
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment --graph outputs/netgamesim-graph.json --algorithm echo --duration 15"
```

**Note:** The NetGameSim graph has directed edges. Echo requires bidirectional edges for reply paths. Wave propagates correctly but the root does not complete because some reply paths do not exist in the directed graph. This is expected topology behavior, not a code bug. See `docs/report.md` for full discussion.

### Experiment Summary

| # | Topology | Algorithm | Result |
|---|----------|-----------|--------|
| 1 | Complete, 6 nodes | Lai-Yang | All 6 nodes complete snapshot ✅ |
| 2 | Ring, 8 nodes | Echo | Root completes, Extinguish exercised ✅ |
| 3 | Sparse G(10,0.35), seed 1003 | Lai-Yang | All 10 nodes, asymmetric inbound counts ✅ |
| 4 | Traffic topology, 5 nodes | PDF Traffic | Timer + input nodes, METRICS logged ✅ |
| 5 | NetGameSim JSON, 8 nodes | Lai-Yang | All 8 nodes complete snapshot ✅ |
| 6 | NetGameSim JSON, 8 nodes | Echo | Wave propagates, directed graph noted ✅ |

---

## All Run Commands

```bash
# Run all tests (must pass before any experiment)
sbt test

# Lai-Yang — complete graph
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/experiment-complete.conf --algorithm laiyang --duration 20"

# Echo — bidirectional ring
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/experiment-ring.conf --algorithm echo --duration 20"

# Lai-Yang — sparse graph
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/experiment-sparse.conf --algorithm laiyang --duration 20"

# PDF traffic (resource-based)
sbt "runMain com.uic.cs553.distributed.examples.PdfTrafficExperiment"

# PDF traffic — file-driven injection
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/traffic-topology.conf --algorithm traffic --duration 15 --inject-file inject.txt"

# PDF traffic — interactive stdin injection
sbt "runMain com.uic.cs553.distributed.examples.SimMain --config conf/traffic-topology.conf --algorithm traffic --duration 60 --interactive"
# Then type: inject node-2 PING hello
#            inject node-3 WORK task-1
#            quit

# NetGameSim — Lai-Yang
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment --graph outputs/netgamesim-graph.json --algorithm laiyang --duration 15"

# NetGameSim — Echo
sbt "runMain com.uic.cs553.distributed.examples.NetGameSimExperiment --graph outputs/netgamesim-graph.json --algorithm echo --duration 15"
```

### Injection file format (`inject.txt`)

```
# Format: <delayMs> <nodeId> <kind> <payload>
500  node-2  PING    hello-from-driver
750  node-3  WORK    process-item-1
500  node-2  GOSSIP  rumour-42
1000 node-3  WORK    process-item-2
500  node-2  PING    goodbye-from-driver
```

---

## Running Tests

```bash
sbt test
# Tests: succeeded 24, failed 0
```

### Test coverage (24 tests)

| Test Group | Tests | What is verified |
|------------|-------|-----------------|
| `GraphTopology.validate` | 3 | Missing node refs rejected; empty allowed types rejected |
| `BaseDistributedNode.sendToNeighbor` | 1 | Allowed kind delivers; forbidden kind blocked and WARN logged |
| `MessagePdf.sample` | 3 | Uniform/Zipf produce only configured types; Zipf rank ordering correct |
| `MessagePdf.validate` | 3 | Sum-to-1.0 check; negative probability rejected |
| `EchoNode` | 3 | Actor lifecycle; no-peer wave completes immediately with `isInitiator=true` |
| `LaiYangNode` | 3 | Actor lifecycle; zero-inbound snapshot completes immediately |
| `EchoState` | 4 | Pure state machine: leaf reply, root completion, pending-set tracking |
| `GraphGenerator` | 4 | Complete edge count; ring out-degree 1; sparse spanning cycle; GraphLoader round-trip |

---

## Algorithms

### Lai-Yang Global Snapshot

**System assumptions:**
- Asynchronous message passing
- Non-FIFO channels (stronger than Chandy-Lamport)
- Messages carry color of sender at send time
- No process failures during snapshot
- General directed graph topology

**Protocol (RED/WHITE coloring):**
1. Initiator turns RED, records local state, broadcasts RED markers as `kind = "CONTROL"` to all neighbors
2. WHITE node receiving first RED marker: turns RED, records state, broadcasts RED to all neighbors
3. WHITE messages arriving at a RED node from a still-WHITE sender are captured as in-transit channel state
4. Node's snapshot is complete when `closedChannels.size == inboundCount` (RED received on every inbound channel)

**Key correctness property:** No message is counted both in a sender's local state and a receiver's channel state. The RED marker overtakes all WHITE messages the sender had already sent.

**Critical implementation note:** Completion uses `inboundCount` (edges pointing IN) not `peerMap.size` (outgoing neighbors). These differ on asymmetric graphs — using the wrong one causes nodes to complete too early or never complete.

### Echo Extinction Anonymous

**System assumptions:**
- Asynchronous message passing
- Reliable bidirectional channels
- General undirected graph topology
- Anonymous (no node IDs used in algorithm decision logic)

**Protocol:**
1. Initiator sends Wave to all neighbours (as `kind = "CONTROL"`)
2. Node receiving Wave for the first time: records sender as parent, forwards Wave to all other neighbours
3. Duplicate Waves are extinguished — node sends Extinguish reply to duplicate sender
4. Leaf nodes (no children) send Echo to parent immediately
5. Node collects Echo or Extinguish from all children, then sends Echo to parent
6. Root completes when all replies arrive

---

## Metrics & Observability

Every experiment prints a final metrics report at shutdown:

```
=== Traffic Metrics Summary ===
Node       Role         PDF family   Tick(ms)   Mode
--------------------------------------------------------
node-1     timer        explicit     200        pdf
node-2     input        uniform      -          -
node-3     input        zipf         -          -
node-4     timer        explicit     400        fixed
node-5     passive      uniform      -          -

Per-node sent/received counts: search logs for 'METRICS'
```

Each node logs at shutdown:
```
Node node-1 METRICS: sent=52 (GOSSIP=14 PING=33 WORK=5) received=8 (PING=3 WORK=5)
```

Algorithm execution produces DOT files visualising message flows:
- `laiyang.dot` / `laiyang.png` — WHITE pre-snapshot traffic (blue), RED cascade (red), closed channels (dashed)
- `echo.dot` / `echo.png` — Wave (blue), Extinguish (orange), Echo (red), closed channels (dashed)

Render with: `dot -Tpng laiyang.dot -o laiyang.png`

**Logging levels:**
- `INFO` — node init, algorithm milestones, timer scheduling, injection events, METRICS summary
- `WARN` — blocked messages (edge label violation), unknown neighbor references

---

## Cinnamon Instrumentation

The Lightbend Cinnamon plugin (v2.21.4) is declared in `project/plugins.sbt` (commented out — requires a Lightbend subscription token):

```scala
// addSbtPlugin("com.lightbend.cinnamon" % "sbt-cinnamon" % "2.21.4")
```

The actor instrumentation configuration is in `src/main/resources/application.conf`:

```hocon
cinnamon {
  akka.actors {
    default-by-class {
      includes = "/user/*"
      report-by = class
    }
  }
  chmetrics {
    reporters += "metrics-slf4j-reporter"
  }
  metrics-slf4j-reporter {
    interval = 10 seconds
  }
}
```

Equivalent open-source instrumentation is provided by **Kamon** (`kamon-bundle`, `kamon-logback`) which resolves from Maven Central without credentials and instruments actor mailboxes, processing times, JVM heap, and GC pauses. To activate full Cinnamon: obtain a token from `https://akka.io/token`, add credentials to `~/.sbt/1.0/credentials.sbt`, and uncomment the plugin line.

---

## Design Decisions

### Why `isInitiator` from config instead of hardcoded node ID?
The original implementation checked `nodeId == "node-1"` in both algorithm nodes, making any topology with a different first node impossible to initiate. `isInitiator: Boolean` is now passed via `InitializeNode` — set to `true` for the first node in the topology list by `ExperimentRunner`. Tests set it directly, making them topology-independent.

### Why `inboundCount` passed explicitly?
A node can only see its own outgoing neighbors via `getNeighbors`. It cannot observe how many nodes send TO it. `ExperimentRunner` computes `topology.incomingEdgesByNode(id).size` and passes it as `inboundCount` in `InitializeNode`. Lai-Yang uses this as the snapshot completion denominator. Using `peerMap.size` (outgoing) caused nodes with in-degree < out-degree to complete too early and nodes with in-degree > out-degree to never complete.

### Why `kind = "CONTROL"` for all algorithm messages?
Algorithm protocol messages (Wave, Echo, Extinguish, RED, WHITE) must propagate regardless of application-level edge label restrictions. Sending them as `CONTROL` ensures they always pass `sendToNeighbor`'s allowed-type check without requiring every experiment config to list algorithm message types in `allowedTypes`.

### Why `var` for algorithm state?
`LaiYangNode` and `EchoNode` extend `BaseDistributedNode`, not a direct `Behaviors.setup` closure. They cannot use `context.become` to pass state as function parameters. The compromise is one `private var state` holding an immutable case class, with all transitions via `.copy()`. Each `var` carries a `// var justified:` comment per the rubric requirement. Akka's single-threaded actor dispatch eliminates any concurrent access concern.

### Why no blocking inside actors?
`PdfTrafficNode` uses `Behaviors.withTimers` / `timers.startTimerAtFixedRate` for periodic ticks — no `Thread.sleep`. `Thread.sleep` and `Await.result` appear only in `ExperimentRunner`, which runs on the main application thread outside any actor, to manage the overall experiment duration.

### Reproducibility
All experiments use fixed seeds in their `conf/` files (`seed = 42`, `seed = 1003`, etc.). `MessagePdf.deterministicSeedForNode(nodeId)` seeds each node's RNG from a MurmurHash3 of its ID, ensuring identical message sequences across runs with the same config.

---

## Versions

| Component | Version |
|-----------|---------|
| Scala | 2.13.12 |
| Akka Typed | 2.8.5 |
| sbt | 1.9.7 |
| Java | 17 (required) |
| ScalaTest | 3.2.17 |
| Logback | 1.4.11 |
| Kamon bundle | 2.7.5 |
| Typesafe Config | 1.4.3 |
| Cinnamon plugin | 2.21.4 (configured, token required) |
| NetGameSim | JSON artifact in `outputs/netgamesim-graph.json` |

---

## References

- [NetGameSim](https://github.com/0x1DOCD00D/NetGameSim)
- [PLANE Akka examples](https://github.com/0x1DOCD00D/PLANE/tree/master/src/main/scala/Akka)
- [Akka documentation](https://doc.akka.io/index.html)
- Lai, T-H. and Yang, T.H. (1987). Distributed Snapshot Algorithm.
- Tel, G. (2000). *Introduction to Distributed Algorithms*, 2nd ed. Cambridge.
