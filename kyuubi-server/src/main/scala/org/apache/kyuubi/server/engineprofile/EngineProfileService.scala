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

import java.io.File
import java.time.Duration
import java.time.format.DateTimeParseException
import java.util.UUID

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.kyuubi.{Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
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
    profileVersion: Long = 1L)

object EngineProfileSnapshot {

  /** Server-owned Kyuubi session overlay; never supplied by a browser request. */
  def engineSessionConfig(snapshot: EngineProfileSnapshot): Map[String, String] =
    snapshot.engineIdleTimeout.map(EngineProfileTimeoutPolicy.kyuubiValue).map { value =>
      Map(
        "kyuubi.session.engine.idle.timeout" -> value,
        // A terminal operation whose client disappeared keeps its session non-idle until the
        // operation is expired. Bound that cleanup by the same profile policy instead of the
        // three-hour platform default.
        "kyuubi.operation.idle.timeout" -> value)
    }.getOrElse(Map.empty)

  def notebookRuntimeIdleTimeoutMillis(snapshot: EngineProfileSnapshot): Option[Long] =
    snapshot.notebookRuntimeIdleTimeout.map(EngineProfileTimeoutPolicy.millis)

  /**
   * Safety net for a Notebook runtime whose frontend-side reaper cannot close its Kyuubi session.
   *
   * The Notebook reaper remains the primary owner of runtime cleanup. Passing the same timeout to
   * the engine session makes an abandoned backend session expire independently, so it cannot keep
   * the shared Spark engine's active-session count above zero forever.
   */
  def notebookSessionConfig(snapshot: EngineProfileSnapshot): Map[String, String] =
    snapshot.notebookRuntimeIdleTimeout.map { value =>
      "kyuubi.session.idle.timeout" -> EngineProfileTimeoutPolicy.kyuubiValue(value)
    }.toMap

  /**
   * Client cleanup cannot depend on a browser unload or a final close request reaching the server.
   * Expire an abandoned session at the profile's engine-idle threshold, then let the engine's own
   * idle timer perform the process shutdown once its active-session count reaches zero.
   */
  def clientSessionCleanupConfig(
      snapshot: EngineProfileSnapshot,
      inheritedEngineIdleTimeoutMillis: Long): Map[String, String] = {
    val timeout = snapshot.engineIdleTimeout
      .map(EngineProfileTimeoutPolicy.millis)
      .orElse(Option(inheritedEngineIdleTimeoutMillis).filter(_ > 0))
    timeout.map { value =>
      Map(
        "kyuubi.session.idle.timeout" -> value.toString,
        "kyuubi.operation.idle.timeout" -> value.toString)
    }.getOrElse(Map.empty)
  }
}

/**
 * Shared ownership, validation and profile-resolution boundary for compute clients.
 *
 * Profile IDs are immutable; the user-visible name is scoped by owner. A profile has one mutable
 * configuration and one stable Kyuubi subdomain. Runtime services reject updates while compute
 * is active, so a Spark engine cannot silently receive changed launch resources while alive.
 */
class EngineProfileService(
    conf: KyuubiConf,
    profileStore: EngineProfileStore,
    stateStore: NotebookStore) extends Logging {

  @volatile private var beforeUpdate: (EngineProfilePrincipal, EngineProfile) => Unit =
    (_, _) => ()

  @volatile private var beforeDelete: (EngineProfilePrincipal, EngineProfile) => Unit =
    (_, _) => ()

  @volatile private var beforePublish: EngineProfile => Unit =
    (_: EngineProfile) => ()

  def initialize(): Unit = profileStore.initialize()

  def healthCheck(): Unit = profileStore.healthCheck()

  def close(): Unit = profileStore.close()

  /**
   * Registers the server-owned workload guard run before a user changes a profile's launch
   * configuration. It is installed by [[NotebookManager]] after runtime and DBT services are
   * available; keeping it here makes legacy callers obey the same policy as the REST API.
   */
  def onBeforeUpdate(callback: (EngineProfilePrincipal, EngineProfile) => Unit): Unit =
    beforeUpdate = callback

  /** Registers lifecycle checks and cleanup that must complete before deleting a profile. */
  def onBeforeDelete(callback: (EngineProfilePrincipal, EngineProfile) => Unit): Unit =
    beforeDelete = callback

  /** Registers the allocation guard used before publishing a new Python environment. */
  def onBeforePublishPythonEnvironment(callback: EngineProfile => Unit): Unit =
    beforePublish = callback

  /** The built-in profile has no overlay; these are explicit launch defaults, not live metrics. */
  def defaultResourceConfig: Map[String, String] =
    EngineProfileService.defaultResourceConfig(serverSparkDefaults, conf.getAll)

  /** Exact Spark runtime image configured by the server, when Kubernetes mode declares one. */
  def runtimeImageReference: Option[String] =
    conf.getAll.get(EngineProfileService.SparkContainerImage)
      .orElse(serverSparkDefaults.get(EngineProfileService.SparkContainerImage))
      .map(_.trim)
      .filter(_.nonEmpty)

  private def serverSparkDefaults: Map[String, String] = {
    conf.getEnvs.get("SPARK_CONF_DIR").filter(_.trim.nonEmpty)
      .orElse(conf.getEnvs.get("SPARK_HOME").filter(_.trim.nonEmpty)
        .map(_ + File.separator + "conf"))
      .map(dir => new File(dir, "spark-defaults.conf"))
      .filter(_.isFile)
      .map { file =>
        try {
          Utils.getPropertiesFromFile(Some(file))
        } catch {
          case NonFatal(e) =>
            warn(s"Could not read Spark resource defaults from ${file.getAbsolutePath}", e)
            Map.empty[String, String]
        }
      }.getOrElse(Map.empty[String, String])
  }

  def list(principal: EngineProfilePrincipal): Seq[EngineProfile] =
    // Keep the existing owner-scoped listing contract.  Administrators can still inspect or
    // operate on a known profile ID; a cross-owner administrative listing needs a deliberate
    // store/API addition instead of accidentally exposing every user's resource configuration.
    profileStore.list(principal.user)

  /** Returns a profile only when the caller is its owner or an administrator. */
  def getOwned(profileId: String, principal: EngineProfilePrincipal): EngineProfile = {
    val profile = if (principal.admin) requireProfile(profileId)
    else {
      profileStore.getOwned(profileId, principal.user)
        .orElse(profileStore.getById(profileId))
        .getOrElse {
          throw NotebookException.notFound(
            NotebookErrorCode.NOTEBOOK_NOT_FOUND,
            s"engine profile '$profileId' was not found")
        }
    }
    requireOwner(profile, principal)
    profile
  }

  /** Internal lookup for background lifecycle work that has a profile ID but no user identity. */
  def find(profileId: String): Option[EngineProfile] = {
    if (profileId == EngineProfileService.DefaultProfileId) {
      None
    } else {
      profileStore.getById(profileId)
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
      createdAt = now,
      updatedAt = now)
    val withSubdomain = profile.copy(subdomain = subdomainFor(profile.profileId))
    persist(withSubdomain)
  }

  def update(
      profileId: String,
      principal: EngineProfilePrincipal,
      request: UpsertEngineProfileRequest): EngineProfile =
    withLockedProfile(profileId, principal) { existing =>
      beforeUpdate(principal, existing)
      val name = Option(request.getName).map(_.trim).filter(_.nonEmpty).getOrElse(existing.name)
      validateName(name)
      requireUnusedName(name, existing.owner, Some(profileId))
      val rawConfig = Option(request.getSparkConfig)
        .map(_ => validatedConfig(request))
        .getOrElse(existing.sparkConfig)
      val timeouts = EngineProfileTimeoutPolicy.update(existing, request)
      val profile = existing.copy(
        name = name,
        sparkConfig = rawConfig,
        notebookRuntimeIdleTimeout = timeouts.notebookRuntimeIdleTimeout,
        engineIdleTimeout = timeouts.engineIdleTimeout,
        version = existing.version + 1L,
        updatedAt = System.currentTimeMillis())
      persist(profile)
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
    profileStore.getById(profileId) match {
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
          createdAt = now,
          updatedAt = now)
        val stable = legacy.copy(subdomain = subdomainFor(profileId))
        persist(stable)
    }
  }

  def delete(profileId: String, principal: EngineProfilePrincipal): Unit = {
    withLockedProfile(profileId, principal) { profile =>
      val pendingEnvironmentChange = stateStore.listPendingPythonEnvironmentChangeRequests()
        .exists(_.profileId == profile.profileId)
      if (pendingEnvironmentChange) {
        throw NotebookException.invalid(
          "cannot delete an Engine Profile while Python environment changes are pending")
      }
      beforeDelete(principal, profile)
      if (!profileStore.delete(profile.profileId, profile.owner, profile.version) &&
        profileStore.getById(profile.profileId).isDefined) {
        throw NotebookException.versionConflict(
          "the Engine Profile changed while it was being deleted; deletion was canceled")
      }
    }
  }

  /**
   * Publishes an already READY environment on the one current profile configuration.
   *
   * The environment builder calls this only after atomically publishing its COMPLETE marker.
   * Resource values are copied instead of being re-read from a browser request, ensuring that a
   * package build can never become a backdoor to mutate Spark launch configuration.
   */
  def publishPythonEnvironment(
      profileId: String,
      environmentRevisionId: String): EngineProfile = {
    val initial = requireProfile(profileId)
    profileStore.withOwnerLock(initial.owner) {
      val existing = requireProfile(profileId)
      if (existing.pythonEnvironmentRevisionId.contains(environmentRevisionId)) {
        existing
      } else {
        beforePublish(existing)
        val published = existing.copy(
          version = existing.version + 1L,
          updatedAt = System.currentTimeMillis(),
          pythonEnvironmentRevisionId = Some(environmentRevisionId))
        persist(published)
      }
    }
  }

  /**
   * Authorizes the caller and captures the exact configuration used for one engine-creation
   * attempt.  There is intentionally no unauthenticated `resolveSparkConfig(profileId)` API.
   */
  def snapshotForUse(
      profileId: String,
      principal: EngineProfilePrincipal): EngineProfileSnapshot =
    withSnapshotForUse(profileId, principal)(snapshot => snapshot)

  def withSnapshotForUse[T](
      profileId: String,
      principal: EngineProfilePrincipal)(
      body: EngineProfileSnapshot => T): T = {
    if (profileId == EngineProfileService.DefaultProfileId) {
      // `default` is the built-in profile shown by the UI, not a persisted user profile. An
      // empty overlay deliberately preserves the server/Helm Spark defaults while the subdomain
      // still gives Notebook, SQL Editor and future DBT runs the same USER-scoped engine key.
      body(EngineProfileSnapshot(
        profileId = EngineProfileService.DefaultProfileId,
        owner = principal.user,
        subdomain = EngineProfileService.DefaultProfileId,
        sparkConfig = Map.empty,
        notebookRuntimeIdleTimeout = None,
        engineIdleTimeout = None,
        capturedAt = System.currentTimeMillis()))
    } else {
      withLockedProfile(profileId, principal) { profile =>
        body(snapshot(profile, profile.sparkConfig))
      }
    }
  }

  /** Holds the profile owner lock while a durable compute allocation is reserved. */
  def withAllocationSnapshot[T](
      profileId: String,
      principal: EngineProfilePrincipal)(
      body: EngineProfileSnapshot => T): T = {
    if (profileId == EngineProfileService.DefaultProfileId) {
      body(EngineProfileSnapshot(
        profileId = EngineProfileService.DefaultProfileId,
        owner = principal.user,
        subdomain = EngineProfileService.DefaultProfileId,
        sparkConfig = defaultResourceConfig,
        capturedAt = System.currentTimeMillis()))
    } else {
      withLockedProfile(profileId, principal) { profile =>
        body(snapshot(profile, defaultResourceConfig ++ profile.sparkConfig))
      }
    }
  }

  private def requireProfile(profileId: String): EngineProfile =
    profileStore.getById(profileId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"engine profile '$profileId' was not found")
    }

  private def withLockedProfile[T](
      profileId: String,
      principal: EngineProfilePrincipal)(body: EngineProfile => T): T = {
    val initial = getOwned(profileId, principal)
    profileStore.withOwnerLock(initial.owner) {
      body(getOwned(profileId, principal))
    }
  }

  private def snapshot(
      profile: EngineProfile,
      sparkConfig: Map[String, String]): EngineProfileSnapshot = {
    // Profiles created before the allowlist must meet the same policy before launch.
    EngineProfilePolicy.validate(profile.sparkConfig)
    EngineProfileSnapshot(
      profileId = profile.profileId,
      owner = profile.owner,
      subdomain = profile.subdomain,
      sparkConfig = sparkConfig.toMap,
      notebookRuntimeIdleTimeout = profile.notebookRuntimeIdleTimeout,
      engineIdleTimeout = profile.engineIdleTimeout,
      pythonEnvironmentRevisionId = profile.pythonEnvironmentRevisionId,
      capturedAt = System.currentTimeMillis(),
      profileVersion = profile.version)
  }

  private def persist(profile: EngineProfile): EngineProfile = {
    val saved = if (profile.version == 1L) {
      Some(profileStore.create(profile))
    } else {
      profileStore.update(profile, profile.version - 1L)
    }
    saved.getOrElse {
      throw NotebookException.versionConflict(
        "the Engine Profile changed while its configuration was being saved; reload and retry")
    }
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
    val conflicting = profileStore.list(owner).find { profile =>
      profile.name == name && !exceptProfileId.contains(profile.profileId)
    }
    if (conflicting.nonEmpty) {
      throw NotebookException.invalid(s"engine profile name '$name' already exists")
    }
  }

  private def subdomainFor(profileId: String): String = {
    val compactId = profileId.replace("-", "").take(24)
    s"ep-$compactId"
  }
}

object EngineProfileService {
  val DefaultProfileId = "default"
  private val SparkContainerImage = "spark.kubernetes.container.image"

  private val ResourceKeys = Set(
    "spark.driver.memory",
    "spark.driver.cores",
    "spark.executor.memory",
    "spark.executor.cores",
    "spark.executor.instances")

  /** spark-submit's explicit --conf values take precedence over spark-defaults.conf. */
  private[kyuubi] def defaultResourceConfig(
      sparkDefaults: Map[String, String],
      serverSettings: Map[String, String]): Map[String, String] =
    (sparkDefaults ++ serverSettings).filter { case (key, value) =>
      ResourceKeys.contains(key) && value != null && value.trim.nonEmpty
    }
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
