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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Whether a forwarded request may speak for the user it claims.
 *
 * The Web UI authenticates with a session cookie, and that cookie is only known to the instance
 * that issued it. When a request has to be forwarded to the instance owning the session, the
 * cookie is worthless at the far end, so the forwarding instance - which has already
 * authenticated the caller - vouches for the identity instead and proves it is a peer with a
 * shared secret.
 *
 * The whole security of that hop rests on one rule: the claimed user is accepted only against a
 * matching secret. A client that guesses the header names gets nowhere, because it cannot supply
 * the secret, and an instance with no secret configured accepts no claim at all.
 */
object NotebookProxyIdentity {

  /**
   * The user a request may act as, when it is a genuine forwarded one.
   *
   * `None` means "decide as usual": either this is an ordinary client request, or it claims an
   * identity it has not proved and the claim is discarded rather than honoured.
   *
   * @param proxied     value of the proxy marker header, if any
   * @param claimedUser value of the real-user header, if any
   * @param token       value of the internal token header, if any
   * @param secret      what this instance was configured with, if anything
   */
  def acceptedUser(
      proxied: Option[String],
      claimedUser: Option[String],
      token: Option[String],
      secret: Option[String]): Option[String] = {
    val configured = secret.map(_.trim).filter(_.nonEmpty)
    val presented = token.map(_.trim).filter(_.nonEmpty)
    val user = claimedUser.map(_.trim).filter(_.nonEmpty)
    val isProxied = proxied.exists(_.trim.equalsIgnoreCase(NotebookProxyHeaders.PROXIED_VALUE))

    for {
      expected <- configured
      supplied <- presented
      named <- user
      if isProxied && constantTimeEquals(expected, supplied)
    } yield named
  }

  /**
   * Compares without leaking where two secrets first differ.
   *
   * A plain `==` on strings stops at the first mismatched character, and the time that takes is
   * measurable across many attempts. This is cheap enough to do properly.
   */
  private def constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
      left.getBytes(StandardCharsets.UTF_8),
      right.getBytes(StandardCharsets.UTF_8))
}
