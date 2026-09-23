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

import javax.ws.rs.{Consumes, POST, Produces}
import javax.ws.rs.core.MediaType

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.client.api.v1.dto
import org.apache.kyuubi.config.KyuubiReservedKeys._
import org.apache.kyuubi.server.engineprofile.{EngineProfilePrincipal, EngineProfileSnapshot}
import org.apache.kyuubi.server.notebook.api.{NotebookException, OpenEditorSessionRequest}

/**
 * Opens SQL Editor sessions from an Engine Profile ID, never browser-supplied Spark settings.
 */
@Tag(name = "Session")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class EditorSessionsResource extends NotebookApiSupport {

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "Open a SQL Editor session using an authorized Engine Profile.")
  @POST
  def open(request: OpenEditorSessionRequest): dto.SessionHandle = {
    val profileId = Option(request).flatMap(value => Option(value.getEngineProfileId))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(throw NotebookException.invalid("engineProfileId must be set"))
    val snapshot = notebooks.engineProfiles.snapshotForUse(
      profileId,
      EngineProfilePrincipal(principal.user, principal.admin))
    val ipAddress = fe.getIpAddress
    val config = snapshot.sparkConfig ++ EngineProfileSnapshot.engineSessionConfig(snapshot) ++
      notebooks.pythonEnvironments.launchConfig(snapshot) ++ Map(
        "kyuubi.engine.type" -> "SPARK_SQL",
        "kyuubi.engine.share.level.subdomain" -> snapshot.subdomain,
        "kyuubi.engine.share.level.sub.domain" -> snapshot.subdomain,
        KYUUBI_CLIENT_IP_KEY -> ipAddress,
        KYUUBI_SERVER_IP_KEY -> fe.host,
        KYUUBI_SESSION_CONNECTION_URL_KEY -> fe.connectionUrl,
        KYUUBI_SESSION_REAL_USER_KEY -> fe.getRealUser())
    val handle = fe.be.openSession(
      SessionsResource.SESSION_PROTOCOL_VERSION,
      principal.user,
      "",
      ipAddress,
      config)
    new dto.SessionHandle(handle.identifier, fe.connectionUrl)
  }
}
