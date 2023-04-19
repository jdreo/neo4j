/*
 * Copyright (c) "Neo4j"
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
package org.neo4j.cypher.internal.frontend

import org.neo4j.cypher.internal.ast.semantics.SemanticError
import org.neo4j.cypher.internal.ast.semantics.SemanticErrorDef
import org.neo4j.cypher.internal.ast.semantics.SemanticFeature
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.util.test_helpers.TestName
import org.neo4j.cypher.internal.util.InputPosition

class MatchModesSemanticAnalysisTest extends CypherFunSuite with SemanticAnalysisTestSuiteWithDefaultQuery
    with TestName {

  override def defaultQuery: String = s"MATCH $testName RETURN *"

  def errorsFromSemanticAnalysis: Seq[SemanticErrorDef] = {
    runSemanticAnalysisWithSemanticFeatures(
      SemanticFeature.QuantifiedPathPatterns,
      SemanticFeature.GpmShortestPath
    ).errors
  }

  def unboundRepeatableElementsSemanticError(pos: InputPosition): SemanticError = SemanticError(
    "Match mode \"REPEATABLE ELEMENTS\" was used, but pattern is not bounded.",
    pos
  )

  def differentRelationshipsWithSelectivePathPatternSemanticError(pos: InputPosition): SemanticError = SemanticError(
    "A selective path pattern can only be used with match mode \"DIFFERENT RELATIONSHIPS\" if it's the only pattern in that clause.",
    pos
  )

  test("DIFFERENT RELATIONSHIPS ((a)-[:REL]->(b)){2}") {
     errorsFromSemanticAnalysis shouldBe empty
  }

  test("((a)-[:REL]->(b)){2}, ((c)-[:REL]->(d))+") {
    errorsFromSemanticAnalysis shouldBe empty
  }

  test("REPEATABLE ELEMENTS ((a)-[:REL]->(b)){2}") {
    errorsFromSemanticAnalysis shouldBe empty
  }

  test("REPEATABLE ELEMENTS ((a)-[:REL]->(b)){1,}") {
    errorsFromSemanticAnalysis shouldEqual Seq(
      unboundRepeatableElementsSemanticError(InputPosition(26, 1, 27))
    )
  }

  test("REPEATABLE ELEMENTS ((a)-[:REL]->(b))+") {
    errorsFromSemanticAnalysis shouldEqual Seq(
      unboundRepeatableElementsSemanticError(InputPosition(26, 1, 27))
    )
  }

  test("REPEATABLE ELEMENTS (a)-[:REL*]->(b)") {
    errorsFromSemanticAnalysis shouldEqual Seq(
      unboundRepeatableElementsSemanticError(InputPosition(26, 1, 27))
    )
  }

  test("REPEATABLE ELEMENTS SHORTEST 2 PATH ((a)-[:REL]->(b))+") {
    errorsFromSemanticAnalysis shouldBe empty
  }

  test("REPEATABLE ELEMENTS ANY ((a)-[:REL]->(b))+") {
    errorsFromSemanticAnalysis shouldBe empty
  }

  test("REPEATABLE ELEMENTS SHORTEST 1 PATH GROUPS ((a)-[:REL]->(b))+") {
    errorsFromSemanticAnalysis shouldBe empty
  }

  test("REPEATABLE ELEMENTS p = shortestPath((a)-[:REL*]->(b))") {
    errorsFromSemanticAnalysis shouldBe empty
  }

  test("REPEATABLE ELEMENTS ((a)-[:REL]->(b)){2}, ((c)-[:REL]->(d))+") {
    errorsFromSemanticAnalysis shouldEqual Seq(
      unboundRepeatableElementsSemanticError(InputPosition(48, 1, 49))
    )
  }

  test("REPEATABLE ELEMENTS ((a)-[:REL]->(b))+, ((c)-[:REL]->(d))+") {
    errorsFromSemanticAnalysis shouldEqual Seq(
      unboundRepeatableElementsSemanticError(InputPosition(26, 1, 27)),
      unboundRepeatableElementsSemanticError(InputPosition(46, 1, 47))
    )
  }

  test("REPEATABLE ELEMENTS SHORTEST 1 PATH (a)-[:REL*]->(b), SHORTEST 1 PATH (c)-[:REL*]->(d)") {
    errorsFromSemanticAnalysis shouldBe empty
  }

  test("DIFFERENT RELATIONSHIPS SHORTEST 1 PATH (a)-[:REL*]->(b), SHORTEST 1 PATH (c)-[:REL*]->(d)") {
    errorsFromSemanticAnalysis shouldEqual Seq(
      differentRelationshipsWithSelectivePathPatternSemanticError(InputPosition(46, 1, 47))
    )
  }

  test("SHORTEST 1 PATH (a)-[:REL*]->(b), (c)-[:REL]->(d)") {
    errorsFromSemanticAnalysis shouldEqual Seq(
      differentRelationshipsWithSelectivePathPatternSemanticError(InputPosition(22, 1, 23))
    )
  }

  test("(a)-[:REL]->(b), ALL PATHS (c)-[:REL]->(d)") {
    errorsFromSemanticAnalysis shouldBe empty
  }

  test("REPEATABLE ELEMENTS (a)-[:REL]->(b), ALL PATHS (c)-[:REL]->(d)") {
    errorsFromSemanticAnalysis shouldBe empty
  }
}
