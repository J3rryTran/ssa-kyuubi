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

import request from '@/utils/request'
import type {
  DbtAction,
  DbtJob,
  DbtJobRun,
  DbtLogPage,
  DbtWorkspace
} from './types'

const call = <T>(config: Record<string, unknown>): Promise<T> =>
  request(config) as unknown as Promise<T>

export const listDbtWorkspaces = () =>
  call<DbtWorkspace[]>({ url: 'api/v1/dbt-workspaces', method: 'get' })
export const getDbtWorkspace = (id: string) =>
  call<DbtWorkspace>({ url: `api/v1/dbt-workspaces/${id}`, method: 'get' })
export const createDbtWorkspace = (data: {
  name: string
  projectRef: string
  engineProfileId: string
}) => call<DbtWorkspace>({ url: 'api/v1/dbt-workspaces', method: 'post', data })
export const updateDbtWorkspace = (id: string, data: Record<string, unknown>) =>
  call<DbtWorkspace>({
    url: `api/v1/dbt-workspaces/${id}`,
    method: 'patch',
    data
  })
export const deleteDbtWorkspace = (id: string, version: number) =>
  call<void>({
    url: `api/v1/dbt-workspaces/${id}`,
    method: 'delete',
    params: { version }
  })

export const listDbtJobs = (workspaceId: string) =>
  call<DbtJob[]>({
    url: `api/v1/dbt-workspaces/${workspaceId}/jobs`,
    method: 'get'
  })
export const createDbtJob = (
  workspaceId: string,
  data: {
    name: string
    action: DbtAction
    selector?: string
    engineProfileIdOverride?: string
  }
) =>
  call<DbtJob>({
    url: `api/v1/dbt-workspaces/${workspaceId}/jobs`,
    method: 'post',
    data
  })
export const updateDbtJob = (id: string, data: Record<string, unknown>) =>
  call<DbtJob>({ url: `api/v1/dbt-jobs/${id}`, method: 'patch', data })
export const deleteDbtJob = (id: string, version: number) =>
  call<void>({
    url: `api/v1/dbt-jobs/${id}`,
    method: 'delete',
    params: { version }
  })

export const previewDbtWorkspace = (workspaceId: string, selector: string) =>
  call<DbtJobRun>({
    url: `api/v1/dbt-workspaces/${workspaceId}:preview`,
    method: 'post',
    data: { selector }
  })
export const runDbtJob = (jobId: string) =>
  call<DbtJobRun>({ url: `api/v1/dbt-jobs/${jobId}:run`, method: 'post' })
export const listDbtWorkspaceRuns = (workspaceId: string, limit = 50) =>
  call<DbtJobRun[]>({
    url: `api/v1/dbt-workspaces/${workspaceId}/runs`,
    method: 'get',
    params: { limit }
  })
export const getDbtRun = (id: string) =>
  call<DbtJobRun>({ url: `api/v1/dbt-job-runs/${id}`, method: 'get' })
export const getDbtRunLogs = (id: string, offset: number, limit = 65536) =>
  call<DbtLogPage>({
    url: `api/v1/dbt-job-runs/${id}/logs`,
    method: 'get',
    params: { offset, limit }
  })
export const cancelDbtRun = (id: string) =>
  call<void>({ url: `api/v1/dbt-job-runs/${id}:cancel`, method: 'post' })
