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

import org.neo4j.cypher.internal.v4_0.ast.{Skip, Statement, _}
import org.neo4j.cypher.internal.v4_0.expressions.{NodePattern, PatternElement, PatternPart, RelationshipChain, _}
import org.neo4j.cypher.internal.v4_0.util.InputPosition

case class Prettifier(expr: ExpressionStringifier) {

  def asString(statement: Statement): String = statement match {
    case Query(maybePeriodicCommit, part) =>
      maybePeriodicCommit match {
        case None => queryPart(part)
        case Some(periodicCommit) =>
          val sb = new StringBuilder
          sb ++= "USING PERIODIC COMMIT"
          for (x <- periodicCommit.size) {
            sb += ' '
            sb ++= x.value.toString
          }
          sb ++= NL
          sb ++= queryPart(part)
          sb.result()
      }

    case CreateIndex(LabelName(label), properties) =>
      s"CREATE INDEX ON :$label${properties.map(_.name).mkString("(", ", ", ")")}"

    case DropIndex(LabelName(label), properties) =>
      s"DROP INDEX ON :$label${properties.map(_.name).mkString("(", ", ", ")")}"

    case CreateNodeKeyConstraint(Variable(variable), LabelName(label), properties) =>
      s"CREATE CONSTRAINT ON ($variable:$label) ASSERT ${asString(properties)} IS NODE KEY"

    case DropNodeKeyConstraint(Variable(variable), LabelName(label), properties) =>
      s"DROP CONSTRAINT ON ($variable:$label) ASSERT ${properties.map(_.asCanonicalStringVal).mkString("(", ", ", ")")} IS NODE KEY"

    case CreateUniquePropertyConstraint(Variable(variable), LabelName(label), properties) =>
      s"CREATE CONSTRAINT ON ($variable:$label) ASSERT ${properties.map(_.asCanonicalStringVal).mkString("(", ", ", ")")} IS UNIQUE"

    case DropUniquePropertyConstraint(Variable(variable), LabelName(label), properties) =>
      s"DROP CONSTRAINT ON ($variable:$label) ASSERT ${properties.map(_.asCanonicalStringVal).mkString("(", ", ", ")")} IS UNIQUE"

    case CreateNodePropertyExistenceConstraint(Variable(variable), LabelName(label), property) =>
      s"CREATE CONSTRAINT ON ($variable:$label) ASSERT exists(${property.asCanonicalStringVal})"

    case DropNodePropertyExistenceConstraint(Variable(variable), LabelName(label), property) =>
      s"DROP CONSTRAINT ON ($variable:$label) ASSERT exists(${property.asCanonicalStringVal})"

    case CreateRelationshipPropertyExistenceConstraint(Variable(variable), RelTypeName(relType), property) =>
      s"CREATE CONSTRAINT ON ()-[$variable:$relType]-() ASSERT exists(${property.asCanonicalStringVal})"

    case DropRelationshipPropertyExistenceConstraint(Variable(variable), RelTypeName(relType), property) =>
      s"DROP CONSTRAINT ON ()-[$variable:$relType]-() ASSERT exists(${property.asCanonicalStringVal})"

    case x: ShowUsers =>
      s"${x.name}"

    case x @ CreateUser(userName, _, initialParameterPassword, requirePasswordChange, suspended) =>
      val userNameString = Prettifier.escapeName(userName)
      val password = if (initialParameterPassword.isDefined)
        s"$$${initialParameterPassword.get.name}"
      else "'******'"
      val passwordString = s"SET PASSWORD $password CHANGE ${if (!requirePasswordChange) "NOT " else ""}REQUIRED"
      val statusString = if(suspended.isDefined) s" SET STATUS ${if (suspended.get) "SUSPENDED" else "ACTIVE"}"
                         else ""
      s"${x.name} $userNameString $passwordString$statusString"

    case x @ DropUser(userName) =>
      s"${x.name} ${Prettifier.escapeName(userName)}"

    case x @ AlterUser(userName, initialStringPassword, initialParameterPassword, requirePasswordChange, suspended) =>
      val userNameString = Prettifier.escapeName(userName)
      val passwordString = if (initialStringPassword.isDefined) s" '******'"
      else if (initialParameterPassword.isDefined)
        s" $$${initialParameterPassword.get.name}"
      else ""
      val passwordModeString = if (requirePasswordChange.isDefined)
        s" CHANGE ${if (!requirePasswordChange.get) "NOT " else ""}REQUIRED"
      else
        ""
      val passwordPrefix = if (passwordString.nonEmpty || passwordModeString.nonEmpty) " SET PASSWORD" else ""
      val statusString = if (suspended.isDefined) s" SET STATUS ${if (suspended.get) "SUSPENDED" else "ACTIVE"}" else ""
      s"${x.name} $userNameString$passwordPrefix$passwordString$passwordModeString$statusString"

    case x @ ShowRoles(withUsers, _) =>
      s"${x.name}${if (withUsers) " WITH USERS" else ""}"

    case x @ CreateRole(roleName, _) =>
      s"${x.name} ${Prettifier.escapeName(roleName)}"

    case x @ DropRole(roleName) =>
      s"${x.name} ${Prettifier.escapeName(roleName)}"

    case x @ GrantRolesToUsers(roleNames, userNames) if roleNames.length > 1 =>
      s"${x.name}S ${roleNames.map(Prettifier.escapeName).mkString(", " )} TO ${userNames.map(Prettifier.escapeName).mkString(", ")}"

    case x @ GrantRolesToUsers(roleNames, userNames) =>
      s"${x.name} ${roleNames.map(Prettifier.escapeName).mkString(", " )} TO ${userNames.map(Prettifier.escapeName).mkString(", ")}"

    case x @ RevokeRolesFromUsers(roleNames, userNames) if roleNames.length > 1 =>
      s"${x.name}S ${roleNames.map(Prettifier.escapeName).mkString(", " )} FROM ${userNames.map(Prettifier.escapeName).mkString(", ")}"

    case x @ RevokeRolesFromUsers(roleNames, userNames) =>
      s"${x.name} ${roleNames.map(Prettifier.escapeName).mkString(", " )} FROM ${userNames.map(Prettifier.escapeName).mkString(", ")}"

    case x @ GrantPrivilege(TraversePrivilege(), _, dbScope, qualifier, roleNames) =>
      val (dbName, label) = Prettifier.extractScope(dbScope, qualifier)
      s"${x.name} ON GRAPH $dbName NODES $label (*) TO ${Prettifier.escapeNames(roleNames)}"

    case x @ RevokePrivilege(TraversePrivilege(), _, dbScope, qualifier, roleNames) =>
      val (dbName, label) = Prettifier.extractScope(dbScope, qualifier)
      s"${x.name} ON GRAPH $dbName NODES $label (*) FROM ${Prettifier.escapeNames(roleNames)}"

    case x @ GrantPrivilege(_, resource, dbScope, qualifier, roleNames) =>
      val (resourceName, dbName, label) = Prettifier.extractScope(resource, dbScope, qualifier)
      s"${x.name} ($resourceName) ON GRAPH $dbName NODES $label (*) TO ${Prettifier.escapeNames(roleNames)}"

    case x @ RevokePrivilege(_, resource, dbScope, qualifier, roleNames) =>
      val (resourceName, dbName, label) = Prettifier.extractScope(resource, dbScope, qualifier)
      s"${x.name} ($resourceName) ON GRAPH $dbName NODES $label (*) FROM ${Prettifier.escapeNames(roleNames)}"

    case x @ ShowPrivileges(scope) =>
      s"SHOW ${Prettifier.extractScope(scope)} PRIVILEGES"

    case x: ShowDatabases =>
      s"${x.name}"

    case x: ShowDefaultDatabase =>
      s"${x.name}"

    case x @ ShowDatabase(dbName) =>
      s"${x.name} ${Prettifier.escapeName(dbName)}"

    case x @ CreateDatabase(dbName) =>
      s"${x.name} ${Prettifier.escapeName(dbName)}"

    case x @ DropDatabase(dbName) =>
      s"${x.name} ${Prettifier.escapeName(dbName)}"

    case x @ StartDatabase(dbName) =>
      s"${x.name} ${Prettifier.escapeName(dbName)}"

    case x @ StopDatabase(dbName) =>
      s"${x.name} ${Prettifier.escapeName(dbName)}"

    case x @ CreateGraph(catalogName, query) =>
      val graphName = catalogName.parts.mkString(".")
      s"${x.name} $graphName {$NL${queryPart(query)}$NL}"

    case x @ DropGraph(catalogName) =>
      val graphName = catalogName.parts.mkString(".")
      s"${x.name} $graphName"

    case x @ CreateView(catalogName, params, query, innerQuery) =>
      val graphName = catalogName.parts.mkString(".")
      val paramString = params.map(p => "$" + p.name).mkString("(", ", ", ")")
      s"CATALOG CREATE VIEW $graphName$paramString {$NL${queryPart(query)}$NL}"

    case x @ DropView(catalogName) =>
      val graphName = catalogName.parts.mkString(".")
      s"CATALOG DROP VIEW $graphName"
  }

  private def queryPart(part: QueryPart): String =
    part match {
      case SingleQuery(clauses) =>
        clauses.map(dispatch).mkString(NL)

      case UnionAll(partA, partB) =>
        s"${queryPart(partA)}${NL}UNION ALL$NL${queryPart(partB)}"

      case UnionDistinct(partA, partB) =>
        s"${queryPart(partA)}${NL}UNION$NL${queryPart(partB)}"
    }

  private def dispatch(clause: Clause) = clause match {
    case e: Return => asString(e)
    case m: Match => asString(m)
    case w: With => asString(w)
    case c: Create => asString(c)
    case u: Unwind => asString(u)
    case u: UnresolvedCall => asString(u)
    case s: SetClause => asString(s)
    case d: Delete => asString(d)
    case m: Merge => asString(m)
    case l: LoadCSV => asString(l)
    case f: Foreach => asString(f)
    case s: Start => asString(s)
    case c: CreateUnique => asString(c)
    case _ => clause.asCanonicalStringVal // TODO
  }

  private val NL = System.lineSeparator()

  def asString(m: Match): String = {
    val o = if(m.optional) "OPTIONAL " else ""
    val p = expr.patterns.apply(m.pattern)
    val w = m.where.map(w => NL + "  WHERE " + expr(w.expression)).getOrElse("")
    val h = m.hints.map(asString).map("  " + _).mkString(NL, NL, "")
    s"${o}MATCH $p$h$w"
  }

  private def asString(m: UsingHint): String = {
    m match {
      case UsingIndexHint(v, l, ps, s) => Seq(
        "USING INDEX ", if (s == SeekOnly) "SEEK " else "",
        expr(v), ":", l.name,
        ps.map(_.name).mkString("(", ",", ")")
      ).mkString

      case UsingScanHint(v, l) => Seq(
        "USING SCAN ", expr(v), ":", l.name
      ).mkString

      case UsingJoinHint(vs) => Seq(
        "USING JOIN ON ", vs.map(expr).toIterable.mkString(", ")
      ).mkString
    }
  }

  private def asString(merge: Merge): String = {
    s"MERGE ${expr.patterns.apply(merge.pattern)}"
  }

  private def asString(o: Skip): String = "SKIP " + expr(o.expression)
  private def asString(o: Limit): String = "LIMIT " + expr(o.expression)

  private def asString(o: OrderBy): String = "ORDER BY " + {
    o.sortItems.map {
      case AscSortItem(expression) => expr(expression) + " ASCENDING"
      case DescSortItem(expression) => expr(expression) + " DESCENDING"
    }.mkString(", ")
  }

  private def asString(r: ReturnItem): String = r match {
    case AliasedReturnItem(e, v) => expr(e) + " AS " + expr(v)
    case UnaliasedReturnItem(e, _) => expr(e)
  }

  private def asString(r: ReturnItemsDef): String = {
    val as = if (r.includeExisting) Seq("*") else Seq()
    val is = r.items.map(asString)
    (as ++ is).mkString(", ")
  }

  private def asString(r: Return): String = {
    val d = if (r.distinct) " DISTINCT" else ""
    val i = asString(r.returnItems)
    val o = r.orderBy.map(NL + "  " + asString(_)).getOrElse("")
    val l = r.limit.map(NL + "  " + asString(_)).getOrElse("")
    val s = r.skip.map(NL + "  " + asString(_)).getOrElse("")
    s"RETURN$d $i$o$s$l"
  }

  private def asString(w: With): String = {
    val d = if (w.distinct) " DISTINCT" else ""
    val i = asString(w.returnItems)
    val o = w.orderBy.map(NL + "  " + asString(_)).getOrElse("")
    val l = w.limit.map(NL + "  " + asString(_)).getOrElse("")
    val s = w.skip.map(NL + "  " + asString(_)).getOrElse("")
    val wh = w.where.map(w => NL + "  WHERE " + expr(w.expression)).getOrElse("")
    s"WITH$d $i$o$s$l$wh"
  }

  private def asString(c: Create): String = {
    val p = expr.patterns.apply(c.pattern)
    s"CREATE $p"
  }

  private def asString(u: Unwind): String = {
    s"UNWIND ${expr(u.expression)} AS ${expr(u.variable)}"
  }

  private def asString(u: UnresolvedCall): String = {
    val namespace = u.procedureNamespace.parts.mkString(".")
    val prefix = if (namespace.isEmpty) "" else namespace + "."
    val arguments = u.declaredArguments.map(list => list.map(expr).mkString(", ")).getOrElse("")
    val yields = u.declaredResult.map(result => " YIELD " + result.items.map(item => expr(item.variable)).mkString(", ")).getOrElse("")
    s"CALL $prefix${u.procedureName.name}($arguments)$yields"
  }

  private def asString(s: SetClause): String = {
    val items = s.items.map {
      case SetPropertyItem(prop, exp) => s"${expr(prop)} = ${expr(exp)}"
      case SetLabelItem(variable, labels) => expr(variable) + labels.map(label =>s":${ExpressionStringifier.backtick(label.name)}").mkString("")
      case SetIncludingPropertiesFromMapItem(variable, exp) => s"${expr(variable)} += ${expr(exp)}"
      case SetExactPropertiesFromMapItem(variable, exp) => s"${expr(variable)} = ${expr(exp)}"
      case _ => s.asCanonicalStringVal
    }
    s"SET ${items.mkString(", ")}"
  }

  private def asString(v: LoadCSV): String = {
    val withHeaders = if (v.withHeaders) " WITH HEADERS" else ""
    val url = expr(v.urlString)
    val varName = v.variable.name
    val fieldTerminator = v.fieldTerminator.map(x => " FIELDTERMINATOR " + expr(x)).getOrElse("")
    s"LOAD CSV$withHeaders FROM $url AS $varName$fieldTerminator"
  }

  private def asString(delete: Delete): String = {
    val detach = if (delete.forced) "DETACH " else ""
    s"${detach}DELETE ${delete.expressions.map(expr).mkString(", ")}"
  }

  private def asString(foreach: Foreach): String = {
    val varName = foreach.variable.name
    val list = expr(foreach.expression)
    val updates = foreach.updates.map(dispatch).mkString(s"$NL  ", s"$NL  ", NL)
    s"FOREACH ( $varName IN $list |$updates)"
  }

  private def asString(start: Start): String = {
    val startItems =
      start.items.map {
        case AllNodes(v) => s"${v.name} = NODE( * )"
        case NodeByIds(v, ids) => s"${v.name} = NODE( ${ids.map(_.value.toString).mkString(", ")} )"
        case NodeByParameter(v, param) => s"${v.name} = NODE( $$${param.name} )"
        case AllRelationships(v) => s"${v.name} = RELATIONSHIP( * )"
        case RelationshipByIds(v, ids) => s"${v.name} = RELATIONSHIP( ${ids.map(_.value.toString).mkString(", ")} )"
        case RelationshipByParameter(v, param) => s"${v.name} = RELATIONSHIP( $$${param.name} )"
      }

    val where = start.where.map(w => NL + "  WHERE " + expr(w.expression)).getOrElse("")
    s"START ${startItems.mkString(s",$NL      ")}$where"
  }

  private def asString(c: CreateUnique): String = {
    val p = expr.patterns.apply(c.pattern)
    s"CREATE UNIQUE $p"
  }

  private def asString(properties: Seq[Property]): String =
    properties.map(_.asCanonicalStringVal).mkString("(", ", ", ")")
}

object Prettifier {

  def extractScope(scope: ShowPrivilegeScope): String = {
    scope match {
      case ShowUserPrivileges(name) => s"USER ${escapeName(name)}"
      case ShowRolePrivileges(name) => s"ROLE ${escapeName(name)}"
      case ShowAllPrivileges() => "ALL"
      case _ => "<unknown>"
    }
  }

  def extractScope(dbScope: GraphScope, qualifier: PrivilegeQualifier): (String, String) = {
    val (r, d, q) = extractScope(AllResource()(InputPosition.NONE), dbScope, qualifier)
    (d, q)
  }

  def extractScope(resource: ActionResource, dbScope: GraphScope, qualifier: PrivilegeQualifier): (String, String, String) = {
    val resourceName = resource match {
      case PropertyResource(name) => escapeName(name)
      case PropertiesResource(names) => names.map(escapeName).mkString(", ")
      case NoResource() => ""
      case AllResource() => "*"
      case _ => "<unknown>"
    }
    val dbName = dbScope match {
      case NamedGraphScope(name) => escapeName(name)
      case AllGraphsScope() => "*"
      case _ => "<unknown>"
    }
    val label = qualifier match {
      case LabelQualifier(name) => escapeName(name)
      case LabelsQualifier(names) => names.map(escapeName).mkString(", ")
      case AllQualifier() => "*"
      case _ => "<unknown>"
    }
    (resourceName, dbName, label)
  }

  /*
   * Some strings (identifiers) were escaped with back-ticks to allow non-identifier characters
   * When printing these again, the knowledge of the back-ticks is lost, but the same test for
   * non-identifier characters can be used to recover that knowledge.
   */
  def escapeName(name: String): String = {
    if (name.isEmpty)
      name
    else {
      val c = name.chars().toArray.toSeq
      if (Character.isJavaIdentifierStart(c.head) && Character.getType(c.head) != Character.CURRENCY_SYMBOL &&
        (c.tail.isEmpty || c.tail.forall(Character.isJavaIdentifierPart)))
        name
      else
        s"`$name`"
    }
  }

  def escapeNames(names: Seq[String]): String = names.map(escapeName).mkString(", ")
}
