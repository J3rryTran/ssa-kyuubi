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

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets

import com.fasterxml.jackson.databind.ObjectMapper

/** The parts of a successful token response the BFF cares about. */
case class TokenResponse(
    accessToken: String,
    refreshToken: Option[String],
    idToken: Option[String],
    expiresInSeconds: Long)

/**
 * A token endpoint error. `error` carries the OAuth2 error code (`invalid_grant`,
 * `invalid_client`, ...) so callers can distinguish "this refresh token is dead, drop the
 * session" from a transient failure. The provider's `error_description` is deliberately not
 * propagated - it can echo request material back into logs.
 */
class OidcTokenException(val error: String, message: String, cause: Throwable = null)
  extends Exception(message, cause)

/**
 * The server side of the OAuth2 token exchange. An interface so tests can drive the refresh and
 * code-exchange paths without a provider - none of the unit tests in this module touch the
 * network.
 */
trait TokenEndpointClient {
  def post(endpoint: String, form: Seq[(String, String)]): TokenResponse
}

/**
 * Default [[TokenEndpointClient]] over [[HttpURLConnection]]. Deliberately dependency-free:
 * the call is a single form POST to a trusted, usually in-cluster endpoint.
 */
class HttpUrlTokenEndpointClient(connectTimeoutMs: Int, readTimeoutMs: Int)
  extends TokenEndpointClient {

  override def post(endpoint: String, form: Seq[(String, String)]): TokenResponse = {
    val body = OidcBffUtils.formEncode(form).getBytes(StandardCharsets.UTF_8)
    var conn: HttpURLConnection = null
    try {
      conn = new URL(endpoint).openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setConnectTimeout(connectTimeoutMs)
      conn.setReadTimeout(readTimeoutMs)
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      conn.setRequestProperty("Accept", "application/json")
      conn.setFixedLengthStreamingMode(body.length)
      val out = conn.getOutputStream
      try out.write(body)
      finally out.close()

      val status = conn.getResponseCode
      if (status / 100 == 2) {
        OidcJson.parseTokenResponse(readAll(conn.getInputStream))
      } else {
        // Read the error body only to extract the OAuth2 error code; nothing else is kept.
        val error = OidcJson.parseErrorCode(readAllQuietly(conn.getErrorStream))
        throw new OidcTokenException(
          error,
          s"Token endpoint returned HTTP $status (error=$error)")
      }
    } catch {
      case e: OidcTokenException => throw e
      case e: Exception =>
        throw new OidcTokenException("request_failed", s"Token request failed: ${e.getMessage}", e)
    } finally {
      if (conn != null) conn.disconnect()
    }
  }

  private def readAll(in: InputStream): String = {
    try {
      val buffer = new ByteArrayOutputStream()
      val chunk = new Array[Byte](4096)
      var read = in.read(chunk)
      while (read != -1) {
        buffer.write(chunk, 0, read)
        read = in.read(chunk)
      }
      new String(buffer.toByteArray, StandardCharsets.UTF_8)
    } finally {
      in.close()
    }
  }

  private def readAllQuietly(in: InputStream): String = {
    if (in == null) return ""
    try readAll(in)
    catch { case _: Exception => "" }
  }
}

/** JSON shapes exchanged with the provider. */
private[oidc] object OidcJson {

  private val mapper = new ObjectMapper()

  def parseTokenResponse(body: String): TokenResponse = {
    val node = mapper.readTree(body)
    val accessToken = Option(node.get("access_token")).map(_.asText).filter(_.nonEmpty)
      .getOrElse(throw new OidcTokenException(
        "invalid_response",
        "Token response contained no access_token"))
    TokenResponse(
      accessToken = accessToken,
      refreshToken = Option(node.get("refresh_token")).map(_.asText).filter(_.nonEmpty),
      idToken = Option(node.get("id_token")).map(_.asText).filter(_.nonEmpty),
      expiresInSeconds = Option(node.get("expires_in")).map(_.asLong).getOrElse(0L))
  }

  def parseErrorCode(body: String): String = {
    try {
      Option(mapper.readTree(body).get("error")).map(_.asText).filter(_.nonEmpty)
        .getOrElse("unknown_error")
    } catch {
      case _: Exception => "unknown_error"
    }
  }
}

/** Reads the provider's discovery document, for endpoints an operator did not pin explicitly. */
object OidcDiscovery {

  private val mapper = new ObjectMapper()

  private val endpointKeys =
    Seq("authorization_endpoint", "token_endpoint", "end_session_endpoint", "jwks_uri", "issuer")

  def fetch(issuer: String): Map[String, String] = fetch(issuer, 5000, 5000)

  def fetch(issuer: String, connectTimeoutMs: Int, readTimeoutMs: Int): Map[String, String] = {
    val base = if (issuer.endsWith("/")) issuer.dropRight(1) else issuer
    val url = new URL(s"$base/.well-known/openid-configuration")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    try {
      conn.setRequestMethod("GET")
      conn.setConnectTimeout(connectTimeoutMs)
      conn.setReadTimeout(readTimeoutMs)
      conn.setRequestProperty("Accept", "application/json")
      val in = conn.getInputStream
      val content =
        try scala.io.Source.fromInputStream(in, StandardCharsets.UTF_8.name()).mkString
        finally in.close()
      val node = mapper.readTree(content)
      endpointKeys.flatMap { key =>
        Option(node.get(key)).map(_.asText).filter(_.nonEmpty).map(key -> _)
      }.toMap
    } finally {
      conn.disconnect()
    }
  }
}
