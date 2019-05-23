/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.v4_0.ast.prettifier

import org.neo4j.cypher.internal.v4_0.ast.prettifier.ExpressionStringifier._
import org.neo4j.cypher.internal.v4_0.expressions._
import org.neo4j.cypher.internal.v4_0.util.InternalException

case class ExpressionStringifier(
  extender: Expression => String = failingExtender,
  alwaysParens: Boolean = false
) extends (Expression => String) {

  val patterns = PatternStringifier(this)

  def apply(ast: Expression): String = {
    stringify(ast)
  }

  private def inner(parent: Expression)(ast: Expression): String = {
    val str = stringify(ast)
    if (alwaysParens)
      "(" + str + ")"
    else {
      val thisPrecedence = precedenceLevel(parent)
      val argumentPrecedence = precedenceLevel(ast)
      if (argumentPrecedence >= thisPrecedence)
        "(" + str + ")"
      else
        str
    }
  }

  private def stringify(ast: Expression): String = {
    ast match {

      case StringLiteral(txt) =>
        quote(txt)


      case l: Literal =>
        l.asCanonicalStringVal

      case e: BinaryOperatorExpression =>
        s"${inner(ast)(e.lhs)} ${operator(e)} ${inner(ast)(e.rhs)}"

      case Variable(v) =>
        backtick(v)

      case ListLiteral(expressions) =>
        expressions.map(inner(ast)).mkString("[", ", ", "]")

      case FunctionInvocation(namespace, functionName, distinct, args) =>
        val ns = namespace.parts.mkString(".")
        val np = if (namespace.parts.isEmpty) "" else "."
        val ds = if (distinct) "DISTINCT " else ""
        val as = args.map(inner(ast)).mkString(", ")
        s"$ns$np${functionName.name}($ds$as)"

      case Property(m, k) =>
        s"${inner(ast)(m)}.${backtick(k.name)}"


      case MapExpression(items) =>
        val is = items.map({
          case (k, i) => s"${backtick(k.name)}: ${inner(ast)(i)}"
        }).mkString(", ")
        s"{$is}"

      case Parameter(name, _) =>
        s"$$${backtick(name)}"

      case _: CountStar =>
        s"count(*)"

      case IsNull(arg) =>
        s"${inner(ast)(arg)} IS NULL"

      case IsNotNull(arg) =>
        s"${inner(ast)(arg)} IS NOT NULL"

      case ContainerIndex(exp, idx) =>
        s"${inner(ast)(exp)}[${inner(ast)(idx)}]"

      case ListSlice(list, start, end) =>
        val l = start.map(inner(ast)).getOrElse("")
        val r = end.map(inner(ast)).getOrElse("")
        s"${inner(ast)(list)}[$l..$r]"

      case PatternExpression(RelationshipsPattern(relChain)) =>
        patterns.apply(relChain)

      case FilterExpression(scope, expression) =>
        s"filter${prettyScope(scope, expression)}"

      case AnyIterablePredicate(scope, expression) =>
        s"any${prettyScope(scope, expression)}"

      case Not(arg) =>
        s"not ${inner(ast)(arg)}"

      case ListComprehension(s, expression) =>
        val v = apply(s.variable)
        val p = s.innerPredicate.map(pr => " WHERE " + inner(ast)(pr)).getOrElse("")
        val e = s.extractExpression.map(ex => " | " + inner(ast)(ex)).getOrElse("")
        val expr = inner(ast)(expression)
        s"[$v IN $expr$p$e]"

      case ExtractExpression(s, expression) =>
        val v = apply(s.variable)
        val p = s.innerPredicate.map(e => " WHERE " + inner(ast)(e)).getOrElse("")
        val e = s.extractExpression.map(e => " | " + inner(ast)(e)).getOrElse("")
        val expr = inner(ast)(expression)
        s"extract($v IN $expr$p$e)"

      case PatternComprehension(variable, RelationshipsPattern(relChain), predicate, proj) =>
        val v = variable.map(apply).map(_ + " = ").getOrElse("")
        val p = patterns.apply(relChain)
        val w = predicate.map(inner(ast)).map(" WHERE " + _).getOrElse("")
        val b = inner(ast)(proj)
        s"[$v$p$w | $b]"


      case HasLabels(arg, labels) =>
        val l = labels.map(label => backtick(label.name)).mkString(":", ":", "")
        s"${inner(ast)(arg)}$l"

      case AllIterablePredicate(scope, e) =>
        s"all${prettyScope(scope, e)}"

      case NoneIterablePredicate(scope, e) =>
        s"none${prettyScope(scope, e)}"

      case SingleIterablePredicate(scope, e) =>
        s"single${prettyScope(scope, e)}"

      case MapProjection(variable, items) =>
        val itemsText = items.map(apply).mkString(", ")
        s"${apply(variable)}{$itemsText}"

      case LiteralEntry(k, e) =>
        s"${backtick(k.name)}: ${inner(ast)(e)}"

      case VariableSelector(v) =>
        apply(v)

      case PropertySelector(v) =>
        s".${apply(v)}"

      case AllPropertiesSelector() => ".*"

      case CaseExpression(expression, alternatives, default) =>
        Seq(
          Seq("CASE"),
          for {e <- expression.toSeq; i <- Seq(inner(ast)(e))} yield i,
          for {(e1, e2) <- alternatives; i <- Seq("WHEN", inner(ast)(e1), "THEN", inner(ast)(e2))} yield i,
          for {e <- default.toSeq; i <- Seq("ELSE", inner(ast)(e))} yield i,
          Seq("END")
        ).flatten.mkString(" ")

      case Ands(expressions) =>

        type BinOp = Expression with BinaryOperatorExpression

        def findChain: Option[List[BinOp]] =
          expressions.toList
            .collect {
              case e: BinaryOperatorExpression => e
            }
            .permutations.find { chain =>
            def hasAll = expressions.forall(chain.contains)

            def aligns = chain.sliding(2).forall(p => p.head.rhs == p.last.lhs)

            hasAll && aligns
          }

        findChain match {
          case Some(chain) =>
            val head = apply(chain.head)
            val tail = chain.tail.flatMap(o => List(o.canonicalOperatorSymbol, inner(ast)(o.rhs)))
            (head :: tail).mkString(" ")
          case None        =>
            expressions.map(x => inner(ast)(x)).mkString(" AND ")
        }

      case Ors(expressions) =>
        expressions.map(x => inner(ast)(x)).mkString(" OR ")

      case ShortestPathExpression(pattern) =>
        patterns.apply(pattern)

      case ReduceExpression(ReduceScope(Variable(acc), Variable(identifier), expression), init, list) =>
        val a = backtick(acc)
        val v = backtick(identifier)
        val i = inner(ast)(init)
        val l = inner(ast)(list)
        val e = inner(ast)(expression)
        s"reduce($a = $i, $v IN $l | $e)"

      case _: ExtractScope | _: FilterScope | _: ReduceScope =>
        // These are not really expressions, they are part of expressions
        ""

      case ExistsSubClause(pat, where) =>
        val p = patterns.apply(pat)
        val w = where.map(wh => s" WHERE ${inner(ast)(wh)}").getOrElse("")
        s"EXISTS { MATCH $p$w }"

      case UnaryAdd(r) =>
        val i = inner(ast)(r)
        s"+$i"

      case UnarySubtract(r) =>
        val i = inner(ast)(r)
        s"-$i"

      case _ =>
        extender(ast)
    }
  }

  private def prettyScope(s: FilterScope, expression: Expression) = {
    Seq(
      for {i <- Seq(apply(s.variable), "IN", inner(s)(expression))} yield i,
      for {p <- s.innerPredicate.toSeq; i <- Seq("WHERE", inner(s)(p))} yield i
    ).flatten.mkString("(", " ", ")")
  }

  private def precedenceLevel(in: Expression): Int = in match {
    case _: Or |
         _: Ors =>
      12

    case _: Xor =>
      11

    case _: And |
         _: Ands =>
      10

    case _: Not =>
      9

    case _: Equals |
         _: NotEquals |
         _: InvalidNotEquals |
         _: GreaterThan |
         _: GreaterThanOrEqual |
         _: LessThan |
         _: LessThanOrEqual =>
      8

    case _: Add |
         _: Subtract =>
      7

    case _: Multiply |
         _: Divide |
         _: Modulo =>
      6

    case _: Pow =>
      5

    case _: UnaryAdd |
         _: UnarySubtract =>
      4

    case _: RegexMatch |
         _: In |
         _: StartsWith |
         _: EndsWith |
         _: Contains |
         _: IsNull |
         _: IsNotNull =>
      3

    case _: Property |
         _: HasLabels |
         _: ContainerIndex |
         _: ListSlice =>
      2

    case _ =>
      1

  }

  def operator(e: BinaryOperatorExpression): String = e match {
    case s: StartsWith => "STARTS WITH"
    case s: EndsWith   => "ENDS WITH"
    case o             => e.canonicalOperatorSymbol
  }
}

object ExpressionStringifier {

  val failingExtender: Expression => String =
    e => throw new InternalException(s"failed to pretty print $e")

  def backtick(txt: String): String = {
    val needsBackticks = !(Character.isJavaIdentifierStart(txt.head) && txt.tail.forall(Character.isJavaIdentifierPart))
    if (needsBackticks)
      s"`$txt`"
    else
      txt
  }

  def quote(txt: String): String = {
    val containsSingle = txt.contains('\'')
    val containsDouble = txt.contains('"')
    if (containsDouble && containsSingle)
      "\"" + txt.replaceAll("\"", "\\\\\"") + "\""
    else if (containsDouble)
      "'" + txt + "'"
    else
      "\"" + txt + "\""
  }
}
