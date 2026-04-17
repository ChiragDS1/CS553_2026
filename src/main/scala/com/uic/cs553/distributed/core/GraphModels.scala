package com.uic.cs553.distributed.core

/**
 * Core graph data model.
 *
 * GraphNode   – a vertex with an id and a probability distribution describing
 *               the application messages it produces.
 * GraphEdge   – a directed edge with a set of message types permitted on that
 *               channel; sending a forbidden type is blocked and logged.
 * GraphTopology – the assembled graph plus query helpers and a validator.
 *
 * Topologies can come from two sources:
 *   1. Hand-crafted HOCON files loaded by GraphLoader (full node/edge lists).
 *   2. Parameterised generation by GraphGenerator (shape + nodeCount + seed).
 *
 * The companion object keeps the programmatic builders (complete, ring) so
 * tests can construct minimal topologies without config files.
 */
final case class GraphNode(
    id: String,
    pdf: MessagePdf = UniformPdf(List("CONTROL"))
)

final case class GraphEdge(
    from: String,
    to: String,
    allowedTypes: Set[String]
)

final case class GraphTopology(
    nodes: List[GraphNode],
    edges: List[GraphEdge]
) {

  lazy val nodeIds: Set[String] = nodes.map(_.id).toSet

  lazy val nodeMap: Map[String, GraphNode] =
    nodes.map(n => n.id -> n).toMap

  lazy val outgoingEdgesByNode: Map[String, List[GraphEdge]] =
    edges.groupBy(_.from).withDefaultValue(Nil)

  lazy val incomingEdgesByNode: Map[String, List[GraphEdge]] =
    edges.groupBy(_.to).withDefaultValue(Nil)

  def outgoingNeighbors(nodeId: String): List[String] =
    outgoingEdgesByNode(nodeId).map(_.to)

  def incomingNeighbors(nodeId: String): List[String] =
    incomingEdgesByNode(nodeId).map(_.from)

  def edge(from: String, to: String): Option[GraphEdge] =
    edges.find(e => e.from == from && e.to == to)

  /** Returns a human-readable summary used in experiment log headers. */
  def summary: String =
    s"nodes=${nodes.size} edges=${edges.size} " +
      s"avgOutDegree=${"%.2f".format(edges.size.toDouble / nodes.size.max(1))}"

  def validate(): Either[List[String], GraphTopology] = {
    val nodeIdSet = nodeIds

    val duplicateNodes =
      nodes.groupBy(_.id).collect {
        case (id, xs) if xs.size > 1 => s"Duplicate node id: $id"
      }.toList

    val missingEdgeEndpoints =
      edges.flatMap { e =>
        List(
          Option.when(!nodeIdSet.contains(e.from))(s"Edge references missing source node: ${e.from}"),
          Option.when(!nodeIdSet.contains(e.to))(s"Edge references missing target node: ${e.to}")
        ).flatten
      }

    val emptyAllowedTypes =
      edges.collect {
        case e if e.allowedTypes.isEmpty =>
          s"Edge ${e.from} -> ${e.to} has no allowed message types"
      }

    val pdfErrors =
      nodes.flatMap { n =>
        MessagePdf.validate(n.pdf).left.toOption.toList.flatten
          .map(err => s"Node ${n.id}: $err")
      }

    val allErrors = duplicateNodes ++ missingEdgeEndpoints ++ emptyAllowedTypes ++ pdfErrors

    if (allErrors.isEmpty) Right(this) else Left(allErrors)
  }
}

object GraphTopology {

  /**
   * Fully-connected directed graph.
   * Every node has an outgoing edge to every other node.
   * Good for testing algorithms on dense topologies.
   */
  def complete(
      nodeCount: Int,
      defaultAllowedTypes: Set[String] = Set("CONTROL"),
      defaultPdf: MessagePdf = UniformPdf(List("CONTROL"))
  ): GraphTopology = {
    val ids   = (1 to nodeCount).map(i => s"node-$i").toList
    val nodes = ids.map(id => GraphNode(id, defaultPdf))
    val edges = ids.flatMap { from =>
      ids.filterNot(_ == from).map { to =>
        GraphEdge(from, to, defaultAllowedTypes)
      }
    }
    GraphTopology(nodes, edges)
  }

  /**
   * Directed ring: node-1 -> node-2 -> ... -> node-n -> node-1.
   * Low degree (out-degree = 1) exercises algorithms on sparse, cyclic paths.
   * Particularly useful for Lai–Yang because most channels have no in-transit
   * messages, so the channel-state recording of white messages is the focus.
   */
  def ring(
      nodeCount: Int,
      defaultAllowedTypes: Set[String] = Set("CONTROL"),
      defaultPdf: MessagePdf = UniformPdf(List("CONTROL"))
  ): GraphTopology = {
    val ids   = (1 to nodeCount).map(i => s"node-$i").toList
    val nodes = ids.map(id => GraphNode(id, defaultPdf))
    val edges = ids.zip(ids.drop(1) :+ ids.head).map {
      case (from, to) => GraphEdge(from, to, defaultAllowedTypes)
    }
    GraphTopology(nodes, edges)
  }
}