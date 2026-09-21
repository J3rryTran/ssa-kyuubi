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

package org.apache.kyuubi.server.dbt

import org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot

object DbtAction extends Enumeration {
  type DbtAction = Value
  val PREVIEW, RUN_MODEL, RUN_PROJECT = Value
}

object DbtRunState extends Enumeration {
  type DbtRunState = Value
  val QUEUED, DISPATCHING, SUBMITTED, RUNNING, SUCCEEDED, FAILED, CANCELLING, CANCELLED = Value

  val active: Set[Value] = Set(QUEUED, DISPATCHING, SUBMITTED, RUNNING, CANCELLING)
  val terminal: Set[Value] = Set(SUCCEEDED, FAILED, CANCELLED)
}

case class DbtWorkspace(
    id: String,
    owner: String,
    name: String,
    projectRef: String,
    selectedEngineProfileId: String,
    createdAt: Long,
    updatedAt: Long,
    version: Long)

case class DbtJob(
    id: String,
    workspaceId: String,
    owner: String,
    name: String,
    action: DbtAction.Value,
    selector: Option[String],
    engineProfileIdOverride: Option[String],
    createdAt: Long,
    updatedAt: Long,
    version: Long)

case class DbtJobRun(
    id: String,
    workspaceId: String,
    jobId: Option[String],
    submittedBy: String,
    effectiveKyuubiUser: String,
    action: DbtAction.Value,
    selector: Option[String],
    projectRef: String,
    profile: EngineProfileSnapshot,
    state: DbtRunState.Value,
    runnerRunId: Option[String],
    dispatchToken: Option[String],
    runnerJobUid: Option[String],
    createdAt: Long,
    startedAt: Option[Long],
    finishedAt: Option[Long],
    errorSummary: Option[String],
    stateUpdatedAt: Long,
    logNextOffset: Long,
    logTruncated: Boolean,
    version: Long)

case class DbtRunnerSubmission(runnerRunId: String, runnerJobUid: Option[String] = None)

case class DbtRunnerStatus(
    state: DbtRunState.Value,
    startedAt: Option[Long] = None,
    finishedAt: Option[Long] = None,
    errorSummary: Option[String] = None)

case class DbtRunnerLogPage(
    content: String,
    nextOffset: Long,
    endOfStream: Boolean,
    truncated: Boolean = false)

case class DbtRunLogChunk(
    sequence: Long,
    startOffset: Long,
    endOffset: Long,
    content: String,
    createdAt: Long)

case class DbtRunRequest(run: DbtJobRun)

/** Port implemented by the Kubernetes runner in Phase D. */
trait DbtRunner {
  def submit(request: DbtRunRequest): DbtRunnerSubmission
  def findSubmission(runId: String): Option[DbtRunnerSubmission]
  def status(runnerRunId: String): DbtRunnerStatus
  def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage
  def cancel(runnerRunId: String): Unit
  def cleanup(runId: String): Unit
}

/** Explicit default: a server must not create a run it cannot dispatch. */
object DisabledDbtRunner extends DbtRunner {
  override def submit(request: DbtRunRequest): DbtRunnerSubmission =
    throw new DbtRunnerNotConfiguredException

  override def cancel(runnerRunId: String): Unit =
    throw new DbtRunnerNotConfiguredException

  override def findSubmission(runId: String): Option[DbtRunnerSubmission] =
    throw new DbtRunnerNotConfiguredException

  override def status(runnerRunId: String): DbtRunnerStatus =
    throw new DbtRunnerNotConfiguredException

  override def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage =
    throw new DbtRunnerNotConfiguredException

  override def cleanup(runId: String): Unit =
    throw new DbtRunnerNotConfiguredException
}

class DbtRunnerNotConfiguredException extends RuntimeException
