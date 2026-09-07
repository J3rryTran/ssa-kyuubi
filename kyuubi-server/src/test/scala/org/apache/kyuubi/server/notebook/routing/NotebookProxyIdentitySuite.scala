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

package org.apache.kyuubi.server.notebook.routing

import org.apache.kyuubi.KyuubiFunSuite

/**
 * The hop is the one place a request can claim to be somebody without proving it the usual way,
 * so every way of getting that wrong is worth pinning down.
 */
class NotebookProxyIdentitySuite extends KyuubiFunSuite {

  private val secret = Some("s3cret")

  private def accepted(
      proxied: Option[String] = Some("true"),
      user: Option[String] = Some("alice"),
      token: Option[String] = Some("s3cret"),
      configured: Option[String] = secret): Option[String] =
    NotebookProxyIdentity.acceptedUser(proxied, user, token, configured)

  test("a peer with the right secret speaks for its caller") {
    assert(accepted() === Some("alice"))
  }

  test("a client that guesses the header names proves nothing") {
    // The whole spoofing scenario: everything except the secret.
    assert(accepted(token = None).isEmpty)
    assert(accepted(token = Some("")).isEmpty)
    assert(accepted(token = Some("wrong")).isEmpty)
  }

  test("an instance with no secret configured honours no claim at all") {
    assert(accepted(configured = None).isEmpty)
    assert(accepted(configured = Some("  ")).isEmpty)
  }

  test("the claim is only read on a request that says it was forwarded") {
    assert(accepted(proxied = None).isEmpty)
    assert(accepted(proxied = Some("false")).isEmpty)
    // Casing of the marker is not worth being strict about.
    assert(accepted(proxied = Some("TRUE")) === Some("alice"))
  }

  test("an empty or missing user is not an identity") {
    assert(accepted(user = None).isEmpty)
    assert(accepted(user = Some("   ")).isEmpty)
  }

  test("surrounding whitespace does not change who is accepted") {
    assert(accepted(user = Some(" alice "), token = Some(" s3cret ")) === Some("alice"))
  }
}
