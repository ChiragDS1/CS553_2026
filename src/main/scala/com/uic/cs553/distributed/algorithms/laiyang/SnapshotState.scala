package com.uic.cs553.distributed.algorithms.laiyang

/**
 * Immutable state for the Lai-Yang snapshot algorithm.
 *
 * inboundCount is set from InitializeNode and holds the number of edges
 * pointing INTO this node.  checkCompletion compares closedChannels.size
 * against inboundCount — not outgoing peer count — because a node must
 * receive a RED marker on every INBOUND channel to close its snapshot.
 * Using outgoing count was the critical bug on asymmetric topologies.
 */
final case class SnapshotState(
    color: String = "white",
    recorded: Boolean = false,
    snapshotId: Option[String] = None,
    localState: List[String] = Nil,
    channelState: Map[String, List[String]] = Map.empty,
    closedChannels: Set[String] = Set.empty,
    completed: Boolean = false,
    appMessagesSent: Int = 0,
    appMessagesReceived: Int = 0,
    inboundCount: Int = 0           // set once from InitializeNode; never mutated after that
)