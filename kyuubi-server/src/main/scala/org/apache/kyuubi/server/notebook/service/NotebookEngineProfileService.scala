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

package org.apache.kyuubi.server.notebook.service

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.store.NotebookStore

/**
 * Persistence and authorization for engine profiles.
 *
 * An engine profile is keyed by `subdomain` and stores Spark configuration that should be
 * applied whenever a runtime is started under that subdomain.  The service is the single
 * authoritative source for those configs; `NotebookSessionService.ensureFor` delegates to
 * `resolveSparkConfig` rather than reading the store directly.
 *
 * Ownership rules:
 *  - Only the owner may update or delete their own profile.
 *  - Any user may read any profile (the subdomain is already part of the notebook metadata).
 *  - Admins may delete any profile.
 */
class NotebookEngineProfileService(store: NotebookStore) {

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  def get(subdomain: String): EngineProfile =
    store.getEngineProfile(subdomain).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"engine profile '$subdomain' was not found")
    }

  def list(principal: NotebookPrincipal): Seq[EngineProfile] =
    store.listEngineProfiles(principal.user)

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  /**
   * Creates or replaces the profile for `subdomain`.
   *
   * The `subdomain` comes from the URL path parameter; `owner` is derived from the authenticated
   * principal; neither is accepted from the request body.
   *
   * Validation:
   *  - All Spark config keys must start with `spark.` or `kyuubi.`.
   *  - Memory values (e.g. `spark.driver.memory`) must match the Spark memory string pattern.
   */
  def upsert(
      subdomain: String,
      principal: NotebookPrincipal,
      request: UpsertEngineProfileRequest): EngineProfile = {
    validateSubdomain(subdomain)
    val rawConfig = Option(request.getSparkConfig)
      .map(_.asScala.toMap)
      .getOrElse(Map.empty)
    validateSparkConfig(rawConfig)
    val existing = store.getEngineProfile(subdomain)
    // Only the owner (or an admin) may overwrite an existing profile.
    existing.foreach { ep =>
      if (!principal.admin && ep.owner != principal.user) {
        throw NotebookException.accessDenied(
          s"engine profile '$subdomain' is owned by '${ep.owner}'")
      }
    }
    val now = System.currentTimeMillis()
    val profile = EngineProfile(
      subdomain = subdomain,
      owner = existing.map(_.owner).getOrElse(principal.user),
      sparkConfig = rawConfig,
      createdAt = existing.map(_.createdAt).getOrElse(now),
      updatedAt = now)
    store.upsertEngineProfile(profile)
    profile
  }

  def delete(subdomain: String, principal: NotebookPrincipal): Unit = {
    val ep = get(subdomain) // throws NOT_FOUND when absent
    if (!principal.admin && ep.owner != principal.user) {
      throw NotebookException.accessDenied(
        s"engine profile '$subdomain' is owned by '${ep.owner}'")
    }
    store.deleteEngineProfile(subdomain, ep.owner)
  }

  // ---------------------------------------------------------------------------
  // Runtime integration
  // ---------------------------------------------------------------------------

  /**
   * Returns the Spark config map for `subdomain`, or an empty map when no profile is stored.
   *
   * This is the entry point for [[NotebookSessionService]]: it calls this method with the
   * effective subdomain and merges the result into the session configuration before opening a
   * Kyuubi session, so that Spark resource settings actually reach the engine JVM.
   *
   * Returning an empty map (rather than throwing) lets the system fall back to the server's
   * default Spark config when a profile has not been saved for the given subdomain, the same
   * behaviour as before this feature was added.
   */
  def resolveSparkConfig(subdomain: String): Map[String, String] =
    store.getEngineProfile(subdomain).map(_.sparkConfig).getOrElse(Map.empty)

  // ---------------------------------------------------------------------------
  // Validation helpers
  // ---------------------------------------------------------------------------

  private val SubdomainPattern = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$".r

  private def validateSubdomain(subdomain: String): Unit = {
    if (subdomain == null || subdomain.isEmpty) {
      throw NotebookException.invalid("engine profile subdomain must not be empty")
    }
    if (!SubdomainPattern.pattern.matcher(subdomain).matches()) {
      throw NotebookException.invalid(
        s"engine profile subdomain '$subdomain' is not a valid Kubernetes DNS label: " +
          "must be lowercase alphanumeric and hyphens only, starting and ending with alphanumeric")
    }
    if (subdomain.length > 63) {
      throw NotebookException.invalid(
        s"engine profile subdomain '$subdomain' is too long (max 63 characters)")
    }
  }

  private val AllowedKeyPrefixes = Seq("spark.", "kyuubi.")
  private val MemoryPattern = "^[0-9]+(g|m|k|t|p|gb|mb|kb|tb|pb)$".r

  private def validateSparkConfig(config: Map[String, String]): Unit = {
    config.foreach { case (key, value) =>
      if (!AllowedKeyPrefixes.exists(key.startsWith)) {
        throw NotebookException.invalid(
          s"Spark config key '$key' must start with one of: ${AllowedKeyPrefixes.mkString(", ")}")
      }
      if (key.endsWith(".memory") || key.endsWith(".memory.overhead")) {
        if (!MemoryPattern.pattern.matcher(value.toLowerCase(java.util.Locale.ROOT)).matches()) {
          throw NotebookException.invalid(
            s"Memory value '$value' for key '$key' is not a valid Spark memory string " +
              "(e.g. '4g', '512m')")
        }
      }
    }
  }
}
