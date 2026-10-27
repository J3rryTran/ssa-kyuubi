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

package org.apache.kyuubi.server.compute

trait ComputeStore extends AutoCloseable {

  def initSchema(): Unit

  def healthCheck(): Unit

  /**
   * Creates one allocation.
   *
   * @return false when its allocation ID, job attempt or open engine key already exists
   */
  def createAllocation(allocation: ComputeAllocation): Boolean

  def getAllocation(id: String): Option[ComputeAllocation]

  def getAllocationByAttempt(attemptId: String): Option[ComputeAllocation]

  def getOpenAllocationByEngineKey(engineKey: String): Option[ComputeAllocation]

  def listOpenAllocationsForProfile(profileId: String): Seq[ComputeAllocation]

  def listOpenAllocations(limit: Int): Seq[ComputeAllocation]

  def countOpenJobAllocations(owner: Option[String]): Long

  def createBinding(binding: ComputeBinding): Boolean

  def getBinding(id: String): Option[ComputeBinding]

  def revokeBinding(id: String, expectedVersion: Long, revokedAt: Long): Boolean

  def revokeBindingsForAllocation(allocationId: String, revokedAt: Long): Int

  /** Atomically verifies binding/allocation scope while inserting an attach reservation. */
  def createAttachReservation(
      reservation: ComputeAttachReservation,
      expectedTokenHash: String,
      now: Long): Boolean

  def getAttachReservation(id: String): Option[ComputeAttachReservation]

  /** Confirms only while both the binding and allocation remain usable. */
  def confirmAttachReservation(id: String, confirmedAt: Long): Boolean

  def releaseAttachReservation(id: String, releasedAt: Long): Boolean

  /** Updates only lifecycle fields and protects the transition with version and state CAS. */
  def updateAllocation(
      allocation: ComputeAllocation,
      expectedVersion: Long,
      expectedStates: Set[ComputeAllocationState.Value]): Boolean

  /** Updates lifecycle state only when the caller still owns the exact controller generation. */
  def updateClaimedAllocation(
      allocation: ComputeAllocation,
      expectedVersion: Long,
      expectedStates: Set[ComputeAllocationState.Value],
      expectedClaim: ComputeControllerClaim): Boolean

  /**
   * Records a terminal/cancel intent even when another replica currently owns the lifecycle
   * lease. The update clears that lease and advances its generation, fencing the previous owner.
   */
  def requestAllocationRelease(
      id: String,
      expectedVersion: Long,
      expectedStates: Set[ComputeAllocationState.Value],
      requestedAt: Long,
      reason: Option[String]): Boolean

  /** Records liveness only for the exact managed workload identity while it remains attachable. */
  def heartbeatJobAllocation(
      id: String,
      runId: String,
      attemptId: String,
      heartbeatAt: Long): Boolean

  /**
   * Acquires or takes over an expired controller claim and advances its fencing generation.
   */
  def claimAllocation(
      id: String,
      expectedVersion: Long,
      controllerId: String,
      now: Long,
      claimExpiresAt: Long): Boolean

  /** Renews only the exact controller generation returned by a successful claim. */
  def renewAllocationClaim(
      id: String,
      expectedVersion: Long,
      controllerId: String,
      controllerGeneration: Long,
      heartbeatAt: Long,
      claimExpiresAt: Long): Boolean

  def listClaimableAllocations(
      states: Set[ComputeAllocationState.Value],
      claimExpiredBefore: Long,
      limit: Int): Seq[ComputeAllocation]
}
