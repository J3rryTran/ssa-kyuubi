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

package org.apache.kyuubi.server.engineprofile

import org.apache.kyuubi.server.notebook.api.{EngineProfileRunSnapshot, NotebookException}
import org.apache.kyuubi.server.notebook.store.NotebookStore

/**
 * Leases one Engine Profile for all Thrift sessions of one DBT run.
 *
 * DBT uses a connection pool and can open SQL and Python sessions concurrently. The pin is
 * therefore durable and shared between Kyuubi replicas, rather than held in one frontend's
 * memory. A lease is renewed whenever another session from the run opens; an expired run is
 * allowed to create a fresh snapshot only after its configured retention period.
 */
class EngineProfileRunSnapshotService(
    store: NotebookStore,
    profiles: EngineProfileService,
    leaseMillis: Long) {

  require(leaseMillis > 0, "Engine Profile run snapshot lease must be positive")

  def resolve(
      runId: String,
      profileId: String,
      principal: EngineProfilePrincipal)(
      capture: => EngineProfileSnapshot): EngineProfileSnapshot = {
    val now = System.currentTimeMillis()
    store.getEngineProfileRunSnapshot(runId) match {
      case Some(pin) if pin.expiresAt > now =>
        validate(pin, profileId, principal)
        store.touchEngineProfileRunSnapshot(runId, now + leaseMillis)
        profiles.snapshotForUse(profileId, principal)
      case _ =>
        // It is safe for several replicas to race here. The row's primary key elects the one
        // snapshot that all of them will use; losers re-read it below.
        store.deleteExpiredEngineProfileRunSnapshots(now)
        val snapshot = capture
        val created = store.createEngineProfileRunSnapshot(
          EngineProfileRunSnapshot(
            runId,
            profileId,
            principal.user,
            profiles.getOwned(profileId, principal).version,
            now,
            now + leaseMillis))
        if (created) {
          snapshot
        } else {
          resolveExisting(runId, profileId, principal)
        }
    }
  }

  def reapExpired(): Int = store.deleteExpiredEngineProfileRunSnapshots(System.currentTimeMillis())

  private def resolveExisting(
      runId: String,
      profileId: String,
      principal: EngineProfilePrincipal): EngineProfileSnapshot = {
    val now = System.currentTimeMillis()
    store.getEngineProfileRunSnapshot(runId) match {
      case Some(pin) if pin.expiresAt > now =>
        validate(pin, profileId, principal)
        store.touchEngineProfileRunSnapshot(runId, now + leaseMillis)
        profiles.snapshotForUse(profileId, principal)
      case _ =>
        throw NotebookException.invalid(
          s"Engine Profile run '$runId' expired while its profile was being leased; retry DBT run")
    }
  }

  private def validate(
      pin: EngineProfileRunSnapshot,
      profileId: String,
      principal: EngineProfilePrincipal): Unit = {
    if (pin.profileId != profileId || pin.owner != principal.user) {
      throw NotebookException.invalid(
        s"Engine Profile run '${pin.runId}' belongs to another user or Engine Profile")
    }
  }
}
