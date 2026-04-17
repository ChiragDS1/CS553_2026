package com.uic.cs553.distributed.core

import scala.util.Random
import scala.util.hashing.MurmurHash3

sealed trait MessagePdf {
  def family: String
}

final case class ExplicitPdf(
    weights: Map[String, Double]
) extends MessagePdf {
  override val family: String = "explicit"
}

final case class UniformPdf(
    messageTypes: List[String]
) extends MessagePdf {
  override val family: String = "uniform"
}

final case class ZipfPdf(
    messageTypes: List[String],
    exponent: Double
) extends MessagePdf {
  override val family: String = "zipf"
}

object MessagePdf {

  private val Tolerance = 1e-6
  private val DefaultSeedBase = 1337L

  def validate(pdf: MessagePdf): Either[List[String], MessagePdf] = {
    val errors = pdf match {
      case ExplicitPdf(weights) =>
        val negativeWeights =
          weights.collect { case (k, v) if v < 0.0 => s"Negative probability for $k: $v" }.toList

        val empty =
          if (weights.isEmpty) List("ExplicitPdf has no message types") else Nil

        val sum = weights.values.sum
        val badSum =
          if (math.abs(sum - 1.0) > Tolerance) List(s"ExplicitPdf probabilities must sum to 1.0 but sum to $sum")
          else Nil

        negativeWeights ++ empty ++ badSum

      case UniformPdf(messageTypes) =>
        if (messageTypes.isEmpty) List("UniformPdf has no message types") else Nil

      case ZipfPdf(messageTypes, exponent) =>
        val noTypes =
          if (messageTypes.isEmpty) List("ZipfPdf has no message types") else Nil

        val badExponent =
          if (exponent <= 0.0) List(s"Zipf exponent must be > 0 but was $exponent") else Nil

        noTypes ++ badExponent
    }

    if (errors.isEmpty) Right(pdf) else Left(errors)
  }

  def weights(pdf: MessagePdf): Map[String, Double] = {
    pdf match {
      case ExplicitPdf(w) =>
        w

      case UniformPdf(messageTypes) =>
        val p = 1.0 / messageTypes.size
        messageTypes.map(mt => mt -> p).toMap

      case ZipfPdf(messageTypes, exponent) =>
        val raw = messageTypes.zipWithIndex.map {
          case (msgType, idx) =>
            val rank = idx + 1
            msgType -> (1.0 / math.pow(rank.toDouble, exponent))
        }.toMap

        val sum = raw.values.sum
        raw.view.mapValues(_ / sum).toMap
    }
  }

  def sample(pdf: MessagePdf, rng: Random): String = {
    val normalized = weights(pdf).toList.sortBy(_._1)
    val x = rng.nextDouble()

    normalized
      .foldLeft((0.0, Option.empty[String])) {
        case ((acc, chosen), (msgType, p)) =>
          chosen match {
            case some @ Some(_) => (acc, some)
            case None =>
              val next = acc + p
              if (x <= next) (next, Some(msgType))
              else (next, None)
          }
      }
      ._2
      .getOrElse(normalized.last._1)
  }

  def deterministicSeedForNode(nodeId: String, baseSeed: Long = DefaultSeedBase): Long =
    baseSeed + MurmurHash3.stringHash(nodeId).toLong
}