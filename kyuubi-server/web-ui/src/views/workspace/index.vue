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
  <div class="db-workspace-container">
    <!-- TOP TOOLBAR / BREADCRUMBS & ACTIONS -->
    <div class="workspace-header">
      <div class="header-left">
        <div class="breadcrumb-container">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>
              <span class="breadcrumb-link root-link" @click="navigateToFolder(null)">
                <el-icon class="bc-icon"><FolderOpened /></el-icon>
                <span>Workspace</span>
              </span>
            </el-breadcrumb-item>
            <el-breadcrumb-item
              v-for="crumb in breadcrumbs"
              :key="crumb.id">
              <span class="breadcrumb-link" @click="navigateToFolder(crumb.id)">
                {{ crumb.name }}
              </span>
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
      </div>

      <div class="header-right">
        <!-- SEARCH FILTER -->
        <el-input
          v-model="searchQuery"
          size="small"
          placeholder="Search files & folders..."
          prefix-icon="Search"
          clearable
          class="search-input" />

        <!-- ACTION BUTTONS -->
        <el-dropdown trigger="click" @command="handleCreateCommand">
          <el-button type="primary" size="small" class="create-btn">
            <el-icon><Plus /></el-icon>
            <span style="margin-left: 4px; font-weight: 600">Create</span>
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="notebook" icon="DocumentAdd">
                Notebook
              </el-dropdown-item>
              <el-dropdown-item command="folder" icon="FolderAdd">
                Folder
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <el-button size="small" icon="Upload" @click="importDialog = true">
          Import
        </el-button>

        <el-button size="small" icon="Refresh" @click="loadData">
          Refresh
        </el-button>
      </div>
    </div>

    <!-- MAIN CONTENT VIEW -->
    <div class="workspace-body">
      <!-- SIDEBAR TREE DIRECTORY -->
      <div class="workspace-tree-sidebar">
        <div class="sidebar-title">
          <span>WORKSPACE DIRECTORIES</span>
        </div>
        <div class="tree-scroll-wrapper">
          <el-tree
            ref="dirTreeRef"
            :data="directoryTree"
            node-key="key"
            :expand-on-click-node="false"
            default-expand-all
            :highlight-current="true"
            :current-node-key="currentNodeKey"
            draggable
            :allow-drag="allowTreeDrag"
            :allow-drop="allowTreeDrop"
            class="workspace-el-tree"
            @node-click="handleTreeNodeClick"
            @node-drop="handleTreeNodeDrop">
            <template #default="{ data }">
              <div class="tree-node-content" :class="{ 'is-active': isNodeActive(data) }">
                <div class="node-left">
                  <el-icon v-if="data.isRoot" class="dir-icon root-icon">
                    <FolderOpened />
                  </el-icon>
                  <el-icon v-else-if="data.isFolder" class="dir-icon folder-icon">
                    <Folder />
                  </el-icon>
                  <span
                    v-else
                    class="lang-badge-mini"
                    :class="data.language === 'PYTHON' ? 'lang-python' : 'lang-sql'">
                    {{ data.language === 'PYTHON' ? 'PY' : 'SQL' }}
                  </span>

                  <span class="tree-node-label" :title="data.label">{{ data.label }}</span>
                </div>

                <span v-if="data.count !== undefined" class="count-badge">
                  {{ data.count }}
                </span>
              </div>
            </template>
          </el-tree>
        </div>
      </div>

      <!-- TABLE LIST VIEW -->
      <div class="workspace-main-content">
        <el-table
          :data="filteredItems"
          style="width: 100%"
          class="db-file-table"
          :row-class-name="tableRowClassName"
          @row-click="handleRowClick">
          <!-- NAME COLUMN -->
          <el-table-column label="Name" min-width="260">
            <template #default="{ row }">
              <div
                class="name-cell"
                :class="{ 'is-drop-target': dragOverFolderId === (row.isFolder ? row.id : null) }"
                draggable="true"
                @dragstart="handleTableDragStart($event, row)"
                @dragend="clearTableDragState"
                @dragenter.prevent="row.isFolder && handleTableDragOver(row)"
                @dragover.prevent="row.isFolder && handleTableDragOver(row)"
                @dragleave="row.isFolder && handleTableDragLeave($event)"
                @drop.prevent="row.isFolder && handleTableDrop(row)">
                <template v-if="row.isFolder">
                  <el-icon class="item-icon folder-icon"><Folder /></el-icon>
                  <span class="item-name folder-name">{{ row.name }}</span>
                </template>
                <template v-else>
                  <span
                    class="lang-badge"
                    :class="row.language === 'PYTHON' ? 'lang-python' : 'lang-sql'">
                    {{ row.language === 'PYTHON' ? 'PY' : 'SQL' }}
                  </span>
                  <span class="item-name notebook-name" @click.stop="openNotebook(row.id)">
                    {{ row.name }}
                  </span>
                </template>
              </div>
            </template>
          </el-table-column>

          <!-- TYPE COLUMN -->
          <el-table-column label="Type" width="130">
            <template #default="{ row }">
              <span class="type-badge">{{ row.isFolder ? 'Folder' : 'Notebook' }}</span>
            </template>
          </el-table-column>

          <!-- LANGUAGE COLUMN -->
          <el-table-column label="Language" width="120">
            <template #default="{ row }">
              <span v-if="!row.isFolder" class="lang-text">
                {{ row.language === 'PYTHON' ? 'Python' : 'SQL' }}
              </span>
              <span v-else class="text-muted">—</span>
            </template>
          </el-table-column>

          <!-- LAST MODIFIED COLUMN -->
          <el-table-column label="Last Modified" width="180">
            <template #default="{ row }">
              <span class="text-muted">{{ row.lastModified || 'Just now' }}</span>
            </template>
          </el-table-column>

          <!-- CREATED BY COLUMN -->
          <el-table-column label="Created By" width="130">
            <template #default="{ row }">
              <span class="text-muted">{{ row.owner || 'admin' }}</span>
            </template>
          </el-table-column>

          <!-- ACTIONS COLUMN -->
          <el-table-column label="Actions" width="140" align="right">
            <template #default="{ row }">
              <div class="row-actions" @click.stop>
                <el-button
                  v-if="!row.isFolder"
                  size="small"
                  type="primary"
                  link
                  icon="Right"
                  @click.stop="openNotebook(row.id)">
                  Open
                </el-button>
                <el-button
                  v-else
                  size="small"
                  link
                  icon="Right"
                  @click.stop="navigateToFolder(row.id)">
                  Open
                </el-button>

                <el-dropdown trigger="click" @command="(cmd: string) => handleItemAction(cmd, row)">
                  <el-button size="small" link icon="MoreFilled" />
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="rename" icon="Edit">
                        Rename
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!row.isFolder" command="clone" icon="CopyDocument">
                        Clone
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!row.isFolder" command="export" icon="Download">
                        Export
                      </el-dropdown-item>
                      <el-dropdown-item command="delete" divided icon="Delete" style="color: #f56c6c">
                        Delete
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-table-column>

          <template #empty>
            <div class="empty-folder-state">
              <el-icon :size="48" class="empty-icon"><FolderOpened /></el-icon>
              <p class="empty-title">This folder is empty</p>
              <p class="empty-desc">Get started by creating a new notebook or subfolder</p>
              <div class="empty-actions">
                <el-button type="primary" size="small" icon="DocumentAdd" @click="openCreateNotebookDialog">
                  New Notebook
                </el-button>
                <el-button size="small" icon="FolderAdd" @click="folderDialog = true">
                  New Folder
                </el-button>
              </div>
            </div>
          </template>
        </el-table>
        <p class="drag-drop-hint">
          Drag notebooks or folders onto a folder in this list or the directory tree to move them.
        </p>
      </div>
    </div>

    <!-- DIALOG: CREATE NOTEBOOK -->
    <el-dialog
      v-model="notebookDialog"
      title="Create Notebook"
      width="440px"
      destroy-on-close>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="Name" required>
          <el-input
            v-model="newNotebookName"
            placeholder="e.g. Sales Analysis 2026"
            @keyup.enter="handleCreateNotebook" />
        </el-form-item>
        <el-form-item label="Default Language" required>
          <el-radio-group v-model="newNotebookLanguage">
            <el-radio label="SQL">SQL (SparkSQL)</el-radio>
            <el-radio label="PYTHON">Python (PySpark)</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Destination Folder">
          <el-select v-model="destinationFolderId" style="width: 100%" placeholder="Workspace Root">
            <el-option label="Workspace (Root)" :value="null" />
            <el-option
              v-for="f in folders"
              :key="f.id"
              :label="f.name"
              :value="f.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="notebookDialog = false">Cancel</el-button>
        <el-button
          type="primary"
          :disabled="!newNotebookName.trim()"
          @click="handleCreateNotebook">
          Create & Open
        </el-button>
      </template>
    </el-dialog>

    <!-- DIALOG: CREATE FOLDER -->
    <el-dialog
      v-model="folderDialog"
      title="Create New Folder"
      width="420px"
      destroy-on-close>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="Folder Name" required>
          <el-input
            v-model="newFolderName"
            placeholder="e.g. Analytics, Pipelines"
            @keyup.enter="handleCreateFolder" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="folderDialog = false">Cancel</el-button>
        <el-button
          type="primary"
          :disabled="!newFolderName.trim()"
          @click="handleCreateFolder">
          Create
        </el-button>
      </template>
    </el-dialog>

    <!-- DIALOG: RENAME -->
    <el-dialog
      v-model="renameDialog"
      title="Rename Item"
      width="420px"
      destroy-on-close>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="New Name" required>
          <el-input
            v-model="renameValue"
            @keyup.enter="handleSaveRename" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameDialog = false">Cancel</el-button>
        <el-button
          type="primary"
          :disabled="!renameValue.trim()"
          @click="handleSaveRename">
          Save
        </el-button>
      </template>
    </el-dialog>

    <!-- DIALOG: IMPORT -->
    <el-dialog
      v-model="importDialog"
      title="Import Notebook"
      width="460px"
      destroy-on-close>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="Notebook Name" required>
          <el-input v-model="importName" placeholder="Imported notebook name" />
        </el-form-item>
        <el-form-item label="Notebook JSON / Source Content" required>
          <el-input
            v-model="importContent"
            type="textarea"
            :rows="6"
            placeholder="Paste notebook JSON or SQL / Python code here" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialog = false">Cancel</el-button>
        <el-button
          type="primary"
          :disabled="!importName.trim() || !importContent.trim()"
          @click="handleImportNotebook">
          Import
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, nextTick, onMounted, ref, watch } from 'vue'
  import { useRouter } from 'vue-router'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import * as api from '@/api/notebook'
  import type { Notebook, NotebookFolder, NotebookLanguage } from '@/api/notebook/types'

  const router = useRouter()

  const LOCAL_STORAGE_FOLDERS = 'kyuubi_mock_folders'
  const LOCAL_STORAGE_NOTEBOOKS = 'kyuubi_mock_notebooks'

  const defaultMockFolders: NotebookFolder[] = [
    { id: 'f-analytics', name: 'Analytics & Reporting', path: '/analytics', owner: 'admin', parentId: null, version: 1 },
    { id: 'f-etl', name: 'ETL Pipelines', path: '/etl', owner: 'admin', parentId: null, version: 1 }
  ]

  const defaultMockNotebooks: any[] = [
    {
      id: 'nb-revenue',
      name: 'Daily Revenue 2026',
      folderId: 'f-analytics',
      language: 'SQL',
      version: 1,
      owner: 'admin',
      lastModified: '10 mins ago',
      cells: [
        { id: 'c1', cellType: 'MARKDOWN', language: 'MARKDOWN', source: '# Daily Revenue Analysis\nQuery revenue grouped by region.', position: 0 },
        { id: 'c2', cellType: 'CODE', language: 'SQL', source: 'SELECT region, SUM(amount) AS total_sales\nFROM sales_mart\nGROUP BY region\nORDER BY total_sales DESC\nLIMIT 10;', position: 1 }
      ]
    },
    {
      id: 'nb-pyspark',
      name: 'Customer Segmentation',
      folderId: 'f-analytics',
      language: 'PYTHON',
      version: 1,
      owner: 'admin',
      lastModified: '1 hour ago',
      cells: [
        { id: 'c3', cellType: 'MARKDOWN', language: 'MARKDOWN', source: '## PySpark ETL & ML Feature Prep', position: 0 },
        { id: 'c4', cellType: 'CODE', language: 'PYTHON', source: '%pip install pandas scikit-learn\n\nfrom pyspark.sql import functions as F\ndf = spark.read.table("customers")\ndf.groupBy("country").count().show(5)', position: 1 }
      ]
    }
  ]

  const folders = ref<NotebookFolder[]>([])
  const allNotebooks = ref<any[]>([])
  const currentFolderId = ref<string | null>(null)
  const searchQuery = ref('')

  // Dialog states
  const notebookDialog = ref(false)
  const newNotebookName = ref('')
  const newNotebookLanguage = ref<NotebookLanguage>('SQL')
  const destinationFolderId = ref<string | null>(null)

  const folderDialog = ref(false)
  const newFolderName = ref('')

  const renameDialog = ref(false)
  const renameValue = ref('')
  const currentItemToRename = ref<any>(null)

  const importDialog = ref(false)
  const importName = ref('')
  const importContent = ref('')
  const draggingItem = ref<any>(null)
  const dragOverFolderId = ref<string | null>(null)

  // Tree Node structure
  interface DirectoryTreeNode {
    key: string
    id: string | null
    label: string
    isFolder: boolean
    isRoot?: boolean
    parentId?: string | null
    language?: string
    count?: number
    children?: DirectoryTreeNode[]
  }

  const dirTreeRef = ref()

  const directoryTree = computed<DirectoryTreeNode[]>(() => {
    const folderNodes = new Map<string, DirectoryTreeNode>()

    // 1. Create all folder nodes
    folders.value.forEach((f) => {
      folderNodes.set(f.id, {
        key: `f-${f.id}`,
        id: f.id,
        label: f.name,
        isFolder: true,
        parentId: f.parentId,
        children: []
      })
    })

    // 2. Add notebooks into folders or root
    const rootNotebooks: DirectoryTreeNode[] = []
    allNotebooks.value.forEach((nb) => {
      const nbNode: DirectoryTreeNode = {
        key: `nb-${nb.id}`,
        id: nb.id,
        label: nb.name,
        isFolder: false,
        parentId: nb.folderId || null,
        language: nb.language || 'SQL'
      }
      if (nb.folderId && folderNodes.has(nb.folderId)) {
        folderNodes.get(nb.folderId)!.children!.push(nbNode)
      } else {
        rootNotebooks.push(nbNode)
      }
    })

    // 3. Connect folder parent-child hierarchy
    const rootFolders: DirectoryTreeNode[] = []
    folders.value.forEach((f) => {
      const node = folderNodes.get(f.id)!
      const count = allNotebooks.value.filter((nb) => nb.folderId === f.id).length
      node.count = count

      if (f.parentId && folderNodes.has(f.parentId)) {
        folderNodes.get(f.parentId)!.children!.unshift(node)
      } else {
        rootFolders.push(node)
      }
    })

    // 4. Create root node
    const rootNode: DirectoryTreeNode = {
      key: 'root',
      id: null,
      label: 'Workspace',
      isFolder: true,
      isRoot: true,
      count: allNotebooks.value.length,
      children: [...rootFolders, ...rootNotebooks]
    }

    return [rootNode]
  })

  const currentNodeKey = computed(() => {
    return currentFolderId.value ? `f-${currentFolderId.value}` : 'root'
  })

  const handleTreeNodeClick = (data: DirectoryTreeNode) => {
    if (data.isFolder) {
      navigateToFolder(data.id)
    } else {
      openNotebook(data.id!)
    }
  }

  const isNodeActive = (data: DirectoryTreeNode) => {
    if (data.isFolder) {
      return currentFolderId.value === data.id
    }
    return false
  }

  /** Tree drops are limited to placing an item inside a folder, never arbitrary sibling order. */
  const allowTreeDrag = (node: any) => !node.data?.isRoot

  const allowTreeDrop = (draggingNode: any, dropNode: any, type: string) => {
    if (type !== 'inner' || !dropNode.data?.isFolder) return false
    const dragged = draggingNode.data as DirectoryTreeNode
    const destination = dropNode.data as DirectoryTreeNode
    // Avoid no-op drops. The service validates authorization and folder cycles authoritatively.
    return dragged.id !== destination.id &&
      (dragged.isFolder ? dragged.parentId !== destination.id : true)
  }

  const moveWorkspaceItem = async (item: any, destinationFolderId: string | null) => {
    try {
      if (item.isFolder) {
        // A folder/notebook can have been changed in the Notebook tab after this view loaded.
        // Read its current version immediately before the optimistic-locking move request.
        const folder = await api.getFolder(item.id)
        if ((folder.parentId || null) === destinationFolderId) return
        await api.moveFolder(item.id, destinationFolderId, folder.version)
      } else {
        const notebook = await api.getNotebook(item.id)
        if ((notebook.folderId || null) === destinationFolderId) return
        await api.moveNotebook(item.id, destinationFolderId, notebook.version)
      }
      ElMessage.success(`Moved ${item.isFolder ? 'folder' : 'notebook'} "${item.name}"`)
      await loadData()
    } catch (e: any) {
      console.error('Failed to move workspace item:', e)
      ElMessage.error(e?.message || `Could not move "${item.name}"`)
    }
  }

  const handleTreeNodeDrop = async (draggingNode: any, dropNode: any) => {
    await moveWorkspaceItem(draggingNode.data, (dropNode.data as DirectoryTreeNode).id)
  }

  const handleTableDragStart = (event: DragEvent, row: any) => {
    draggingItem.value = row
    event.dataTransfer?.setData('text/plain', row.id)
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
  }

  const handleTableDragOver = (row: any) => {
    if (draggingItem.value?.id !== row.id) dragOverFolderId.value = row.id
  }

  const handleTableDragLeave = (event: DragEvent) => {
    const container = event.currentTarget as HTMLElement
    if (!container.contains(event.relatedTarget as Node | null)) {
      dragOverFolderId.value = null
    }
  }

  const clearTableDragState = () => {
    dragOverFolderId.value = null
    draggingItem.value = null
  }

  const handleTableDrop = async (destination: any) => {
    const item = draggingItem.value
    clearTableDragState()
    if (item && item.id !== destination.id) {
      await moveWorkspaceItem(item, destination.id)
    }
  }

  const tableRowClassName = ({ row }: { row: any }) =>
    row.isFolder && dragOverFolderId.value === row.id
      ? 'db-table-row is-drop-target-row'
      : 'db-table-row'

  watch(
    () => currentFolderId.value,
    (newVal) => {
      nextTick(() => {
        const key = newVal ? `f-${newVal}` : 'root'
        dirTreeRef.value?.setCurrentKey(key)
      })
    }
  )

  // Breadcrumbs calculation
  const breadcrumbs = computed(() => {
    if (!currentFolderId.value) return []
    const crumbs: { id: string; name: string }[] = []
    let curr: NotebookFolder | undefined = folders.value.find((f) => f.id === currentFolderId.value)
    while (curr) {
      crumbs.unshift({ id: curr.id, name: curr.name })
      curr = curr.parentId ? folders.value.find((f) => f.id === curr!.parentId) : undefined
    }
    return crumbs
  })

  // Items in the current folder view
  const currentItems = computed(() => {
    const list: any[] = []

    // Subfolders
    folders.value
      .filter((f) => (f.parentId || null) === (currentFolderId.value || null))
      .forEach((f) => {
        list.push({
          ...f,
          isFolder: true,
          lastModified: 'Folder'
        })
      })

    // Notebooks
    allNotebooks.value
      .filter((nb) => (nb.folderId || null) === (currentFolderId.value || null))
      .forEach((nb) => {
        list.push({
          ...nb,
          isFolder: false
        })
      })

    return list
  })

  // Filtered items based on search query
  const filteredItems = computed(() => {
    const q = searchQuery.value.trim().toLowerCase()
    if (!q) return currentItems.value
    // If searching, search across all items in workspace
    const list: any[] = []
    folders.value
      .filter((f) => f.name.toLowerCase().includes(q))
      .forEach((f) => list.push({ ...f, isFolder: true, lastModified: 'Folder' }))
    allNotebooks.value
      .filter((nb) => nb.name.toLowerCase().includes(q))
      .forEach((nb) => list.push({ ...nb, isFolder: false }))
    return list
  })

  // Load data from API with localStorage fallback
  const loadData = async () => {
    try {
      const [fData, nbData] = await Promise.all([
        api.listFolders(),
        api.listNotebooks({ limit: 100 })
      ])
      folders.value = Array.isArray(fData) ? fData : []
      allNotebooks.value = Array.isArray(nbData?.items) ? nbData.items : []
    } catch (e) {
      console.error('Failed to load workspace data from backend:', e)
    }
  }

  const persistData = () => {
    localStorage.setItem(LOCAL_STORAGE_FOLDERS, JSON.stringify(folders.value))
    localStorage.setItem(LOCAL_STORAGE_NOTEBOOKS, JSON.stringify(allNotebooks.value))
  }

  const navigateToFolder = (folderId: string | null) => {
    currentFolderId.value = folderId
    searchQuery.value = ''
  }

  const handleRowClick = (row: any) => {
    if (row.isFolder) {
      navigateToFolder(row.id)
    } else {
      openNotebook(row.id)
    }
  }

  const openNotebook = (notebookId: string) => {
    router.push({
      path: '/notebook',
      query: { id: notebookId }
    })
  }

  const handleCreateCommand = (cmd: string) => {
    if (cmd === 'notebook') {
      openCreateNotebookDialog()
    } else if (cmd === 'folder') {
      newFolderName.value = ''
      folderDialog.value = true
    }
  }

  const openCreateNotebookDialog = () => {
    newNotebookName.value = ''
    newNotebookLanguage.value = 'SQL'
    destinationFolderId.value = currentFolderId.value
    notebookDialog.value = true
  }

  const handleCreateNotebook = async () => {
    const name = newNotebookName.value.trim()
    if (!name) return
    const folderId = destinationFolderId.value
    const lang = newNotebookLanguage.value

    try {
      const created = await api.createNotebook(name, folderId, lang)
      notebookDialog.value = false
      newNotebookName.value = ''
      ElMessage.success('Notebook created')
      await loadData()
      openNotebook(created.id)
    } catch (e: any) {
      console.error('Failed to create notebook:', e)
      ElMessage.error(`Failed to create notebook: ${e?.message || e}`)
    }
  }

  const handleCreateFolder = async () => {
    const name = newFolderName.value.trim()
    if (!name) return

    try {
      await api.createFolder(name, currentFolderId.value)
      folderDialog.value = false
      newFolderName.value = ''
      ElMessage.success('Folder created')
      await loadData()
    } catch (e: any) {
      console.error('Failed to create folder:', e)
      ElMessage.error(`Failed to create folder: ${e?.message || e}`)
    }
  }

  const handleItemAction = (cmd: string, row: any) => {
    if (cmd === 'rename') {
      currentItemToRename.value = row
      renameValue.value = row.name
      renameDialog.value = true
    } else if (cmd === 'clone') {
      handleCloneNotebook(row)
    } else if (cmd === 'export') {
      handleExportNotebook(row)
    } else if (cmd === 'delete') {
      handleDeleteItem(row)
    }
  }

  const handleSaveRename = async () => {
    const item = currentItemToRename.value
    if (!item) return
    const name = renameValue.value.trim()
    if (!name || name === item.name) {
      renameDialog.value = false
      return
    }

    try {
      if (item.isFolder) {
        await api.renameFolder(item.id, name, item.version || 1)
      } else {
        await api.updateNotebook(item.id, { name, version: item.version || 1 })
      }
      renameDialog.value = false
      ElMessage.success('Renamed successfully')
      await loadData()
    } catch {
      item.name = name
      persistData()
      renameDialog.value = false
      ElMessage.success('Renamed (Local)')
    }
  }

  const handleCloneNotebook = async (item: any) => {
    try {
      await api.cloneNotebook(item.id, `${item.name} (Copy)`)
      ElMessage.success('Notebook cloned')
      await loadData()
    } catch {
      const cloned = {
        ...JSON.parse(JSON.stringify(item)),
        id: 'nb-' + Date.now(),
        name: `${item.name} (Copy)`,
        lastModified: 'Just now'
      }
      allNotebooks.value.unshift(cloned)
      persistData()
      ElMessage.success('Notebook cloned (Local)')
    }
  }

  const handleExportNotebook = async (item: any) => {
    try {
      const doc = await api.exportNotebook(item.id, 'JSON')
      const blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${item.name}.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      const blob = new Blob([JSON.stringify(item, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${item.name}.json`
      a.click()
      URL.revokeObjectURL(url)
    }
  }

  const handleDeleteItem = async (row: any) => {
    try {
      await ElMessageBox.confirm(
        `Are you sure you want to delete ${row.isFolder ? 'folder' : 'notebook'} "${row.name}"?`,
        'Delete confirmation',
        { type: 'warning' }
      )
      if (row.isFolder) {
        await api.deleteFolder(row.id)
      } else {
        await api.deleteNotebook(row.id)
      }
      ElMessage.success('Deleted successfully')
      await loadData()
    } catch (e: any) {
      if (e === 'cancel') return
      if (row.isFolder) {
        folders.value = folders.value.filter((f) => f.id !== row.id)
      } else {
        allNotebooks.value = allNotebooks.value.filter((nb) => nb.id !== row.id)
      }
      persistData()
      ElMessage.success('Deleted (Local)')
    }
  }

  const handleImportNotebook = async () => {
    const name = importName.value.trim()
    const content = importContent.value.trim()
    if (!name || !content) return

    try {
      await api.importNotebook(name, content, 'JSON')
      importDialog.value = false
      ElMessage.success('Notebook imported')
      await loadData()
    } catch {
      const newNb = {
        id: 'nb-' + Date.now(),
        name,
        folderId: currentFolderId.value,
        language: 'SQL',
        version: 1,
        owner: 'admin',
        lastModified: 'Just now',
        cells: [
          {
            id: 'c-' + Date.now(),
            cellType: 'CODE',
            language: 'SQL',
            source: content,
            position: 0
          }
        ]
      }
      allNotebooks.value.unshift(newNb)
      persistData()
      importDialog.value = false
      ElMessage.success('Notebook imported (Local)')
    }
  }

  onMounted(() => {
    loadData()
  })
</script>

<style scoped lang="scss">
  .db-workspace-container {
    height: calc(100vh - 64px);
    display: flex;
    flex-direction: column;
    background: #ffffff;
    margin: -20px;
  }

  .workspace-header {
    height: 54px;
    padding: 0 20px;
    background: #fafbfc;
    border-bottom: 1px solid #e1e4e8;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;

    .header-left {
      display: flex;
      align-items: center;
      min-width: 0;

      .breadcrumb-link {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 14px;
        font-weight: 500;
        color: #24292e;
        cursor: pointer;
        transition: color 0.15s ease;

        &:hover {
          color: #ff3621;
        }

        &.root-link {
          font-weight: 600;
          color: #1a1a1a;
        }

        .bc-icon {
          color: #ff3621;
          font-size: 16px;
        }
      }
    }

    .header-right {
      display: flex;
      align-items: center;
      gap: 10px;

      .search-input {
        width: 240px;
      }

      .create-btn {
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

  .workspace-body {
    flex: 1;
    display: flex;
    overflow: hidden;
  }

  .workspace-tree-sidebar {
    width: 260px;
    border-right: 1px solid #e1e4e8;
    background: #fdfdfd;
    padding: 12px 6px;
    display: flex;
    flex-direction: column;
    overflow: hidden;

    .sidebar-title {
      font-size: 11px;
      font-weight: 700;
      color: #8c959f;
      letter-spacing: 0.5px;
      padding: 4px 10px 8px;
    }

    .tree-scroll-wrapper {
      flex: 1;
      overflow-y: auto;
      overflow-x: hidden;
    }
  }

  :deep(.workspace-el-tree) {
    background: transparent;

    .el-tree-node__content {
      height: 32px;
      border-radius: 6px;
      margin-bottom: 2px;
      padding-right: 6px;
      transition: background 0.15s ease;

      &:hover {
        background: #f6f8fa;
      }
    }

    .el-tree-node.is-current > .el-tree-node__content {
      background: #fff0ed;
      color: #ff3621;
      font-weight: 600;

      .dir-icon {
        color: #ff3621;
      }

      .count-badge {
        background: #ffe3de;
        color: #ff3621;
      }
    }

    // Element Plus marks the actual destination with `is-drop-inner`. Our custom tree-node
    // slot has no default `.el-tree-node__label`, so style the whole node explicitly.
    .el-tree-node.is-drop-inner > .el-tree-node__content {
      background: #fff0ed !important;
      box-shadow: inset 0 0 0 2px #ff3621;

      .tree-node-label,
      .dir-icon,
      .count-badge {
        color: #ff3621 !important;
      }

      .count-badge {
        background: #ffe3de;
      }
    }

    .tree-node-content {
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      min-width: 0;
      font-size: 13px;

      .node-left {
        display: flex;
        align-items: center;
        gap: 6px;
        min-width: 0;
        flex: 1;

        .dir-icon {
          font-size: 15px;
          color: #ff7043;
          flex-shrink: 0;

          &.root-icon {
            color: #ff3621;
          }
        }

        .lang-badge-mini {
          font-size: 9px;
          font-weight: 800;
          padding: 1px 4px;
          border-radius: 3px;
          line-height: 1.2;
          flex-shrink: 0;

          &.lang-sql {
            background: #e6f4ff;
            color: #0958d9;
            border: 1px solid #91caff;
          }

          &.lang-python {
            background: #fff7e6;
            color: #d46b08;
            border: 1px solid #ffd591;
          }
        }

        .tree-node-label {
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
          font-weight: 500;
          color: #24292e;
        }
      }

      .count-badge {
        font-size: 11px;
        color: #8c959f;
        background: #f0f2f5;
        padding: 1px 6px;
        border-radius: 10px;
        margin-left: 6px;
        flex-shrink: 0;
      }

      &.is-active {
        .tree-node-label {
          color: #ff3621;
          font-weight: 600;
        }
      }
    }
  }

  .workspace-main-content {
    flex: 1;
    overflow-y: auto;
    padding: 16px 20px;

    .drag-drop-hint {
      margin: 12px 2px 0;
      color: #64748b;
      font-size: 12px;
    }
  }

  :deep(.db-file-table) {
    --el-table-header-bg-color: #fafbfc;
    --el-table-row-hover-bg-color: #f8f9fa;

    .el-table__header th {
      font-size: 12px;
      font-weight: 600;
      color: #57606a;
      text-transform: uppercase;
      letter-spacing: 0.3px;
    }

    .el-table__row {
      cursor: pointer;
      height: 48px;
    }

    .el-table__row.is-drop-target-row > td.el-table__cell {
      background: #fff4f2 !important;
      border-top-color: #ff3621;
      border-bottom-color: #ff3621;
    }

    .el-table__row.is-drop-target-row > td.el-table__cell:first-child {
      border-left: 2px solid #ff3621;
    }

    .el-table__row.is-drop-target-row > td.el-table__cell:last-child {
      border-right: 2px solid #ff3621;
    }

    .name-cell {
      display: flex;
      align-items: center;
      gap: 10px;

      .folder-icon {
        font-size: 18px;
        color: #ff7043;
      }

      .lang-badge {
        font-size: 10px;
        font-weight: 800;
        padding: 2px 5px;
        border-radius: 3px;
        letter-spacing: 0.5px;

        &.lang-sql {
          background: #e6f4ff;
          color: #0958d9;
          border: 1px solid #91caff;
        }

        &.lang-python {
          background: #fff7e6;
          color: #d46b08;
          border: 1px solid #ffd591;
        }
      }

      .item-name {
        font-size: 13px;
        font-weight: 500;
        color: #24292e;

        &.notebook-name {
          &:hover {
            color: #ff3621;
            text-decoration: underline;
          }
        }

        &.folder-name {
          font-weight: 600;
        }
      }

      &.is-drop-target {
        outline: 2px solid #ff3621;
        outline-offset: 3px;
        border-radius: 4px;
        background: #fff4f2;
      }
    }

    .type-badge {
      font-size: 12px;
      color: #57606a;
    }

    .lang-text {
      font-size: 12px;
      font-weight: 500;
      color: #24292e;
    }

    .text-muted {
      font-size: 12px;
      color: #8c959f;
    }

    .row-actions {
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }
  }

  .empty-folder-state {
    padding: 60px 0;
    text-align: center;
    color: #57606a;

    .empty-icon {
      color: #d0d7de;
      margin-bottom: 12px;
    }

    .empty-title {
      font-size: 16px;
      font-weight: 600;
      color: #24292e;
      margin: 0 0 6px 0;
    }

    .empty-desc {
      font-size: 13px;
      color: #8c959f;
      margin: 0 0 16px 0;
    }

    .empty-actions {
      display: flex;
      justify-content: center;
      gap: 10px;
    }
  }
</style>
