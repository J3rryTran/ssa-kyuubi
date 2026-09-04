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

import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.server.notebook.api._

/**
 * REST resource for engine profile management.
 *
 * Endpoints:
 *   GET    /api/v1/engine-profiles                      list caller's profiles
 *   GET    /api/v1/engine-profiles/{subdomain}          get one profile
 *   PUT    /api/v1/engine-profiles/{subdomain}          upsert a profile
 *   DELETE /api/v1/engine-profiles/{subdomain}          delete a profile
 *
 * The `subdomain` path parameter becomes both the Zookeeper engine subdomain key and the DB
 * primary key. `owner` is always derived from the authenticated security context; it is never
 * read from the request body.
 */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class NotebookEngineProfilesResource extends NotebookApiSupport {

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
    notebooks.engineProfiles.list(principal).map(EngineProfileView.apply)

  /**
   * Fetch a single engine profile by subdomain.
   */
  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[EngineProfileView]))),
    description = "Get the engine profile for the given subdomain.")
  @GET
  @Path("{subdomain}")
  def get(@PathParam("subdomain") subdomain: String): EngineProfileView =
    EngineProfileView(notebooks.engineProfiles.get(subdomain))

  /**
   * Create or replace the engine profile for `subdomain`.
   *
   * On first creation the caller becomes the owner.  A subsequent PUT from the same owner
   * replaces the Spark config in full.  An admin may overwrite any owner's profile.
   */
  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[EngineProfileView]))),
    description = "Create or replace the engine profile for the given subdomain.")
  @PUT
  @Path("{subdomain}")
  def upsert(
      @PathParam("subdomain") subdomain: String,
      request: UpsertEngineProfileRequest): EngineProfileView =
    EngineProfileView(notebooks.engineProfiles.upsert(subdomain, principal, request))

  /**
   * Delete the engine profile for `subdomain`.  Only the owner or an admin may do so.
   */
  @ApiResponse(
    responseCode = "204",
    description = "Engine profile deleted.")
  @DELETE
  @Path("{subdomain}")
  def delete(@PathParam("subdomain") subdomain: String): Response = {
    notebooks.engineProfiles.delete(subdomain, principal)
    Response.noContent().build()
  }
}
