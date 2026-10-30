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

package org.apache.kyuubi.server.http.authentication

import java.security.Principal
import java.util.Base64
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import org.mockito.Mockito.{lenient, verify}
import org.scalatestplus.mockito.MockitoSugar.mock

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.http.util.HttpAuthUtils
import org.apache.kyuubi.service.authentication.{AuthTypes, PasswdAuthenticationProvider, TokenAuthenticationProvider, TokenCredential}

class TestPasswdAuthenticationProvider(conf: KyuubiConf) extends PasswdAuthenticationProvider {
  override def authenticate(user: String, password: String): Unit = {}
  override def authenticateAndResolve(user: String, password: String): String = {
    TestPasswdAuthenticationProvider.lastUser = user
    TestPasswdAuthenticationProvider.lastPassword = password
    s"resolved_$user"
  }
}

object TestPasswdAuthenticationProvider {
  var lastUser: String = _
  var lastPassword: String = _
}

class TestTokenAuthenticationProvider(conf: KyuubiConf) extends TokenAuthenticationProvider {
  override def authenticate(credential: TokenCredential): Principal = null
}

class AuthenticationFilterSuite extends KyuubiFunSuite {
  test("add auth handler and destroy") {
    val filter = new AuthenticationFilter(KyuubiConf())
    filter.addAuthHandler(new BasicAuthenticationHandler(null))
    assert(filter.authSchemeHandlers.isEmpty)
    filter.addAuthHandler(new BasicAuthenticationHandler(AuthTypes.LDAP))
    assert(filter.authSchemeHandlers.size == 1)
    filter.addAuthHandler(new BasicAuthenticationHandler(AuthTypes.LDAP))
    assert(filter.authSchemeHandlers.size == 1)
    filter.addAuthHandler(new KerberosAuthenticationHandler())
    assert(filter.authSchemeHandlers.size == 1)
    filter.destroy()
    assert(filter.authSchemeHandlers.isEmpty)
  }

  test("BasicAuthenticationHandler parses credentials and resolves effective user") {
    val handler = new BasicAuthenticationHandler(AuthTypes.CUSTOM)
    val conf = KyuubiConf()
    conf.set(
      KyuubiConf.AUTHENTICATION_CUSTOM_BASIC_CLASS.key,
      classOf[TestPasswdAuthenticationProvider].getName)
    handler.init(conf)

    val req = mock[HttpServletRequest]
    val resp = mock[HttpServletResponse]

    // Credentials with colons in password: "token:my:jwt:payload"
    val creds = Base64.getEncoder.encodeToString("token:my:jwt:payload".getBytes("UTF-8"))
    lenient().when(req.getHeader(HttpAuthUtils.AUTHORIZATION_HEADER)).thenReturn(s"Basic $creds")

    val authUser = handler.authenticate(req, resp)
    assert(authUser === "resolved_token")
    assert(TestPasswdAuthenticationProvider.lastUser === "token")
    assert(TestPasswdAuthenticationProvider.lastPassword === "my:jwt:payload")
    verify(resp).setStatus(HttpServletResponse.SC_OK)
  }

  test("BasicAuthenticationHandler empty user handling across auth types") {
    val conf = KyuubiConf()
    conf.set(
      KyuubiConf.AUTHENTICATION_CUSTOM_BASIC_CLASS.key,
      classOf[TestPasswdAuthenticationProvider].getName)

    // LDAP rejects empty username immediately
    val ldapHandler = new BasicAuthenticationHandler(AuthTypes.LDAP)
    ldapHandler.init(conf)
    val ldapReq = mock[HttpServletRequest]
    val ldapResp = mock[HttpServletResponse]
    val emptyUserCreds = Base64.getEncoder.encodeToString(":secret".getBytes("UTF-8"))
    lenient().when(ldapReq.getHeader(HttpAuthUtils.AUTHORIZATION_HEADER))
      .thenReturn(s"Basic $emptyUserCreds")

    assert(ldapHandler.authenticate(ldapReq, ldapResp) == null)
    verify(ldapResp).setStatus(HttpServletResponse.SC_UNAUTHORIZED)

    // OIDC allows empty username to be resolved by provider
    val oidcHandler = new BasicAuthenticationHandler(AuthTypes.OIDC)
    oidcHandler.init(conf)
    val oidcReq = mock[HttpServletRequest]
    val oidcResp = mock[HttpServletResponse]
    lenient().when(oidcReq.getHeader(HttpAuthUtils.AUTHORIZATION_HEADER))
      .thenReturn(s"Basic $emptyUserCreds")

    val authUser = oidcHandler.authenticate(oidcReq, oidcResp)
    assert(authUser === "resolved_")
    assert(TestPasswdAuthenticationProvider.lastUser === "")
    assert(TestPasswdAuthenticationProvider.lastPassword === "secret")
    verify(oidcResp).setStatus(HttpServletResponse.SC_OK)
  }

  test("BasicAuthenticationHandler rejects empty password") {
    val handler = new BasicAuthenticationHandler(AuthTypes.OIDC)
    val conf = KyuubiConf()
    handler.init(conf)

    val req = mock[HttpServletRequest]
    val resp = mock[HttpServletResponse]
    val emptyPassCreds = Base64.getEncoder.encodeToString("user:".getBytes("UTF-8"))
    lenient().when(req.getHeader(HttpAuthUtils.AUTHORIZATION_HEADER))
      .thenReturn(s"Basic $emptyPassCreds")

    assert(handler.authenticate(req, resp) == null)
    verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED)
  }

  test("BearerAuthenticationHandler.authenticationSupported with valid and invalid classes") {
    // Valid class implementing TokenAuthenticationProvider -> true
    val validHandler = new BearerAuthenticationHandler(
      classOf[TestTokenAuthenticationProvider].getName)
    assert(validHandler.authenticationSupported)

    // Existing class not implementing TokenAuthenticationProvider (e.g. java.lang.String) -> false
    val invalidHandler = new BearerAuthenticationHandler(classOf[String].getName)
    assert(!invalidHandler.authenticationSupported)

    // Non-existent class -> false
    val nonExistentHandler =
      new BearerAuthenticationHandler("org.apache.kyuubi.NonExistentProvider")
    assert(!nonExistentHandler.authenticationSupported)
  }
}
