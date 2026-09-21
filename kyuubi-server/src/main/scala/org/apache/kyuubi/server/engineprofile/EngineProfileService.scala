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

import java.time.Duration
import java.time.format.DateTimeParseException
import java.util.UUID

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.store.NotebookStore

/**
 * Authenticated identity used to manage or consume an engine profile.
 *
 * The type deliberately has no Notebook dependency, so a future SQL Editor or DBT service can
 * use the same authorization boundary.  Phase B keeps the existing notebook persistence schema
 * and REST request/view models to avoid a data migration.
 */
case class EngineProfilePrincipal(user: String, admin: Boolean)

/**
 * Immutable profile material captured before a client opens a Kyuubi session.
 *
 * A later profile update must not alter this value.  DBT runs will persist this snapshot in a
 * later phase; Notebook currently uses it immediately while starting a runtime.
 */
case class EngineProfileSnapshot(
    profileId: String,
    owner: String,
    subdomain: String,
    sparkConfig: Map[String, String],
    capturedAt: Long,
    notebookRuntimeIdleTimeout: Option[String] = None,
    engineIdleTimeout: Option[String] = None,
    pythonEnvironmentRevisionId: Option[String] = None,
    revision: Long = 1L)

object EngineProfileSnapshot {

  /** Server-owned Kyuubi session overlay; never supplied by a browser request. */
  def engineSessionConfig(snapshot: EngineProfileSnapshot): Map[String, String] =
    snapshot.engineIdleTimeout.map { value =>
      "kyuubi.session.engine.idle.timeout" -> EngineProfileTimeoutPolicy.kyuubiValue(value)
    }.toMap

  def notebookRuntimeIdleTimeoutMillis(snapshot: EngineProfileSnapshot): Option[Long] =
    snapshot.notebookRuntimeIdleTimeout.map(EngineProfileTimeoutPolicy.millis)
}

/**
 * Shared ownership, validation and profile-resolution boundary for compute clients.
 *
 * Profile IDs are immutable; the user-visible name is scoped by owner. Each config update
 * creates a new revision-specific Kyuubi subdomain, so a Spark engine cannot silently receive
 * changed launch resources while it is still alive.
 */
class EngineProfileService(store: NotebookStore) {

  def list(principal: EngineProfilePrincipal): Seq[EngineProfile] =
    // Keep the existing owner-scoped listing contract.  Administrators can still inspect or
    // operate on a known profile ID; a cross-owner administrative listing needs a deliberate
    // store/API addition instead of accidentally exposing every user's resource configuration.
    store.listEngineProfiles(principal.user)

  /** Returns a profile only when the caller is its owner or an administrator. */
  def getOwned(profileId: String, principal: EngineProfilePrincipal): EngineProfile = {
    val profile = requireProfile(profileId)
    requireOwner(profile, principal)
    profile
  }

  def listRevisions(
      profileId: String,
      principal: EngineProfilePrincipal): Seq[EngineProfileRevision] = {
    getOwned(profileId, principal)
    store.listEngineProfileRevisions(profileId)
  }

  /** Captures an immutable former or current revision for lifecycle observation. */
  def snapshotForRevision(
      profileId: String,
      revision: Long,
      principal: EngineProfilePrincipal): EngineProfileSnapshot = {
    val profile = getOwned(profileId, principal)
    val profileRevision = store.listEngineProfileRevisions(profileId)
      .find(_.revision == revision)
      .getOrElse(throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"engine profile '$profileId' revision '$revision' was not found"))
    EngineProfilePolicy.validate(profileRevision.sparkConfig)
    EngineProfileSnapshot(
      profileId = profile.profileId,
      owner = profile.owner,
      subdomain = profileRevision.subdomain,
      sparkConfig = profileRevision.sparkConfig.toMap,
      notebookRuntimeIdleTimeout = profileRevision.notebookRuntimeIdleTimeout,
      engineIdleTimeout = profileRevision.engineIdleTimeout,
      pythonEnvironmentRevisionId = profileRevision.pythonEnvironmentRevisionId,
      revision = profileRevision.revision,
      capturedAt = System.currentTimeMillis())
  }

  /**
   * Returns a former revision that may be explicitly terminated.
   *
   * The current revision is deliberately rejected: profile save must never turn into a hidden
   * kill operation, and the active revision remains the normal way to open new sessions.
   */
  def requireDrainingRevision(
      profileId: String,
      revision: Long,
      principal: EngineProfilePrincipal): EngineProfileRevision = {
    val profile = getOwned(profileId, principal)
    if (revision >= profile.revision) {
      throw NotebookException.invalid(
        s"only a draining revision can be terminated; current revision is ${profile.revision}")
    }
    store.listEngineProfileRevisions(profileId).find(_.revision == revision).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"engine profile '$profileId' revision '$revision' was not found")
    }
  }

  def create(
      principal: EngineProfilePrincipal,
      request: UpsertEngineProfileRequest): EngineProfile = {
    val name = requireName(request)
    requireUnusedName(name, principal.user, None)
    val rawConfig = validatedConfig(request)
    val timeouts = EngineProfileTimeoutPolicy.create(request)
    val now = System.currentTimeMillis()
    val profile = EngineProfile(
      profileId = UUID.randomUUID().toString,
      name = name,
      subdomain = "",
      owner = principal.user,
      sparkConfig = rawConfig,
      notebookRuntimeIdleTimeout = timeouts.notebookRuntimeIdleTimeout,
      engineIdleTimeout = timeouts.engineIdleTimeout,
      revision = 1L,
      createdAt = now,
      updatedAt = now)
    val withSubdomain = profile.copy(subdomain = subdomainFor(profile.profileId, profile.revision))
    store.upsertEngineProfile(withSubdomain)
    withSubdomain
  }

  def update(
      profileId: String,
      principal: EngineProfilePrincipal,
      request: UpsertEngineProfileRequest): EngineProfile = {
    val existing = getOwned(profileId, principal)
    val name = Option(request.getName).map(_.trim).filter(_.nonEmpty).getOrElse(existing.name)
    validateName(name)
    requireUnusedName(name, existing.owner, Some(profileId))
    val rawConfig = validatedConfig(request)
    val timeouts = EngineProfileTimeoutPolicy.update(existing, request)
    val nextRevision = existing.revision + 1L
    val profile = existing.copy(
      name = name,
      subdomain = subdomainFor(existing.profileId, nextRevision),
      sparkConfig = rawConfig,
      notebookRuntimeIdleTimeout = timeouts.notebookRuntimeIdleTimeout,
      engineIdleTimeout = timeouts.engineIdleTimeout,
      revision = nextRevision,
      updatedAt = System.currentTimeMillis())
    store.upsertEngineProfile(profile)
    profile
  }

  /**
   * Compatibility bridge for the original PUT /engine-profiles/{subdomain} API.
   *
   * New clients must use [[create]] and [[update]]. Keeping this method lets deployments upgrade
   * without invalidating old automation before their UI bundle has been rolled out.
   */
  def upsert(
      profileId: String,
      principal: EngineProfilePrincipal,
      request: UpsertEngineProfileRequest): EngineProfile = {
    store.getEngineProfile(profileId) match {
      case Some(_) => update(profileId, principal, request)
      case None =>
        if (Option(request.getName).forall(_.trim.isEmpty)) request.setName(profileId)
        val name = requireName(request)
        requireUnusedName(name, principal.user, None)
        val now = System.currentTimeMillis()
        val timeouts = EngineProfileTimeoutPolicy.create(request)
        val legacy = EngineProfile(
          profileId = profileId,
          name = name,
          subdomain = profileId,
          owner = principal.user,
          sparkConfig = validatedConfig(request),
          notebookRuntimeIdleTimeout = timeouts.notebookRuntimeIdleTimeout,
          engineIdleTimeout = timeouts.engineIdleTimeout,
          revision = 1L,
          createdAt = now,
          updatedAt = now)
        store.upsertEngineProfile(legacy)
        legacy
    }
  }

  def delete(profileId: String, principal: EngineProfilePrincipal): Unit = {
    val profile = getOwned(profileId, principal)
    val environments = store.listPythonEnvironmentRevisions(profile.profileId)
    if (environments.exists(_.state != PythonEnvironmentState.RETIRED)) {
      throw NotebookException.invalid(
        "cannot delete an Engine Profile while it retains a persistent Python environment")
    }
    store.deleteEngineProfile(profile.profileId, profile.owner)
  }

  /**
   * Publishes an already READY environment as a new immutable profile revision.
   *
   * The environment builder calls this only after atomically publishing its COMPLETE marker.
   * Resource values are copied instead of being re-read from a browser request, ensuring that a
   * package build can never become a backdoor to mutate Spark launch configuration.
   */
  def publishPythonEnvironment(
      profileId: String,
      environmentRevisionId: String): EngineProfile = {
    val existing = requireProfile(profileId)
    if (existing.pythonEnvironmentRevisionId.contains(environmentRevisionId)) {
      existing
    } else {
      val nextRevision = existing.revision + 1L
      val published = existing.copy(
        subdomain = subdomainFor(existing.profileId, nextRevision),
        revision = nextRevision,
        updatedAt = System.currentTimeMillis(),
        pythonEnvironmentRevisionId = Some(environmentRevisionId))
      store.upsertEngineProfile(published)
      published
    }
  }

  /**
   * Authorizes the caller and captures the exact configuration used for one engine-creation
   * attempt.  There is intentionally no unauthenticated `resolveSparkConfig(profileId)` API.
   */
  def snapshotForUse(
      profileId: String,
      principal: EngineProfilePrincipal): EngineProfileSnapshot = {
    if (profileId == EngineProfileService.DefaultProfileId) {
      // `default` is the built-in profile shown by the UI, not a persisted user profile. An
      // empty overlay deliberately preserves the server/Helm Spark defaults while the subdomain
      // still gives Notebook, SQL Editor and future DBT runs the same USER-scoped engine key.
      EngineProfileSnapshot(
        profileId = EngineProfileService.DefaultProfileId,
        owner = principal.user,
        subdomain = EngineProfileService.DefaultProfileId,
        sparkConfig = Map.empty,
        notebookRuntimeIdleTimeout = None,
        engineIdleTimeout = None,
        revision = 0L,
        capturedAt = System.currentTimeMillis())
    } else {
      val profile = getOwned(profileId, principal)
      // Profiles created before the Phase B allowlist must meet the same safety policy before
      // they can affect a new engine.  This prevents an old broad `spark.*` record from becoming
      // a bypass after the API is tightened.
      EngineProfilePolicy.validate(profile.sparkConfig)
      EngineProfileSnapshot(
        profileId = profile.profileId,
        owner = profile.owner,
        subdomain = profile.subdomain,
        sparkConfig = profile.sparkConfig.toMap,
        notebookRuntimeIdleTimeout = profile.notebookRuntimeIdleTimeout,
        engineIdleTimeout = profile.engineIdleTimeout,
        pythonEnvironmentRevisionId = profile.pythonEnvironmentRevisionId,
        revision = profile.revision,
        capturedAt = System.currentTimeMillis())
    }
  }

  private def requireProfile(profileId: String): EngineProfile =
    store.getEngineProfile(profileId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"engine profile '$profileId' was not found")
    }

  private def requireOwner(profile: EngineProfile, principal: EngineProfilePrincipal): Unit = {
    if (!principal.admin && profile.owner != principal.user) {
      throw NotebookException.accessDenied(
        s"engine profile '${profile.subdomain}' is owned by '${profile.owner}'")
    }
  }

  private val NamePattern = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$".r

  private def requireName(request: UpsertEngineProfileRequest): String = {
    val name = Option(request.getName).map(_.trim).getOrElse("")
    validateName(name)
    name
  }

  private def validateName(name: String): Unit = {
    if (name == null || name.isEmpty) {
      throw NotebookException.invalid("engine profile name must not be empty")
    }
    if (!NamePattern.pattern.matcher(name).matches()) {
      throw NotebookException.invalid(
        s"engine profile name '$name' is not a valid Kubernetes DNS label: " +
          "must be lowercase alphanumeric and hyphens only, starting and ending with alphanumeric")
    }
    if (name.length > 63) {
      throw NotebookException.invalid(
        s"engine profile name '$name' is too long (max 63 characters)")
    }
  }

  private def validatedConfig(request: UpsertEngineProfileRequest): Map[String, String] = {
    val config = Option(request.getSparkConfig).map(_.asScala.toMap).getOrElse(Map.empty)
    EngineProfilePolicy.validate(config)
    config
  }

  private def requireUnusedName(
      name: String,
      owner: String,
      exceptProfileId: Option[String]): Unit = {
    val conflicting = store.listEngineProfiles(owner).find { profile =>
      profile.name == name && !exceptProfileId.contains(profile.profileId)
    }
    if (conflicting.nonEmpty) {
      throw NotebookException.invalid(s"engine profile name '$name' already exists")
    }
  }

  private def subdomainFor(profileId: String, revision: Long): String = {
    val compactId = profileId.replace("-", "").take(24)
    s"ep-$compactId-r$revision"
  }
}

object EngineProfileService {
  val DefaultProfileId = "default"
}

/** Validates profile-owned duration overrides and renders native Kyuubi timeConf values. */
private[engineprofile] object EngineProfileTimeoutPolicy {
  private val MinMillis = Duration.ofMinutes(5).toMillis
  private val MaxMillis = Duration.ofHours(24).toMillis
  private val Inherit = "inherit"

  case class Values(notebookRuntimeIdleTimeout: Option[String], engineIdleTimeout: Option[String])

  def create(request: UpsertEngineProfileRequest): Values =
    Values(
      normalize(request.getNotebookRuntimeIdleTimeout),
      normalize(request.getEngineIdleTimeout))

  def update(existing: EngineProfile, request: UpsertEngineProfileRequest): Values =
    Values(
      updateValue(existing.notebookRuntimeIdleTimeout, request.getNotebookRuntimeIdleTimeout),
      updateValue(existing.engineIdleTimeout, request.getEngineIdleTimeout))

  def millis(value: String): Long = Duration.parse(value).toMillis

  /**
   * Kyuubi timeConf accepts ISO-8601 or a plain millisecond value, not a Spark-style `ms`
   * suffix.  Profile input stays ISO-8601 for the public API; the server-owned session overlay
   * uses the unambiguous numeric representation understood by both frontend and engine JVMs.
   */
  def kyuubiValue(value: String): String = millis(value).toString

  private def updateValue(existing: Option[String], requested: String): Option[String] =
    if (requested == null) existing else normalize(requested)

  private def normalize(requested: String): Option[String] = Option(requested).map(_.trim) match {
    case None | Some("") | Some(Inherit) => None
    case Some(value) =>
      val duration =
        try {
          Duration.parse(value)
        } catch {
          case _: DateTimeParseException =>
            throw NotebookException.invalid(
              s"engine profile timeout '$value' must be an ISO-8601 duration, for example PT15M")
        }
      val millis =
        try {
          duration.toMillis
        } catch {
          case _: ArithmeticException =>
            throw NotebookException.invalid(s"engine profile timeout '$value' is too large")
        }
      if (millis < MinMillis || millis > MaxMillis) {
        throw NotebookException.invalid(
          s"engine profile timeout must be between PT5M and PT24H, but was '$value'")
      }
      Some(Duration.ofMillis(millis).toString)
  }
}

/** Server-controlled allowlist for values that can be persisted in an engine profile. */
private[engineprofile] object EngineProfilePolicy {
  private val MemoryKeys = Set("spark.driver.memory", "spark.executor.memory")
  private val PositiveIntegerKeys = Set(
    "spark.driver.cores",
    "spark.executor.cores",
    "spark.executor.instances",
    "spark.dynamicAllocation.minExecutors",
    "spark.dynamicAllocation.initialExecutors",
    "spark.dynamicAllocation.maxExecutors")
  private val DynamicAllocationKey = "spark.dynamicAllocation.enabled"
  private val AllowedKeys = MemoryKeys ++ PositiveIntegerKeys + DynamicAllocationKey
  private val MemoryPattern = "^[0-9]+(g|m|k|t|p|gb|mb|kb|tb|pb)$".r

  def validate(config: Map[String, String]): Unit = {
    config.foreach { case (key, value) =>
      if (!AllowedKeys.contains(key)) {
        throw NotebookException.invalid(
          s"Spark config key '$key' is not allowed in an engine profile")
      }
      if (value == null || value.trim.isEmpty) {
        throw NotebookException.invalid(s"Spark config value for key '$key' must not be empty")
      }
      if (MemoryKeys.contains(key) &&
        !MemoryPattern.pattern.matcher(value.toLowerCase(java.util.Locale.ROOT)).matches()) {
        throw NotebookException.invalid(
          s"Memory value '$value' for key '$key' is not a valid Spark memory string " +
            "(e.g. '4g', '512m')")
      }
      if (PositiveIntegerKeys.contains(key)) {
        val parsed =
          try Some(value.toInt)
          catch { case _: NumberFormatException => None }
        if (!parsed.exists(_ > 0)) {
          throw NotebookException.invalid(
            s"Spark config value '$value' for key '$key' must be a positive integer")
        }
      }
      if (key == DynamicAllocationKey &&
        !Set("true", "false").contains(value.toLowerCase(java.util.Locale.ROOT))) {
        throw NotebookException.invalid(
          s"Spark config value '$value' for key '$key' must be 'true' or 'false'")
      }
    }

    val dynamicValues = Seq(
      "spark.dynamicAllocation.minExecutors",
      "spark.dynamicAllocation.initialExecutors",
      "spark.dynamicAllocation.maxExecutors").flatMap { key =>
      config.get(key).map(value => key -> value.toInt)
    }
      .toMap
    val min = dynamicValues.get("spark.dynamicAllocation.minExecutors")
    val initial = dynamicValues.get("spark.dynamicAllocation.initialExecutors")
    val max = dynamicValues.get("spark.dynamicAllocation.maxExecutors")
    if (min.exists(minValue => initial.exists(_ < minValue) || max.exists(_ < minValue)) ||
      initial.exists(initialValue => max.exists(_ < initialValue))) {
      throw NotebookException.invalid(
        "spark.dynamicAllocation executor bounds must satisfy " +
          "minExecutors <= initialExecutors <= maxExecutors")
    }
  }
}
