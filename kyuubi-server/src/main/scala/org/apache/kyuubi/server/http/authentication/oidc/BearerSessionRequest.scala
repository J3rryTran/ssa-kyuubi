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

package org.apache.kyuubi.server.http.authentication.oidc

import java.util.Collections
import javax.servlet.http.{HttpServletRequest, HttpServletRequestWrapper}

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.http.util.HttpAuthUtils.AUTHORIZATION_HEADER

/**
 * Presents a cookie-authenticated request as if the browser had sent
 * `Authorization: Bearer <access token>`.
 *
 * This is what keeps the BFF from forking the authentication logic: once the session cookie has
 * been resolved to an access token, the request continues down the ordinary Bearer path and is
 * validated by the same provider as a JDBC client's token would be.
 */
class BearerSessionRequest(request: HttpServletRequest, accessToken: String)
  extends HttpServletRequestWrapper(request) {

  private val authorization = s"Bearer $accessToken"

  private def isAuthorizationHeader(name: String): Boolean =
    name != null && name.equalsIgnoreCase(AUTHORIZATION_HEADER)

  override def getHeader(name: String): String = {
    if (isAuthorizationHeader(name)) authorization else super.getHeader(name)
  }

  override def getHeaders(name: String): java.util.Enumeration[String] = {
    if (isAuthorizationHeader(name)) Collections.enumeration(Seq(authorization).asJava)
    else super.getHeaders(name)
  }

  override def getHeaderNames: java.util.Enumeration[String] = {
    val names = super.getHeaderNames.asScala.toSeq
    val merged =
      if (names.exists(isAuthorizationHeader)) names else names :+ AUTHORIZATION_HEADER
    Collections.enumeration(merged.asJava)
  }
}
