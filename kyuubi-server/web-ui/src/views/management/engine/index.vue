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
      <el-radio-group v-model="currentView" size="large">
        <el-radio-button label="instances">
          <el-icon><Monitor /></el-icon>
          <span style="margin-left: 6px">Active Engines</span>
        </el-radio-button>
        <el-radio-button label="profiles">
          <el-icon><Setting /></el-icon>
          <span style="margin-left: 6px">Engine Profiles</span>
        </el-radio-button>
      </el-radio-group>

      <div v-if="currentView === 'profiles'" class="header-actions">
        <el-button type="primary" icon="Plus" @click="handleOpenCreateProfile">
          Create Engine Profile
        </el-button>
      </div>
    </div>

    <!-- VIEW 1: ACTIVE INSTANCES -->
    <div v-if="currentView === 'instances'">
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
    <div v-else class="profiles-container">
      <el-card class="table-container">
        <el-table v-loading="profilesLoading" :data="profiles" style="width: 100%">
          <el-table-column prop="subdomain" label="Engine Name" min-width="20%">
            <template #default="{ row }">
              <div style="font-weight: 600; color: #1E293B">{{ row.name || row.subdomain }}</div>
              <div class="sub-text">{{ row.subdomain }}</div>
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
          <el-table-column fixed="right" label="Actions" width="160">
            <template #default="{ row }">
              <el-space>
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
                  :loading="deletingProfile === row.subdomain"
                  @click="handleDeleteProfile(row)" />
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
  </div>
</template>

<script lang="ts" setup>
  import { ref, reactive, watch } from 'vue'
  import { getAllEngines, deleteEngine } from '@/api/engine'
  import { IEngineSearch } from '@/api/engine/types'
  import { useTable } from '@/utils/use-table'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import { getEngineType, getShareLevel } from '@/utils/engine'
  import EngineProfileDialog from './components/EngineProfileDialog.vue'
  import { listEngineProfiles, upsertEngineProfile, deleteEngineProfile } from '@/api/notebook'
  import type { EngineProfile } from '@/api/notebook/types'

  const { t } = useI18n()
  const currentView = ref<'instances' | 'profiles'>('instances')

  watch(currentView, (view) => {
    if (view === 'profiles') {
      loadProfiles()
    } else {
      getList()
    }
  })

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
    getList()
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
  const deletingProfile = ref<string | null>(null)

  const loadProfiles = async () => {
    profilesLoading.value = true
    try {
      const res = await listEngineProfiles()
      if (Array.isArray(res)) {
        profiles.value = res
      }
    } catch (e: any) {
      console.error('Failed to load engine profiles from backend:', e)
      ElMessage.error(`Failed to load engine profiles: ${e?.message || e}`)
    } finally {
      profilesLoading.value = false
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

  const handleDeleteProfile = async (profile: EngineProfile) => {
    const subdomain = profile.subdomain
    let activeEngines: any[] = []
    try {
      // Query the exact Kyuubi discovery space for this user/subdomain before deleting the
      // profile. Passing the owner also makes an administrator terminate the profile owner's
      // engine rather than their own.
      const response = await getAllEngines({
        type: 'SPARK_SQL',
        sharelevel: 'USER',
        'hive.server2.proxy.user': profile.owner || null,
        subdomain
      })
      // The shared axios interceptor unwraps `data` at runtime, while the legacy helper is
      // typed as AxiosResponse. Support both shapes until that helper is typed consistently.
      activeEngines = Array.isArray(response)
        ? response
        : Array.isArray(response.data)
          ? response.data
          : []
    } catch (e: any) {
      ElMessage.error(`Unable to check active Engines: ${e?.message || e}`)
      return
    }

    const engineCount = activeEngines.length
    const confirmation = engineCount
      ? `Profile '${subdomain}' currently has ${engineCount} running Engine ${engineCount === 1 ? 'Pod' : 'Pods'} on the cluster. Deleting this profile will terminate ${engineCount === 1 ? 'that Pod' : 'those Pods'} and release its resources. Do you want to continue?`
      : `Delete Engine Profile '${subdomain}'? No active Engine Pod was found.`

    try {
      await ElMessageBox.confirm(confirmation, 'Delete Engine Profile', {
        confirmButtonText: engineCount ? 'Terminate Engine & Delete Profile' : 'Delete Profile',
        cancelButtonText: 'Cancel',
        type: 'warning'
      })
    } catch {
      return
    }

    deletingProfile.value = subdomain
    try {
      if (engineCount) {
        await deleteEngine({
          type: 'SPARK_SQL',
          sharelevel: 'USER',
          'hive.server2.proxy.user': profile.owner || null,
          subdomain,
          kill: true
        })
      }
      await deleteEngineProfile(subdomain)
      ElMessage.success(
        engineCount
          ? `Engine '${subdomain}' was terminated and its profile was deleted`
          : `Engine profile '${subdomain}' deleted successfully`
      )
      await loadProfiles()
    } catch (e: any) {
      ElMessage.error(`Failed to terminate/delete '${subdomain}': ${e?.message || e}`)
    } finally {
      deletingProfile.value = null
    }
  }

  const handleSaveProfile = async (profileData: { subdomain: string; sparkConfig: Record<string, string> }) => {
    try {
      await upsertEngineProfile(profileData.subdomain, profileData.sparkConfig)
      ElMessage.success(`Engine profile '${profileData.subdomain}' saved successfully`)
      await loadProfiles()
    } catch (e: any) {
      ElMessage.error(`Failed to save engine profile: ${e?.message || e}`)
    }
  }

  init()

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
