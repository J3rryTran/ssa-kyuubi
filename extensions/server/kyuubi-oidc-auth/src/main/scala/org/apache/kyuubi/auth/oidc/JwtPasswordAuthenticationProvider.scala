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

  override def authenticate(user: String, password: String): Unit = {
    if (conf == null) {
      throw new IllegalStateException("KyuubiConf must not be null")
    }

    if (password == null || password.trim.isEmpty || password.split("\\.").length != 3) {
      throw new AuthenticationException(
        "Password authentication is disabled; pass an OIDC access token as the password")
    }

    val principal = JwtPasswordAuthenticationProvider.verifier(conf)
      .authenticate(DefaultTokenCredential(password))

    if (principal.getName != user) {
      throw new AuthenticationException(
        s"Token subject '${principal.getName}' does not match SASL user '$user'")
    }
  }
}

object JwtPasswordAuthenticationProvider {
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
