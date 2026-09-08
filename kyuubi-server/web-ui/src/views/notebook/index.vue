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
  <div class="db-notebook-workspace">
    <!-- LEFT PANEL: WORKSPACE FILE TREE -->
    <div
      v-show="isTreeVisible"
      class="db-tree-panel"
      :style="{ width: `${treeWidth}px` }">
      <WorkspaceTree
        ref="workspaceTree"
        :python-enabled="pythonEnabled"
        @select="handleSelectNotebook" />
    </div>

    <!-- COLLAPSE/EXPAND TOGGLE STRIP -->
    <div
      class="db-tree-toggle-strip"
      :title="isTreeVisible ? 'Collapse Sidebar' : 'Expand Sidebar'"
      @click="isTreeVisible = !isTreeVisible">
      <el-icon :size="12">
        <component :is="isTreeVisible ? 'ArrowLeft' : 'ArrowRight'" />
      </el-icon>
    </div>

    <!-- RIGHT MAIN WORKSPACE -->
    <div class="db-main-panel">
      <!-- TOP MULTI-TAB BAR -->
      <NotebookTabBar
        :tabs="openTabs"
        :active-tab-id="activeTabId"
        @select-tab="handleSwitchTab"
        @close-tab="handleCloseTab"
        @new-tab="handleNewTab" />

      <!-- NOTEBOOK CONTENT AREA -->
      <div v-loading="loading" class="db-content-canvas">
        <template v-if="notebook">
          <!-- VTNEXUS / DATABRICKS ACTION TOOLBAR -->
          <div class="db-action-toolbar">
            <div class="toolbar-top-row">
              <div class="title-section">
                <el-icon class="nb-icon">
                  <component :is="notebook.language === 'PYTHON' ? 'Opportunity' : 'DataLine'" />
                </el-icon>

                <!-- EDITABLE TITLE -->
                <div v-if="!isEditingTitle" class="title-display" @click="startEditTitle">
                  <span class="nb-name">{{ notebook.name }}</span>
                  <el-icon class="edit-pen-icon"><Edit /></el-icon>
                </div>
                <el-input
                  v-else
                  ref="titleInputRef"
                  v-model="editTitleValue"
                  size="small"
                  class="title-input"
                  @blur="saveTitle"
                  @keyup.enter="saveTitle" />

                <!-- LANGUAGE BADGE -->
                <span
                  class="nb-lang-tag"
                  :class="notebook.language === 'PYTHON' ? 'tag-python' : 'tag-sql'">
                  {{ notebook.language === 'PYTHON' ? 'Python' : 'SQL' }}
                </span>
                <span class="nb-path">{{ notebook.path || '/Workspace/' + notebook.name }}</span>
              </div>

              <!-- RIGHT ACTION BUTTONS -->
              <div class="actions-section">
                <!-- COMPUTE / ENGINE SELECTOR (BACKEND DB POWERED) -->
                <div class="engine-compute-selector">
                  <span
                    class="status-indicator"
                    :class="{ 'is-running': session && session.state !== 'STOPPED' }">
                    ●
                  </span>
                  <el-tooltip
                    :content="selectedProfileSpecs"
                    placement="bottom"
                    :disabled="!selectedProfileSpecs">
                    <el-select
                      v-model="currentEngineProfile"
                      size="small"
                      style="width: 260px"
                      placeholder="Select Engine Profile"
                      popper-class="engine-profile-dropdown"
                      @change="onEngineProfileChange">
                      <el-option
                        v-for="p in engineProfiles"
                        :key="p.subdomain"
                        :label="p.name || p.subdomain"
                        :value="p.subdomain"
                        class="engine-profile-option">
                        <div class="option-content">
                          <div class="option-header">
                            <span class="profile-name">{{ p.name || p.subdomain }}</span>
                            <span class="profile-subdomain">{{ p.subdomain }}</span>
                          </div>
                          <div class="option-specs">
                            <el-icon class="spec-icon"><Cpu /></el-icon>
                            <span>{{ formatSpecs(p) }}</span>
                          </div>
                        </div>
                      </el-option>
                    </el-select>
                  </el-tooltip>
                  <el-button
                    size="small"
                    icon="Setting"
                    class="config-btn"
                    title="Manage Engine Profiles in Database"
                    @click="engineConfigDialogVisible = true" />
                </div>

                <el-button
                  size="small"
                  type="primary"
                  class="run-all-btn"
                  icon="VideoPlay"
                  @click="runAllCells">
                  Run all
                </el-button>

                <el-button size="small" icon="Calendar" @click="scheduleDialog = true">
                  Schedule
                </el-button>

                <el-button size="small" icon="Clock" @click="openRevisions">
                  Revisions
                </el-button>

                <el-button size="small" icon="Share" @click="openPermissions">
                  Share
                </el-button>

                <el-dropdown trigger="click" @command="handleSessionCommand">
                  <el-button size="small" icon="MoreFilled" circle />
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="restart" icon="RefreshRight">
                        Restart Session
                      </el-dropdown-item>
                      <el-dropdown-item command="stop" icon="SwitchButton">
                        Stop Session
                      </el-dropdown-item>
                      <el-dropdown-item command="clear-output" icon="Delete" divided>
                        Clear All Outputs
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>

            <!-- SUB-MENUBAR (File, Edit, View, Run, Help) -->
            <div class="toolbar-menu-row">
              <span class="menu-item" @click="saveNotebookState">File</span>
              <span class="menu-item" @click="clearAllOutputs">Edit</span>
              <span class="menu-item" @click="isTreeVisible = !isTreeVisible">View</span>
              <span class="menu-item" @click="runAllCells">Run</span>
              <span class="menu-item" @click="showHelp">Help</span>
            </div>
          </div>

          <!-- CELLS CANVAS -->
          <div class="db-cells-canvas">
            <NotebookCellItem
              v-for="(cell, idx) in cells"
              :key="cell.id"
              :index="idx + 1"
              :cell="cell"
              :execution="executions[cell.id]"
              :output="outputs[cell.id]"
              :is-initializing="Boolean(initializingCells[cell.id])"
              :read-only="readOnly()"
              :python-enabled="pythonEnabled"
              :notebook-language="notebook.language"
              @run="runCell"
              @stop="stopCell"
              @remove="removeCell"
              @save="saveCell"
              @move-up="handleMoveCell(cell, 'up')"
              @move-down="handleMoveCell(cell, 'down')"
              @add-cell="handleAddCell"
              @load-more="() => loadMoreRows(cell.id)" />

            <!-- ADD FIRST CELL IF EMPTY -->
            <div v-if="!cells.length" class="db-empty-cells">
              <el-button
                type="primary"
                plain
                icon="Plus"
                @click="addCell('CODE')">
                Add Code Cell
              </el-button>
              <el-button
                plain
                icon="Plus"
                @click="addCell('MARKDOWN')">
                Add Text Cell
              </el-button>
            </div>
          </div>
        </template>

        <!-- WELCOME EMPTY STATE IF NO OPEN NOTEBOOK -->
        <div v-else class="db-welcome-state">
          <div class="welcome-box">
            <div class="welcome-icon">📁</div>
            <h2>VTNexus Workspace</h2>
            <p>Select a notebook from the workspace tree on the left or create a new one to start analytics.</p>
            <div class="welcome-actions">
              <el-button
                type="primary"
                size="large"
                icon="DocumentAdd"
                @click="handleNewTab">
                New Notebook
              </el-button>
              <el-button
                size="large"
                icon="Upload"
                @click="workspaceTree?.openNewNotebookDialog()">
                Explore Files
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- REVISIONS DIALOG -->
    <el-dialog v-model="revisionsDialog" title="Notebook Revisions" width="620px">
      <el-button size="small" style="margin-bottom: 8px" @click="checkpoint">
        Create checkpoint
      </el-button>
      <el-table :data="revisions" size="small" max-height="360">
        <el-table-column prop="revisionNumber" label="#" width="60" />
        <el-table-column prop="createdBy" label="By" width="120" />
        <el-table-column label="When" width="180">
          <template #default="scope">
            {{ new Date(scope.row.createdAt).toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="Reason" />
        <el-table-column label="Actions" width="120">
          <template #default="scope">
            <el-button
              size="small"
              link
              type="primary"
              @click="restore(scope.row.revisionNumber)">
              Restore
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- SHARE PERMISSIONS DIALOG -->
    <el-dialog v-model="permissionsDialog" title="Share Notebook" width="520px">
      <div v-for="(entry, index) in permissions" :key="index" class="grant-row">
        <el-input v-model="entry.principalId" placeholder="User" size="small" />
        <el-select v-model="entry.role" size="small" style="width: 120px">
          <el-option label="Editor" value="EDITOR" />
          <el-option label="Viewer" value="VIEWER" />
        </el-select>
        <el-button size="small" link type="danger" @click="permissions.splice(index, 1)">
          Remove
        </el-button>
      </div>
      <el-button size="small" style="margin-top: 8px" @click="addGrant">
        + Add User Permission
      </el-button>
      <template #footer>
        <el-button @click="permissionsDialog = false">Cancel</el-button>
        <el-button type="primary" @click="savePermissions">Save</el-button>
      </template>
    </el-dialog>

    <!-- SCHEDULE DIALOG -->
    <el-dialog v-model="scheduleDialog" title="Schedule Notebook Execution" width="460px">
      <el-form label-width="120px">
        <el-form-item label="Cron Expression">
          <el-input v-model="cronExpr" placeholder="0 0 * * *" />
          <div style="font-size: 11px; color: #909399">e.g. 0 0 * * * (Every midnight)</div>
        </el-form-item>
        <el-form-item label="Timezone">
          <el-input v-model="cronTz" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scheduleDialog = false">Cancel</el-button>
        <el-button type="primary" @click="saveSchedule">Save Schedule</el-button>
      </template>
    </el-dialog>

    <!-- ENGINE CONFIGURATION PRESETS DIALOG (BACKEND DRIVEN) -->
    <EngineConfigDialog
      v-model:visible="engineConfigDialogVisible"
      @save="onEngineProfileSave"
      @change="refreshEngineProfiles" />
  </div>
</template>

<script setup lang="ts">
  import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
  import { useRoute } from 'vue-router'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import WorkspaceTree from './components/WorkspaceTree.vue'
  import NotebookTabBar, { NotebookTab } from './components/NotebookTabBar.vue'
  import NotebookCellItem from './components/NotebookCellItem.vue'
  import EngineConfigDialog from './components/EngineConfigDialog.vue'
  import { useNotebook } from './use-notebook'
  import * as api from '@/api/notebook'
  import type { EngineProfile, NotebookPermission, NotebookRevision } from '@/api/notebook/types'

  const {
    notebook,
    cells,
    session,
    loading,
    pythonEnabled,
    executions,
    outputs,
    initializingCells,
    readOnly,
    open,
    loadRuntimeSpecs,
    runCell,
    stopCell,
    loadMoreRows,
    saveCell,
    addCell,
    removeCell,
    moveCell,
    reloadCells,
    restartSession,
    stopSession,
    dispose,
    reportError
  } = useNotebook()

  const workspaceTree = ref<InstanceType<typeof WorkspaceTree> | null>(null)
  const isTreeVisible = ref(true)
  const treeWidth = ref(260)

  // MULTI-TABS MANAGEMENT
  const openTabs = ref<NotebookTab[]>([])
  const activeTabId = ref<string | null>(null)

  // TITLE INLINE EDITING
  const isEditingTitle = ref(false)
  const editTitleValue = ref('')
  const titleInputRef = ref()

  // ENGINE PROFILES (BACKEND DB INTEGRATED)
  const engineConfigDialogVisible = ref(false)
  const engineProfiles = ref<EngineProfile[]>([
    {
      name: 'default',
      subdomain: 'default',
      driverMemory: '1g',
      driverCores: 1,
      executorMemory: '2g',
      executorCores: 1,
      executorInstances: 1
    }
  ])

  const route = useRoute()

  // DIALOGS
  const revisionsDialog = ref(false)
  const revisions = ref<NotebookRevision[]>([])
  const permissionsDialog = ref(false)
  const permissions = ref<NotebookPermission[]>([])
  const scheduleDialog = ref(false)
  const cronExpr = ref('0 3 * * *')
  const cronTz = ref('Asia/Ho_Chi_Minh')

  const parseProfile = (apiProfile: EngineProfile): EngineProfile => {
    const sparkConfig = apiProfile.sparkConfig || {}
    const isDefault = apiProfile.subdomain === 'default'
    return {
      ...apiProfile,
      name: apiProfile.name || apiProfile.subdomain,
      driverMemory: sparkConfig['spark.driver.memory'] || apiProfile.driverMemory || (isDefault ? '1g' : ''),
      executorMemory: sparkConfig['spark.executor.memory'] || apiProfile.executorMemory || (isDefault ? '2g' : ''),
      driverCores: sparkConfig['spark.driver.cores'] ? Number(sparkConfig['spark.driver.cores']) : apiProfile.driverCores || (isDefault ? 1 : undefined),
      executorCores: sparkConfig['spark.executor.cores'] ? Number(sparkConfig['spark.executor.cores']) : apiProfile.executorCores || (isDefault ? 1 : undefined),
      executorInstances: sparkConfig['spark.executor.instances'] ? Number(sparkConfig['spark.executor.instances']) : apiProfile.executorInstances || (isDefault ? 1 : undefined)
    }
  }

  const refreshEngineProfiles = async () => {
    let loadedFromBackend = false
    try {
      const res = await api.listEngineProfiles()
      loadedFromBackend = true
      if (Array.isArray(res) && res.length > 0) {
        const mapped = res.map(parseProfile)
        if (!mapped.some((p) => p.subdomain === 'default')) {
          mapped.unshift({
            name: 'default',
            subdomain: 'default',
            driverMemory: '1g',
            driverCores: 1,
            executorMemory: '2g',
            executorCores: 1,
            executorInstances: 1
          })
        }
        engineProfiles.value = mapped
      } else {
        engineProfiles.value = [
          {
            name: 'default',
            subdomain: 'default',
            driverMemory: '1g',
            driverCores: 1,
            executorMemory: '2g',
            executorCores: 1,
            executorInstances: 1
          }
        ]
      }
    } catch (e) {
      console.error('Failed to load engine profiles from backend:', e)
      engineProfiles.value = [
        {
          name: 'default',
          subdomain: 'default',
          driverMemory: '1g',
          driverCores: 1,
          executorMemory: '2g',
          executorCores: 1,
          executorInstances: 1
        }
      ]
    }

    // Only treat a missing profile as deleted after a successful API response. A network/API
    // failure must not silently switch a Notebook away from its selected Engine.
    if (loadedFromBackend && notebook.value && notebook.value.runtimeProfile) {
      const exists = engineProfiles.value.some((p) => p.subdomain === notebook.value?.runtimeProfile)
      if (!exists && engineProfiles.value.length > 0) {
        await onEngineProfileChange(engineProfiles.value[0].subdomain)
      }
    }
  }

  const formatSpecs = (p: EngineProfile): string => {
    const sparkConfig = p.sparkConfig || {}
    const isDefault = p.subdomain === 'default'
    const driver = sparkConfig['spark.driver.memory'] || p.driverMemory || (isDefault ? '1g' : undefined)
    const exec = sparkConfig['spark.executor.memory'] || p.executorMemory || (isDefault ? '2g' : undefined)
    const cores = sparkConfig['spark.executor.cores'] || p.executorCores || (isDefault ? 1 : undefined)
    const inst = sparkConfig['spark.executor.instances'] || p.executorInstances || (isDefault ? 1 : undefined)

    if (!driver && !exec && !cores && !inst) {
      if (p.subdomain === 'default') {
        return 'Default cluster compute settings'
      }
      return 'Standard cluster settings'
    }

    const parts: string[] = []
    if (driver) parts.push(`Driver: ${String(driver).toUpperCase()}`)
    if (exec) parts.push(`Exec: ${String(exec).toUpperCase()}`)
    const execDetails: string[] = []
    if (cores) execDetails.push(`${cores} cores`)
    if (inst) execDetails.push(`${inst} inst`)
    if (execDetails.length > 0) {
      parts.push(`(${execDetails.join(', ')})`)
    }
    return parts.join(' • ')
  }

  const currentEngineProfile = computed({
    get: () => {
      const profile = notebook.value?.runtimeProfile || ''
      if (profile) {
        const exists = engineProfiles.value.some((p) => p.subdomain === profile)
        if (!exists) return engineProfiles.value[0]?.subdomain || 'default'
      }
      return profile || (engineProfiles.value[0]?.subdomain || 'default')
    },
    set: (val) => {
      if (notebook.value) {
        notebook.value.runtimeProfile = val
      }
    }
  })

  const selectedProfileSpecs = computed(() => {
    const found = engineProfiles.value.find((p) => p.subdomain === currentEngineProfile.value)
    return found ? formatSpecs(found) : ''
  })

  const onEngineProfileChange = async (newSubdomain: string) => {
    if (!notebook.value) return
    try {
      const updated = await api.updateNotebook(notebook.value.id, {
        runtimeProfile: newSubdomain
      })
      notebook.value = updated
      if (newSubdomain) {
        ElMessage.success(`Engine profile changed to '${newSubdomain}'`)
      }
      if (session.value && session.value.state !== 'STOPPED') {
        await stopSession()
      }
      session.value = null
    } catch (error) {
      reportError(error, 'Failed to update engine profile')
    }
  }

  const onEngineProfileSave = (profile: EngineProfile) => {
    const existingIndex = engineProfiles.value.findIndex(
      (p) => p.subdomain === profile.subdomain
    )
    if (existingIndex >= 0) {
      engineProfiles.value[existingIndex] = profile
    } else {
      engineProfiles.value.push(profile)
    }
    onEngineProfileChange(profile.subdomain)
  }

  // TAB OPERATIONS
  const handleSelectNotebook = async (notebookId: string) => {
    await open(notebookId)
    if (notebook.value) {
      // Reload profiles after the Notebook and its server-side session have been attached. If
      // the selected profile was deleted elsewhere, refreshEngineProfiles persists `default`
      // and stops the old runtime before this Notebook can submit another cell to it.
      await refreshEngineProfiles()
      // The selector displays the default profile when runtimeProfile is empty, but the
      // execution path uses the persisted Notebook value. Persist the same default as soon as
      // the Notebook is reopened so switching tabs does not leave the UI and runtime state out
      // of sync.
      if (!notebook.value.runtimeProfile) {
        try {
          notebook.value = await api.updateNotebook(notebook.value.id, {
            runtimeProfile: 'default'
          })
        } catch (error) {
          // Keep the local state usable when the metadata update is temporarily unavailable.
          notebook.value.runtimeProfile = 'default'
          console.warn('Could not persist the default engine profile:', error)
        }
      }
      activeTabId.value = notebook.value.id
      const existing = openTabs.value.find((t) => t.id === notebook.value!.id)
      if (!existing) {
        openTabs.value.push({
          id: notebook.value.id,
          name: notebook.value.name,
          language: notebook.value.language
        })
      }
    }
  }

  const handleSwitchTab = (tabId: string) => {
    handleSelectNotebook(tabId)
  }

  const handleCloseTab = (tabId: string) => {
    const idx = openTabs.value.findIndex((t) => t.id === tabId)
    if (idx < 0) return
    openTabs.value.splice(idx, 1)
    if (activeTabId.value === tabId) {
      if (openTabs.value.length) {
        const nextTab = openTabs.value[Math.max(0, idx - 1)]
        handleSelectNotebook(nextTab.id)
      } else {
        activeTabId.value = null
        notebook.value = null
        cells.value = []
      }
    }
  }

  const handleNewTab = () => {
    workspaceTree.value?.openNewNotebookDialog(null)
  }

  // TITLE RENAME
  const startEditTitle = () => {
    if (!notebook.value) return
    editTitleValue.value = notebook.value.name
    isEditingTitle.value = true
    nextTick(() => {
      titleInputRef.value?.focus()
    })
  }

  const saveTitle = async () => {
    if (!isEditingTitle.value || !notebook.value) return
    isEditingTitle.value = false
    const newName = editTitleValue.value.trim()
    if (!newName || newName === notebook.value.name) return
    try {
      await api.updateNotebook(notebook.value.id, {
        name: newName,
        version: notebook.value.version
      })
      notebook.value.name = newName
      const tab = openTabs.value.find((t) => t.id === notebook.value!.id)
      if (tab) tab.name = newName
      workspaceTree.value?.reload()
      ElMessage.success('Notebook renamed')
    } catch {
      notebook.value.name = newName
      const tab = openTabs.value.find((t) => t.id === notebook.value!.id)
      if (tab) tab.name = newName
      ElMessage.success('Notebook renamed (Local)')
    }
  }

  // CELL OPERATIONS
  const handleAddCell = (type: 'CODE' | 'MARKDOWN', afterCellId: string) => {
    addCell(type, afterCellId)
  }

  const handleMoveCell = (cell: any, direction: 'up' | 'down') => {
    moveCell(cell, direction)
  }

  const runAllCells = async () => {
    if (!cells.value.length) return
    ElMessage.info('Executing all cells sequentially...')
    for (const cell of cells.value) {
      if (cell.cellType === 'CODE') {
        await runCell(cell, cell.source)
      }
    }
  }

  const clearAllOutputs = () => {
    Object.keys(outputs).forEach((key) => delete outputs[key])
    ElMessage.success('All outputs cleared')
  }

  const saveNotebookState = () => {
    ElMessage.success('Notebook saved')
  }

  const showHelp = () => {
    ElMessageBox.alert(
      'VTNexus Notebook Workspace:\n- Shift + Enter or Ctrl + Enter: Run current cell\n- Click notebook title to rename\n- Use top compute dropdown to change engine profile',
      'Keyboard Shortcuts & Help'
    )
  }

  // SESSION COMMANDS
  const handleSessionCommand = (cmd: string) => {
    if (cmd === 'restart') restartSession()
    else if (cmd === 'stop') stopSession()
    else if (cmd === 'clear-output') clearAllOutputs()
  }

  // REVISIONS & PERMISSIONS
  const openRevisions = async () => {
    if (!notebook.value) return
    try {
      revisions.value = (await api.listRevisions(notebook.value.id)).items
      revisionsDialog.value = true
    } catch (error) {
      reportError(error, 'The revisions list could not be loaded')
    }
  }

  const checkpoint = async () => {
    try {
      const { value } = await ElMessageBox.prompt('Reason for this checkpoint', 'Checkpoint', {
        inputPlaceholder: 'e.g. before major refactoring'
      })
      await api.createRevision(notebook.value!.id, value)
      revisions.value = (await api.listRevisions(notebook.value!.id)).items
    } catch (error) {
      if (error !== 'cancel') reportError(error, 'The checkpoint could not be created')
    }
  }

  const restore = async (revisionNumber: number) => {
    try {
      await ElMessageBox.confirm(
        `Restore the notebook to revision ${revisionNumber}? Current unsaved changes will be lost.`,
        'Restore revision',
        { type: 'warning' }
      )
      await api.restoreRevision(notebook.value!.id, revisionNumber)
      await reloadCells()
      revisionsDialog.value = false
      ElMessage.success(`Restored revision ${revisionNumber}`)
    } catch (error) {
      if (error !== 'cancel') reportError(error, 'The revision could not be restored')
    }
  }

  const openPermissions = async () => {
    if (!notebook.value) return
    try {
      permissions.value = await api.listPermissions(notebook.value.id)
      permissionsDialog.value = true
    } catch (error) {
      reportError(error, 'The permissions list could not be loaded')
    }
  }

  const addGrant = () => {
    permissions.value.push({
      principalType: 'USER',
      principalId: '',
      role: 'VIEWER'
    })
  }

  const savePermissions = async () => {
    try {
      await api.setPermissions(
        notebook.value!.id,
        permissions.value.filter((p) => p.principalId.trim().length > 0)
      )
      permissionsDialog.value = false
      ElMessage.success('Permissions saved')
    } catch (error) {
      reportError(error, 'The permissions could not be saved')
    }
  }

  const saveSchedule = () => {
    scheduleDialog.value = false
    ElMessage.success(`Schedule saved: ${cronExpr.value} (${cronTz.value})`)
  }

  watch(
    () => route.query.id,
    (newId) => {
      if (newId && typeof newId === 'string' && newId !== activeTabId.value) {
        handleSelectNotebook(newId)
      }
    }
  )

  onMounted(async () => {
    void loadRuntimeSpecs()
    const targetId = route.query.id as string | undefined
    if (targetId) {
      await handleSelectNotebook(targetId)
    } else {
      await refreshEngineProfiles()
    }
  })

  onBeforeUnmount(dispose)
</script>

<style scoped lang="scss">
  .db-notebook-workspace {
    display: flex;
    height: calc(100vh - 56px);
    background: #ffffff;
    overflow: hidden;
    margin: -20px;

    .db-tree-panel {
      height: 100%;
      flex-shrink: 0;
      transition: width 0.2s ease;
    }

    .db-tree-toggle-strip {
      width: 10px;
      height: 100%;
      background: #f8fafc;
      border-right: 1px solid #e2e8f0;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      color: #94a3b8;
      transition: all 0.15s ease;

      &:hover {
        background: #f1f5f9;
        color: #ff3621;
      }
    }

    .db-main-panel {
      flex: 1;
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow: hidden;
      background: #ffffff;
    }

    .db-content-canvas {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow-y: auto;
    }

    /* ACTION TOOLBAR */
    .db-action-toolbar {
      padding: 10px 18px 4px;
      border-bottom: 1px solid #e2e8f0;
      background: #ffffff;

      .toolbar-top-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 6px;

        .title-section {
          display: flex;
          align-items: center;
          gap: 10px;

          .nb-icon {
            font-size: 18px;
            color: #ff3621;
          }

          .title-display {
            display: flex;
            align-items: center;
            gap: 6px;
            cursor: pointer;
            padding: 2px 6px;
            border-radius: 4px;
            &:hover {
              background: #f1f5f9;
              .edit-pen-icon {
                opacity: 1;
              }
            }

            .nb-name {
              font-size: 15px;
              font-weight: 600;
              color: #0f172a;
            }

            .edit-pen-icon {
              font-size: 13px;
              color: #94a3b8;
              opacity: 0;
              transition: opacity 0.15s ease;
            }
          }

          .title-input {
            width: 220px;
          }

          .nb-lang-tag {
            font-size: 10px;
            font-weight: 700;
            padding: 1px 6px;
            border-radius: 3px;
            letter-spacing: 0.5px;

            &.tag-sql {
              background: #eff6ff;
              color: #2563eb;
              border: 1px solid #bfdbfe;
            }

            &.tag-python {
              background: #fefce8;
              color: #ca8a04;
              border: 1px solid #fef08a;
            }
          }

          .nb-path {
            font-size: 11px;
            color: #94a3b8;
          }
        }

        .actions-section {
          display: flex;
          align-items: center;
          gap: 8px;

          .engine-compute-selector {
            display: flex;
            align-items: center;
            gap: 4px;
            background: #ffffff;
            border: 1px solid #e2e8f0;
            border-radius: 6px;
            padding: 2px 4px 2px 8px;

            .status-indicator {
              color: #94a3b8;
              font-size: 12px;
              &.is-running {
                color: #10b981;
              }
            }

            :deep(.el-select) {
              .el-input__wrapper {
                box-shadow: none !important;
                padding: 0;
              }
            }

            .config-btn {
              padding: 4px 6px;
              border: none;
              color: #64748b;
              &:hover {
                color: #ff3621;
                background: #f1f5f9;
              }
            }
          }

          .run-all-btn {
            background: #ff3621;
            border-color: #ff3621;
            font-weight: 600;
            &:hover {
              background: #e02f1d;
              border-color: #e02f1d;
            }
          }
        }
      }

      .toolbar-menu-row {
        display: flex;
        align-items: center;
        gap: 16px;
        font-size: 12px;
        color: #64748b;
        padding-top: 2px;
        padding-bottom: 4px;

        .menu-item {
          cursor: pointer;
          padding: 2px 4px;
          border-radius: 3px;
          &:hover {
            color: #ff3621;
            background: #f1f5f9;
          }
        }
      }
    }

    /* CELLS CANVAS */
    .db-cells-canvas {
      padding: 16px 24px;
      flex: 1;
    }

    .db-empty-cells {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 12px;
      padding: 40px 0;
      border: 2px dashed #e2e8f0;
      border-radius: 8px;
    }

    /* WELCOME SCREEN */
    .db-welcome-state {
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100%;
      background: #f8fafc;

      .welcome-box {
        text-align: center;
        max-width: 480px;

        .welcome-icon {
          font-size: 48px;
          margin-bottom: 12px;
        }

        h2 {
          font-size: 20px;
          color: #0f172a;
          margin-bottom: 8px;
        }

        p {
          font-size: 13px;
          color: #64748b;
          line-height: 1.5;
          margin-bottom: 24px;
        }

        .welcome-actions {
          display: flex;
          justify-content: center;
          gap: 12px;

          .el-button--primary {
            background: #ff3621;
            border-color: #ff3621;
            &:hover {
              background: #e02f1d;
              border-color: #e02f1d;
            }
          }
        }
      }
    }

    .grant-row {
      display: flex;
      gap: 6px;
      margin-bottom: 6px;
    }
  }
</style>

<style lang="scss">
  .engine-profile-dropdown {
    min-width: 340px !important;

    .el-select-dropdown__item {
      height: auto !important;
      padding: 9px 12px !important;
      line-height: normal !important;

      &.hover,
      &:hover {
        background-color: #f8fafc;
      }

      &.selected {
        background-color: #fff4f2;
        color: #ff3621;
        font-weight: normal;

        .profile-name {
          color: #ff3621 !important;
        }
      }
    }

    .option-content {
      display: flex;
      flex-direction: column;
      gap: 4px;
      width: 100%;

      .option-header {
        display: flex;
        justify-content: space-between;
        align-items: center;

        .profile-name {
          font-weight: 600;
          color: #0f172a;
          font-size: 13px;
        }

        .profile-subdomain {
          color: #64748b;
          font-size: 11px;
          background: #f1f5f9;
          padding: 1px 6px;
          border-radius: 4px;
          font-family: monospace;
        }
      }

      .option-specs {
        display: flex;
        align-items: center;
        gap: 5px;
        font-size: 11px;
        color: #64748b;

        .spec-icon {
          font-size: 12px;
          color: #ff3621;
        }
      }
    }
  }
</style>
