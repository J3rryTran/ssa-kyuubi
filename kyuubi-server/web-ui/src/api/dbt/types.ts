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

export type DbtAction = 'PREVIEW' | 'RUN_MODEL' | 'RUN_PROJECT'
export type DbtRunState =
  | 'QUEUED'
  | 'DISPATCHING'
  | 'SUBMITTED'
  | 'RUNNING'
  | 'CANCELLING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'

export const ACTIVE_DBT_RUN_STATES: DbtRunState[] = [
  'QUEUED',
  'DISPATCHING',
  'SUBMITTED',
  'RUNNING',
  'CANCELLING'
]

type DbtEnum<T extends string> = T | { value: T; enumClass?: string }

export interface DbtWorkspace {
  id: string
  owner: string
  name: string
  projectRef: string
  selectedEngineProfileId: string
  createdAt: number
  updatedAt: number
  version: number
}

export interface DbtJob {
  id: string
  workspaceId: string
  owner: string
  name: string
  action: DbtEnum<DbtAction>
  selector: string | null
  engineProfileIdOverride: string | null
  createdAt: number
  updatedAt: number
  version: number
}

export interface DbtEngineProfileSnapshot {
  profileId: string
  owner: string
  subdomain: string
  sparkConfig: Record<string, string>
  capturedAt: number
}

export interface DbtJobRun {
  id: string
  workspaceId: string
  jobId: string | null
  action: DbtEnum<DbtAction>
  selector: string | null
  projectRef: string
  profile: DbtEngineProfileSnapshot
  state: DbtEnum<DbtRunState>
  createdAt: number
  startedAt: number | null
  finishedAt: number | null
  errorSummary: string | null
  logTruncated: boolean
}

export interface DbtLogPage {
  content: string
  nextOffset: number
  endOfStream: boolean
  truncated: boolean
}

export const enumValue = <T extends string>(value: DbtEnum<T>): T =>
  typeof value === 'string' ? value : value.value
