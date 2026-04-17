package com.uic.cs553.distributed.core

import java.io.File
import scala.util.{Try, Using}
import scala.io.Source

/**
 * Loads a GraphTopology from a NetGameSim-generated graph artifact.
 *
 * NetGameSim (https://github.com/0x1DOCD00D/NetGameSim) exports graphs in a
 * simple JSON format.  This loader reads that format and converts it into the
 * project's GraphTopology model so that NetGameSim-generated topologies can
 * be used directly in experiments without hand-crafting HOCON configs.
 *
 * NetGameSim JSON schema (simplified):
 * {
 *   "nodes": [
 *     { "id": 0, "children": [1, 3], "props": { ... } },
 *     ...
 *   ],
 *   "edges": [
 *     { "from": 0, "to": 1, "weight": 0.5 },
 *     ...
 *   ]
 * }
 *
 * Mapping decisions:
 *   - Every NetGameSim node becomes a GraphNode with a UniformPdf over
 *     the default allowed types (CONTROL, PING, GOSSIP, WORK).
 *   - Every NetGameSim edge becomes a GraphEdge; all four message types
 *     are allowed by default so CONTROL (algorithm messages) always passes.
 *   - Node IDs are prefixed with "node-" to match the naming convention
 *     used by the rest of the simulator.
 *   - An optional allowedTypes override list can be supplied to restrict
 *     application-level traffic on edges while keeping CONTROL permitted.
 *
 * Usage:
 *   NetGameSimLoader.loadFromFile("outputs/netgamesim-graph.json") match {
 *     case Right(topology) => // use topology
 *     case Left(errors)    => // handle errors
 *   }
 */
object NetGameSimLoader {

  /** Default message types assigned to every edge when none are specified. */
  val DefaultAllowedTypes: Set[String] = Set("CONTROL", "PING", "GOSSIP", "WORK")

  /** Default PDF assigned to every node. */
  val DefaultPdf: MessagePdf = UniformPdf(DefaultAllowedTypes.toList.sorted)

  /**
   * Loads a NetGameSim graph from a JSON file on disk.
   *
   * Returns Right(topology) on success or Left(errors) if the file is
   * missing, cannot be parsed, or fails validation.
   */
  def loadFromFile(
      jsonPath: String,
      allowedTypes: Set[String] = DefaultAllowedTypes,
      defaultPdf: MessagePdf   = DefaultPdf
  ): Either[List[String], GraphTopology] = {
    val file = new File(jsonPath)
    if (!file.exists())
      return Left(List(s"NetGameSim graph file not found: $jsonPath"))

    Using(Source.fromFile(file)) { src =>
      val json = src.mkString
      parseNetGameSimJson(json, allowedTypes, defaultPdf)
    }.getOrElse(Left(List(s"Failed to read file: $jsonPath")))
  }

  /**
   * Parses a NetGameSim JSON string into a GraphTopology.
   *
   * Uses a lightweight hand-rolled parser to avoid adding a JSON library
   * dependency.  The format is simple enough that regex extraction works
   * reliably for the fields we need.
   */
  def parseNetGameSimJson(
      json: String,
      allowedTypes: Set[String] = DefaultAllowedTypes,
      defaultPdf: MessagePdf   = DefaultPdf
  ): Either[List[String], GraphTopology] = {

    Try {
      // Extract node IDs from "id": <number> entries inside node objects.
      val nodeIdPattern  = """"id"\s*:\s*(\d+)""".r
      val edgeFromPattern = """"from"\s*:\s*(\d+)""".r
      val edgeToPattern   = """"to"\s*:\s*(\d+)""".r

      // Split into nodes section and edges section by looking for the arrays.
      val nodesSection = extractJsonArray(json, "nodes")
      val edgesSection = extractJsonArray(json, "edges")

      val nodeIds: List[Int] =
        if (nodesSection.nonEmpty)
          nodeIdPattern.findAllMatchIn(nodesSection).map(_.group(1).toInt).toList.distinct.sorted
        else Nil

      // If no nodes array, try to infer nodes from edge endpoints.
      val edgePairs: List[(Int, Int)] = {
        val froms = edgeFromPattern.findAllMatchIn(edgesSection).map(_.group(1).toInt).toList
        val tos   = edgeToPattern.findAllMatchIn(edgesSection).map(_.group(1).toInt).toList
        froms.zip(tos)
      }

      val allNodeIds: List[Int] =
        if (nodeIds.nonEmpty) nodeIds
        else (edgePairs.flatMap { case (f, t) => List(f, t) }).distinct.sorted

      if (allNodeIds.isEmpty)
        return Left(List("NetGameSim JSON contains no nodes or edges"))

      val nodes: List[GraphNode] = allNodeIds.map { id =>
        GraphNode(id = s"node-$id", pdf = defaultPdf)
      }

      val edges: List[GraphEdge] = edgePairs.map { case (from, to) =>
        GraphEdge(
          from         = s"node-$from",
          to           = s"node-$to",
          allowedTypes = allowedTypes
        )
      }.distinctBy(e => (e.from, e.to))

      GraphTopology(nodes, edges)

    }.toEither
      .left.map(e => List(s"JSON parse error: ${e.getMessage}"))
      .flatMap(_.validate())
  }

  /**
   * Converts a GraphTopology back to a minimal NetGameSim-compatible JSON
   * string.  Useful for round-trip testing and for saving generated
   * topologies in a format NetGameSim can visualise.
   */
  def topologyToJson(topology: GraphTopology): String = {
    val nodeNums = topology.nodes.map { n =>
      val id = n.id.stripPrefix("node-")
      s"""  {"id": $id}"""
    }.mkString(",\n")

    val edgeNums = topology.edges.map { e =>
      val from = e.from.stripPrefix("node-")
      val to   = e.to.stripPrefix("node-")
      s"""  {"from": $from, "to": $to}"""
    }.mkString(",\n")

    s"""{
       |  "nodes": [
       |$nodeNums
       |  ],
       |  "edges": [
       |$edgeNums
       |  ]
       |}""".stripMargin
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  /**
   * Extracts the raw string content of a named top-level JSON array.
   * Returns empty string if the key is not found.
   */
  private def extractJsonArray(json: String, key: String): String = {
    val pattern = s""""$key"\\s*:\\s*\\[""".r
    pattern.findFirstMatchIn(json) match {
      case None => ""
      case Some(m) =>
        val start = m.end
        var depth = 1
        var i     = start
        while (i < json.length && depth > 0) {
          json(i) match {
            case '[' => depth += 1
            case ']' => depth -= 1
            case _   =>
          }
          i += 1
        }
        json.substring(start, i - 1)
    }
  }
}
