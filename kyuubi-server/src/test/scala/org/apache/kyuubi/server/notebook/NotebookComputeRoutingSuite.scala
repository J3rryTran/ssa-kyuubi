/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.notebook

import java.util
import java.util.UUID

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.compute.{ComputeAllocation, ComputeAllocationState, ComputeMode, ComputeTriggerType, ComputeWorkloadType}
import org.apache.kyuubi.server.engineprofile.EngineProfilePrincipal
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.runtime._
import org.apache.kyuubi.server.notebook.service.{NotebookPrincipal, NotebookRuntimeService, NotebookSessionService}

class NotebookComputeRoutingSuite extends NotebookTestBase {

  private var adapter: ComputeRecordingAdapter = _
  private var runtimes: NotebookRuntimeService = _
  private var sessions: NotebookSessionService = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    adapter = new ComputeRecordingAdapter
    runtimes = new NotebookRuntimeService(
      manager.store,
      new RuntimeAdapterRegistry(Seq(adapter)),
      () => "compute-routing-test",
      Some(manager.engineProfiles),
      Some(manager.pythonEnvironments),
      Some(manager.computeAllocations))
    sessions = new NotebookSessionService(
      manager.store,
      documents,
      permissions,
      runtimes,
      () => "compute-routing-test")
  }

  test("Notebook and Thrift DBT reuse one interactive profile allocation") {
    val profileId = createProfile("shared-interactive")
    val session = createSession(profileId, "shared-interactive-notebook")
    val runtime = runtimes.ensureFor(alice, session, None, Map.empty)
    val allocation = allocationOf(runtime)
    assert(manager.store.getRuntime(runtime.id).flatMap(_.computeAllocationId).contains(
      allocation.id))

    val resolver = new org.apache.kyuubi.server.engineprofile.EngineProfileSessionConfigResolver(
      manager.engineProfiles,
      manager.pythonEnvironments,
      manager.engineProfileRunSnapshots,
      manager.computeBindings,
      manager.computeAllocations)
    val dbt = resolver.resolve(
      alice.user,
      Map(
        org.apache.kyuubi.server.engineprofile.EngineProfileSessionConfigResolver.ProfileIdKey ->
          profileId,
        org.apache.kyuubi.server.engineprofile.EngineProfileSessionConfigResolver.RunIdKey ->
          UUID.randomUUID().toString))

    assert(allocation.mode === ComputeMode.INTERACTIVE)
    assert(dbt("kyuubi.engine.share.level.subdomain") === allocation.subdomain)
    assert(manager.computeStore.listOpenAllocationsForProfile(profileId).map(_.id) ===
      Seq(allocation.id))

    runtimes.stop(runtime)
    assert(manager.computeStore.getAllocation(allocation.id).exists(value =>
      ComputeAllocationState.open.contains(value.state)))
    release(allocation)
  }

  test("Notebook session keeps its selected profile after the document is edited") {
    val originalProfileId = createProfile("session-profile-original")
    val replacementProfileId = createProfile("session-profile-replacement")
    val (notebook, session) = createNotebookSession(
      originalProfileId,
      "session-profile-is-pinned")

    val update = new UpdateNotebookRequest
    update.setRuntimeProfile(replacementProfileId)
    update.setVersion(Long.box(notebook.version))
    documents.updateNotebook(alice, notebook.id, update)

    val runtime = runtimes.ensureFor(alice, session, None, Map.empty)
    val allocation = allocationOf(runtime)
    assert(allocation.profileId === originalProfileId)
    release(allocation)
  }

  test("managed Notebook allocation never resumes a lost Python runtime") {
    val profileId = createProfile("managed-notebook")
    val session = createSession(profileId, "managed-notebook-session")
    val allocation = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profileId,
      ComputeWorkloadType.NOTEBOOK,
      ComputeTriggerType.SCHEDULE,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)
    val runtime = runtimes.ensureForAllocation(
      alice,
      session,
      None,
      Map.empty,
      allocation.id)
    adapter.lose(runtime.id)

    val error = interceptNotebook(NotebookErrorCode.KYUUBI_SESSION_LOST) {
      runtimes.ensureForAllocation(alice, session, None, Map.empty, allocation.id)
    }
    assert(error.message.contains("new attempt"))
    interceptNotebook(NotebookErrorCode.KYUUBI_SESSION_LOST) {
      runtimes.ensureForAllocation(alice, session, None, Map.empty, allocation.id)
    }
    assert(adapter.startedRuntimeIds.count(_ == runtime.id) === 1)
    release(allocation)
  }

  test("Notebook runtime rejects client-owned engine identity and launch resources") {
    val profileId = createProfile("reject-client-routing")
    val session = createSession(profileId, "reject-client-routing-notebook")

    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      runtimes.ensureFor(
        alice,
        session,
        None,
        Map(
          "kyuubi.engine.share.level.subdomain" -> "forged",
          "spark.executor.memory" -> "100g"))
    }
    assert(manager.computeStore.listOpenAllocationsForProfile(profileId).isEmpty)
  }

  private def createProfile(name: String): String = {
    val request = new UpsertEngineProfileRequest
    request.setName(name + "-" + UUID.randomUUID().toString.take(8))
    request.setSparkConfig(new util.HashMap(Map(
      "spark.driver.memory" -> "2g",
      "spark.executor.memory" -> "4g").asJava))
    manager.engineProfiles.create(profilePrincipal(alice), request).profileId
  }

  private def createSession(profileId: String, name: String): NotebookSession =
    createNotebookSession(profileId, name)._2

  private def createNotebookSession(
      profileId: String,
      name: String): (Notebook, NotebookSession) = {
    val request = new CreateNotebookRequest
    request.setName(name + "-" + UUID.randomUUID().toString.take(8))
    request.setRuntimeProfile(profileId)
    val (notebook, _) = documents.createNotebook(alice, request)
    notebook -> sessions.create(alice, notebook.id, new CreateSessionRequest)
  }

  private def allocationOf(runtime: NotebookRuntime): ComputeAllocation =
    manager.computeStore.getAllocation(runtime.computeAllocationId.get).get

  private def profilePrincipal(principal: NotebookPrincipal): EngineProfilePrincipal =
    EngineProfilePrincipal(principal.user, principal.admin)

  private def release(allocation: ComputeAllocation): Unit = {
    val current = manager.computeStore.getAllocation(allocation.id).get
    val now = math.max(System.currentTimeMillis(), current.updatedAt + 1)
    val releasing = current.copy(
      state = ComputeAllocationState.RELEASING,
      updatedAt = now,
      version = current.version + 1)
    assert(manager.computeStore.updateAllocation(releasing, current.version, Set(current.state)))
    val released = releasing.copy(
      state = ComputeAllocationState.RELEASED,
      updatedAt = now + 1,
      releasedAt = Some(now + 1),
      version = releasing.version + 1)
    assert(manager.computeStore.updateAllocation(
      released,
      releasing.version,
      Set(ComputeAllocationState.RELEASING)))
  }
}

private class ComputeRecordingAdapter extends NotebookRuntimeAdapter {

  var startedRuntimeIds: Seq[String] = Seq.empty
  private var lostRuntimeIds: Set[String] = Set.empty

  override val runtimeType: String = "SPARK"

  override val runtimeSpec: RuntimeSpec = RuntimeSpec(
    id = "compute-recording",
    displayName = "Compute Recording",
    language = CellLanguage.SQL.toString,
    version = "test",
    enabled = true,
    configurableKeys = Seq("spark.sql.shuffle.partitions"),
    limits = Map.empty,
    supportedLanguages = Seq(CellLanguage.SQL.toString, CellLanguage.PYTHON.toString))

  override def startRuntime(
      runtime: NotebookRuntime,
      configuration: Map[String, String]): AdapterRuntime = {
    require(configuration("kyuubi.engine.share.level") == "USER")
    require(configuration("kyuubi.engine.type") == "SPARK_SQL")
    require(configuration.contains("kyuubi.engine.share.level.subdomain"))
    startedRuntimeIds = startedRuntimeIds :+ runtime.id
    AdapterRuntime("handle-" + runtime.id, Some("compute-routing-test"))
  }

  override def getRuntimeStatus(runtime: NotebookRuntime): AdapterRuntimeStatus =
    if (lostRuntimeIds.contains(runtime.id)) {
      AdapterRuntimeStatus(RuntimeState.LOST, Some("engine lost"))
    } else {
      AdapterRuntimeStatus(RuntimeState.IDLE, None)
    }

  override def execute(
      runtime: NotebookRuntime,
      execution: CellExecution,
      configuration: Map[String, String]): AdapterExecution =
    AdapterExecution("operation-" + execution.id)

  override def getExecutionStatus(execution: CellExecution): AdapterExecutionStatus =
    AdapterExecutionStatus(ExecutionState.SUCCEEDED, None, None, None, None, false)

  override def interruptExecution(execution: CellExecution): Unit = ()

  override def closeExecution(execution: CellExecution): Unit = ()

  override def restartRuntime(
      runtime: NotebookRuntime,
      configuration: Map[String, String]): AdapterRuntime = startRuntime(runtime, configuration)

  override def stopRuntime(runtime: NotebookRuntime): Unit = ()

  override def fetchLogs(
      execution: CellExecution,
      offset: Long,
      maxLines: Int): AdapterLogPage = AdapterLogPage(Seq.empty, offset, false)

  def lose(runtimeId: String): Unit = lostRuntimeIds += runtimeId
}
