package com.uic.cs553.distributed.core

import java.io.File

import scala.jdk.CollectionConverters._

import com.typesafe.config.{Config, ConfigFactory}

object GraphLoader {

  def loadFromFile(path: String): Either[List[String], GraphTopology] = {
    val config = ConfigFactory.parseFile(new File(path)).resolve()
    loadFromConfig(config)
  }

  def loadFromResource(resourceName: String): Either[List[String], GraphTopology] = {
    val config = ConfigFactory.load(resourceName)
    loadFromConfig(config)
  }

  def loadDefault(): Either[List[String], GraphTopology] = {
    val config = ConfigFactory.load()
    loadFromConfig(config)
  }

  def loadFromConfig(config: Config): Either[List[String], GraphTopology] = {
    val root =
      if (config.hasPath("sim.graph")) config.getConfig("sim.graph")
      else config

    val nodes =
      if (root.hasPath("nodes")) {
        root.getConfigList("nodes").asScala.toList.map(parseNode)
      } else {
        Nil
      }

    val edges =
      if (root.hasPath("edges")) {
        root.getConfigList("edges").asScala.toList.map(parseEdge)
      } else {
        Nil
      }

    val topology = GraphTopology(nodes, edges)
    topology.validate()
  }

  private def parseNode(nodeCfg: Config): GraphNode = {
    val id = nodeCfg.getString("id")

    val pdf =
      if (nodeCfg.hasPath("pdf")) parsePdf(nodeCfg.getConfig("pdf"))
      else UniformPdf(List("CONTROL"))

    GraphNode(id = id, pdf = pdf)
  }

  private def parseEdge(edgeCfg: Config): GraphEdge = {
    val from = edgeCfg.getString("from")
    val to = edgeCfg.getString("to")
    val allowedTypes =
      if (edgeCfg.hasPath("allow")) edgeCfg.getStringList("allow").asScala.toSet
      else Set("CONTROL")

    GraphEdge(from = from, to = to, allowedTypes = allowedTypes)
  }

  private def parsePdf(pdfCfg: Config): MessagePdf = {
    val family =
      if (pdfCfg.hasPath("family")) pdfCfg.getString("family").toLowerCase
      else "explicit"

    family match {
      case "uniform" =>
        val messageTypes = pdfCfg.getStringList("messageTypes").asScala.toList
        UniformPdf(messageTypes)

      case "zipf" =>
        val messageTypes = pdfCfg.getStringList("messageTypes").asScala.toList
        val exponent = pdfCfg.getDouble("exponent")
        ZipfPdf(messageTypes, exponent)

      case "explicit" =>
        val weightsCfg = pdfCfg.getConfig("weights")
        val weights =
          weightsCfg.entrySet().asScala.map { entry =>
            val key = entry.getKey
            key -> weightsCfg.getDouble(key)
          }.toMap

        ExplicitPdf(weights)

      case other =>
        throw new IllegalArgumentException(s"Unsupported pdf family: $other")
    }
  }
}