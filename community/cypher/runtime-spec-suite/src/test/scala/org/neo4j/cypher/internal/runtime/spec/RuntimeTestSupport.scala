/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.spec

import org.neo4j.common.DependencyResolver
import org.neo4j.cypher.internal.CypherRuntime
import org.neo4j.cypher.internal.ExecutionPlan
import org.neo4j.cypher.internal.LogicalQuery
import org.neo4j.cypher.internal.MasterCompiler
import org.neo4j.cypher.internal.PreParser
import org.neo4j.cypher.internal.ResourceManagerFactory
import org.neo4j.cypher.internal.RuntimeContext
import org.neo4j.cypher.internal.RuntimeContextManager
import org.neo4j.cypher.internal.config.CypherConfiguration
import org.neo4j.cypher.internal.javacompat.GraphDatabaseCypherService
import org.neo4j.cypher.internal.logical.plans.Prober
import org.neo4j.cypher.internal.options.CypherDebugOptions
import org.neo4j.cypher.internal.plandescription.InternalPlanDescription
import org.neo4j.cypher.internal.plandescription.PlanDescriptionBuilder
import org.neo4j.cypher.internal.planner.spi.IDPPlannerName
import org.neo4j.cypher.internal.runtime.CypherRow
import org.neo4j.cypher.internal.runtime.InputDataStream
import org.neo4j.cypher.internal.runtime.InputValues
import org.neo4j.cypher.internal.runtime.NoInput
import org.neo4j.cypher.internal.runtime.NormalMode
import org.neo4j.cypher.internal.runtime.ProfileMode
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.QueryTransactionalContext
import org.neo4j.cypher.internal.runtime.ResourceManager
import org.neo4j.cypher.internal.runtime.ResourceMonitor
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.IndexSearchMonitor
import org.neo4j.cypher.internal.runtime.interpreted.TransactionalContextWrapper
import org.neo4j.cypher.internal.runtime.spec.rewriters.TestPlanCombinationRewriter
import org.neo4j.cypher.internal.runtime.spec.rewriters.TestPlanCombinationRewriter.NoRewrites
import org.neo4j.cypher.internal.runtime.spec.rewriters.TestPlanCombinationRewriter.TestPlanCombinationRewriterHint
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.result.QueryProfile
import org.neo4j.cypher.result.RuntimeResult
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.QueryStatistics
import org.neo4j.internal.kernel.api.CursorFactory
import org.neo4j.internal.kernel.api.security.LoginContext
import org.neo4j.kernel.api.KernelTransaction
import org.neo4j.kernel.api.KernelTransaction.Type
import org.neo4j.kernel.impl.coreapi.InternalTransaction
import org.neo4j.kernel.impl.locking.Locks
import org.neo4j.kernel.impl.query.ChainableQuerySubscriberProbe
import org.neo4j.kernel.impl.query.Neo4jTransactionalContextFactory
import org.neo4j.kernel.impl.query.NonRecordingQuerySubscriber
import org.neo4j.kernel.impl.query.QuerySubscriber
import org.neo4j.kernel.impl.query.QuerySubscriberProbe
import org.neo4j.kernel.impl.query.RecordingQuerySubscriber
import org.neo4j.kernel.impl.query.TransactionalContext
import org.neo4j.kernel.lifecycle.LifeSupport
import org.neo4j.logging.InternalLogProvider
import org.neo4j.monitoring.Monitors
import org.neo4j.storageengine.api.TransactionIdStore
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.VirtualValues

import java.io.PrintStream
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.mutable.ArrayBuffer

/**
 * This class contains various ugliness needed to perform physical compilation
 * and then execute a query.
 */
class RuntimeTestSupport[CONTEXT <: RuntimeContext](
  val graphDb: GraphDatabaseService,
  val edition: Edition[CONTEXT],
  val runtime: CypherRuntime[CONTEXT],
  val workloadMode: Boolean,
  val logProvider: InternalLogProvider,
  val debugOptions: CypherDebugOptions = CypherDebugOptions.default,
  val defaultTransactionType: Type = Type.EXPLICIT
) extends RuntimeExecutionSupport[CONTEXT] {

  private val cypherGraphDb = new GraphDatabaseCypherService(graphDb)
  private val lifeSupport = new LifeSupport
  private val resolver: DependencyResolver = cypherGraphDb.getDependencyResolver

  protected val runtimeContextManager: RuntimeContextManager[CONTEXT] =
    edition.newRuntimeContextManager(resolver, lifeSupport, logProvider)
  private val monitors = resolver.resolveDependency(classOf[Monitors])
  private val contextFactory = Neo4jTransactionalContextFactory.create(cypherGraphDb)
  private lazy val txIdStore = resolver.resolveDependency(classOf[TransactionIdStore])

  private[this] var _tx: InternalTransaction = _
  private[this] var _txContext: TransactionalContext = _

  private[this] var runtimeTestParameters: RuntimeTestParameters = RuntimeTestParameters()
  private[this] var isParallel: Boolean = _

  def setRuntimeTestParameters(params: RuntimeTestParameters, parallelExecution: Boolean): Unit = {
    runtimeTestParameters = params
    isParallel = parallelExecution
  }

  private def createQuerySubscriberProbe(params: RuntimeTestParameters): QuerySubscriberProbe = {
    var probe: ChainableQuerySubscriberProbe = null

    def addProbe(nextProbe: QuerySubscriberProbe): Unit = {
      val nextChainable = new ChainableQuerySubscriberProbe(nextProbe)
      probe =
        if (probe == null) {
          nextChainable
        } else {
          probe.chain(nextChainable)
        }
    }

    // Slow sleeping subscriber probe
    if (params.sleepSubscriber.isDefined) {
      addProbe(new QuerySubscriberProbe {
        private[this] var count: Long = 0L

        override def onRecordCompleted(): Unit = {
          count += 1L;
          val sleepPerNRows = params.sleepSubscriber.get
          if (count % sleepPerNRows.perNRows == 0) {
            try {
              Thread.sleep(0L, sleepPerNRows.sleepNanos)
            } catch {
              case e: InterruptedException => // Ignore
            }
          }
        }
      })
    }

    // Slow busy-waiting subscriber probe
    if (params.busySubscriber) {
      addProbe(new QuerySubscriberProbe {
        override def onRecordCompleted(): Unit = {
          var i = 0
          while (i < 1000000000) {
            Thread.onSpinWait()
            i += 1
          }
        }
      })
    }

    // Print progress probe
    if (params.printProgress.isDefined) {
      val printEveryNRows = params.printProgress.get
      addProbe(new QuerySubscriberProbe {
        private[this] var count: Long = 0L

        override def onRecordCompleted(): Unit = {
          count += 1L;
          if (count % printEveryNRows.everyNRows == 0) {
            val printCount = if (printEveryNRows.printRowCount) count.toString else ""
            print(s"${printEveryNRows.messagePrefix}$printCount${printEveryNRows.messageSuffix}")
          }
        }
      })
    }

    // Print config probe
    if (params.printConfig) {
      addProbe(new QuerySubscriberProbe {
        var shouldPrint: Boolean = false

        override def onResultCompleted(statistics: QueryStatistics): Unit = {
          printConfig()
        }

        override def onError(throwable: Throwable): Unit = {
          printConfig()
        }

        private def printConfig(): Unit = {
          val nl = System.lineSeparator()
          if (shouldPrint) {
            print(s"${nl}Test config:${nl}${edition.configs.mkString(nl)}${nl}${nl}${nl}")
            shouldPrint = false
          }
        }
      })
    }

    // Kill transaction probe
    params.killTransactionAfterRows match {
      case Some(n) =>
        addProbe(new QuerySubscriberProbe {
          private[this] var count: Long = 0L

          override def onRecordCompleted(): Unit = {
            count += 1L;
            if (count == n) {
              _tx.terminate()
            }
          }
        })

      case None => // Do nothing
    }
    probe
  }

  private def newRecordingQuerySubscriber: RecordingQuerySubscriber = {
    new RecordingQuerySubscriber(createQuerySubscriberProbe(runtimeTestParameters))
  }

  private def newNonRecordingQuerySubscriber: NonRecordingQuerySubscriber = {
    new NonRecordingQuerySubscriber(createQuerySubscriberProbe(runtimeTestParameters))
  }

  private def newRecordingRuntimeResult(
    runtimeResult: RuntimeResult,
    recordingQuerySubscriber: RecordingQuerySubscriber
  ): RecordingRuntimeResult = {
    RecordingRuntimeResult(runtimeResult, recordingQuerySubscriber, runtimeTestParameters.resultConsumptionController)
  }

  private def newNonRecordingRuntimeResult(
    runtimeResult: RuntimeResult,
    nonRecordingQuerySubscriber: NonRecordingQuerySubscriber
  ): NonRecordingRuntimeResult = {
    NonRecordingRuntimeResult(
      runtimeResult,
      nonRecordingQuerySubscriber,
      runtimeTestParameters.resultConsumptionController
    )
  }

  def start(): Unit = {
    lifeSupport.init()
    lifeSupport.start()
  }

  def stop(): Unit = {
    lifeSupport.stop()
    lifeSupport.shutdown()
  }

  def startTx(transactionType: KernelTransaction.Type = defaultTransactionType): Unit = {
    _tx = cypherGraphDb.beginTransaction(transactionType, LoginContext.AUTH_DISABLED)
    _txContext = contextFactory.newContext(_tx, "<<queryText>>", VirtualValues.EMPTY_MAP)
  }

  def restartTx(transactionType: KernelTransaction.Type = defaultTransactionType): Unit = {
    _txContext.close()
    _tx.commit()
    _tx = cypherGraphDb.beginTransaction(transactionType, LoginContext.AUTH_DISABLED)
    _txContext = contextFactory.newContext(_tx, "<<queryText>>", VirtualValues.EMPTY_MAP)
  }

  def rollbackAndRestartTx(transactionType: KernelTransaction.Type = defaultTransactionType): Unit = {
    _txContext.close()
    _tx.rollback()
    _tx = cypherGraphDb.beginTransaction(transactionType, LoginContext.AUTH_DISABLED)
    _txContext = contextFactory.newContext(_tx, "<<queryText>>", VirtualValues.EMPTY_MAP)
  }

  def restartImplicitTx(): Unit = {
    _txContext.close()
    if (_tx.isOpen) {
      _tx.commit()
    }
    _tx = cypherGraphDb.beginTransaction(Type.IMPLICIT, LoginContext.AUTH_DISABLED)
    _txContext = contextFactory.newContext(_tx, "<<queryText>>", VirtualValues.EMPTY_MAP)
  }

  def stopTx(): Unit = {
    _txContext.close()
    _tx.close()
  }

  def startNewTx(): InternalTransaction = {
    cypherGraphDb.beginTransaction(defaultTransactionType, LoginContext.AUTH_DISABLED)
  }

  def getLastClosedTransactionId: Long = {
    txIdStore.getLastClosedTransactionId
  }

  def tx: InternalTransaction = _tx
  def txContext: TransactionalContext = _txContext

  def locks: Locks = cypherGraphDb.getDependencyResolver.resolveDependency(classOf[Locks])

  override def buildPlan(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    testPlanCombinationRewriterHints: Set[TestPlanCombinationRewriterHint]
  ): ExecutionPlan = {
    val queryContext = newQueryContext(_txContext, logicalQuery.readOnly)
    try {
      compileWithTx(
        logicalQuery,
        runtime,
        queryContext,
        testPlanCombinationRewriterHints
      )._1
    } finally {
      queryContext.resources.close()
    }
  }

  override def buildPlanAndContext(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT]
  ): (ExecutionPlan, CONTEXT) = {
    val queryContext = newQueryContext(_txContext, logicalQuery.readOnly)
    compileWithTx(logicalQuery, runtime, queryContext)
  }

  override def execute(
    executablePlan: ExecutionPlan,
    readOnly: Boolean = true,
    implicitTx: Boolean = false
  ): RecordingRuntimeResult = {
    val subscriber = newRecordingQuerySubscriber
    val result = run(
      executablePlan,
      NoInput,
      (_, result) => result,
      subscriber,
      profile = false,
      readOnly,
      implicitTx = implicitTx
    )
    newRecordingRuntimeResult(result, subscriber)
  }

  override def execute(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    inputStream: InputDataStream
  ): RecordingRuntimeResult = {
    val subscriber = newRecordingQuerySubscriber
    val result = runLogical(logicalQuery, runtime, inputStream, (_, result) => result, subscriber, profile = false)
    newRecordingRuntimeResult(result, subscriber)
  }

  override def execute(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    input: InputDataStream,
    subscriber: QuerySubscriber,
    testPlanCombinationRewriterHints: Set[TestPlanCombinationRewriterHint]
  ): RuntimeResult = runLogical(
    logicalQuery,
    runtime,
    input,
    (_, result) => result,
    subscriber,
    profile = false,
    testPlanCombinationRewriterHints = testPlanCombinationRewriterHints
  )

  override def executeAndConsumeTransactionally(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    parameters: Map[String, Any] = Map.empty,
    profileAssertion: Option[QueryProfile => Unit] = None
  ): IndexedSeq[Array[AnyValue]] = {
    val subscriber = newRecordingQuerySubscriber
    runTransactionally(
      logicalQuery,
      runtime,
      NoInput,
      (_, result) => {
        val recordingRuntimeResult = newRecordingRuntimeResult(result, subscriber)
        val seq = recordingRuntimeResult.awaitAll()
        profileAssertion.foreach(_(recordingRuntimeResult.runtimeResult.queryProfile()))
        recordingRuntimeResult.runtimeResult.close()
        seq
      },
      subscriber,
      parameters,
      profile = profileAssertion.isDefined
    )
  }

  override def executeAndConsumeTransactionallyNonRecording(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    parameters: Map[String, Any] = Map.empty,
    profileAssertion: Option[QueryProfile => Unit] = None
  ): Long = {
    val subscriber = newNonRecordingQuerySubscriber
    runTransactionallyAndRollback[Long](
      logicalQuery,
      runtime,
      NoInput,
      (_, result) => {
        val nonRecordingRuntimeResult = newNonRecordingRuntimeResult(result, subscriber)
        val seq = nonRecordingRuntimeResult.awaitAll()
        profileAssertion.foreach(_(nonRecordingRuntimeResult.runtimeResult.queryProfile()))
        nonRecordingRuntimeResult.runtimeResult.close()
        seq
      },
      subscriber,
      parameters,
      profile = profileAssertion.isDefined
    )
  }

  override def profile(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    inputDataStream: InputDataStream = NoInput,
    testPlanCombinationRewriterHints: Set[TestPlanCombinationRewriterHint]
  ): RecordingRuntimeResult = {
    val subscriber = newRecordingQuerySubscriber
    val result = runLogical(
      logicalQuery,
      runtime,
      inputDataStream,
      (_, result) => result,
      subscriber,
      profile = true,
      testPlanCombinationRewriterHints = testPlanCombinationRewriterHints
    )
    newRecordingRuntimeResult(result, subscriber)
  }

  override def profile(
    executionPlan: ExecutionPlan,
    inputDataStream: InputDataStream,
    readOnly: Boolean
  ): RecordingRuntimeResult = {
    val subscriber = newRecordingQuerySubscriber
    val result = run(executionPlan, inputDataStream, (_, result) => result, subscriber, profile = true, readOnly)
    newRecordingRuntimeResult(result, subscriber)
  }

  override def profileNonRecording(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    inputDataStream: InputDataStream = NoInput
  ): NonRecordingRuntimeResult = {
    val subscriber = newNonRecordingQuerySubscriber
    val result = runLogical(logicalQuery, runtime, inputDataStream, (_, result) => result, subscriber, profile = true)
    newNonRecordingRuntimeResult(result, subscriber)
  }

  override def profileWithSubscriber(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    subscriber: QuerySubscriber,
    inputDataStream: InputDataStream = NoInput
  ): RuntimeResult = {
    runLogical(logicalQuery, runtime, inputDataStream, (_, result) => result, subscriber, profile = true)
  }

  override def executeAndContext(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    input: InputValues
  ): (RecordingRuntimeResult, CONTEXT) = {
    val subscriber = newRecordingQuerySubscriber
    val (result, context) = runLogical(
      logicalQuery,
      runtime,
      input.stream(),
      (context, result) => (result, context),
      subscriber,
      profile = false
    )
    (newRecordingRuntimeResult(result, subscriber), context)
  }

  override def executeAndContextNonRecording(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    input: InputValues
  ): (NonRecordingRuntimeResult, CONTEXT) = {
    val subscriber = newNonRecordingQuerySubscriber
    val (result, context) = runLogical(
      logicalQuery,
      runtime,
      input.stream(),
      (context, result) => (result, context),
      subscriber,
      profile = false
    )
    (newNonRecordingRuntimeResult(result, subscriber), context)
  }

  override def executeAndExplain(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    input: InputValues
  ): (RecordingRuntimeResult, InternalPlanDescription) = {
    val subscriber = newRecordingQuerySubscriber
    val executionPlan = buildPlan(logicalQuery, runtime, testPlanCombinationRewriterHints = Set(NoRewrites))
    val result = run(
      executionPlan,
      input.stream(),
      (_, result) => result,
      subscriber,
      profile = false,
      logicalQuery.readOnly,
      parameters = Map.empty
    )
    val executionPlanDescription = {
      val planDescriptionBuilder =
        PlanDescriptionBuilder(
          executionPlan.rewrittenPlan.getOrElse(logicalQuery.logicalPlan),
          IDPPlannerName,
          logicalQuery.readOnly,
          logicalQuery.effectiveCardinalities,
          debugOptions.rawCardinalitiesEnabled,
          logicalQuery.providedOrders,
          executionPlan,
          renderPlanDescription = false
        )
      planDescriptionBuilder.explain()
    }
    (newRecordingRuntimeResult(result, subscriber), executionPlanDescription)
  }

  // PRIVATE EXECUTE HELPER METHODS

  private def runLogical[RESULT](
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    input: InputDataStream,
    resultMapper: (CONTEXT, RuntimeResult) => RESULT,
    subscriber: QuerySubscriber,
    profile: Boolean,
    parameters: Map[String, Any] = Map.empty,
    testPlanCombinationRewriterHints: Set[TestPlanCombinationRewriterHint] = Set.empty[TestPlanCombinationRewriterHint]
  ): RESULT = {
    run(
      buildPlan(logicalQuery, runtime, testPlanCombinationRewriterHints),
      input,
      resultMapper,
      subscriber,
      profile,
      logicalQuery.readOnly,
      parameters
    )
  }

  private def runTransactionally[RESULT](
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    input: InputDataStream,
    resultMapper: (CONTEXT, RuntimeResult) => RESULT,
    subscriber: QuerySubscriber,
    parameters: Map[String, Any],
    profile: Boolean
  ): RESULT = {
    val tx = cypherGraphDb.beginTransaction(Type.EXPLICIT, LoginContext.AUTH_DISABLED)
    val txContext = contextFactory.newContext(tx, "<<queryText>>", VirtualValues.EMPTY_MAP)
    val queryContext = newQueryContext(txContext, logicalQuery.readOnly)
    try {
      val executionPlan = compileWithTx(logicalQuery, runtime, queryContext)._1
      runWithTx(
        executionPlan,
        input,
        resultMapper,
        subscriber,
        profile = profile,
        logicalQuery.readOnly,
        parameters,
        tx,
        txContext
      )
    } finally {
      queryContext.resources.close()
      txContext.close()
      tx.close()
    }
  }

  private def runTransactionallyAndRollback[RESULT](
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    input: InputDataStream,
    resultMapper: (CONTEXT, RuntimeResult) => RESULT,
    subscriber: QuerySubscriber,
    parameters: Map[String, Any],
    profile: Boolean
  ): RESULT = {
    val tx = cypherGraphDb.beginTransaction(Type.EXPLICIT, LoginContext.AUTH_DISABLED)
    val txContext = contextFactory.newContext(tx, "<<queryText>>", VirtualValues.EMPTY_MAP)
    val queryContext = newQueryContext(txContext, logicalQuery.readOnly)
    try {
      val executionPlan = compileWithTx(logicalQuery, runtime, queryContext)._1
      runWithTx(
        executionPlan,
        input,
        resultMapper,
        subscriber,
        profile = profile,
        logicalQuery.readOnly,
        parameters,
        tx,
        txContext
      )
    } finally {
      queryContext.resources.close()
      tx.rollback()
      txContext.close()
      tx.close()
    }
  }

  private def run[RESULT](
    executableQuery: ExecutionPlan,
    input: InputDataStream,
    resultMapper: (CONTEXT, RuntimeResult) => RESULT,
    subscriber: QuerySubscriber,
    profile: Boolean,
    readOnly: Boolean,
    parameters: Map[String, Any] = Map.empty,
    implicitTx: Boolean = false
  ): RESULT = {
    if (implicitTx) {
      restartImplicitTx()
    }
    runWithTx(executableQuery, input, resultMapper, subscriber, profile, readOnly, parameters, _tx, _txContext)
  }

  private def runWithTx[RESULT](
    executableQuery: ExecutionPlan,
    input: InputDataStream,
    resultMapper: (CONTEXT, RuntimeResult) => RESULT,
    subscriber: QuerySubscriber,
    profile: Boolean,
    readOnly: Boolean,
    parameters: Map[String, Any],
    tx: InternalTransaction,
    txContext: TransactionalContext
  ): RESULT = {
    val queryContext = newQueryContext(txContext, readOnly, executableQuery.threadSafeExecutionResources())
    val runtimeContext = newRuntimeContext(queryContext)

    val executionMode = if (profile) ProfileMode else NormalMode
    val (keys, values) = parameters.mapValues(Values.of).unzip match { case (a, b) => (a.toArray, b.toArray[AnyValue]) }
    val paramsMap = VirtualValues.map(keys, values)
    val result =
      executableQuery.run(queryContext, executionMode, paramsMap, prePopulateResults = true, input, subscriber)
    val assertAllReleased =
      if (!workloadMode) {
        () =>
          {
            runtimeContextManager.waitForWorkersToIdle(5000)
            runtimeContextManager.assertAllReleased()
          }
      } else () => ()
    resultMapper(
      runtimeContext,
      new ClosingRuntimeTestResult(result, tx, txContext, queryContext.resources, subscriber, assertAllReleased)
    )
  }

  private def compileWithTx(
    logicalQuery: LogicalQuery,
    runtime: CypherRuntime[CONTEXT],
    queryContext: QueryContext,
    testPlanCombinationRewriterHints: Set[TestPlanCombinationRewriterHint] = Set.empty[TestPlanCombinationRewriterHint]
  ): (ExecutionPlan, CONTEXT) = {
    val runtimeContext = newRuntimeContext(queryContext)
    val rewrittenLogicalQuery =
      rewriteLogicalQuery(logicalQuery, runtimeContext.anonymousVariableNameGenerator, testPlanCombinationRewriterHints)
    (runtime.compileToExecutable(rewrittenLogicalQuery, runtimeContext), runtimeContext)
  }

  private def rewriteLogicalQuery(
    logicalQuery: LogicalQuery,
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator,
    testPlanCombinationRewriterHints: Set[TestPlanCombinationRewriterHint]
  ): LogicalQuery = {
    runtimeTestParameters.planCombinationRewriter match {
      case Some(rewriterConfig) if testPlanCombinationRewriterHints.nonEmpty =>
        val augmentedRewriterConfig =
          rewriterConfig.copy(hints = rewriterConfig.hints.union(testPlanCombinationRewriterHints))
        TestPlanCombinationRewriter(augmentedRewriterConfig, logicalQuery, isParallel, anonymousVariableNameGenerator)
      case Some(rewriterConfig) =>
        TestPlanCombinationRewriter(rewriterConfig, logicalQuery, isParallel, anonymousVariableNameGenerator)
      case _ =>
        logicalQuery
    }
  }

  // CONTEXTS

  def newQueryTransactionalContext(): QueryTransactionalContext = {
    TransactionalContextWrapper(_txContext, null)
  }

  protected def newRuntimeContext(queryContext: QueryContext): CONTEXT = {

    val cypherConfiguration: CypherConfiguration = edition.cypherConfig()

    val queryOptions = PreParser.queryOptions(List.empty, InputPosition.NONE, cypherConfiguration)

    runtimeContextManager.create(
      queryContext,
      queryContext.transactionalContext.schemaRead,
      MasterCompiler.CLOCK,
      debugOptions,
      compileExpressions = queryOptions.useCompiledExpressions,
      materializedEntitiesMode = queryOptions.materializedEntitiesMode,
      operatorEngine = queryOptions.queryOptions.operatorEngine,
      interpretedPipesFallback = queryOptions.queryOptions.interpretedPipesFallback,
      anonymousVariableNameGenerator = new AnonymousVariableNameGenerator()
    )
  }

  private def newQueryContext(
    txContext: TransactionalContext,
    readOnly: Boolean,
    maybeExecutionResources: Option[(CursorFactory, ResourceManagerFactory)] = None
  ): QueryContext = {
    val (threadSafeCursorFactory, resourceManager) = maybeExecutionResources match {
      case Some((tFactory, rFactory)) => (tFactory, rFactory(ResourceMonitor.NOOP))
      case None => (null, new ResourceManager(ResourceMonitor.NOOP, txContext.kernelTransaction().memoryTracker()))
    }

    new TransactionBoundQueryContext(TransactionalContextWrapper(txContext, threadSafeCursorFactory), resourceManager)(
      monitors.newMonitor(classOf[IndexSearchMonitor])
    )
  }

  def waitForWorkersToIdle(timeoutMs: Int): Unit = {
    runtimeContextManager.waitForWorkersToIdle(timeoutMs)
  }
}

//=============================================================================
// TESTING PROBES
//=============================================================================

/**
 * Probe that records the requested variables for each row that it sees
 * Inject into the query using a [[Prober]] plan
 */
class RecordingProbe(variablesToRecord: String*)(chain: Prober.Probe = null) extends Prober.Probe()
    with RecordingRowsProbe {
  private[this] val _seenRows = new ArrayBuffer[Array[AnyValue]]

  override def onRow(row: AnyRef, queryStatistics: QueryStatistics, transactionsCommitted: Int): Unit = {
    val cypherRow = row.asInstanceOf[CypherRow]
    val recordedVars = variablesToRecord.toArray.map { v =>
      cypherRow.getByName(v)
    }
    _seenRows += recordedVars

    if (chain != null) {
      chain.onRow(row, queryStatistics, transactionsCommitted)
    }
  }

  override def seenRows: Array[Array[AnyValue]] = {
    _seenRows.toArray
  }
}

object RecordingProbe {

  def apply(variablesToRecord: String*): RecordingProbe =
    new RecordingProbe(variablesToRecord: _*)()
}

trait RecordingRowsProbe {
  def seenRows: Array[Array[AnyValue]]
}

/**
 * Thread-safe probe that records the requested variables for each row that it sees
 * Inject into the query using a [[Prober]] plan
 */
class ThreadSafeRecordingProbe(variablesToRecord: String*)(chain: Prober.Probe = null) extends Prober.Probe()
    with RecordingRowsProbe {
  private[this] val _seenRows = new ConcurrentLinkedQueue[Array[AnyValue]]

  override def onRow(row: AnyRef, queryStatistics: QueryStatistics, transactionsCommitted: Int): Unit = {
    val cypherRow = row.asInstanceOf[CypherRow]
    val recordedVars = variablesToRecord.toArray.map { v =>
      cypherRow.getByName(v)
    }
    _seenRows.add(recordedVars)

    if (chain != null) {
      chain.onRow(row, queryStatistics, transactionsCommitted)
    }
  }

  override def seenRows: Array[Array[AnyValue]] = {
    _seenRows.toArray().map(_.asInstanceOf[Array[AnyValue]])
  }
}

object ThreadSafeRecordingProbe {

  def apply(variablesToRecord: String*): ThreadSafeRecordingProbe =
    new ThreadSafeRecordingProbe(variablesToRecord: _*)()
}

/**
 * Probe that prints the requested variables for each row that it sees
 * Inject into the query using a [[Prober]] plan
 */
class PrintingProbe(variablesToPrint: String*)(
  prefix: String = "",
  override val name: String = "",
  printStream: PrintStream = System.out
)(chain: Prober.Probe = null) extends Prober.Probe() {
  private[this] var rowCount = 0L

  override def onRow(row: AnyRef, queryStatistics: QueryStatistics, transactionsCommitted: Int): Unit = {
    val cypherRow = row.asInstanceOf[CypherRow]
    val variablesString = variablesToPrint.toArray.map { v =>
      val value = cypherRow.getByName(v)
      s"$v = $value"
    }.mkString("{ ", ", ", " }")
    printStream.println(s"$prefix Row: $rowCount $variablesString")
    rowCount += 1

    if (chain != null) {
      chain.onRow(row, queryStatistics, transactionsCommitted)
    }
  }
}

object PrintingProbe {

  def apply(variablesToRecord: String*): PrintingProbe =
    new PrintingProbe(variablesToRecord: _*)()()
}

object RecordingAndPrintingProbe {

  def apply(variablesToRecord: String*): RecordingProbe =
    new RecordingProbe(variablesToRecord: _*)(new PrintingProbe(variablesToRecord: _*)()())
}
