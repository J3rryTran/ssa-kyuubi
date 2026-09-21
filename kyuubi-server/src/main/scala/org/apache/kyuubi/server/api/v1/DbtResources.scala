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

package org.apache.kyuubi.server.api.v1

import javax.ws.rs._
import javax.ws.rs.core.{MediaType, Response}

import org.apache.kyuubi.server.dbt.{DbtJob, DbtJobRun, DbtRunnerLogPage, DbtRunnerNotConfiguredException, DbtWorkspace}
import org.apache.kyuubi.server.notebook.api._

/** DBT metadata API. It deliberately contains no endpoint that accepts raw Spark configuration. */
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class DbtWorkspacesResource extends NotebookApiSupport {
  @GET
  def list(): Seq[DbtWorkspace] = notebooks.dbt.listWorkspaces(principal)

  @POST
  def create(request: CreateDbtWorkspaceRequest): DbtWorkspace =
    notebooks.dbt.createWorkspace(
      principal,
      request.getName,
      request.getProjectRef,
      request.getEngineProfileId)

  @GET
  @Path("{workspaceId}")
  def get(@PathParam("workspaceId") workspaceId: String): DbtWorkspace =
    notebooks.dbt.getWorkspace(principal, workspaceId)

  @PATCH
  @Path("{workspaceId}")
  def update(
      @PathParam("workspaceId") workspaceId: String,
      request: UpdateDbtWorkspaceRequest): DbtWorkspace = {
    val version = Option(request.getVersion).map(_.longValue).getOrElse(
      throw NotebookException.invalid("version is required"))
    notebooks.dbt.updateWorkspace(
      principal,
      workspaceId,
      Option(request.getName),
      Option(request.getProjectRef),
      Option(request.getEngineProfileId),
      version)
  }

  @DELETE
  @Path("{workspaceId}")
  def delete(
      @PathParam("workspaceId") workspaceId: String,
      @QueryParam("version") version: Long): Response = {
    notebooks.dbt.deleteWorkspace(principal, workspaceId, version)
    Response.noContent().build()
  }

  @GET
  @Path("{workspaceId}/jobs")
  def listJobs(@PathParam("workspaceId") workspaceId: String): Seq[DbtJob] =
    notebooks.dbt.listJobs(principal, workspaceId)

  @GET
  @Path("{workspaceId}/runs")
  def listRuns(
      @PathParam("workspaceId") workspaceId: String,
      @DefaultValue("50") @QueryParam("limit") limit: Int): Seq[DbtJobRun] =
    notebooks.dbt.listRunsForWorkspace(principal, workspaceId, limit)

  @POST
  @Path("{workspaceId}/jobs")
  def createJob(
      @PathParam("workspaceId") workspaceId: String,
      request: CreateDbtJobRequest): DbtJob =
    notebooks.dbt.createJob(
      principal,
      workspaceId,
      request.getName,
      request.getAction,
      Option(request.getSelector),
      Option(request.getEngineProfileIdOverride))

  @POST
  @Path("{workspaceId}:preview")
  def preview(
      @PathParam("workspaceId") workspaceId: String,
      request: DbtPreviewRequest): DbtJobRun = withRunner {
    notebooks.dbt.preview(principal, workspaceId, request.getSelector)
  }

  private def withRunner(f: => DbtJobRun): DbtJobRun =
    try f
    catch {
      case _: DbtRunnerNotConfiguredException =>
        throw new NotebookException(
          NotebookErrorCode.DBT_RUNNER_NOT_CONFIGURED,
          "DBT runner is not configured on this server")
    }
}

@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class DbtJobsResource extends NotebookApiSupport {
  @GET
  @Path("{jobId}")
  def get(@PathParam("jobId") jobId: String): DbtJob = notebooks.dbt.getJob(principal, jobId)

  @GET
  @Path("{jobId}/runs")
  def listRuns(
      @PathParam("jobId") jobId: String,
      @DefaultValue("50") @QueryParam("limit") limit: Int): Seq[DbtJobRun] =
    notebooks.dbt.listRunsForJob(principal, jobId, limit)

  @PATCH
  @Path("{jobId}")
  def update(@PathParam("jobId") jobId: String, request: UpdateDbtJobRequest): DbtJob = {
    val version = Option(request.getVersion).map(_.longValue).getOrElse(
      throw NotebookException.invalid("version is required"))
    notebooks.dbt.updateJob(
      principal,
      jobId,
      Option(request.getName),
      Option(request.getAction),
      Option(request.getSelector),
      Option(request.getEngineProfileIdOverride),
      Option(request.getClearEngineProfileIdOverride).exists(_.booleanValue),
      version)
  }

  @DELETE
  @Path("{jobId}")
  def delete(@PathParam("jobId") jobId: String, @QueryParam("version") version: Long): Response = {
    notebooks.dbt.deleteJob(principal, jobId, version)
    Response.noContent().build()
  }

  @POST
  @Path("{jobId}:run")
  def run(@PathParam("jobId") jobId: String): DbtJobRun =
    try notebooks.dbt.submitJob(principal, jobId)
    catch {
      case _: DbtRunnerNotConfiguredException =>
        throw new NotebookException(
          NotebookErrorCode.DBT_RUNNER_NOT_CONFIGURED,
          "DBT runner is not configured on this server")
    }
}

@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class DbtJobRunsResource extends NotebookApiSupport {
  @GET
  @Path("{runId}")
  def get(@PathParam("runId") runId: String): DbtJobRun = notebooks.dbt.getRun(principal, runId)

  /** Logs become available when Phase D installs a DbtRunner implementation. */
  @GET
  @Path("{runId}/logs")
  def logs(
      @PathParam("runId") runId: String,
      @QueryParam("offset") offset: Long,
      @QueryParam("limit") limit: java.lang.Integer): DbtRunnerLogPage =
    try notebooks.dbt.logs(principal, runId, offset, Option(limit).map(_.intValue))
    catch { case _: DbtRunnerNotConfiguredException => runnerNotConfigured() }

  /** Cancellation is deliberately runner-owned; it must never terminate a shared Spark engine. */
  @POST
  @Path("{runId}:cancel")
  def cancel(@PathParam("runId") runId: String): Response = {
    try {
      notebooks.dbt.cancel(principal, runId)
      Response.accepted().build()
    } catch { case _: DbtRunnerNotConfiguredException => runnerNotConfigured() }
  }

  private def runnerNotConfigured[T](): T =
    throw new NotebookException(
      NotebookErrorCode.DBT_RUNNER_NOT_CONFIGURED,
      "DBT runner is not configured on this server")
}
