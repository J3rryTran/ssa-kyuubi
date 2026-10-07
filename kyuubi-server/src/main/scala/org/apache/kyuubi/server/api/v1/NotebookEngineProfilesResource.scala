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

import scala.collection.JavaConverters._

import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.Logging
import org.apache.kyuubi.server.engineprofile.{EngineProfilePrincipal, EngineProfileRuntimeService}
import org.apache.kyuubi.server.notebook.api._

/**
 * REST resource for engine profile management.
 *
 * Endpoints:
 *   GET    /api/v1/engine-profiles                      list caller's profiles
 *   GET    /api/v1/engine-profiles/{profileId}          get one profile
 *   POST   /api/v1/engine-profiles                      create a profile
 *   PATCH  /api/v1/engine-profiles/{profileId}          update a stopped profile
 *   DELETE /api/v1/engine-profiles/{profileId}          delete a profile
 *
 * `profileId` is an immutable server-generated ID. The profile's display name is unique only
 * within its owner, while the runtime subdomain is stable for the profile's lifetime.
 * `owner` is always derived from the authenticated security context; it is never read from the
 * request body.
 */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class NotebookEngineProfilesResource extends NotebookApiSupport with Logging {

  private def engineProfilePrincipal: EngineProfilePrincipal =
    EngineProfilePrincipal(principal.user, principal.admin)

  /**
   * List all engine profiles owned by the calling user.
   */
  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[EngineProfileView]))),
    description = "List engine profiles owned by the caller.")
  @GET
  def list(): Seq[EngineProfileView] =
    notebooks.engineProfiles.list(engineProfilePrincipal).map(EngineProfileView.apply)

  /** Explicit launch resource settings for the default profile, not live engine metrics. */
  @GET
  @Path("default/config")
  def defaultConfig(): Map[String, String] =
    notebooks.engineProfiles.defaultResourceConfig

  /**
   * Fetch one of the caller's engine profiles by ID. An administrator may fetch a known
   * profile owned by another user.
   */
  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[EngineProfileView]))),
    description = "Get an engine profile owned by the caller.")
  @GET
  @Path("{profileId}")
  def get(@PathParam("profileId") profileId: String): EngineProfileView =
    EngineProfileView(notebooks.engineProfiles.getOwned(profileId, engineProfilePrincipal))

  @GET
  @Path("{profileId}/engine")
  def engineStatus(
      @PathParam("profileId") profileId: String): EngineProfileEngineStatusView =
    notebooks.engineProfileRuntimes.status(engineProfilePrincipal, profileId)

  /** Warm an engine for the caller/profile, then release the bootstrap session immediately. */
  @POST
  @Path("{profileId}:start")
  def startEngine(
      @PathParam("profileId") profileId: String): EngineProfileEngineStatusView =
    notebooks.engineProfileRuntimes.start(engineProfilePrincipal, profileId)

  @POST
  @Path("{profileId}:stop")
  def stopEngine(
      @PathParam("profileId") profileId: String): EngineProfileEngineStatusView =
    notebooks.engineProfileRuntimes.stop(engineProfilePrincipal, profileId)

  /**
   * Create an engine profile. The server assigns its immutable ID and initial engine revision.
   */
  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[EngineProfileView]))),
    description = "Create an engine profile for the caller.")
  @POST
  def create(request: UpsertEngineProfileRequest): EngineProfileView =
    EngineProfileView(notebooks.engineProfiles.create(engineProfilePrincipal, request))

  /** Updates the one current configuration after all profile compute has stopped. */
  @PATCH
  @Path("{profileId}")
  def update(
      @PathParam("profileId") profileId: String,
      request: UpsertEngineProfileRequest): EngineProfileView =
    EngineProfileView(notebooks.engineProfiles.update(profileId, engineProfilePrincipal, request))

  /** Returns safe status and package metadata for the profile's persistent environments. */
  @GET
  @Path("{profileId}/python-environments")
  def pythonEnvironments(
      @PathParam("profileId") profileId: String): Seq[PythonEnvironmentRevisionView] = {
    notebooks.engineProfiles.getOwned(profileId, engineProfilePrincipal)
    notebooks.store.listPythonEnvironmentRevisions(profileId)
      .map(PythonEnvironmentRevisionView.apply)
  }

  @POST
  @Path("{profileId}/python-packages:install")
  def installPythonPackages(
      @PathParam("profileId") profileId: String,
      request: ChangePythonEnvironmentPackagesRequest): PythonEnvironmentChangeRequest =
    changePythonPackages(profileId, "INSTALL", request)

  @POST
  @Path("{profileId}/python-packages:uninstall")
  def uninstallPythonPackages(
      @PathParam("profileId") profileId: String,
      request: ChangePythonEnvironmentPackagesRequest): PythonEnvironmentChangeRequest =
    changePythonPackages(profileId, "UNINSTALL", request)

  @GET
  @Path("{profileId}/environment-builds/{requestId}")
  def pythonEnvironmentBuild(
      @PathParam("profileId") profileId: String,
      @PathParam("requestId") requestId: String): PythonEnvironmentChangeRequest = {
    notebooks.engineProfiles.getOwned(profileId, engineProfilePrincipal)
    notebooks.store.getPythonEnvironmentChangeRequest(requestId).filter(_.profileId == profileId)
      .getOrElse(throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"Python environment build '$requestId' was not found"))
  }

  private def changePythonPackages(
      profileId: String,
      operation: String,
      request: ChangePythonEnvironmentPackagesRequest): PythonEnvironmentChangeRequest = {
    val packages = Option(request).flatMap(value => Option(value.getPackages))
      .map(_.asScala.toSeq).getOrElse(Seq.empty)
    notebooks.pythonEnvironments.requestPackageChange(
      engineProfilePrincipal,
      profileId,
      operation,
      packages)
  }

  /**
   * Compatibility endpoint for callers built before profile IDs were introduced. New UI/API
   * clients use POST and PATCH above. It can be removed after all supported clients migrate.
   */
  @PUT
  @Path("{legacyProfileId}")
  def upsertLegacy(
      @PathParam("legacyProfileId") legacyProfileId: String,
      request: UpsertEngineProfileRequest): EngineProfileView =
    EngineProfileView(notebooks.engineProfiles.upsert(
      legacyProfileId,
      engineProfilePrincipal,
      request))

  /**
   * Delete the engine profile for `profileId`. Only the owner or an admin may do so.
   */
  @ApiResponse(
    responseCode = "204",
    description = "Engine profile deleted.")
  @DELETE
  @Path("{profileId}")
  def delete(@PathParam("profileId") profileId: String): Response = {
    notebooks.engineProfiles.getOwned(profileId, engineProfilePrincipal)
    val status = notebooks.engineProfileRuntimes.status(engineProfilePrincipal, profileId)
    if (status.state != EngineProfileRuntimeService.STOPPED) {
      throw NotebookException.invalid(
        s"stop the Engine Profile engine before deletion: ${status.state}")
    }
    if (notebooks.dbt.hasActiveRunsForProfile(profileId)) {
      throw NotebookException.invalid("cannot delete an Engine Profile with an active DBT run")
    }
    notebooks.engineProfiles.delete(profileId, engineProfilePrincipal)
    Response.noContent().build()
  }
}
