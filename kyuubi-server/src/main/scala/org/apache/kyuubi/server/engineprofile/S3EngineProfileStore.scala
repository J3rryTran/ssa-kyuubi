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

import java.io.{ByteArrayOutputStream, FileNotFoundException}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}

import org.apache.kyuubi.{KyuubiException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.ha.HighAvailabilityConf.{HA_ADDRESSES, HA_NAMESPACE}
import org.apache.kyuubi.ha.client.zookeeper.ZookeeperClientProvider
import org.apache.kyuubi.server.notebook.NotebookConf.{ENGINE_PROFILE_STORE_FS_PROBE_ENABLED, ENGINE_PROFILE_STORE_FS_ROOT, ENGINE_PROFILE_STORE_ZK_LOCK_TIMEOUT}
import org.apache.kyuubi.server.notebook.api.EngineProfile
import org.apache.kyuubi.shaded.curator.framework.CuratorFramework

/**
 * S3-compatible store which keeps all current Engine Profiles for one owner in one JSON object.
 * ZooKeeper serializes writers because Hadoop S3A rename is copy/delete and cannot be used as a
 * compare-and-set primitive.
 */
class S3EngineProfileStore private[engineprofile] (
    root: Path,
    probeEnabled: Boolean,
    lockTimeoutMillis: Long,
    namespace: String,
    client: CuratorFramework) extends EngineProfileStore with Logging {

  import S3EngineProfileStore._

  private lazy val fs: FileSystem = root.getFileSystem(new Configuration())
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  private val ownerLock = new EngineProfileOwnerLock(client, namespace, lockTimeoutMillis)

  override def initialize(): Unit = {
    try {
      verifyS3aDependencies()
      client.start()
      val connectionTimeoutMillis = Math.toIntExact(lockTimeoutMillis)
      if (!client.blockUntilConnected(connectionTimeoutMillis, TimeUnit.MILLISECONDS)) {
        throw new KyuubiException("Could not connect to ZooKeeper for the Engine Profile store")
      }
      if (!fs.exists(root) && !fs.mkdirs(root)) {
        throw new KyuubiException(s"Could not create Engine Profile store root $root")
      }
      if (probeEnabled) probe()
    } catch {
      case error: KyuubiException => throw error
      case NonFatal(error) =>
        throw new KyuubiException(s"Could not initialize S3 Engine Profile store at $root", error)
    }
  }

  override def healthCheck(): Unit = {
    try {
      fs.getFileStatus(root)
      if (!client.getZookeeperClient.isConnected) {
        throw new KyuubiException("ZooKeeper is disconnected for the Engine Profile store")
      }
    } catch {
      case error: KyuubiException => throw error
      case NonFatal(error) =>
        throw new KyuubiException(s"S3 Engine Profile store is unavailable at $root", error)
    }
  }

  override def withOwnerLock[T](owner: String)(body: => T): T =
    ownerLock.withLock(ownerHash(owner))(body)

  override def list(owner: String): Seq[EngineProfile] =
    readOwner(owner).map(_.profiles).getOrElse(Seq.empty)

  override def getOwned(profileId: String, owner: String): Option[EngineProfile] =
    readOwner(owner).flatMap(_.profiles.find(_.profileId == profileId))

  override def getById(profileId: String): Option[EngineProfile] = {
    val matches = listOwnerDocuments().flatMap { status =>
      readDocument(status.getPath).flatMap(_.profiles.find(_.profileId == profileId))
    }
    matches match {
      case Seq() => None
      case Seq(profile) => Some(profile)
      case _ =>
        throw new KyuubiException(s"Engine Profile $profileId appears in multiple owner documents")
    }
  }

  override def create(profile: EngineProfile): EngineProfile = withOwnerWrite(profile.owner) {
    current =>
      if (current.profiles.exists(_.profileId == profile.profileId)) {
        throw new KyuubiException(s"Engine Profile ${profile.profileId} already exists")
      }
      if (current.profiles.exists(_.name == profile.name)) {
        throw new KyuubiException(s"Engine Profile name '${profile.name}' already exists")
      }
      val stored = profile.copy(version = 1L)
      val next = nextDocument(
        current,
        current.profiles :+ stored,
        current.profileVersions + (stored.profileId -> Long.box(stored.version)))
      writeOwner(next)
      next.profiles.find(_.profileId == profile.profileId).get
  }

  override def update(profile: EngineProfile, expectedVersion: Long): Option[EngineProfile] =
    withOwnerWrite(profile.owner) { current =>
      if (profileVersion(current, profile.profileId) != expectedVersion) {
        None
      } else if (!current.profiles.exists(_.profileId == profile.profileId)) {
        None
      } else if (current.profiles.exists(p =>
          p.name == profile.name && p.profileId != profile.profileId)) {
        throw new KyuubiException(s"Engine Profile name '${profile.name}' already exists")
      } else {
        val stored = profile.copy(version = expectedVersion + 1L)
        val next = nextDocument(
          current,
          current.profiles.map(p => if (p.profileId == profile.profileId) stored else p),
          current.profileVersions + (profile.profileId -> Long.box(stored.version)))
        writeOwner(next)
        next.profiles.find(_.profileId == profile.profileId)
      }
    }

  override def delete(profileId: String, owner: String, expectedVersion: Long): Boolean =
    withOwnerWrite(owner) { current =>
      if (profileVersion(current, profileId) != expectedVersion ||
        !current.profiles.exists(_.profileId == profileId)) {
        false
      } else {
        val remaining = current.profiles.filterNot(_.profileId == profileId)
        if (remaining.isEmpty) {
          val path = ownerPath(owner)
          if (fs.exists(path) && !fs.delete(path, false)) {
            throw new KyuubiException(s"Could not delete empty Engine Profile document $path")
          }
        } else {
          writeOwner(nextDocument(current, remaining, current.profileVersions - profileId))
        }
        true
      }
    }

  override def close(): Unit = client.close()

  private def withOwnerWrite[T](owner: String)(body: OwnerDocument => T): T = {
    withOwnerLock(owner) {
      body(readOwner(owner).getOrElse(
        OwnerDocument(CurrentFormatVersion, owner, 0L, 0L, Seq.empty, Map.empty)))
    }
  }

  private def nextDocument(
      current: OwnerDocument,
      profiles: Seq[EngineProfile],
      profileVersions: Map[String, Number]): OwnerDocument = {
    val generation = current.generation + 1L
    OwnerDocument(
      CurrentFormatVersion,
      current.owner,
      generation,
      System.currentTimeMillis(),
      profiles,
      profileVersions)
  }

  private def readOwner(owner: String): Option[OwnerDocument] =
    readDocument(ownerPath(owner)).map { document =>
      if (document.owner != owner) {
        throw new KyuubiException(
          s"Engine Profile owner document ${ownerPath(owner)} has a mismatched owner")
      }
      document
    }

  private def writeOwner(document: OwnerDocument): Unit = {
    val path = ownerPath(document.owner)
    writeDocument(path, document)
    readDocument(path) match {
      case Some(actual) if actual == document => ()
      case Some(_) =>
        throw new KyuubiException(s"Engine Profile document $path changed while written")
      case None =>
        throw new KyuubiException(s"Engine Profile document $path disappeared after write")
    }
  }

  private def readDocument(path: Path): Option[OwnerDocument] = {
    try {
      val document = mapper.readValue(readBytes(path), classOf[OwnerDocument])
      if (document.formatVersion != CurrentFormatVersion) {
        throw new KyuubiException(
          s"Engine Profile document $path has unsupported format ${document.formatVersion}")
      }
      if (path.getName != ownerHash(document.owner) + ".json") {
        throw new KyuubiException(s"Engine Profile document $path does not match its owner hash")
      }
      val normalizedVersions: Map[String, Number] = document.profileVersions.map {
        case (profileId, version) => profileId -> Long.box(version.longValue())
      }.toMap
      Some(document.copy(
        profiles = document.profiles.map { profile =>
          profile.copy(version =
            normalizedVersions.get(profile.profileId).map(_.longValue()).getOrElse(1L))
        },
        profileVersions = normalizedVersions))
    } catch {
      case _: FileNotFoundException => None
      case error: KyuubiException => throw error
      case NonFatal(error) =>
        throw new KyuubiException(s"Could not read Engine Profile document $path", error)
    }
  }

  private def writeDocument(path: Path, document: OwnerDocument): Unit = {
    val out = fs.create(path, true)
    try out.write(mapper.writeValueAsBytes(document))
    finally out.close()
  }

  private def listOwnerDocuments(): Seq[FileStatus] =
    fs.listStatus(root).toSeq.filter(status =>
      status.isFile && status.getPath.getName.endsWith(".json"))

  private def ownerPath(owner: String): Path = new Path(root, ownerHash(owner) + ".json")

  private def ownerHash(owner: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8))
    digest.map(byte => f"${byte & 0xFF}%02x").mkString
  }

  private def profileVersion(document: OwnerDocument, profileId: String): Long =
    document.profileVersions.get(profileId).map(_.longValue()).getOrElse(1L)

  private def readBytes(path: Path): String = {
    val in = fs.open(path)
    try {
      val buffer = new ByteArrayOutputStream()
      val bytes = new Array[Byte](8192)
      var count = in.read(bytes)
      while (count > 0) {
        buffer.write(bytes, 0, count)
        count = in.read(bytes)
      }
      new String(buffer.toByteArray, StandardCharsets.UTF_8)
    } finally in.close()
  }

  private def probe(): Unit = {
    val path = new Path(root, s".probe-${UUID.randomUUID()}")
    val out = fs.create(path, false)
    try out.write(ProbeBody.getBytes(StandardCharsets.UTF_8))
    finally out.close()
    try {
      if (readBytes(path) != ProbeBody || !fs.listStatus(root).exists(_.getPath == path)) {
        throw new KyuubiException(s"Engine Profile S3 probe failed for $path")
      }
    } finally {
      if (fs.exists(path) && !fs.delete(path, false)) {
        throw new KyuubiException(s"Could not delete Engine Profile S3 probe $path")
      }
    }
  }

  private def verifyS3aDependencies(): Unit = {
    if (Option(root.toUri.getScheme).exists(_.equalsIgnoreCase("s3a"))) {
      Class.forName("org.apache.hadoop.fs.s3a.S3AFileSystem")
      Class.forName("com.amazonaws.services.s3.AmazonS3")
    }
  }
}

object S3EngineProfileStore {
  private val CurrentFormatVersion = 1
  private val ProbeBody = "kyuubi-engine-profile-store-probe"

  private[engineprofile] case class OwnerDocument(
      formatVersion: Int,
      owner: String,
      generation: Long,
      updatedAt: Long,
      profiles: Seq[EngineProfile],
      profileVersions: Map[String, Number] = Map.empty)

  def create(conf: KyuubiConf): S3EngineProfileStore = {
    val root = conf.get(ENGINE_PROFILE_STORE_FS_ROOT).map(_.trim).filter(_.nonEmpty).getOrElse {
      throw new KyuubiException(
        "kyuubi.engine.profile.store.fs.root is required for the S3 Engine Profile store")
    }
    if (conf.get(HA_ADDRESSES).trim.isEmpty) {
      throw new KyuubiException(
        "ZooKeeper HA addresses are required for the S3 Engine Profile store")
    }
    new S3EngineProfileStore(
      new Path(root),
      conf.get(ENGINE_PROFILE_STORE_FS_PROBE_ENABLED),
      conf.get(ENGINE_PROFILE_STORE_ZK_LOCK_TIMEOUT),
      conf.get(HA_NAMESPACE),
      ZookeeperClientProvider.buildZookeeperClient(conf))
  }
}
