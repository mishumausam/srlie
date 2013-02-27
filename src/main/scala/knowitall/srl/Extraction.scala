package knowitall.srl

import edu.washington.cs.knowitall.collection.immutable.Interval
import scala.util.control.Exception
import edu.washington.cs.knowitall.tool.parse.graph.DependencyNode
import edu.washington.cs.knowitall.tool.srl.Role
import edu.washington.cs.knowitall.tool.srl.Frame
import edu.washington.cs.knowitall.tool.parse.graph.DependencyGraph
import edu.washington.cs.knowitall.tool.srl.Roles
import edu.washington.cs.knowitall.collection.immutable.graph.Graph.Edge
import edu.washington.cs.knowitall.collection.immutable.graph.DirectedEdge
import edu.washington.cs.knowitall.collection.immutable.graph.Direction
import edu.washington.cs.knowitall.tool.srl.Roles.R
import edu.washington.cs.knowitall.tool.srl.FrameHierarchy
import edu.washington.cs.knowitall.collection.immutable.graph.UpEdge

case class Extraction(relation: Relation, arguments: Seq[Argument]) {
  val arg1 = arguments.find(arg => (arg.role.label matches "A\\d+") && (relation.intervals.forall(interval => arg.interval leftOf interval))).getOrElse {
    throw new IllegalArgumentException("Extraction has no arg1.")
  }

  val arg2s = arguments.filter { arg =>
    relation.intervals.forall(interval => arg.interval rightOf interval)
  }

  override def toString = {
    val parts = Iterable(arg1.text, relation.text, arg2s.iterator.map(_.toString).mkString("; "))
    parts.mkString("(", "; ", ")")
  }

  // an extraction is active if A0 is the first A*
  def active = {
    arguments.find(_.role.label startsWith "A") match {
      case Some(node) => node.role == Roles.A0
      case None => false
    }
  }

  // an extraction is active if it's not passive
  def passive = !active
}
case class Sense(name: String, id: String)
class Argument(val text: String, val tokens: Seq[DependencyNode], val interval: Interval, val role: Role) {
  override def toString = text
}

class TemporalArgument(text: String, tokens: Seq[DependencyNode], interval: Interval, role: Role)
  extends Argument(text, tokens, interval, role) {
  override def toString = "T:" + super.toString
}

class LocationArgument(text: String, tokens: Seq[DependencyNode], interval: Interval, role: Role)
  extends Argument(text, tokens, interval, role) {
  override def toString = "L:" + super.toString
}

case class Relation(text: String, sense: Option[Sense], tokens: Seq[DependencyNode], intervals: Seq[Interval]) {
  // make sure all the intervals are disjoint
  require(intervals.forall(x => !intervals.exists(y => x != y && (x intersects y))))

  override def toString = text

  def concat(other: Relation) = {
    Relation(this.text + " " + other.text, None, this.tokens ++ other.tokens, this.intervals ++ other.intervals)
  }
}
object Relation {
  val expansionLabels = Set("advmod", "neg", "aux", "cop", "auxpass", "prt", "acomp")
}

object Extraction {
  def contiguousAdjacent(graph: DependencyGraph, node: DependencyNode, cond: DirectedEdge[DependencyNode] => Boolean, until: Set[DependencyNode]) = {
    def takeAdjacent(interval: Interval, nodes: List[DependencyNode], pool: List[DependencyNode]): List[DependencyNode] = pool match {
      // can we add the top node?
      case head :: tail if (head.indices borders interval) && !until.contains(head) =>
        takeAdjacent(interval union head.indices, head :: nodes, tail)
      // otherwise abort
      case _ => nodes
    }

    val inferiors = graph.graph.connected(node, cond)
    val span = Interval.span(inferiors.map(_.indices))
    val contiguous = graph.nodes.drop(span.start).take(span.length).toList.sorted

    // split into nodes left and right of node
    val lefts = contiguous.takeWhile(_ != node).reverse
    val rights = contiguous.dropWhile(_ != node).drop(1)

    // take adjacent nodes from each list
    val withLefts = takeAdjacent(node.indices, List(node), lefts)
    val expanded = takeAdjacent(node.indices, withLefts, rights)

    expanded
  }

  val forbiddenEdgeLabel = Seq("appos")
  def fromFrame(dgraph: DependencyGraph)(frame: Frame): Option[Extraction] = {
    val args = frame.arguments.filterNot { arg =>
      arg.role match {
        case Roles.AM_MNR => true
        case Roles.AM_MOD => true
        case Roles.AM_NEG => true
        case _: Roles.R => true
        case _: Roles.C => true
        case _ => false
      }
    }

    val boundaries = args.map(_.node).toSet + frame.relation.node

    val rel = {
      // sometimes we need detatched tokens: "John shouts profanities out loud."
      val nodes = dgraph.graph.inferiors(frame.relation.node, edge => (Relation.expansionLabels contains edge.label) && !(boundaries contains edge.dest))
      val remoteNodes = (
          // expand to certain nodes connected by a conj edge
          (dgraph.graph.superiors(frame.relation.node, edge => edge.label == "conj") - frame.relation.node) flatMap (node => dgraph.graph.inferiors(node, edge => edge.label == "aux" && edge.dest.text == "to") - node)
        ).filter(_.index < frame.relation.node.index)
      val nodeSeq = (remoteNodes ++ nodes).toSeq.sorted
      val text = nodeSeq.iterator.map(_.text).mkString(" ")
      Relation(text, Some(Sense(frame.relation.name, frame.relation.sense)), nodeSeq, Seq(frame.relation.node.indices))
    }

    val mappedArgs = args.map { arg =>
      val nodes = (
          // expand along certain contiguous nodes
          contiguousAdjacent(dgraph, arg.node, dedge => dedge.dir == Direction.Down && !(forbiddenEdgeLabel contains dedge.edge.label), boundaries)
        ).sorted

      val text =
        dgraph.text.substring(nodes.head.offsets.start, nodes.last.offsets.end)
      val nodeSeq = nodes.toSeq
      arg.role match {
        case Roles.AM_TMP => new TemporalArgument(text, nodeSeq, Interval.span(nodes.map(_.indices)), arg.role)
        case Roles.AM_LOC => new LocationArgument(text, nodeSeq, Interval.span(nodes.map(_.indices)), arg.role)
        case _ => new Argument(text, nodeSeq, Interval.span(nodes.map(_.indices)), arg.role)
      }
    }

    Exception.catching(classOf[IllegalArgumentException]) opt Extraction(rel, mappedArgs)
  }

  def fromFrameHierarchy(dgraph: DependencyGraph)(frameh: FrameHierarchy): Seq[Extraction] = {
    def rec(frameh: FrameHierarchy): Seq[Extraction] = {
      if (frameh.children.isEmpty) Extraction.fromFrame(dgraph)(frameh.frame).toSeq
      else {
        Extraction.fromFrame(dgraph)(frameh.frame) match {
          case Some(extr) =>
            val subextrs = frameh.children flatMap rec

            extr +: (subextrs map { subextr =>
              new Extraction(extr.relation concat subextr.relation, subextr.arguments)
            })
          case None => Seq.empty
        }
      }
    }

    rec(frameh)
  }
}
