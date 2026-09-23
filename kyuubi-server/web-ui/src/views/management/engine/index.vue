<!--
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
-->
<template>
  <div class="engine-management-page">
    <div class="view-header">
      <div class="page-title">Engine Profiles</div>
      <div class="header-actions">
        <el-button icon="Refresh" @click="loadProfiles">Refresh status</el-button>
        <el-button type="primary" icon="Plus" @click="handleOpenCreateProfile">
          Create Engine Profile
        </el-button>
      </div>
    </div>

    <!-- VIEW 1: ACTIVE INSTANCES -->
    <div v-if="false">
      <el-card :body-style="{ padding: '10px 14px' }" class="filter_card">
        <header>
          <el-space class="search-box">
            <el-select
              v-model="searchParam.type"
              :placeholder="$t('engine_type')"
              clearable
              style="width: 210px"
              @change="getList">
              <el-option
                v-for="item in getEngineType()"
                :key="item"
                :label="item"
                :value="item" />
            </el-select>
            <el-select
              v-model="searchParam.sharelevel"
              :placeholder="$t('share_level')"
              clearable
              style="width: 210px"
              @change="getList">
              <el-option
                v-for="item in getShareLevel()"
                :key="item"
                :label="item"
                :value="item" />
            </el-select>
            <el-input
              v-model="searchParam['hive.server2.proxy.user']"
              :placeholder="$t('user')"
              style="width: 210px"
              @keyup.enter="getList" />
            <el-button type="primary" icon="Search" @click="getList" />
          </el-space>
        </header>
      </el-card>

      <el-card class="table-container">
        <el-table v-loading="loading" :data="tableData" style="width: 100%">
          <el-table-column
            prop="instance"
            :label="$t('engine_address')"
            min-width="20%" />
          <el-table-column :label="$t('engine_id')" min-width="20%">
            <template #default="scope">
              <span>{{
                scope.row.attributes && scope.row.attributes['kyuubi.engine.id']
                  ? scope.row.attributes['kyuubi.engine.id']
                  : '-'
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Engine Profile" min-width="15%">
            <template #default="scope">
              <el-tag size="small" effect="plain" type="warning">
                {{ engineProfileOf(scope.row) }}
              </el-tag>
              <div class="sub-text">Subdomain</div>
            </template>
          </el-table-column>
          <el-table-column
            prop="engineType"
            :label="$t('engine_type')"
            min-width="15%" />
          <el-table-column
            prop="sharelevel"
            :label="$t('share_level')"
            min-width="15%" />

          <el-table-column prop="user" :label="$t('user')" min-width="15%" />
          <el-table-column prop="version" :label="$t('version')" min-width="10%" />
          <el-table-column fixed="right" :label="$t('operation.text')" width="120">
            <template #default="scope">
              <el-space wrap>
                <el-tooltip
                  effect="dark"
                  :content="
                    $t('engine_ui') +
                    ': ' +
                    scope.row.attributes['kyuubi.engine.url']
                  "
                  placement="top">
                  <el-button
                    type="primary"
                    icon="Link"
                    circle
                    @click="
                      openEngineUI(scope.row.attributes['kyuubi.engine.url'])
                    " />
                </el-tooltip>
                <el-popconfirm
                  :title="$t('operation.delete_confirm')"
                  @confirm="handleDeleteEngine(scope.row)">
                  <template #reference>
                    <span>
                      <el-tooltip
                        effect="dark"
                        :content="$t('operation.delete')"
                        placement="top">
                        <template #default>
                          <el-button type="danger" icon="Delete" circle />
                        </template>
                      </el-tooltip>
                    </span>
                  </template>
                </el-popconfirm>
              </el-space>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- VIEW 2: ENGINE PROFILES -->
    <div class="profiles-container">
      <el-card class="table-container">
        <el-table v-loading="profilesLoading" :data="profiles" style="width: 100%">
          <el-table-column prop="name" label="Engine Profile" min-width="20%">
            <template #default="{ row }">
              <div style="font-weight: 600; color: #1E293B">{{ row.name }}</div>
              <div class="sub-text">Profile ID: {{ row.profileId }}</div>
            </template>
          </el-table-column>
          <el-table-column label="Revision" min-width="10%">
            <template #default="{ row }">
              <el-tag size="small" type="success">r{{ row.revision }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="Engine status" min-width="14%">
            <template #default="{ row }">
              <el-tag :type="engineStateType(row.profileId)" size="small">
                {{ engineState(row.profileId) }}
              </el-tag>
              <div class="sub-text">
                {{ engineCount(row.profileId) }} running engine(s)
              </div>
            </template>
          </el-table-column>
          <el-table-column label="Driver" min-width="15%">
            <template #default="{ row }">
              <el-tag v-if="row.sparkConfig?.['spark.driver.memory']" size="small" type="info">
                {{ row.sparkConfig['spark.driver.memory'] }}
                <span v-if="row.sparkConfig['spark.driver.cores']"> / {{ row.sparkConfig['spark.driver.cores'] }}c</span>
              </el-tag>
              <span v-else class="sub-text">Default (Cluster)</span>
            </template>
          </el-table-column>
          <el-table-column label="Executor" min-width="15%">
            <template #default="{ row }">
              <el-tag v-if="row.sparkConfig?.['spark.executor.memory']" size="small" type="success">
                {{ row.sparkConfig['spark.executor.memory'] }}
                <span v-if="row.sparkConfig['spark.executor.cores']"> / {{ row.sparkConfig['spark.executor.cores'] }}c</span>
              </el-tag>
              <span v-else class="sub-text">Default (Cluster)</span>
            </template>
          </el-table-column>
          <el-table-column label="Instances" min-width="12%">
            <template #default="{ row }">
              <span v-if="row.sparkConfig?.['spark.executor.instances']">
                {{ row.sparkConfig['spark.executor.instances'] }}
              </span>
              <span v-else class="sub-text">-</span>
            </template>
          </el-table-column>
          <el-table-column label="Custom Configs" min-width="18%">
            <template #default="{ row }">
              <span v-if="getExtraConfigsCount(row.sparkConfig) > 0" class="custom-config-tag">
                {{ getExtraConfigsCount(row.sparkConfig) }} properties
              </span>
              <span v-else class="sub-text">None</span>
            </template>
          </el-table-column>
          <el-table-column label="Python environment" min-width="16%">
            <template #default="{ row }">
              <span v-if="row.pythonEnvironmentRevisionId" class="custom-config-tag">
                {{ row.pythonEnvironmentRevisionId.slice(0, 12) }}
              </span>
              <span v-else class="sub-text">No persistent packages</span>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="Actions" width="390">
            <template #default="{ row }">
              <el-space>
                <el-button
                  size="small"
                  type="primary"
                  icon="VideoPlay"
                  :loading="startingProfile === row.profileId"
                  :disabled="isEngineTransitioning(row.profileId)"
                  @click="handleStartEngine(row)">
                  Start
                </el-button>
                <el-button
                  size="small"
                  type="danger"
                  icon="VideoPause"
                  :loading="stoppingProfile === row.profileId"
                  :disabled="engineState(row.profileId) !== 'RUNNING'"
                  @click="handleStopEngine(row)">
                  Stop
                </el-button>
                <el-button
                  size="small"
                  icon="InfoFilled"
                  @click="handleOpenLifecycle(row)">
                  Lifecycle
                </el-button>
                <el-button
                  size="small"
                  icon="Edit"
                  @click="handleEditProfile(row)">
                  Edit
                </el-button>
                <el-button
                  size="small"
                  type="danger"
                  icon="Delete"
                  :loading="deletingProfile === row.profileId"
                  @click="handleDeleteProfile(row)">
                  Delete
                </el-button>
              </el-space>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- ENGINE PROFILE DIALOG -->
    <EngineProfileDialog
      v-model="profileDialogVisible"
      :profile-data="selectedProfile"
      @save="handleSaveProfile" />
    <EngineProfileLifecycleDialog
      v-model="lifecycleDialogVisible"
      :profile="lifecycleProfile"
      @changed="loadProfiles" />
  </div>
</template>

<script lang="ts" setup>
  import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
  import { getAllEngines, deleteEngine } from '@/api/engine'
  import { IEngineSearch } from '@/api/engine/types'
  import { useTable } from '@/utils/use-table'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import { getEngineType, getShareLevel } from '@/utils/engine'
  import EngineProfileDialog from './components/EngineProfileDialog.vue'
  import EngineProfileLifecycleDialog from './components/EngineProfileLifecycleDialog.vue'
  import {
    createEngineProfile,
    deleteEngineProfile,
    getEngineProfileEngineStatus,
    listEngineProfiles,
    startEngineProfile,
    stopEngineProfile,
    updateEngineProfile
  } from '@/api/notebook'
  import type { EngineProfile, EngineProfileEngineStatus } from '@/api/notebook/types'

  const { t } = useI18n()
  // VIEW 1: ACTIVE INSTANCES
  const { tableData, loading, getList: _getList } = useTable()
  const searchParam: IEngineSearch = reactive({
    type: 'SPARK_SQL',
    sharelevel: 'USER',
    'hive.server2.proxy.user': 'anonymous'
  })
  const getList = () => {
    _getList(getAllEngines, searchParam)
  }
  const init = () => {
    loadProfiles()
  }

  const engineProfileOf = (engine: any): string =>
    engine?.subdomain ||
    engine?.attributes?.['kyuubi.engine.share.level.subdomain'] ||
    engine?.attributes?.['kyuubi.engine.share.level.sub.domain'] ||
    'default'

  function handleDeleteEngine(row: any) {
    deleteEngine({
      type: row?.engineType,
      sharelevel: row?.sharelevel,
      'hive.server2.proxy.user': row?.user,
      subdomain: row?.subdomain,
      kill: true
    })
      .then(() => {
        ElMessage({
          message: t('delete_succeeded', { name: 'engine' }),
          type: 'success'
        })
      })
      .catch(() => {
        ElMessage({
          message: t('delete_failed', { name: 'engine' }),
          type: 'error'
        })
      })
      .finally(() => {
        getList()
      })
  }

  function getProxyEngineUI(url: string): string {
    url = (url || '').replaceAll(/http:|https:/gi, '')
    return `${import.meta.env.VITE_APP_DEV_WEB_URL}engine-ui/${url}/`
  }

  function openEngineUI(url: string) {
    window.open(getProxyEngineUI(url))
  }

  // VIEW 2: ENGINE PROFILES (CONNECTED TO REAL REST API)
  const profiles = ref<EngineProfile[]>([])
  const profilesLoading = ref(false)
  const profileDialogVisible = ref(false)
  const selectedProfile = ref<EngineProfile | null>(null)
  const lifecycleDialogVisible = ref(false)
  const lifecycleProfile = ref<EngineProfile | null>(null)
  const deletingProfile = ref<string | null>(null)
  const startingProfile = ref<string | null>(null)
  const stoppingProfile = ref<string | null>(null)
  const engineStatuses = ref<Record<string, EngineProfileEngineStatus>>({})

  const loadProfiles = async () => {
    profilesLoading.value = true
    try {
      const res = await listEngineProfiles()
      if (Array.isArray(res)) {
        profiles.value = res
        await refreshEngineStatuses(res)
      }
    } catch (e: any) {
      console.error('Failed to load engine profiles from backend:', e)
      ElMessage.error(`Failed to load engine profiles: ${e?.message || e}`)
    } finally {
      profilesLoading.value = false
    }
  }

  // Poll only engine state. Re-fetching the full profile list every few seconds replaces all
  // table rows and makes the page look as though it is continuously reloading.
  const refreshEngineStatuses = async (profileList = profiles.value) => {
    const statuses = await Promise.all(profileList.map(async (profile) => {
      try {
        return await getEngineProfileEngineStatus(profile.profileId)
      } catch {
        return {
          profileId: profile.profileId,
          revision: profile.revision || 1,
          state: 'UNKNOWN' as const,
          engineCount: 0
        }
      }
    }))
    engineStatuses.value = Object.fromEntries(
      statuses.map((status) => [status.profileId, status])
    )
  }

  const engineState = (profileId: string): string =>
    engineStatuses.value[profileId]?.state || 'STOPPED'

  const engineCount = (profileId: string): number =>
    engineStatuses.value[profileId]?.engineCount || 0

  const engineStateType = (profileId: string) =>
    ({
      STARTING: 'warning',
      RUNNING: 'success',
      STOPPING: 'warning',
      STOPPED: 'info',
      FAILED: 'danger',
      UNKNOWN: 'info'
    }[engineState(profileId)] || 'info')

  const isEngineTransitioning = (profileId: string) =>
    ['STARTING', 'STOPPING'].includes(engineState(profileId))

  const handleStartEngine = async (profile: EngineProfile) => {
    startingProfile.value = profile.profileId
    try {
      engineStatuses.value[profile.profileId] = await startEngineProfile(profile.profileId)
      ElMessage.success(`Engine '${profile.name || profile.subdomain}' is starting`)
    } catch (e: any) {
      ElMessage.error(`Failed to start engine: ${e?.message || e}`)
    } finally {
      startingProfile.value = null
    }
  }

  const handleStopEngine = async (profile: EngineProfile) => {
    stoppingProfile.value = profile.profileId
    try {
      engineStatuses.value[profile.profileId] = await stopEngineProfile(profile.profileId)
      ElMessage.success(`Engine '${profile.name || profile.subdomain}' is stopping`)
    } catch (e: any) {
      ElMessage.error(`Failed to stop engine: ${e?.message || e}`)
    } finally {
      stoppingProfile.value = null
    }
  }

  const getExtraConfigsCount = (sparkConfig?: Record<string, string>): number => {
    if (!sparkConfig) return 0
    const standardKeys = [
      'spark.driver.memory',
      'spark.driver.cores',
      'spark.executor.memory',
      'spark.executor.cores',
      'spark.executor.instances'
    ]
    return Object.keys(sparkConfig).filter((k) => !standardKeys.includes(k)).length
  }

  const handleOpenCreateProfile = () => {
    selectedProfile.value = null
    profileDialogVisible.value = true
  }

  const handleEditProfile = (profile: EngineProfile) => {
    selectedProfile.value = JSON.parse(JSON.stringify(profile))
    profileDialogVisible.value = true
  }

  const handleOpenLifecycle = (profile: EngineProfile) => {
    lifecycleProfile.value = profile
    lifecycleDialogVisible.value = true
  }

  const handleDeleteProfile = async (profile: EngineProfile) => {
    try {
      await ElMessageBox.confirm(
        `Delete Engine Profile '${profile.name || profile.subdomain}'? Existing engines are not terminated; they remain available until their idle timeout.`,
        'Delete Engine Profile',
        {
          confirmButtonText: 'Delete Profile',
          cancelButtonText: 'Cancel',
          type: 'warning'
        })
    } catch {
      return
    }

    deletingProfile.value = profile.profileId
    try {
      await deleteEngineProfile(profile.profileId)
      ElMessage.success(`Engine profile '${profile.name || profile.subdomain}' deleted successfully`)
      await loadProfiles()
    } catch (e: any) {
      ElMessage.error(`Failed to delete profile: ${e?.message || e}`)
    } finally {
      deletingProfile.value = null
    }
  }

  const handleSaveProfile = async (profileData: {
    profileId?: string
    name: string
    sparkConfig: Record<string, string>
    notebookRuntimeIdleTimeout: string
    engineIdleTimeout: string
  }) => {
    try {
      if (profileData.profileId) {
        await updateEngineProfile(profileData.profileId, profileData.name, profileData.sparkConfig, {
          notebookRuntimeIdleTimeout: profileData.notebookRuntimeIdleTimeout,
          engineIdleTimeout: profileData.engineIdleTimeout
        })
      } else {
        await createEngineProfile(profileData.name, profileData.sparkConfig, {
          notebookRuntimeIdleTimeout: profileData.notebookRuntimeIdleTimeout,
          engineIdleTimeout: profileData.engineIdleTimeout
        })
      }
      ElMessage.success(`Engine profile '${profileData.name}' saved successfully`)
      await loadProfiles()
    } catch (e: any) {
      ElMessage.error(`Failed to save engine profile: ${e?.message || e}`)
    }
  }

  let statusPoll: ReturnType<typeof setInterval> | undefined

  onMounted(() => {
    init()
    statusPoll = setInterval(refreshEngineStatuses, 5000)
  })

  onBeforeUnmount(() => {
    if (statusPoll) clearInterval(statusPoll)
  })

  defineExpose({
    getProxyEngineUI
  })
</script>

<style scoped lang="scss">
  .engine-management-page {
    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 16px;
    }
    .page-title {
      font-size: 20px;
      font-weight: 600;
      color: #1e293b;
    }
    header {
      display: flex;
      justify-content: flex-end;
    }
    .filter_card {
      margin-bottom: 12px;
    }
    .sub-text {
      font-size: 11px;
      color: #909399;
    }
    .custom-config-tag {
      font-size: 12px;
      color: #475569;
      background: #F1F5F9;
      padding: 2px 6px;
      border-radius: 4px;
      border: 1px solid #E2E8F0;
    }
  }
</style>
