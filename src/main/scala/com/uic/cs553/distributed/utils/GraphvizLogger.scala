package com.uic.cs553.distributed.utils

import java.io.{File, PrintWriter}

object GraphvizLogger {

  // var justified: object-level mutable reference guarded by synchronized;
  // set once on init, cleared on finish, no concurrent unsynchronized access.
  private var writer: Option[PrintWriter] = None

  def init(fileName: String = "laiyang.dot"): Unit = synchronized {
    if (writer.isEmpty) {
      val out = new PrintWriter(new File(fileName))
      writer = Some(out)
      out.println("digraph LaiYang {")
      out.println("  rankdir=LR;")
      out.println("  node [shape=circle];")
      out.flush()
    }
  }

  def logEdge(from: String, to: String, label: String, color: String = "black"): Unit = synchronized {
    writer.foreach { out =>
      out.println(s"""  "$from" -> "$to" [label="$label", color="$color"];""")
      out.flush()
    }
  }

  def logClosedChannel(from: String, to: String): Unit = synchronized {
    writer.foreach { out =>
      out.println(s"""  "$from" -> "$to" [style=dashed, label="closed"];""")
      out.flush()
    }
  }

  def finish(): Unit = synchronized {
    writer.foreach { out =>
      out.println("}")
      out.flush()
      out.close()
    }
    writer = None
  }
}