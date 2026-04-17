package com.uic.cs553.distributed.algorithms.echo

final case class EchoState(
    parent: Option[String] = None,
    seenWaves: Set[String] = Set.empty,
    pendingReplies: Set[String] = Set.empty,
    completed: Boolean = false,
    active: Boolean = false
) 