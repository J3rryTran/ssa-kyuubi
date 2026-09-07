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

import java.util.concurrent.{ConcurrentHashMap, ScheduledExecutorService, TimeUnit}

import scala.collection.JavaConverters._

import org.apache.kyuubi.Logging
import org.apache.kyuubi.util.ThreadUtils

/**
 * A browser session established by the BFF login flow.
 *
 * The tokens never leave the server: the browser only ever holds the opaque session id that
 * keys this entry. `expiresAt` tracks the *access token* lifetime, while `createdAt` plus the
 * configured session timeout bounds the session itself.
 */
case class OidcSession(
    accessToken: String,
    refreshToken: Option[String],
    idToken: Option[String],
    expiresAt: Long,
    username: String,
    createdAt: Long)

/** The state of a login that has been started but whose callback has not arrived yet. */
case class PendingLogin(
    codeVerifier: String,
    nonce: String,
    redirectPath: String,
    redirectUri: String,
    createdAt: Long)

/**
 * Keyed store of values that expire. Separated behind an interface so an out-of-process
 * implementation (Redis, JDBC, ...) can replace the in-memory default without touching callers;
 * see the HA note in the BFF design - with `sessionAffinity: ClientIP` a single-pod store is
 * sufficient because a miss only costs a silent re-login against the still-live SSO session.
 */
trait OidcSessionStore[V] {
  def get(key: String): Option[V]
  def put(key: String, value: V): Unit
  def remove(key: String): Option[V]
  def size: Int
}

/**
 * In-memory [[OidcSessionStore]] backed by a [[ConcurrentHashMap]]. Entries older than `ttlMs`
 * are treated as absent on read and swept by a background task every minute, so an abandoned
 * login cannot pin memory until the next access.
 */
class InMemoryOidcSessionStore[V](
    ttlMs: Long,
    name: String,
    cleanupIntervalMs: Long = TimeUnit.MINUTES.toMillis(1))
  extends OidcSessionStore[V] with Logging {

  private case class Entry(value: V, insertedAt: Long)

  private val entries = new ConcurrentHashMap[String, Entry]()

  private val cleaner: ScheduledExecutorService =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor(s"oidc-$name-cleaner")

  cleaner.scheduleWithFixedDelay(
    new Runnable {
      override def run(): Unit =
        try {
          evictExpired()
        } catch {
          case e: Throwable => warn(s"Failed to evict expired oidc $name entries", e)
        }
    },
    cleanupIntervalMs,
    cleanupIntervalMs,
    TimeUnit.MILLISECONDS)

  private def expired(entry: Entry, now: Long): Boolean = now - entry.insertedAt >= ttlMs

  override def get(key: String): Option[V] = {
    if (key == null) return None
    Option(entries.get(key)).flatMap { entry =>
      if (expired(entry, System.currentTimeMillis())) {
        entries.remove(key, entry)
        None
      } else {
        Some(entry.value)
      }
    }
  }

  override def put(key: String, value: V): Unit = {
    entries.put(key, Entry(value, System.currentTimeMillis()))
  }

  override def remove(key: String): Option[V] = {
    if (key == null) return None
    Option(entries.remove(key)).flatMap { entry =>
      if (expired(entry, System.currentTimeMillis())) None else Some(entry.value)
    }
  }

  override def size: Int = {
    evictExpired()
    entries.size()
  }

  private[oidc] def evictExpired(): Unit = {
    val now = System.currentTimeMillis()
    entries.entrySet().asScala.foreach { e =>
      if (expired(e.getValue, now)) entries.remove(e.getKey, e.getValue)
    }
  }

  def shutdown(): Unit = {
    ThreadUtils.shutdown(cleaner)
    entries.clear()
  }
}
