/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package table

import com.precog.common.{Path, VectorCase}
import com.precog.bytecode.JType
import com.precog.yggdrasil.jdbm3._
import com.precog.yggdrasil.util._

import blueeyes.bkka.AkkaTypeClasses
import blueeyes.json._
import blueeyes.json.JsonAST._
import org.apache.commons.collections.primitives.ArrayIntList
import org.joda.time.DateTime
import com.google.common.io.Files
import com.weiglewilczek.slf4s.Logging

import org.apache.jdbm.DBMaker
import java.io.File
import java.util.SortedMap

import scala.collection.BitSet
import scala.annotation.tailrec

import scalaz._
import scalaz.Ordering._
import scalaz.std.function._
import scalaz.std.list._
import scalaz.std.tuple._
//import scalaz.std.iterable._
import scalaz.std.option._
import scalaz.std.map._
import scalaz.std.set._
import scalaz.std.stream._
import scalaz.syntax.arrow._
import scalaz.syntax.monad._
import scalaz.syntax.traverse._
import scalaz.syntax.std.boolean._

trait ColumnarTableTypes {
  type F1 = CF1
  type F2 = CF2
  type Scanner = CScanner
  type Reducer[α] = CReducer[α]
  type RowId = Int
}

trait ColumnarTableModule[M[+_]] extends TableModule[M] with ColumnarTableTypes with IdSourceScannerModule[M] with SliceTransforms[M] {
  import TableModule._
  import trans._
  import trans.constants._

  type Table <: ColumnarTable
  type TableCompanion <: ColumnarTableCompanion

  def newScratchDir(): File = Files.createTempDir()
  def jdbmCommitInterval: Long = 200000l

  implicit def liftF1(f: F1) = new F1Like {
    def compose(f1: F1) = f compose f1
    def andThen(f1: F1) = f andThen f1
  }

  implicit def liftF2(f: F2) = new F2Like {
    def applyl(cv: CValue) = new CF1(f(Column.const(cv), _))
    def applyr(cv: CValue) = new CF1(f(_, Column.const(cv)))

    def andThen(f1: F1) = new CF2((c1, c2) => f(c1, c2) flatMap f1.apply)
  }

  trait ColumnarTableCompanion extends TableCompanionLike {
    def apply(slices: StreamT[M, Slice]): Table

    def empty: Table = Table(StreamT.empty[M, Slice])
    
    def constBoolean(v: collection.Set[CBoolean]): Table = {
      val column = ArrayBoolColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(JPath.Identity, CBoolean) -> column), v.size) :: StreamT.empty[M, Slice])
    }

    def constLong(v: collection.Set[CLong]): Table = {
      val column = ArrayLongColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(JPath.Identity, CLong) -> column), v.size) :: StreamT.empty[M, Slice])
    }

    def constDouble(v: collection.Set[CDouble]): Table = {
      val column = ArrayDoubleColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(JPath.Identity, CDouble) -> column), v.size) :: StreamT.empty[M, Slice])
    }

    def constDecimal(v: collection.Set[CNum]): Table = {
      val column = ArrayNumColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(JPath.Identity, CNum) -> column), v.size) :: StreamT.empty[M, Slice])
    }

    def constString(v: collection.Set[CString]): Table = {
      val column = ArrayStrColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(JPath.Identity, CString) -> column), v.size) :: StreamT.empty[M, Slice])
    }

    def constDate(v: collection.Set[CDate]): Table =  {
      val column = ArrayDateColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(JPath.Identity, CDate) -> column), v.size) :: StreamT.empty[M, Slice])
    }

    def constNull: Table = 
      Table(Slice(Map(ColumnRef(JPath.Identity, CNull) -> new InfiniteColumn with NullColumn), 1) :: StreamT.empty[M, Slice])

    def constEmptyObject: Table = 
      Table(Slice(Map(ColumnRef(JPath.Identity, CEmptyObject) -> new InfiniteColumn with EmptyObjectColumn), 1) :: StreamT.empty[M, Slice])

    def constEmptyArray: Table = 
      Table(Slice(Map(ColumnRef(JPath.Identity, CEmptyArray) -> new InfiniteColumn with EmptyArrayColumn), 1) :: StreamT.empty[M, Slice])

    def transformStream[A](sliceTransform: SliceTransform1[A], slices: StreamT[M, Slice]): StreamT[M, Slice] = {
      def stream(state: A, slices: StreamT[M, Slice]): StreamT[M, Slice] = StreamT(
        for {
          head <- slices.uncons
        } yield {
          head map { case (s, sx) =>
            val (nextState, s0) = sliceTransform.f(state, s)
            StreamT.Yield(s0, stream(nextState, sx))
          } getOrElse {
            StreamT.Done
          }
        }
      )

      stream(sliceTransform.initial, slices)
    }

    /**
     * Intersects the given tables on identity, where identity is defined by the provided TransSpecs
     */
    def intersect(identitySpec: TransSpec1, tables: Table*): M[Table] = {
      val inputCount = tables.size
      val mergedSlices: StreamT[M, Slice] = tables.map(_.slices).reduce( _ ++ _ )
      Table(mergedSlices).sort(identitySpec).map {
        sortedTable => {
          sealed trait CollapseState
          case class Boundary(prevSlice: Slice, prevStartIdx: Int) extends CollapseState
          case object InitialCollapse extends CollapseState

          def genComparator(sl1: Slice, sl2: Slice) = Slice.rowComparatorFor(sl1, sl2) {
            // only need to compare identities (field "0" of the sorted table) between projections
            // TODO: Figure out how we might do this directly with the identitySpec
            slice => slice.columns.keys.filter({ case ColumnRef(selector, _) => selector.nodes.startsWith(JPathField("0") :: Nil) }).toList.sorted
          }
          
          def boundaryCollapse(prevSlice: Slice, prevStart: Int, curSlice: Slice): (BitSet, Int) = {
            val comparator = genComparator(prevSlice, curSlice)

            var curIndex = 0

            while (curIndex < curSlice.size && comparator.compare(prevStart, curIndex) == EQ) {
              curIndex += 1
            }

            if (curIndex == 0) {
              // First element is unequal...
              // We either marked the span to retain in the previous slice, or 
              // we don't have enough here to mark the new slice to retain
              (BitSet.empty, curIndex)
            } else {
              val count = (prevSlice.size - prevStart) + curIndex

              if (count == inputCount) {
                (BitSet(curIndex - 1), curIndex)
              } else if (count > inputCount) {
                sys.error("Found too many EQ identities in intersect. This indicates a bug in the graph processing algorithm.")
              } else {
                (BitSet.empty, curIndex)
              }
            }
          } 

          // Collapse the slices, returning the BitSet for which the rows are defined as well as the start of the
          // last span 
          def selfCollapse(slice: Slice, startIndex: Int, defined: BitSet): (BitSet, Int) = {
            val comparator = genComparator(slice, slice)

            var retain = defined

            // We'll collect spans of EQ rows in chunks, retainin the start row of completed spans with the correct
            // count and then inchworming over it
            var spanStart = startIndex
            var spanEnd   = startIndex
            
            while (spanEnd < slice.size) {
              while (spanEnd < slice.size && comparator.compare(spanStart, spanEnd) == EQ) {
                spanEnd += 1
              }

              val count = spanEnd - spanStart

              if (count == inputCount) {
                retain += (spanEnd - 1)
              } else if (count > inputCount) {
                sys.error("Found too many EQ identities in intersect. This indicates a bug in the graph processing algorithm.")
              }

              if (spanEnd < slice.size) {
                spanStart = spanEnd
              }
            }

            (retain, spanStart)
          }

          val collapse = SliceTransform1[CollapseState](InitialCollapse, {
            case (InitialCollapse, slice) => {
              val (retain, spanStart) = selfCollapse(slice, 0, BitSet.empty)
              // Pass on the remainder, if any, of this slice to the next slice for continued comparison
              (Boundary(slice, spanStart), slice.redefineWith(retain))
            }

            case (Boundary(prevSlice, prevStart), slice) => {
              // First, do a boundary comparison on the previous slice to see if we need to retain lead elements in the new slice
              val (boundaryRetain, boundaryEnd) = boundaryCollapse(prevSlice, prevStart, slice)
              val (retain, spanStart) = selfCollapse(slice, boundaryEnd, boundaryRetain)
              (Boundary(slice, spanStart), slice.redefineWith(retain))
            }
          })

          // Break the idents out into field "0", original data in "1"
          val splitIdentsTransSpec = ObjectConcat(WrapObject(identitySpec, "0"), WrapObject(Leaf(Source), "1"))

          Table(transformStream(collapse, sortedTable.transform(splitIdentsTransSpec).slices)).transform(DerefObjectStatic(Leaf(Source), JPathField("1")))
        }
      }
    }

    ///////////////////////
    // Grouping Support //
    ///////////////////////
  
    type TicVar = JPathField

    case class MergeAlignment(left: MergeSpec, right: MergeSpec, keys: Seq[TicVar])
    
    sealed trait MergeSpec
    case class SourceMergeSpec(binding: Binding, groupKeyTransSpec: TransSpec1, order: Seq[TicVar]) extends MergeSpec
    case class LeftAlignMergeSpec(alignment: MergeAlignment) extends MergeSpec
    case class IntersectMergeSpec(mergeSpecs: Set[MergeSpec]) extends MergeSpec
    case class NodeMergeSpec(ordering: Seq[TicVar], toAlign: Set[MergeSpec]) extends MergeSpec
    case class CrossMergeSpec(left: MergeSpec, right: MergeSpec) extends MergeSpec
    
    // The GroupKeySpec for a binding is comprised only of conjunctions that refer only
    // to members of the source table. The targetTrans defines a transformation of the
    // table to be used as the value output after keys have been derived. 
    // while Binding as the same general structure as GroupingSource, we keep it as a seperate type because
    // of the constraint that the groupKeySpec must be a conjunction, or just a single source clause. Reusing
    // the same type would be confusing
    case class Binding(source: Table, idTrans: TransSpec1, targetTrans: Option[TransSpec1], groupId: GroupId, groupKeySpec: GroupKeySpec) 

    // MergeTrees describe intersections as edges in a graph, where the nodes correspond
    // to sets of bindings
    case class MergeNode(keys: Set[TicVar], binding: Binding) {
      def ticVars = keys
    }
    object MergeNode {
      def apply(binding: Binding): MergeNode = MergeNode(Universe.sources(binding.groupKeySpec).map(_.key).toSet, binding)
    }

    /**
     * Represents an adjaceny based on a common subset of TicVars
     */
    class MergeEdge private[MergeEdge](val a: MergeNode, val b: MergeNode) {
      /** The common subset of ticvars shared by both nodes */
      val sharedKeys = a.keys & b.keys

      /** The set of nodes joined by this edge */
      val nodes = Set(a, b)
      def touches(node: MergeNode) = nodes.contains(node)

      /** The total set of keys joined by this edge (for alignment) */
      val keys: Set[TicVar] = a.keys ++ b.keys

      def joins(x: MergeNode, y: MergeNode) = (x == a && y == b) || (x == b && y == a)

      // Overrrides for set equality
      override def equals(other: Any) = other match {
        case e: MergeEdge => e.nodes == this.nodes
        case _ => false
      }
      override def hashCode() = nodes.hashCode()
      override def toString() = "MergeEdge(%s, %s)".format(a, b)
    }

    object MergeEdge {
      def apply(a: MergeNode, b: MergeNode) = new MergeEdge(a, b)
      def unapply(n: MergeEdge): Option[(MergeNode, MergeNode)] = Some((n.a, n.b))
    }

    // A maximal spanning tree for a merge graph, where the edge weights correspond
    // to the size of the shared keyset for that edge. We use hte maximal weights
    // since the larger the set of shared keys, the fewer constraints are imposed
    // making it more likely that a sorting for those shared keys can be reused.
    case class MergeGraph(nodes: Set[MergeNode], edges: Set[MergeEdge] = Set()) {
      def join(other: MergeGraph, edge: MergeEdge) = MergeGraph(nodes ++ other.nodes, edges ++ other.edges + edge)

      val edgesFor: Map[MergeNode, Set[MergeEdge]] = edges.foldLeft(nodes.map((_, Set.empty[MergeEdge])).toMap) {
        case (acc, edge @ MergeEdge(a, b)) => 
          val aInbound = acc(a) + edge
          val bInbound = acc(b) + edge
          acc + (a -> aInbound) + (b -> bInbound)
      }

      def adjacent(a: MergeNode, b: MergeNode) = {
        edges.find { e => (e.a == a && e.b == a) || (e.a == b && e.b == a) }.isDefined
      }

      val rootNode = (edgesFor.toList maxBy { case (_, edges) => edges.size })._1
    }

    case class Universe(bindings: List[Binding]) {
      import Universe._

      def spanningGraphs: Set[MergeGraph] = {
        val clusters: Map[MergeNode, List[Binding]] = bindings groupBy { 
          case binding @ Binding(_, _, _, _, groupKeySpec) => MergeNode(sources(groupKeySpec).map(_.key).toSet, binding) 
        }

        findSpanningGraphs(edgeMap(clusters.keySet))
      }
    }

    type ConnectedSubgraph = Set[NodeSubset]

    case class BorgResult(table: Table, groupKeyTrans: TransSpec1, idTrans: Map[GroupId, TransSpec1], rowTrans: Map[GroupId, TransSpec1])

    object Universe {
      def allEdges(nodes: collection.Set[MergeNode]): collection.Set[MergeEdge] = {
        for {
          l <- nodes
          r <- nodes
          if l != r
          sharedKey = l.keys intersect r.keys
          if sharedKey.nonEmpty
        } yield {
          MergeEdge(l, r)
        }
      }

      def edgeMap(nodes: collection.Set[MergeNode]): Map[MergeNode, Set[MergeEdge]] = {
        allEdges(nodes).foldLeft(nodes.map(n => n -> Set.empty[MergeEdge]).toMap) { 
          case (acc, edge @ MergeEdge(a, b)) => acc + (a -> (acc.getOrElse(a, Set()) + edge)) + (b -> (acc.getOrElse(b, Set()) + edge))
        } 
      }

      // a universe is a conjunction of binding clauses, which must contain no disjunctions
      def sources(spec: GroupKeySpec): Seq[GroupKeySpecSource] = (spec: @unchecked) match {
        case GroupKeySpecAnd(left, right) => sources(left) ++ sources(right)
        case src: GroupKeySpecSource => Vector(src)
      }

      // An implementation of our algorithm for finding a minimally connected set of graphs
      def findSpanningGraphs(outbound: Map[MergeNode, Set[MergeEdge]]): Set[MergeGraph] = {
        def isConnected(from: MergeNode, to: MergeNode, outbound: Map[MergeNode, Set[MergeEdge]], constraintSet: Set[TicVar]): Boolean = {
          outbound.getOrElse(from, Set()).exists {
            case edge @ MergeEdge(a, b) => 
              a == to || b == to ||
              {
                val other = if (a == from) b else a
                // the other node's keys must be a superset of the constraint set we're concerned with in order to traverse it.
                ((other.keys & constraintSet) == constraintSet) && {
                  val pruned = outbound mapValues { _ - edge }
                  isConnected(other,to, pruned, constraintSet)
                }
              }
          }
        }

        def find0(outbound: Map[MergeNode, Set[MergeEdge]], edges: Set[MergeEdge]): Map[MergeNode, Set[MergeEdge]] = {
          if (edges.isEmpty) {
            outbound
          } else {
            val edge = edges.head

            // node we're searching from
            val fromNode = edge.a
            val toNode = edge.b

            val pruned = outbound mapValues { _ - edge }

            find0(if (isConnected(fromNode, toNode, pruned, edge.keys)) pruned else outbound, edges.tail)
          }
        }

        def partition(in: Map[MergeNode, Set[MergeEdge]]): Set[MergeGraph] = {
          in.values.flatten.foldLeft(in.keySet map { k => MergeGraph(Set(k)) }) {
            case (acc, edge @ MergeEdge(a, b)) => 
              val g1 = acc.find(_.nodes.contains(a)).get
              val g2 = acc.find(_.nodes.contains(b)).get

              val resultGraph = g1.join(g2, edge)
              acc - g1 - g2 + resultGraph
          }
        }

        partition(find0(outbound, outbound.values.flatten.toSet))
      }
    }

    case class OrderingConstraint(ordering: Seq[Set[TicVar]]) { self =>
      // Fix this binding constraint into a sort order. Any non-singleton TicVar sets will simply
      // be converted into an arbitrary sequence
      lazy val fixed = ordering.flatten

      def & (that: OrderingConstraint): Option[OrderingConstraint] = OrderingConstraints.replacementFor(self, that)

      def - (ticVars: Set[TicVar]): OrderingConstraint = OrderingConstraint(ordering.map(_.filterNot(ticVars.contains)).filterNot(_.isEmpty))

      override def toString = ordering.map(_.map(_.toString.substring(1)).mkString("{", ", ", "}")).mkString("OrderingConstraint(", ",", ")")
    }
    object OrderingConstraint {
      val Zero = OrderingConstraint(Vector.empty)

      def fromFixed(order: Seq[TicVar]): OrderingConstraint = OrderingConstraint(order.map(v => Set(v)))
    }

    sealed trait OrderingConstraint2 { self =>
      def fixed: Seq[TicVar]

      def normalize: OrderingConstraint2

      def flatten: OrderingConstraint2

      def render: String

      def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2

      def - (that: OrderingConstraint2): OrderingConstraint2 = {
        val thatVars = that.variables

        (filter {
          case OrderingConstraint2.Variable(x) => !thatVars.contains(x)

          case x => true
        }).normalize
      }

      lazy val fixedConstraint = OrderingConstraint2.orderedVars(fixed: _*)

      lazy val variables: Set[TicVar] = fixed.toSet

      lazy val size = fixed.size      

      import OrderingConstraint2._

      def join(that: OrderingConstraint2): Join = {
        def join2(left: OrderingConstraint2, right: OrderingConstraint2): Join = {
          (left, right) match {
            case (left, Zero) => Join(left)

            case (Zero, right) => Join(right)

            case (l @ Ordered(left), r @ Ordered(right)) => 
              val joinedHeads = left.head.join(right.head)

              if (joinedHeads.join == Zero) Join.unjoined(l, r)
              else {
                val leftTail = ordered(joinedHeads.leftRem, Ordered(left.tail))
                val rightTail = ordered(joinedHeads.rightRem, Ordered(right.tail))

                val joinedTails = leftTail.join(rightTail)

                if (joinedTails.join == Zero) Join.unjoined(l, r)
                else {
                  Join(ordered(joinedHeads.join, joinedTails.join), joinedTails.leftRem, joinedTails.rightRem)
                }
              }

            case (l @ Ordered(left), r @ Variable(right)) => 
              if (left.head == r) Join(r, leftRem = Ordered(left.tail), rightRem = Zero) else Join.unjoined(l, r)

            case (l @ Ordered(left), r @ Unordered(right)) => 
              def foldSet(lastJoin: Join, remaining: Set[OrderingConstraint2]): Vector[Join] = {
                if (lastJoin.leftRem == Zero) Vector(lastJoin)
                else if (remaining.size == 0) Vector(lastJoin)
                else {
                  remaining.foldLeft(Vector.empty[Join]) {
                    case (acc, choice) => 
                      val nextChoices = remaining - choice

                      val newJoin = lastJoin.leftRem.join(choice)

                      if (newJoin.join == Zero) acc
                      else {
                        acc ++ foldSet(
                          Join(ordered(lastJoin.join, newJoin.join), leftRem = newJoin.leftRem, rightRem = unordered(lastJoin.rightRem, newJoin.rightRem)),
                          nextChoices
                        )
                      }
                  }
                }
              }
              
              foldSet(Join(Zero, leftRem = l), right).filterNot(_.join == Zero).sortBy(_.size).last

            case (l @ Variable(left), r @ Variable(right)) => 
              if (left == right) Join(l) else Join.unjoined(l, r)

            case (l @ Variable(left), r @ Ordered(right)) => 
              join2(r, l).flip

            case (l @ Variable(left), r @ Unordered(right)) => 
              if (right.contains(l)) Join(l, leftRem = Zero, rightRem = Unordered(right - l)) else Join.unjoined(l, r)

            case (Unordered(left), Unordered(right)) =>  
              // TODO: This is not right
              val common = left intersect right
              val leftUnique = left -- common
              val rightUnique = right -- common

              Join(Unordered(common), leftRem = Unordered(leftUnique), rightRem = Unordered(rightUnique))

            case (l @ Unordered(left), r @ Ordered(right)) => 
              join2(r, l).flip

            case (l @ Unordered(left), r @ Variable(right)) => 
              join2(r, l).flip
          }
        }

        join2(self.normalize, that.normalize).normalize
      }
    }
    object OrderingConstraint2 {
      case class Join(join: OrderingConstraint2, leftRem: OrderingConstraint2 = Zero, rightRem: OrderingConstraint2 = Zero) {
        def normalize = copy(join = join.normalize, leftRem = leftRem.normalize, rightRem = rightRem.normalize)

        def flip = copy(leftRem = rightRem, rightRem = leftRem)

        def size = join.size
      }

      object Join {
        def unjoined(left: OrderingConstraint2, right: OrderingConstraint2): Join = Join(Zero, left, right)
      }

      object ConstraintParser extends scala.util.parsing.combinator.JavaTokenParsers {
        lazy val ticVar: Parser[OrderingConstraint2] = "'" ~> ident ^^ (s => Variable(JPathField(s)))

        lazy val ordered: Parser[OrderingConstraint2] = ("[" ~> repsep(constraint, ",") <~ "]") ^^ (v => Ordered(v.toSeq))

        lazy val unordered: Parser[OrderingConstraint2] = ("{" ~> repsep(constraint, ",") <~ "}") ^^  (v => Unordered(v.toSet))

        lazy val zero: Parser[OrderingConstraint2] = "*" ^^^ Zero

        lazy val constraint: Parser[OrderingConstraint2] = ticVar | ordered | unordered | zero

        def parse(input: String): OrderingConstraint2 = parseAll(constraint, input).getOrElse(Zero)
      }

      def parse(input: String): OrderingConstraint2 = ConstraintParser.parse(input)

      def ordered(values: OrderingConstraint2*) = Ordered(Vector(values: _*))

      def orderedVars(values: TicVar*) = Ordered(Vector(values: _*).map(Variable.apply))

      def unordered(values: OrderingConstraint2*) = Unordered(values.toSet)

      def unorderedVars(values: TicVar*) = Unordered(values.toSet.map(Variable.apply))

      case object Zero extends OrderingConstraint2 { self =>
        def fixed = Vector.empty

        def flatten = self

        def normalize = self

        def render = "*"

        def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2 = Zero
      }

      case class Variable(value: TicVar) extends OrderingConstraint2 { self =>
        def fixed = Vector(value)

        def flatten: Variable = self

        def normalize = self

        def render = "'" + value.toString.substring(1)

        def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2 = pf.lift(self).filter(_ == true).map(Function.const(self)).getOrElse(Zero)
      }
      case class Ordered(value: Seq[OrderingConstraint2]) extends OrderingConstraint2 { self =>
        def fixed = value.map(_.fixed).flatten

        def normalize = {
          val f = Ordered(value.map(_.normalize)).flatten
          val fv = f.value

          if (fv.length == 0) Zero
          else if (fv.length == 1) fv.head 
          else f
        }

        def flatten: Ordered = Ordered(value.map(_.flatten).flatMap {
          case x: Ordered => x.value
          case Zero => Vector.empty
          case x => Vector(x)
        })

        def render = value.map(_.render).mkString("[", ", ", "]")

        def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2 = {
          val self2 = Ordered(value.map(_.filter(pf)))

          pf.lift(self2).filter(_ == true).map(Function.const(self2)).getOrElse(Zero)
        }
      }
      case class Unordered(value: Set[OrderingConstraint2]) extends OrderingConstraint2 { self =>
        def fixed = value.toSeq.map(_.fixed).flatten

        def flatten: Unordered = Unordered(value.map(_.flatten).flatMap {
          case x: Unordered => x.value
          case Zero => Set.empty[OrderingConstraint2]
          case x => Set(x)
        })

        def normalize = {
          val f = Unordered(value.map(_.normalize)).flatten
          val fv = f.value

          if (fv.size == 0) Zero
          else if (fv.size == 1) fv.head 
          else f
        }

        def render = value.toSeq.sortBy(_.render).map(_.render).mkString("{", ", ", "}")

        def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2 = {
          val self2 = Unordered(value.map(_.filter(pf)))

          pf.lift(self2).filter(_ == true).map(Function.const(self2)).getOrElse(Zero)
        }
      }
    }

    object OrderingConstraints {
      /**
       * Compute a new constraint that can replace both input constraints
       */
      def replacementFor(a: OrderingConstraint, b: OrderingConstraint): Option[OrderingConstraint] = {
        @tailrec
        def alignConstraints(left: Seq[Set[TicVar]], right: Seq[Set[TicVar]], computed: Seq[Set[TicVar]] = Seq()): Option[OrderingConstraint] = {
          if (left.isEmpty) {
            // left is a prefix or equal to the shifted right, so we can use computed :: right as our common constraint
            Some(OrderingConstraint(computed ++ right))
          } else if (right.isEmpty) {
            Some(OrderingConstraint(computed ++ left))
          } else {
            val intersection = left.head & right.head
            val diff = right.head diff left.head
            if (intersection == left.head) {
              // If left's head is a subset of right's, we can split right's head, use the subset as the next part
              // of our computed sequence, then push the unused portion back onto right for another round of alignment
              val newRight = if (diff.nonEmpty) diff +: right.tail else right.tail
              alignConstraints(left.tail, newRight, computed :+ intersection)
            } else {
              // left is not a subset, so these constraints can't be aligned
              None
            }
          }
        }

        alignConstraints(a.ordering, b.ordering) orElse alignConstraints(b.ordering, a.ordering)
      }

      /**
       * Given the set of input constraints, find a _minimal_ set of compatible OrderingConstraints that
       * covers that set.
       */
      def minimize(constraints: Set[OrderingConstraint]): Set[OrderingConstraint] = {
        @tailrec
        def reduce(unreduced: Set[OrderingConstraint], minimized: Set[OrderingConstraint]): Set[OrderingConstraint] = {
          if (unreduced.isEmpty) {
            minimized
          } else {
            // Find the first constraint in the tail that can be reduced with the head
            unreduced.tail.iterator.map { c => (c, replacementFor(c, unreduced.head)) } find { _._2.isDefined } match {
              // We have a reduction, so re-run, replacing the two reduced constraints with the newly compute one
              case Some((other, Some(reduced))) => reduce(unreduced -- Set(other, unreduced.head) + reduced, minimized)
              // No reduction possible, so head is part of the minimized set
              case _ => reduce(unreduced.tail, minimized + unreduced.head)
            }
          }
        }

        reduce(constraints, Set())
      }
    }

    // todo: Maybe make spec an ArrayConcat?
    case class GroupKeyTrans(spec: TransSpec1, keyOrder: Seq[TicVar]) {
      import GroupKeyTrans._

      def alignTo(targetOrder: Seq[TicVar]): GroupKeyTrans = {
        if (keyOrder == targetOrder) this else {
          val keyMap = targetOrder.zipWithIndex.toMap
          val newOrder = keyOrder.sortBy(key => keyMap.getOrElse(key, Int.MaxValue))

          val keyComponents = newOrder.zipWithIndex map { 
            case (ticvar, i) => reindex(spec, keyOrder.indexOf(ticvar), i)
          }

          GroupKeyTrans(
            ObjectConcat(keyComponents: _*),
            newOrder
          )
        }
      }

      def prefixTrans(length: Int): TransSpec1 = {
        if (keyOrder.size == length) spec else {
          ObjectConcat((0 until length) map { i => reindex(spec, i, i) }: _*)
        }
      }
    }

    object GroupKeyTrans {
      // 999999 ticvars should be enough for anybody.
      def keyName(i: Int) = "%06d".format(i)
      def keyVar(i: Int): TicVar = JPathField(keyName(i))

      def reindex(spec: TransSpec1, from: Int, to: Int) = WrapObject(DerefObjectStatic(spec, keyVar(from)), keyName(to))

      // the GroupKeySpec passed to deriveTransSpecs must be either a source or a conjunction; all disjunctions
      // have been factored out by this point
      def apply(conjunction: Seq[GroupKeySpecSource]): GroupKeyTrans = {
        // [avalue, bvalue]
        val (keySpecs, fullKeyOrder) = conjunction.zipWithIndex.map({ case (src, i) => WrapObject(src.spec, keyName(i)) -> src.key }).unzip
        val groupKeys = ObjectConcat(keySpecs: _*)

        GroupKeyTrans(groupKeys, fullKeyOrder)
      }
    }

    sealed trait NodeMetadata {
      def size: Long
    }

    object NodeMetadata {
      def apply(size0: Long) = new NodeMetadata {
        def size = size0
      }
    }

    case class NodeSubset(node: MergeNode, table: Table, idTrans: TransSpec1, targetTrans: Option[TransSpec1], groupKeyTrans: GroupKeyTrans, groupKeyPrefix: Seq[TicVar], size: Long = 1) extends NodeMetadata {
      def sortedOn = groupKeyTrans.alignTo(groupKeyPrefix).prefixTrans(groupKeyPrefix.size)
    }

    /////////////////
    /// functions ///
    /////////////////

    def findBindingUniverses(grouping: GroupingSpec): Seq[Universe] = {
      @inline def find0(v: Vector[(GroupingSource, Vector[GroupKeySpec])]): Stream[Universe] = {
        val protoUniverses = (v map { case (src, specs) => specs map { (src, _) } toStream } toList).sequence 
        
        protoUniverses map { proto =>
          Universe(proto map { case (src, spec) => Binding(src.table, src.idTrans, src.targetTrans, src.groupId, spec) })
        }
      }

      import GroupKeySpec.{dnf, toVector}
      find0(grouping.sources map { source => (source, ((dnf _) andThen (toVector _)) apply source.groupKeySpec) })
    }

    def findRequiredSorts(spanningGraph: MergeGraph): Map[MergeNode, Set[Seq[TicVar]]] = {
      findRequiredSorts(spanningGraph, spanningGraph.nodes.toList)
    }

    private[table] def findRequiredSorts(spanningGraph: MergeGraph, nodeList: List[MergeNode]): Map[MergeNode, Set[Seq[TicVar]]] = {
      import OrderingConstraints.minimize
      def inPrefix(seq: Seq[TicVar], keys: Set[TicVar], acc: Seq[TicVar] = Vector()): Option[Seq[TicVar]] = {
        if (keys.isEmpty) Some(acc) else {
          seq.headOption.flatMap { a => 
            if (keys.contains(a)) inPrefix(seq.tail, keys - a, acc :+ a) else None
          }
        }
      }

      def fix(nodes: List[MergeNode], underconstrained: Map[MergeNode, Set[OrderingConstraint]]): Map[MergeNode, Set[OrderingConstraint]] = {
        if (nodes.isEmpty) underconstrained else {
          val node = nodes.head
          val fixed = minimize(underconstrained(node)).map(_.ordering.flatten)
          val newUnderconstrained = spanningGraph.edgesFor(node).foldLeft(underconstrained) {
            case (acc, edge @ MergeEdge(a, b)) => 
              val other = if (a == node) b else a
              val edgeConstraint: Seq[TicVar] = 
                fixed.view.map(inPrefix(_, edge.sharedKeys)).collect({ case Some(seq) => seq }).head

              acc + (other -> (acc.getOrElse(other, Set()) + OrderingConstraint(edgeConstraint.map(Set(_)))))
          }

          fix(nodes.tail, newUnderconstrained)
        }
      }

      if (spanningGraph.edges.isEmpty) {
        spanningGraph.nodes.map(_ -> Set.empty[Seq[TicVar]]).toMap
      } else {
        val unconstrained = spanningGraph.edges.foldLeft(Map.empty[MergeNode, Set[OrderingConstraint]]) {
          case (acc, edge @ MergeEdge(a, b)) =>
            val edgeConstraint = OrderingConstraint(Seq(edge.sharedKeys))
            val aConstraints = acc.getOrElse(a, Set()) + edgeConstraint
            val bConstraints = acc.getOrElse(b, Set()) + edgeConstraint

            acc + (a -> aConstraints) + (b -> bConstraints)
        }
        
        fix(nodeList, unconstrained).mapValues(s => minimize(s).map(_.fixed))
      }
    }

    /**
     * Perform the sorts required for the specified node (needed to align this node with each
     * node to which it is connected) and return as a map from the sort ordering for the connecting
     * edge to the sorted table with the appropriate dereference transspecs.
     */
    def materializeSortOrders(node: MergeNode, requiredSorts: Set[Seq[TicVar]]): M[Map[Seq[TicVar], NodeSubset]] = {
      import TransSpec.deepMap

      val protoGroupKeyTrans = GroupKeyTrans(Universe.sources(node.binding.groupKeySpec))

      // Since a transspec for a group key may perform a bunch of work (computing values, etc)
      // it seems like we really want to do that work only once; prior to the initial sort. 
      // This means carrying around the *computed* group key everywhere
      // post the initial sort separate from the values that it was derived from. 
      val (payloadTrans, idTrans, targetTrans, groupKeyTrans) = node.binding.targetTrans match {
        case Some(targetSetTrans) => 
          val payloadTrans = ArrayConcat(WrapArray(node.binding.idTrans), WrapArray(protoGroupKeyTrans.spec), WrapArray(targetSetTrans))

          (payloadTrans,
           TransSpec1.DerefArray0, 
           Some(TransSpec1.DerefArray2), 
           GroupKeyTrans(TransSpec1.DerefArray1, protoGroupKeyTrans.keyOrder))

        case None =>
          val payloadTrans = ArrayConcat(WrapArray(node.binding.idTrans), WrapArray(protoGroupKeyTrans.spec))
          (payloadTrans, 
           TransSpec1.DerefArray0, 
           None, 
           GroupKeyTrans(TransSpec1.DerefArray1, protoGroupKeyTrans.keyOrder))
      }

      val requireFullGroupKeyTrans = FilterDefined(payloadTrans, groupKeyTrans.spec, AllDefined)
      val filteredSource: Table = node.binding.source.transform(requireFullGroupKeyTrans)

      val nodeSubsetsM: M[Set[(Seq[TicVar], NodeSubset)]] = requiredSorts.map { ticvars => 
        val sortTransSpec = groupKeyTrans.alignTo(ticvars).prefixTrans(ticvars.length)

        val sorted: M[Table] = filteredSource.sort(sortTransSpec)
        sorted.map { sortedTable => 
          ticvars ->
          NodeSubset(node,
                     sortedTable,
                     deepMap(idTrans) { case Leaf(_) => TransSpec1.DerefArray1 },
                     targetTrans.map(t => deepMap(t) { case Leaf(_) => TransSpec1.DerefArray1 }),
                     groupKeyTrans.copy(spec = deepMap(groupKeyTrans.spec) { case Leaf(_) => TransSpec1.DerefArray1 }),
                     ticvars)
        }
      }.sequence

      nodeSubsetsM map { _.toMap }
    }

    def alignOnEdges(spanningGraph: MergeGraph): M[Map[GroupId, Set[NodeSubset]]] = {
      import OrderingConstraints._

      // Compute required sort orders based on graph traversal
      val requiredSorts: Map[MergeNode, Set[Seq[TicVar]]] = findRequiredSorts(spanningGraph)

      val sortPairs: M[Map[MergeNode, Map[Seq[TicVar], NodeSubset]]] = 
        requiredSorts.map({ case (node, orders) => materializeSortOrders(node, orders) map { node -> _ }}).toStream
        .sequence.map(_.toMap)
      
      for {
        sorts <- sortPairs
        groupedSubsets <- {
          val edgeAlignments = spanningGraph.edges flatMap {
            case MergeEdge(a, b) =>
              // Find the compatible sortings for this edge's endpoints
              val common: Set[(NodeSubset, NodeSubset)] = for {
                aVars <- sorts(a).keySet
                bVars <- sorts(b).keySet
                if aVars.startsWith(bVars) || bVars.startsWith(aVars)
              } yield {
                (sorts(a)(aVars), sorts(b)(bVars))
              }

              common map {
                case (aSorted, bSorted) => 
                  val alignedM = Table.align(aSorted.table, aSorted.sortedOn, bSorted.table, bSorted.sortedOn)
                  
                  alignedM map {
                    case (aAligned, bAligned) => List(
                      NodeSubset(a, aAligned, aSorted.idTrans, aSorted.targetTrans, aSorted.groupKeyTrans, aSorted.groupKeyPrefix),
                      NodeSubset(b, bAligned, bSorted.idTrans, bSorted.targetTrans, bSorted.groupKeyTrans, bSorted.groupKeyPrefix)
                    )
                  }
              }
          }

          edgeAlignments.sequence
        }
      } yield {
        groupedSubsets.flatten.groupBy(_.node.binding.groupId)
      }
    }

    // TODO: This should NOT return NodeSubset, but rather, something like it, without
    //        groupKeyPrefix (which is MEANINGELSS for the return value)
    def intersect(set: Set[NodeSubset]): M[NodeSubset] = {
      sys.error("todo")
    }

    /**
     * Represents the cost of a particular borg traversal plan, measured in terms of IO.
     * Computational complexity of algorithms occurring in memory is neglected. 
     * This should be thought of as a rough approximation that eliminates stupid choices.
     */
    final case class BorgTraversalCostModel private (ioCost: Long, size: Long, ticVars: Set[TicVar]) { self =>
      /**
       * Computes a new model derived from this one by cogroup with the specified set.
       */
      def consume(rightSize: Long, rightTicVars: Set[TicVar], accResort: Boolean): BorgTraversalCostModel = {
        val commonTicVars = self.ticVars intersect rightTicVars

        val unionTicVars = self.ticVars ++ rightTicVars

        val uniqueTicVars = unionTicVars -- commonTicVars

        // TODO: Develop a better model!
        val newSize = self.size.max(rightSize) * (uniqueTicVars.size + 1)

        val newIoCost = if (!accResort) {
          3 * rightSize
        } else {
          val inputCost = self.size + rightSize
          val resortCost = self.size * 2
          val outputCost = newSize

          inputCost + resortCost + outputCost
        }

        BorgTraversalCostModel(self.ioCost + newIoCost, newSize, self.ticVars ++ rightTicVars)
      }
    }

    object BorgTraversalCostModel {
      val Zero = new BorgTraversalCostModel(0, 0, Set.empty)
    }

    /**
     * Represents a step in a borg traversal plan. The step is defined by the following elements:
     * 
     *  1. The order of the accumulator prior to executing the step.
     *  2. The order of the accumulator after executing the step.
     *  3. The node being incorporated into the accumulator during this step.
     *  4. The tic variables of the node being incorporated into this step.
     *  5. The ordering of the node required for cogroup.
     * 
     */
    case class BorgTraversalPlanStep(accOrderPre: OrderingConstraint, node: MergeNode, accOrderPost: OrderingConstraint) { self =>
      // Tic variables before the step is executed:
      lazy val preTicVars = accOrderPre.ordering.toSet.flatten

      // Tic variables after the step is executed:
      lazy val postTicVars = accOrderPost.ordering.toSet.flatten

      // New tic variables gained during the step:
      lazy val newTicVars = postTicVars -- preTicVars

      def nodeTicVars: Set[TicVar] = node.ticVars

      // Whether or not the accumulator needs to be resorted for this step.
      lazy val accResort: Boolean = {
        val leftUniqueTicVars = preTicVars -- nodeTicVars
        val rightUniqueTicVars = nodeTicVars -- preTicVars

        val leftCommon = (accOrderPre - leftUniqueTicVars)
        val rightCommon = (nodeOrder - rightUniqueTicVars)

        val resortNeeded = (leftCommon & rightCommon).isEmpty

        //if (resortNeeded) println("RESORT NEEDED!!!!!!!!!!!!!!!")

        resortNeeded
      }

      // The order the node must be sorted by in order to perform the step:
      lazy val nodeOrder: OrderingConstraint = {
        val uniqueAccTicVars = preTicVars -- nodeTicVars

        accOrderPost - uniqueAccTicVars
      }

      // Choses an arbitrary order:
      def fixed: BorgTraversalPlanStep = BorgTraversalPlanStep.fromAccOrderPost(preTicVars, OrderingConstraint.fromFixed(accOrderPost.fixed), node)

      def fixedBefore(next: BorgTraversalPlanStep): BorgTraversalPlanStep = {
        accOrderPost & next.accOrderPre match {
          case Some(newAccOrderPost) => 
            BorgTraversalPlanStep.fromAccOrderPost(preTicVars, newAccOrderPost, node)

          case None => 
            self.fixed
        }
      }
    }

    object BorgTraversalPlanStep {
      def fromAccOrderPre(accOrderPre: OrderingConstraint, node: MergeNode): BorgTraversalPlanStep = {
        val preTicVars  = accOrderPre.ordering.toSet.flatten
        val nodeTicVars = node.ticVars

        val commonNodeTicVars = preTicVars intersect nodeTicVars
        val uniqueNodeTicVars = nodeTicVars -- preTicVars

        val nodeOrderConstraint = OrderingConstraint(Seq(commonNodeTicVars, uniqueNodeTicVars))

        val accOrderPost: OrderingConstraint = accOrderPre & nodeOrderConstraint match {
          case Some(constraint) => 
            // No resort necessary:
            constraint

          case None => 
            // Have to resort:
            val commonTicVars = preTicVars intersect nodeTicVars
            val unionTicVars = preTicVars union nodeTicVars

            OrderingConstraint(Seq(commonTicVars, unionTicVars -- commonTicVars))
        }

        BorgTraversalPlanStep(accOrderPre, node, accOrderPost)
      }

      def fromAccOrderPost(preTicVars: Set[TicVar], accOrderPost: OrderingConstraint, node: MergeNode): BorgTraversalPlanStep = {
        val postTicVars = accOrderPost.ordering.toSet.flatten
        val newTicVars = postTicVars -- preTicVars

        val accOrderPre = accOrderPost - newTicVars

        BorgTraversalPlanStep(accOrderPre, node, accOrderPost)
      }
    }

    /**
     * Represents a (perhaps partial) traversal plan for applying the borg algorithm,
     * together with the cost of the plan.
     */
    case class BorgTraversalPlan(steps: Vector[BorgTraversalPlanStep], costModel: BorgTraversalCostModel) {
      /**
       * The set of all tic variables after the plan has been executed.
       */
      def ticVars = steps.lastOption.map(_.postTicVars).getOrElse(Set.empty)

      /**
       * Generates a new plan by cogrouping the result of this plan with the specified node.
       */
      def consume(rightNode: MergeNode, rightSize: Long) = {
        val rightTicVars: Set[TicVar] = rightNode.ticVars

        val accOrderPre = steps.lastOption match {
          case Some(last) => last.accOrderPost
          case None => OrderingConstraint.Zero
        }

        val newStep = BorgTraversalPlanStep.fromAccOrderPre(accOrderPre, rightNode)

        val newCostModel = costModel.consume(rightSize, rightTicVars, newStep.accResort)

        copy(
          steps = steps :+ newStep,
          costModel = newCostModel
        )
      }

      /**
       * Generates a completely fixed plan (a totally defined sort order for the accumulate and nodes being consumed).
       */
      def fixed: BorgTraversalPlan = {
        // Back-propagate constraints to fix everything:
        val fixedSteps = {
          def fix0(unfixed: Vector[BorgTraversalPlanStep], fixed: Vector[BorgTraversalPlanStep]): Vector[BorgTraversalPlanStep] = {
            unfixed.lastOption match {
              case None => fixed

              case Some(unfixedHead) =>
                fixed.headOption match {
                  case None =>
                    // We've met all constraints, just pick any fixed ordering:
                    fix0(unfixed.init, Vector(unfixedHead.fixed))

                  case Some(fixedHead) =>
                    val newFixed = unfixedHead.fixedBefore(fixedHead)

                    fix0(unfixed.init, newFixed +: fixed)
                }
            }
          }

          fix0(steps, Vector.empty)
        }

        copy(steps = fixedSteps)
      }
    }

    object BorgTraversalPlan {
      val Zero = BorgTraversalPlan(Vector.empty, BorgTraversalCostModel.Zero) 
    }

    /**
     * Finds a traversal order for the borg algorithm which minimizes the number of resorts 
     * required.
     */
    def findBorgTraversalOrder(spanningGraph: MergeGraph, nodeOracle: MergeNode => NodeMetadata): BorgTraversalPlan = {
      // Find all the nodes accessible from the specified node (through edges):
      def connections(node: MergeNode): Set[MergeNode] = spanningGraph.edgesFor(node).flatMap(e => Set(e.a, e.b)) - node

      // TODO: Discard junk in the Map[Set[MergeNode], Set[BorgTraversalPlan]]

      def pick0(fixed: Set[MergeNode], unfixed: Set[MergeNode], options: Set[MergeNode], 
                plans: Map[Set[MergeNode], Set[BorgTraversalPlan]]): Map[Set[MergeNode], Set[BorgTraversalPlan]] = {
        // Normally, the choices are constrained to those nodes that are connected to those 
        // already merged into the Borg collective. However, initially, any node can be chosen
        // as the starting node. So this helper function factors out the duplication:
        def chooseFrom(choices: Set[MergeNode]): Map[Set[MergeNode], Set[BorgTraversalPlan]] = {
          // We have lots of choices, let's try each one and see what happens!
          choices.foldLeft(plans) {
            case (plans, choice) =>
              val nodeMetadata = nodeOracle(choice)

              val newFixed   = fixed + choice
              val newUnfixed = unfixed - choice
              val newOptions = options ++ connections(choice) -- newFixed

              plans(fixed).foldLeft(plans) {
                case (plans, fixedPlan) =>
                  val newPlan = fixedPlan.consume(choice, nodeMetadata.size)

                  val newSet = plans.getOrElse(newFixed, Set.empty) + newPlan

                  pick0(newFixed, newUnfixed, newOptions, plans + (newFixed -> newSet))
              }
          }
        }

        if (unfixed.isEmpty) plans                    // Nothing to traverse, return plans
        else if (options.isEmpty) chooseFrom(unfixed) // First pass through, choose any node
        else chooseFrom(options)                      // Can only choose from those merged into collective
      }
      
      val plans = pick0(Set.empty, spanningGraph.nodes, Set.empty, Map(Set.empty -> Set(BorgTraversalPlan.Zero)))

      plans(spanningGraph.nodes).toSeq.sortBy(_.costModel.ioCost).head
    }

    /* Take the distinctiveness of each node (in terms of group keys) and add it to the uber-cogrouped-all-knowing borgset */
    def borg(tuple: (MergeGraph, ConnectedSubgraph)): M[BorgResult] = {
      val (spanningGraph, connectedSubgraph) = tuple

      val metaForNode: Map[MergeNode, NodeSubset] = connectedSubgraph.groupBy(_.node).mapValues(_.head)

      // case class BorgResult(table: Table, groupKeyTrans: TransSpec1, idTrans: Map[GroupId, TransSpec1], rowTrans: Map[GroupId, TransSpec1])
      // case class NodeSubset(node: MergeNode, table: Table, idTrans: TransSpec1, 
      //                       targetTrans: Option[TransSpec1], groupKeyTrans: GroupKeyTrans, groupKeyPrefix: Seq[TicVar]) 
      val plan = findBorgTraversalOrder(spanningGraph, metaForNode)

      val planSteps = plan.fixed.steps

      val x =  planSteps.head
      val xs = planSteps.tail

      val node = metaForNode(x.node)

      val initial = (BorgResult(
                      table         = node.table, 
                      groupKeyTrans = node.groupKeyTrans.spec,
                      idTrans       = Map(node.node.binding.groupId -> node.idTrans),
                      rowTrans      = node.targetTrans.map(node.node.binding.groupId -> _).toMap
                    ), x).point[M]

      // TODO: Sort initial according to x

      (xs.foldLeft(initial) { 
        case (accM, newStep) => 
          accM.map {
            case ((acc, lastStep)) =>
              val node = metaForNode(newStep.node)

              // Cogroup on acc data and node data:

              (acc, newStep)
          }
      }).map(_._1)
    }

    def crossAll(borgResults: Set[BorgResult]): M[BorgResult] = {
      sys.error("todo")
    }

    // Create the omniverse
    def unionAll(borgResults: Set[BorgResult]): M[BorgResult] = {
      sys.error("todo")
    }

    /**
     * Merge controls the iteration over the table of group key values. 
     */
    def merge(grouping: GroupingSpec)(body: (Table, GroupId => M[Table]) => M[Table]): M[Table] = {
      // all of the universes will be unioned together.
      val universes = findBindingUniverses(grouping)
      val borgedUniverses: M[Stream[BorgResult]] = universes.toStream.map { universe =>
        val alignedSpanningGraphsM: M[Set[(MergeGraph, Map[GroupId, Set[NodeSubset]])]] = 
          universe.spanningGraphs.map { spanningGraph =>
            for (aligned <- alignOnEdges(spanningGraph))
              yield (spanningGraph, aligned)
          }.sequence

        val minimizedSpanningGraphsM: M[Set[(MergeGraph, ConnectedSubgraph)]] = for {
          aligned      <- alignedSpanningGraphsM
          intersected  <- aligned.map { 
                            case (spanningGraph, alignment) => 
                              for (intersected <- alignment.values.toStream.map(intersect).sequence)
                                yield (spanningGraph, intersected.toSet)
                          }.sequence
        } yield intersected

        for {
          spanningGraphs <- minimizedSpanningGraphsM
          borgedGraphs <- spanningGraphs.map(borg).sequence
          crossed <- crossAll(borgedGraphs)
        } yield crossed
      }.sequence

      for {
        omniverse <- borgedUniverses.flatMap(s => unionAll(s.toSet))
        result <- omniverse.table.partitionMerge(omniverse.groupKeyTrans) { partition =>
          val groups: M[Map[GroupId, Table]] = 
            for {
              grouped <- omniverse.rowTrans.toStream.map{ 
                           case (groupId, rowTrans) => 
                             val recordTrans = ArrayConcat(WrapArray(omniverse.idTrans(groupId)), WrapArray(rowTrans))
                             val sortByTrans = TransSpec.deepMap(omniverse.idTrans(groupId)) { 
                                                 case Leaf(_) => TransSpec1.DerefArray1 
                                               }

                             // TODO: This sort should not include the globalId so that it can dedup on the id.
                             partition.transform(recordTrans).sort(sortByTrans) map {
                               t => (groupId -> t.transform(DerefArrayStatic(TransSpec1.DerefArray1, JPathIndex(1))))
                             }
                         }.sequence
            } yield grouped.toMap

          body(
            partition.takeRange(0, 1).transform(omniverse.groupKeyTrans), 
            (groupId: GroupId) => groups.map(_(groupId))
          )
        }
      } yield result
    }
  }

  abstract class ColumnarTable(val slices: StreamT[M, Slice]) extends TableLike { self: Table =>
    import SliceTransform._

    /**
     * Folds over the table to produce a single value (stored in a singleton table).
     */
    def reduce[A](reducer: Reducer[A])(implicit monoid: Monoid[A]): M[A] = {  
      (slices map { s => reducer.reduce(s.logicalColumns, 0 until s.size) }).foldLeft(monoid.zero)((a, b) => monoid.append(a, b))
    }

    def compact(spec: TransSpec1): Table = {
      transform(FilterDefined(Leaf(Source), spec, AnyDefined)).normalize
    }

    /**
     * Performs a one-pass transformation of the keys and values in the table.
     * If the key transform is not identity, the resulting table will have
     * unknown sort order.
     */
    def transform(spec: TransSpec1): Table = {
      Table(Table.transformStream(composeSliceTransform(spec), slices))
    }
    
    /**
     * Cogroups this table with another table, using equality on the specified
     * transformation on rows of the table.
     */
    def cogroup(leftKey: TransSpec1, rightKey: TransSpec1, that: Table)(leftResultTrans: TransSpec1, rightResultTrans: TransSpec1, bothResultTrans: TransSpec2): Table = {
      class IndexBuffers(lInitialSize: Int, rInitialSize: Int) {
        val lbuf = new ArrayIntList(lInitialSize)
        val rbuf = new ArrayIntList(rInitialSize)
        val leqbuf = new ArrayIntList(lInitialSize max rInitialSize)
        val reqbuf = new ArrayIntList(lInitialSize max rInitialSize)

        @inline def advanceLeft(lpos: Int): Unit = {
          lbuf.add(lpos)
          rbuf.add(-1)
          leqbuf.add(-1)
          reqbuf.add(-1)
        }

        @inline def advanceRight(rpos: Int): Unit = {
          lbuf.add(-1)
          rbuf.add(rpos)
          leqbuf.add(-1)
          reqbuf.add(-1)
        }

        @inline def advanceBoth(lpos: Int, rpos: Int): Unit = {
          lbuf.add(-1)
          rbuf.add(-1)
          leqbuf.add(lpos)
          reqbuf.add(rpos)
        }

        def cogrouped[LR, RR, BR](lslice: Slice, 
                                  rslice: Slice, 
                                  leftTransform:  SliceTransform1[LR], 
                                  rightTransform: SliceTransform1[RR], 
                                  bothTransform:  SliceTransform2[BR]): (Slice, LR, RR, BR) = {

          val remappedLeft = lslice.remap(lbuf)
          val remappedRight = rslice.remap(rbuf)

          val remappedLeq = lslice.remap(leqbuf)
          val remappedReq = rslice.remap(reqbuf)

          val (ls0, lx) = leftTransform(remappedLeft)
          val (rs0, rx) = rightTransform(remappedRight)
          val (bs0, bx) = bothTransform(remappedLeq, remappedReq)

          assert(lx.size == rx.size && rx.size == bx.size)
          val resultSlice = lx zip rx zip bx

          (resultSlice, ls0, rs0, bs0)
        }

        override def toString = {
          "left: " + lbuf.toArray.mkString("[", ",", "]") + "\n" + 
          "right: " + rbuf.toArray.mkString("[", ",", "]") + "\n" + 
          "both: " + (leqbuf.toArray zip reqbuf.toArray).mkString("[", ",", "]")
        }
      }

      sealed trait NextStep
      case class SplitLeft(lpos: Int) extends NextStep
      case class SplitRight(rpos: Int) extends NextStep
      case class AppendLeft(lpos: Int, rpos: Int, rightCartesian: Option[(Int, Option[Int])]) extends NextStep
      case class AppendRight(lpos: Int, rpos: Int, rightCartesian: Option[(Int, Option[Int])]) extends NextStep

      def cogroup0[LK, RK, LR, RR, BR](stlk: SliceTransform1[LK], strk: SliceTransform1[RK], stlr: SliceTransform1[LR], strr: SliceTransform1[RR], stbr: SliceTransform2[BR]) = {
        case class SlicePosition[K](
          /** The position in the current slice. This will only be nonzero when the slice has been appended
            * to as a result of a cartesian crossing the slice boundary */
          pos: Int, 
          /** Present if not in a final right or left run. A pair of a key slice that is parallel to the 
            * current data slice, and the value that is needed as input to sltk or srtk to produce the next key. */
          keyState: K,
          key: Slice, 
          /** The current slice to be operated upon. */
          data: Slice, 
          /** The remainder of the stream to be operated upon. */
          tail: StreamT[M, Slice])

        sealed trait CogroupState
        case class EndLeft(lr: LR, lhead: Slice, ltail: StreamT[M, Slice]) extends CogroupState
        case class Cogroup(lr: LR, rr: RR, br: BR, left: SlicePosition[LK], right: SlicePosition[RK], rightReset: Option[(Int, Option[Int])]) extends CogroupState
        case class EndRight(rr: RR, rhead: Slice, rtail: StreamT[M, Slice]) extends CogroupState
        case object CogroupDone extends CogroupState

        val Reset = -1

        // step is the continuation function fed to uncons. It is called once for each emitted slice
        def step(state: CogroupState): M[Option[(Slice, CogroupState)]] = {
          // step0 is the inner monadic recursion needed to cross slice boundaries within the emission of a slice
          def step0(lr: LR, rr: RR, br: BR, leftPosition: SlicePosition[LK], rightPosition: SlicePosition[RK], rightReset: Option[(Int, Option[Int])])
                   (ibufs: IndexBuffers = new IndexBuffers(leftPosition.key.size, rightPosition.key.size)): M[Option[(Slice, CogroupState)]] = {
            val SlicePosition(lpos0, lkstate, lkey, lhead, ltail) = leftPosition
            val SlicePosition(rpos0, rkstate, rkey, rhead, rtail) = rightPosition

            val comparator = Slice.rowComparatorFor(lkey, rkey) { slice => 
              // since we've used the key transforms, and since transforms are contracturally
              // forbidden from changing slice size, we can just use all
              slice.columns.keys.toList.sorted
            }

            // the inner tight loop; this will recur while we're within the bounds of
            // a pair of slices. Any operation that must cross slice boundaries
            // must exit this inner loop and recur through the outer monadic loop
            // xrstart is an int with sentinel value for effieiency, but is Option at the slice level.
            @inline @tailrec def buildRemappings(lpos: Int, rpos: Int, xrstart: Int, xrend: Int, endRight: Boolean): NextStep = {
              if (xrstart != -1) {
                // We're currently in a cartesian. 
                if (lpos < lhead.size && rpos < rhead.size) {
                  comparator.compare(lpos, rpos) match {
                    case LT => 
                      buildRemappings(lpos + 1, xrstart, xrstart, rpos, endRight)
                    case GT => 
                      // catch input-out-of-order errors early
                      if (xrend == -1) sys.error("Inputs are not sorted; value on the left exceeded value on the right at the end of equal span.")
                      buildRemappings(lpos, xrend, Reset, Reset, endRight)
                    case EQ => 
                      ibufs.advanceBoth(lpos, rpos)
                      buildRemappings(lpos, rpos + 1, xrstart, xrend, endRight)
                  }
                } else if (lpos < lhead.size) {
                  if (endRight) {
                    // we know there won't be another slice on the RHS, so just keep going to exhaust the left
                    buildRemappings(lpos + 1, xrstart, xrstart, rpos, endRight)
                  } else {
                    // right slice is exhausted, so we need to append to that slice from the right tail
                    // then continue in the cartesian
                    AppendRight(lpos, rpos, Some((xrstart, (xrend != -1).option(xrend))))
                  }
                } else if (rpos < rhead.size) {
                  // left slice is exhausted, so we need to append to that slice from the left tail
                  // then continue in the cartesian
                  AppendLeft(lpos, rpos, Some((xrstart, (xrend != -1).option(xrend))))
                } else {
                  sys.error("This state should be unreachable, since we only increment one side at a time.")
                }
              } else {
                // not currently in a cartesian, hence we can simply proceed.
                if (lpos < lhead.size && rpos < rhead.size) {
                  comparator.compare(lpos, rpos) match {
                    case LT => 
                      ibufs.advanceLeft(lpos)
                      buildRemappings(lpos + 1, rpos, Reset, Reset, endRight)
                    case GT => 
                      ibufs.advanceRight(rpos)
                      buildRemappings(lpos, rpos + 1, Reset, Reset, endRight)
                    case EQ =>
                      ibufs.advanceBoth(lpos, rpos)
                      buildRemappings(lpos, rpos + 1, rpos, Reset, endRight)
                  }
                } else if (lpos < lhead.size) {
                  // right side is exhausted, so we should just split the left and emit 
                  SplitLeft(lpos)
                } else if (rpos < rhead.size) {
                  // left side is exhausted, so we should just split the right and emit
                  SplitRight(rpos)
                } else {
                  sys.error("This state should be unreachable, since we only increment one side at a time.")
                }
              }
            }

            def continue(nextStep: NextStep): M[Option[(Slice, CogroupState)]] = nextStep match {
              case SplitLeft(lpos) =>
                val (lpref, lsuf) = lhead.split(lpos + 1)
                val (_, lksuf) = lkey.split(lpos + 1)
                val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(lpref, rhead, 
                                                                     SliceTransform1[LR](lr, stlr.f),
                                                                     SliceTransform1[RR](rr, strr.f),
                                                                     SliceTransform2[BR](br, stbr.f))

                rtail.uncons map {
                  case Some((nextRightHead, nextRightTail)) => 
                    val (rkstate0, rkey0) = strk.f(rkstate, nextRightHead)
                    val nextState = Cogroup(lr0, rr0, br0, 
                                            SlicePosition(0, lkstate,  lksuf, lsuf, ltail),
                                            SlicePosition(0, rkstate0, rkey0, nextRightHead, nextRightTail), None) 

                    Some(completeSlice -> nextState)

                  case None => 
                    val nextState = EndLeft(lr0, lsuf, ltail)
                    Some(completeSlice -> nextState)
                }

              case SplitRight(rpos) => 
                val (rpref, rsuf) = rhead.split(rpos + 1)
                val (_, rksuf) = rkey.split(rpos + 1)
                val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(lhead, rpref, 
                                                                     SliceTransform1[LR](lr, stlr.f),
                                                                     SliceTransform1[RR](rr, strr.f),
                                                                     SliceTransform2[BR](br, stbr.f))

                ltail.uncons map {
                  case Some((nextLeftHead, nextLeftTail)) =>
                    val (lkstate0, lkey0) = stlk.f(lkstate, nextLeftHead)
                    val nextState = Cogroup(lr0, rr0, br0,
                                            SlicePosition(0, lkstate0, lkey0, nextLeftHead, nextLeftTail),
                                            SlicePosition(0, rkstate,  rksuf, rsuf, rtail), None)

                    Some(completeSlice -> nextState)

                  case None =>
                    val nextState = EndRight(rr0, rsuf, rtail)
                    Some(completeSlice -> nextState)
                }

              case AppendLeft(lpos, rpos, rightReset) => 
                ltail.uncons flatMap {
                  case Some((nextLeftHead, nextLeftTail)) =>
                    val (lkstate0, lkey0) = stlk.f(lkstate, nextLeftHead)
                    step0(lr, rr, br,
                          SlicePosition(lpos, lkstate0, lkey append lkey0, lhead append nextLeftHead, nextLeftTail),
                          SlicePosition(rpos, rkstate, rkey, rhead, rtail), 
                          rightReset)(ibufs)

                  case None => 
                    rightReset.flatMap(_._2) map { rend =>
                      // We've found an actual end to the cartesian on the right, and have run out of 
                      // data inside the cartesian on the left, so we have to split the right, emit,
                      // and then end right
                      val (rpref, rsuf) = rhead.split(rend)
                      val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(lhead, rpref,
                                                                           SliceTransform1[LR](lr, stlr.f),
                                                                           SliceTransform1[RR](rr, strr.f),
                                                                           SliceTransform2[BR](br, stbr.f))

                      val nextState = EndRight(rr0, rsuf, rtail)
                      M.point(Some((completeSlice -> nextState)))
                    } getOrElse {
                      // the end of the cartesian must be found on the right before trying to find the end
                      // on the left, so if we're here then the right must be 
                      val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(lhead, rhead,
                                                                           SliceTransform1[LR](lr, stlr.f),
                                                                           SliceTransform1[RR](rr, strr.f),
                                                                           SliceTransform2[BR](br, stbr.f))
                      M.point(Some(completeSlice -> CogroupDone))
                    }
                }

              case AppendRight(lpos, rpos, rightReset) => 
                rtail.uncons flatMap {
                  case Some((nextRightHead, nextRightTail)) =>
                    val (rkstate0, rkey0) = strk.f(rkstate, nextRightHead)
                    step0(lr, rr, br, 
                          SlicePosition(lpos, lkstate, lkey, lhead, ltail), 
                          SlicePosition(rpos, rkstate0, rkey append rkey0, rhead append nextRightHead, nextRightTail),
                          rightReset)(ibufs)

                  case None =>
                    // run out the left hand side, since the right will never advance
                    continue(buildRemappings(lpos, rpos, rightReset.map(_._1).getOrElse(Reset), rightReset.flatMap(_._2).getOrElse(Reset), true))
                }
            }

            continue(buildRemappings(lpos0, rpos0, rightReset.map(_._1).getOrElse(Reset), rightReset.flatMap(_._2).getOrElse(Reset), false))
          } // end of step0 

          state match {
            case EndLeft(lr, data, tail) =>
              val (lr0, leftResult) = stlr.f(lr, data)
              tail.uncons map { unconsed =>
                Some(leftResult -> (unconsed map { case (nhead, ntail) => EndLeft(lr0, nhead, ntail) } getOrElse CogroupDone))
              }

            case Cogroup(lr, rr, br, left, right, rightReset) =>
              step0(lr, rr, br, left, right, rightReset)()

            case EndRight(rr, data, tail) =>
              val (rr0, rightResult) = strr.f(rr, data)
              tail.uncons map { unconsed =>
                Some(rightResult -> (unconsed map { case (nhead, ntail) => EndRight(rr0, nhead, ntail) } getOrElse CogroupDone))
              }

            case CogroupDone => M.point(None)
          }
        } // end of step

        val initialState = for {
          leftUnconsed  <- self.slices.uncons
          rightUnconsed <- that.slices.uncons
        } yield {
          val cogroup = for {
            (leftHead, leftTail)   <- leftUnconsed
            (rightHead, rightTail) <- rightUnconsed
          } yield {
            val (lkstate, lkey) = stlk(leftHead)
            val (rkstate, rkey) = strk(rightHead)
            Cogroup(stlr.initial, strr.initial, stbr.initial, 
                    SlicePosition(0, lkstate, lkey, leftHead,  leftTail), 
                    SlicePosition(0, rkstate, rkey, rightHead, rightTail), None)
          } 
          
          cogroup orElse {
            leftUnconsed map {
              case (head, tail) => EndLeft(stlr.initial, head, tail)
            }
          } orElse {
            rightUnconsed map {
              case (head, tail) => EndRight(strr.initial, head, tail)
            }
          }
        }

        Table(StreamT.wrapEffect(initialState map { state => StreamT.unfoldM[M, Slice, CogroupState](state getOrElse CogroupDone)(step) }))
      }

      cogroup0(composeSliceTransform(leftKey), 
               composeSliceTransform(rightKey), 
               composeSliceTransform(leftResultTrans), 
               composeSliceTransform(rightResultTrans), 
               composeSliceTransform2(bothResultTrans))
    }

    /**
     * Performs a full cartesian cross on this table with the specified table,
     * applying the specified transformation to merge the two tables into
     * a single table.
     */
    def cross(that: Table)(spec: TransSpec2): Table = {
      def cross0[A](transform: SliceTransform2[A]): M[StreamT[M, Slice]] = {
        case class CrossState(a: A, position: Int, tail: StreamT[M, Slice])

        def crossLeftSingle(lhead: Slice, right: StreamT[M, Slice]): StreamT[M, Slice] = {
          def step(state: CrossState): M[Option[(Slice, CrossState)]] = {
            if (state.position < lhead.size) {
              state.tail.uncons flatMap {
                case Some((rhead, rtail0)) =>
                  val lslice = new Slice {
                    val size = rhead.size
                    val columns = lhead.columns.mapValues { cf.util.Remap({ case _ => state.position })(_).get }
                  }

                  val (a0, resultSlice) = transform.f(state.a, lslice, rhead)
                  M.point(Some((resultSlice, CrossState(a0, state.position, rtail0))))
                  
                case None => 
                  step(CrossState(state.a, state.position + 1, right))
              }
            } else {
              M.point(None)
            }
          }

          StreamT.unfoldM(CrossState(transform.initial, 0, right))(step _)
        }
        
        def crossRightSingle(left: StreamT[M, Slice], rhead: Slice): StreamT[M, Slice] = {
          def step(state: CrossState): M[Option[(Slice, CrossState)]] = {
            state.tail.uncons map {
              case Some((lhead, ltail0)) =>
                val lslice = new Slice {
                  val size = rhead.size * lhead.size
                  val columns = lhead.columns.mapValues { cf.util.Remap({ case i => i / rhead.size })(_).get }
                }

                val rslice = new Slice {
                  val size = rhead.size * lhead.size
                  val columns = rhead.columns.mapValues { cf.util.Remap({ case i => i % rhead.size })(_).get }
                }

                val (a0, resultSlice) = transform.f(state.a, lslice, rslice)
                Some((resultSlice, CrossState(a0, state.position, ltail0)))
                
              case None => None
            }
          }

          StreamT.unfoldM(CrossState(transform.initial, 0, left))(step _)
        }

        def crossBoth(ltail: StreamT[M, Slice], rtail: StreamT[M, Slice]): StreamT[M, Slice] = {
          ltail.flatMap(crossLeftSingle(_ :Slice, rtail))
        }

        this.slices.uncons flatMap {
          case Some((lhead, ltail)) =>
            that.slices.uncons flatMap {
              case Some((rhead, rtail)) =>
                for {
                  lempty <- ltail.isEmpty //TODO: Scalaz result here is negated from what it should be!
                  rempty <- rtail.isEmpty
                } yield {
                  if (lempty) {
                    // left side is a small set, so restart it in memory
                    crossLeftSingle(lhead, rhead :: rtail)
                  } else if (rempty) {
                    // right side is a small set, so restart it in memory
                    crossRightSingle(lhead :: ltail, rhead)
                  } else {
                    // both large sets, so just walk the left restarting the right.
                    crossBoth(this.slices, that.slices)
                  }
                }

              case None => M.point(StreamT.empty[M, Slice])
            }

          case None => M.point(StreamT.empty[M, Slice])
        }
      }

      Table(StreamT(cross0(composeSliceTransform2(spec)) map { tail => StreamT.Skip(tail) }))
    }
    
    /**
     * Yields a new table with distinct rows. Assumes this table is sorted.
     */
    def distinct(spec: TransSpec1): Table = {
      def distinct0[T](id: SliceTransform1[Option[Slice]], filter: SliceTransform1[T]): Table = {
        def stream(state: (Option[Slice], T), slices: StreamT[M, Slice]): StreamT[M, Slice] = StreamT(
          for {
            head <- slices.uncons
          } yield
            head map { case (s, sx) =>
              val (prevFilter, cur) = id.f(state._1, s)
              val (nextT, curFilter) = filter.f(state._2, s)
              
              val next = cur.distinct(prevFilter, curFilter)
              
              StreamT.Yield(next, stream((if (next.size > 0) Some(curFilter) else prevFilter, nextT), sx))
            } getOrElse {
              StreamT.Done
            }
        )
        
        Table(stream((id.initial, filter.initial), slices))
      }

      distinct0(SliceTransform.identity(None : Option[Slice]), composeSliceTransform(spec))
    }
    
    def takeRange(startIndex: Long, numberToTake: Long): Table = {  //in slice.takeRange, need to numberToTake to not be larger than the slice. 
      def loop(s: Stream[Slice], readSoFar: Long): Stream[Slice] = s match {
        case h #:: rest if (readSoFar + h.size) < startIndex => loop(rest, readSoFar + h.size)
        case rest if readSoFar < startIndex + 1 => {
          inner(rest, 0, (startIndex - readSoFar).toInt)
        }
        case _ => Stream.empty[Slice]
      }

      def inner(s: Stream[Slice], takenSoFar: Long, sliceStartIndex: Int): Stream[Slice] = s match {
        case h #:: rest if takenSoFar < numberToTake && h.size > numberToTake - takenSoFar => {
          val needed = h.takeRange(sliceStartIndex, (numberToTake - takenSoFar).toInt)
          needed #:: Stream.empty[Slice]
        }
        case h #:: rest if takenSoFar < numberToTake =>
          h #:: inner(rest, takenSoFar + h.size, 0)
        case _ => Stream.empty[Slice]
      }

      Table(StreamT.fromStream(slices.toStream.map(loop(_, 0))))
    }

    /**
     * In order to call partitionMerge, the table must be sorted according to 
     * the values specified by the partitionBy transspec.
     */
    def partitionMerge(partitionBy: TransSpec1)(f: Table => M[Table]): M[Table] = {
      @tailrec
      def findEnd(index: Int, size: Int, step: Int, compare: Int => Ordering): Int = {
        if (index < size) {
          compare(index) match {
            case GT =>
              sys.error("Inputs to partitionMerge not sorted.")

            case EQ => 
              findEnd(index + step, size, step, compare)

            case LT =>
              if (step <= 1) index else findEnd(index - (step / 2), size, step / 2, compare)
          }
        } else {
          size
        }
      }

      def subTable(comparatorGen: Slice => (Int => Ordering), slices: StreamT[M, Slice]): StreamT[M, Slice] = StreamT.wrapEffect {
        slices.uncons map {
          case Some((head, tail)) =>
            val headComparator = comparatorGen(head)
            val spanEnd = findEnd(head.size - 1, head.size, head.size, headComparator)
            if (spanEnd < head.size) {
              val (prefix, _) = head.split(spanEnd) 
              prefix :: StreamT.empty[M, Slice]
            } else {
              head :: subTable(comparatorGen, tail)
            }
            
          case None =>
            StreamT.empty[M, Slice]
        }
      }

      def dropAndSplit(comparatorGen: Slice => (Int => Ordering), slices: StreamT[M, Slice]): StreamT[M, Slice] = StreamT.wrapEffect {
        slices.uncons map {
          case Some((head, tail)) =>
            val headComparator = comparatorGen(head)
            val spanEnd = findEnd(head.size - 1, head.size, head.size, headComparator)
            if (spanEnd < head.size) {
              val (_, suffix) = head.split(spanEnd) 
              stepPartition(suffix, tail)
            } else {
              dropAndSplit(comparatorGen, tail)
            }
            
          case None =>
            StreamT.empty[M, Slice]
        }
      }

      def stepPartition(head: Slice, tail: StreamT[M, Slice]): StreamT[M, Slice] = {
        val comparatorGen = (s: Slice) => {
          val rowComparator = Slice.rowComparatorFor(head, s) {
            (s0: Slice) => s0.columns.keys.collect({ case ref @ ColumnRef(JPath(JPathField("0"), _ @ _*), _) => ref }).toList.sorted
          }

          (i: Int) => rowComparator.compare(0, i)
        }

        val groupedM = f(Table(subTable(comparatorGen, head :: tail)).transform(DerefObjectStatic(Leaf(Source), JPathField("1"))))
        val groupedStream: StreamT[M, Slice] = StreamT.wrapEffect(groupedM.map(_.slices))

        groupedStream ++ dropAndSplit(comparatorGen, head :: tail)
      }

      val keyTrans = ObjectConcat(
        WrapObject(partitionBy, "0"),
        WrapObject(Leaf(Source), "1")
      )

      this.transform(keyTrans).slices.uncons map {
        case Some((head, tail)) =>
          Table(stepPartition(head, tail))
        case None =>
          Table.empty
      }
    }
    def normalize: Table = Table(slices.filter(!_.isEmpty))

    def toStrings: M[Iterable[String]] = {
      toEvents { (slice, row) => slice.toString(row) }
    }
    
    def toJson: M[Iterable[JValue]] = {
      toEvents { (slice, row) => slice.toJson(row) }
    }

    private def toEvents[A](f: (Slice, RowId) => Option[A]): M[Iterable[A]] = {
      for (stream <- self.compact(Leaf(Source)).slices.toStream) yield {
        (for (slice <- stream; i <- 0 until slice.size) yield f(slice, i)).flatten 
      }
    }
  }
}
// vim: set ts=4 sw=4 et:
