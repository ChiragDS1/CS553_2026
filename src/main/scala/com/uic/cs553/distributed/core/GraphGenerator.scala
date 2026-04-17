package com.uic.cs553.distributed.core

import scala.util.Random
import scala.jdk.CollectionConverters._
import com.typesafe.config.{Config, ConfigFactory}

/**
 * GraphGenerator produces GraphTopology instances from configuration parameters
 * rather than hardcoded Scala calls.  It supports three structural shapes:
 *
 *   complete  – every node has a directed edge to every other node
 *   ring      – each node connects only to its successor (circular)
 *   sparse    – random Erdos–Renyi directed graph with a configurable edge
 *               probability; edges are added until the graph is strongly
 *               connected so distributed algorithms have something to do
 *
 * Configuration schema (under sim.generator):
 *
 *   sim.generator {
 *     shape        = "complete"   # complete | ring | sparse
 *     nodeCount    = 6
 *     seed         = 42           # optional; omit for random seed
 *     edgeProb     = 0.40         # sparse only
 *     allowedTypes = ["CONTROL", "PING", "GOSSIP"]
 *     defaultPdf {
 *       family       = "uniform"
 *       messageTypes = ["CONTROL", "PING"]
 *     }
 *   }
 *
 * The generator is separate from GraphLoader so that experiments can either
 * supply a hand-crafted topology.conf (full node/edge list) or delegate
 * structure to the generator block.  GraphLoader is tried first; if the
 * config has no sim.graph.nodes the generator block is used instead.
 */
object GraphGenerator {

  // Entry point called by SimMain and experiment runners when no explicit
  // node list is found in the config file.
  def fromConfig(config: Config): Either[List[String], GraphTopology] = {
    val genCfg =
      if (config.hasPath("sim.generator")) config.getConfig("sim.generator")
      else return Left(List("No sim.generator block found in config"))

    val shape     = if (genCfg.hasPath("shape"))     genCfg.getString("shape")  else "complete"
    val nodeCount = if (genCfg.hasPath("nodeCount")) genCfg.getInt("nodeCount") else 5
    val seed      = if (genCfg.hasPath("seed"))      Some(genCfg.getLong("seed")) else None
    val edgeProb  = if (genCfg.hasPath("edgeProb"))  genCfg.getDouble("edgeProb") else 0.4

    val allowedTypes: Set[String] =
      if (genCfg.hasPath("allowedTypes"))
        genCfg.getStringList("allowedTypes").asScala.toSet
      else
        Set("CONTROL")

    val defaultPdf: MessagePdf =
      if (genCfg.hasPath("defaultPdf")) parsePdf(genCfg.getConfig("defaultPdf"))
      else UniformPdf(allowedTypes.toList.sorted)

    if (nodeCount < 1) return Left(List(s"nodeCount must be >= 1, got $nodeCount"))

    val topology = shape.toLowerCase match {
      case "complete" => GraphTopology.complete(nodeCount, allowedTypes, defaultPdf)
      case "ring"     => GraphTopology.ring(nodeCount, allowedTypes, defaultPdf)
      case "sparse"   =>
        val rng = seed.fold(new Random)(s => new Random(s))
        sparse(nodeCount, edgeProb, allowedTypes, defaultPdf, rng)
      case other =>
        return Left(List(s"Unknown generator shape: '$other'. Use complete, ring, or sparse."))
    }

    topology.validate()
  }

  /**
   * Generates a random directed graph using the Erdos–Renyi G(n,p) model and
   * then ensures strong connectivity by stitching a directed spanning cycle
   * through any unreachable nodes.
   *
   * This is the shape used for the sparse-graph experiment.  A sparse graph
   * exercises the Lai–Yang channel-state recording more thoroughly than a
   * clique because many channels have no in-transit messages, and it exercises
   * the Echo algorithm's Extinguish path because duplicate wave arrivals are
   * rarer on a clique.
   */
  def sparse(
      nodeCount: Int,
      edgeProb: Double,
      allowedTypes: Set[String],
      defaultPdf: MessagePdf,
      rng: Random
  ): GraphTopology = {

    val ids   = (1 to nodeCount).map(i => s"node-$i").toList
    val nodes = ids.map(id => GraphNode(id, defaultPdf))

    // Phase 1: random directed edges by Erdos–Renyi probability
    val randomEdges = ids.flatMap { from =>
      ids.filterNot(_ == from).flatMap { to =>
        if (rng.nextDouble() < edgeProb) List(GraphEdge(from, to, allowedTypes))
        else Nil
      }
    }

    // Phase 2: ensure every node can be reached from node-1 by adding a
    // directed spanning cycle: node-1 -> node-2 -> ... -> node-n -> node-1.
    // Edges that already exist are not duplicated.
    val existingPairs = randomEdges.map(e => (e.from, e.to)).toSet
    val cycleEdges = ids.zip(ids.drop(1) :+ ids.head).flatMap {
      case (from, to) =>
        if (existingPairs.contains((from, to))) Nil
        else List(GraphEdge(from, to, allowedTypes))
    }

    GraphTopology(nodes, randomEdges ++ cycleEdges)
  }

  // ------------------------------------------------------------------
  // Config helpers (mirrors GraphLoader.parsePdf, kept local to avoid
  // adding a dependency between the two objects)
  // ------------------------------------------------------------------

  private def parsePdf(pdfCfg: Config): MessagePdf = {
    val family =
      if (pdfCfg.hasPath("family")) pdfCfg.getString("family").toLowerCase
      else "uniform"

    family match {
      case "uniform" =>
        val types = pdfCfg.getStringList("messageTypes").asScala.toList
        UniformPdf(types)

      case "zipf" =>
        val types    = pdfCfg.getStringList("messageTypes").asScala.toList
        val exponent = pdfCfg.getDouble("exponent")
        ZipfPdf(types, exponent)

      case "explicit" =>
        val wcfg    = pdfCfg.getConfig("weights")
        val weights = wcfg.entrySet().asScala.map(e => e.getKey -> wcfg.getDouble(e.getKey)).toMap
        ExplicitPdf(weights)

      case other =>
        throw new IllegalArgumentException(s"Unsupported pdf family in generator config: $other")
    }
  }
}