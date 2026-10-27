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

package org.apache.kyuubi.auth.oidc

import javax.security.sasl.AuthenticationException

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.service.authentication.{DefaultTokenCredential, PasswdAuthenticationProvider}

/**
 * A [[PasswdAuthenticationProvider]] for SASL PLAIN (e.g. Thrift binary) that accepts an OIDC
 * access token in the password field and verifies it against the provider's JWKS using
 * [[JwtTokenAuthenticationProvider]].
 *
 * The SASL user must match the username claim inside the validated token.
 */
class JwtPasswordAuthenticationProvider(conf: KyuubiConf)
  extends PasswdAuthenticationProvider with Logging {

  def this() = this(null)

  private val passwordEnabled: Boolean = {
    if (conf == null) true
    else conf.getOption(JwtPasswordAuthenticationProvider.PASSWORD_ENABLED)
      .map(_.trim.toBoolean).getOrElse(true)
  }

  private val userAliases: Set[String] = {
    if (conf == null) Set("token")
    else {
      val raw = conf.getOption(JwtPasswordAuthenticationProvider.PASSWORD_USER_ALIASES)
        .getOrElse("token")
      raw.split(",").map(_.trim).filter(_.nonEmpty).toSet
    }
  }

  override def authenticate(user: String, password: String): Unit = {
    authenticateAndResolve(user, password)
  }

  override def authenticateAndResolve(user: String, password: String): String = {
    if (conf == null) {
      throw new IllegalStateException("KyuubiConf must not be null")
    }

    if (!passwordEnabled) {
      throw new AuthenticationException(
        "Password authentication is disabled; " +
          "use OIDC/JWT bearer authentication over HTTP transport")
    }

    if (password == null || password.trim.isEmpty) {
      throw new AuthenticationException(
        "Password authentication is disabled; pass an OIDC access token as the password")
    }

    val trimmedPassword = password.trim
    val rawToken = if (trimmedPassword.regionMatches(true, 0, "Bearer ", 0, 7)) {
      trimmedPassword.substring(7).trim
    } else {
      trimmedPassword
    }

    if (rawToken.split("\\.").length != 3) {
      throw new AuthenticationException(
        "Password authentication is disabled; pass an OIDC access token as the password")
    }

    val principal = JwtPasswordAuthenticationProvider.verifier(conf)
      .authenticate(DefaultTokenCredential(rawToken))
    val tokenUser = principal.getName

    val clientUser = if (user == null) "" else user.trim
    if (clientUser.isEmpty || userAliases.contains(clientUser) || clientUser == tokenUser) {
      debug(s"Authenticated password token for user '$tokenUser' (client username: '$clientUser')")
      tokenUser
    } else {
      warn(s"Client user '$clientUser' does not match token subject '$tokenUser'")
      throw new AuthenticationException(
        s"Token subject '$tokenUser' does not match user '$clientUser'")
    }
  }
}

object JwtPasswordAuthenticationProvider {
  val PREFIX = "kyuubi.authentication.jwt.password."
  val PASSWORD_ENABLED = PREFIX + "enabled"
  val PASSWORD_USER_ALIASES = PREFIX + "user.aliases"

  @volatile private var _verifier: JwtTokenAuthenticationProvider = _

  def verifier(conf: KyuubiConf): JwtTokenAuthenticationProvider = {
    if (conf == null) {
      throw new IllegalStateException("KyuubiConf must not be null")
    }
    if (_verifier == null) {
      synchronized {
        if (_verifier == null) {
          _verifier = new JwtTokenAuthenticationProvider(conf)
        }
      }
    }
    _verifier
  }

  private[oidc] def resetVerifier(): Unit = synchronized {
    _verifier = null
  }
}
