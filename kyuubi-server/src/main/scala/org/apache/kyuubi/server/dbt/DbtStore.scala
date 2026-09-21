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

trait DbtStore extends AutoCloseable {
  def initSchema(): Unit
  def createWorkspace(workspace: DbtWorkspace): Unit
  def getWorkspace(id: String): Option[DbtWorkspace]
  def listWorkspaces(owner: String): Seq[DbtWorkspace]
  def updateWorkspace(workspace: DbtWorkspace, expectedVersion: Long): Boolean
  def deleteWorkspace(id: String, owner: String, expectedVersion: Long): Boolean

  def createJob(job: DbtJob): Unit
  def getJob(id: String): Option[DbtJob]
  def listJobs(workspaceId: String): Seq[DbtJob]
  def updateJob(job: DbtJob, expectedVersion: Long): Boolean
  def deleteJob(id: String, owner: String, expectedVersion: Long): Boolean

  def createRun(run: DbtJobRun): Unit
  def getRun(id: String): Option[DbtJobRun]
  def listRunsForWorkspace(workspaceId: String, limit: Int): Seq[DbtJobRun]
  def listRunsForJob(jobId: String, limit: Int): Seq[DbtJobRun]
  def updateRun(run: DbtJobRun, expectedStates: Set[DbtRunState.Value]): Boolean
  def claimDispatch(id: String, expectedVersion: Long, token: String, now: Long): Boolean
  def appendLog(run: DbtJobRun, content: String, now: Long): Boolean
  def readLogs(runId: String, offset: Long, limit: Int): DbtRunnerLogPage
  def listActiveRuns(): Seq[DbtJobRun]
  def listActiveRunsForProfile(profileId: String): Seq[DbtJobRun]
  def listActiveRunsForWorkspace(workspaceId: String): Seq[DbtJobRun]
}
