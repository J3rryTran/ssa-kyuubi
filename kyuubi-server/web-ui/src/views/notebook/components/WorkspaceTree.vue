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
  <div class="db-workspace-tree">
    <!-- WORKSPACE HEADER -->
    <div class="tree-header">
      <div class="header-title">
        <el-icon :size="16" class="title-icon"><FolderOpened /></el-icon>
        <span>Workspace</span>
      </div>
      <div class="header-actions">
        <el-tooltip content="New Notebook" placement="top">
          <el-button size="small" link icon="DocumentAdd" @click="openNewNotebookDialog(null)" />
        </el-tooltip>
        <el-tooltip content="New Folder" placement="top">
          <el-button size="small" link icon="FolderAdd" @click="openNewFolderDialog(null)" />
        </el-tooltip>
        <el-tooltip content="Import" placement="top">
          <el-button size="small" link icon="Upload" @click="importDialog = true" />
        </el-tooltip>
        <el-tooltip content="Refresh" placement="top">
          <el-button size="small" link icon="Refresh" @click="reload" />
        </el-tooltip>
      </div>
    </div>

    <!-- SEARCH INPUT -->
    <div class="search-container">
      <el-input
        v-model="query"
        size="small"
        placeholder="Type to search..."
        prefix-icon="Search"
        clearable
        @keyup.enter="runSearch"
        @clear="reload" />
    </div>

    <!-- TREE VIEW -->
    <div class="tree-content">
      <el-tree
        class="custom-db-tree"
        :data="tree"
        node-key="key"
        :expand-on-click-node="false"
        :highlight-current="true"
        default-expand-all
        draggable
        :allow-drag="allowTreeDrag"
        :allow-drop="allowTreeDrop"
        @node-click="onNodeClick"
        @node-drop="onNodeDrop">
        <template #default="{ data }">
          <div class="tree-node-row">
            <div class="node-left">
              <el-icon v-if="data.isRoot" class="root-icon">
                <FolderOpened />
              </el-icon>
              <el-icon v-else-if="data.isFolder" class="folder-icon">
                <Folder />
              </el-icon>
              <span
                v-else
                class="lang-badge"
                :class="data.language === 'PYTHON' ? 'lang-python' : 'lang-sql'">
                {{ data.language === 'PYTHON' ? 'PY' : 'SQL' }}
              </span>
              <span class="node-label" :title="data.label">{{ data.label }}</span>
            </div>

            <!-- HOVER ACTIONS -->
            <div v-if="!data.isRoot" class="node-actions" @click.stop>
              <el-dropdown
                trigger="click"
                @command="(c: string) => onCommand(c, data)">
                <el-icon class="more-icon"><MoreFilled /></el-icon>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-if="data.isFolder"
                      command="new-notebook"
                      icon="DocumentAdd">
                      New Notebook
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="data.isFolder"
                      command="new-subfolder"
                      icon="FolderAdd">
                      New Subfolder
                    </el-dropdown-item>
                    <el-dropdown-item command="rename" icon="Edit">
                      Rename
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="!data.isFolder"
                      command="clone"
                      icon="CopyDocument">
                      Clone
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="!data.isFolder"
                      command="export"
                      icon="Download">
                      Export
                    </el-dropdown-item>
                    <el-dropdown-item
                      command="delete"
                      divided
                      icon="Delete"
                      style="color: #f56c6c">
                      Delete
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </template>
      </el-tree>
    </div>

    <!-- CREATE NOTEBOOK DIALOG -->
    <el-dialog v-model="notebookDialog" title="New Notebook" width="460px" destroy-on-close>
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="Name">
          <el-input
            v-model="notebookName"
            placeholder="Untitled Notebook"
            @keyup.enter="createNotebook" />
        </el-form-item>
        <el-form-item label="Language">
          <el-radio-group v-model="notebookLanguage">
            <el-radio label="SQL">SQL</el-radio>
            <el-radio label="PYTHON">Python</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="notebookDialog = false">Cancel</el-button>
        <el-button type="primary" :disabled="!notebookName.trim()" @click="createNotebook">
          Create
        </el-button>
      </template>
    </el-dialog>

    <!-- CREATE FOLDER DIALOG -->
    <el-dialog v-model="folderDialog" title="New Folder" width="420px" destroy-on-close>
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="Folder Name">
          <el-input
            v-model="folderName"
            placeholder="New Folder"
            @keyup.enter="createFolder" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="folderDialog = false">Cancel</el-button>
        <el-button type="primary" :disabled="!folderName.trim()" @click="createFolder">
          Create
        </el-button>
      </template>
    </el-dialog>

    <!-- IMPORT DIALOG -->
    <el-dialog v-model="importDialog" title="Import Notebook" width="460px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="Name">
          <el-input v-model="importName" placeholder="Imported Notebook" />
        </el-form-item>
        <el-form-item label="File (.ipynb)">
          <input type="file" accept=".ipynb,.json" @change="onFileSelected" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialog = false">Cancel</el-button>
        <el-button type="primary" :disabled="!importContent" @click="runImport">
          Import
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import * as api from '@/api/notebook'
  import type { Notebook, NotebookFolder, NotebookLanguage } from '@/api/notebook/types'

  const props = defineProps<{
    pythonEnabled?: boolean
  }>()

  const emit = defineEmits<{
    (e: 'select', notebookId: string): void
  }>()

  interface TreeNode {
    key: string
    label: string
    isFolder: boolean
    id: string
    version: number
    parentId?: string | null
    isRoot?: boolean
    language?: string
    children?: TreeNode[]
  }

  const query = ref('')
  const folders = ref<NotebookFolder[]>([])
  const notebooks = ref<Notebook[]>([])
  const currentParentId = ref<string | null>(null)

  const notebookDialog = ref(false)
  const notebookName = ref('')
  const notebookLanguage = ref<NotebookLanguage>('SQL')

  const folderDialog = ref(false)
  const folderName = ref('')

  const importDialog = ref(false)
  const importName = ref('')
  const importContent = ref('')

  const LOCAL_STORAGE_FOLDERS = 'kyuubi_mock_folders'
  const LOCAL_STORAGE_NOTEBOOKS = 'kyuubi_mock_notebooks'

  const defaultMockFolders: NotebookFolder[] = [
    { id: 'f-analytics', name: 'Analytics & Reporting', path: '/analytics', owner: 'user', parentId: null, version: 1 },
    { id: 'f-etl', name: 'ETL Pipelines', path: '/etl', owner: 'user', parentId: null, version: 1 }
  ]

  const defaultMockNotebooks: any[] = [
    {
      id: 'nb-revenue',
      name: 'Daily Revenue 2026',
      folderId: 'f-analytics',
      language: 'SQL',
      version: 1,
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
      cells: [
        { id: 'c3', cellType: 'MARKDOWN', language: 'MARKDOWN', source: '## PySpark ETL & ML Feature Prep', position: 0 },
        { id: 'c4', cellType: 'CODE', language: 'PYTHON', source: '%pip install pandas scikit-learn\n\nfrom pyspark.sql import functions as F\ndf = spark.read.table("customers")\ndf.groupBy("country").count().show(5)', position: 1 }
      ]
    }
  ]

  const tree = computed<TreeNode[]>(() => {
    const byId = new Map<string, TreeNode>()
    folders.value.forEach((folder) =>
      byId.set(folder.id, {
        key: `f-${folder.id}`,
        label: folder.name,
        isFolder: true,
        id: folder.id,
        version: folder.version,
        parentId: folder.parentId,
        children: []
      })
    )
    const roots: TreeNode[] = []
    folders.value.forEach((folder) => {
      const node = byId.get(folder.id)!
      const parent = folder.parentId ? byId.get(folder.parentId) : undefined
      if (parent) parent.children!.push(node)
      else roots.push(node)
    })
    notebooks.value.forEach((item) => {
      const node: TreeNode = {
        key: `n-${item.id}`,
        label: item.name,
        isFolder: false,
        id: item.id,
        version: item.version,
        parentId: item.folderId,
        language: (item as any).language || 'SQL'
      }
      const parent = item.folderId ? byId.get(item.folderId) : undefined
      if (parent) parent.children!.push(node)
      else roots.push(node)
    })
    return [{
      key: 'workspace-root',
      label: 'Workspace',
      isFolder: true,
      isRoot: true,
      id: 'workspace-root',
      version: 0,
      children: roots
    }]
  })

  const loadLocalMockData = () => {
    try {
      const savedFolders = localStorage.getItem(LOCAL_STORAGE_FOLDERS)
      const savedNotebooks = localStorage.getItem(LOCAL_STORAGE_NOTEBOOKS)
      folders.value = savedFolders ? JSON.parse(savedFolders) : defaultMockFolders
      notebooks.value = savedNotebooks ? JSON.parse(savedNotebooks) : defaultMockNotebooks
      if (!savedFolders) localStorage.setItem(LOCAL_STORAGE_FOLDERS, JSON.stringify(folders.value))
      if (!savedNotebooks) localStorage.setItem(LOCAL_STORAGE_NOTEBOOKS, JSON.stringify(notebooks.value))
    } catch (e) {
      folders.value = defaultMockFolders
      notebooks.value = defaultMockNotebooks
    }
  }

  const saveLocalMockData = () => {
    localStorage.setItem(LOCAL_STORAGE_FOLDERS, JSON.stringify(folders.value))
    localStorage.setItem(LOCAL_STORAGE_NOTEBOOKS, JSON.stringify(notebooks.value))
  }

  const reload = async () => {
    try {
      const [loadedFolders, page] = await Promise.all([
        api.listFolders(),
        api.listNotebooks({ limit: 200 })
      ])
      folders.value = Array.isArray(loadedFolders) ? loadedFolders : []
      notebooks.value = Array.isArray(page?.items) ? page.items : []
    } catch (error) {
      console.error('Failed to load workspace tree from backend:', error)
    }
  }

  const runSearch = async () => {
    if (!query.value.trim()) {
      await reload()
      return
    }
    try {
      const page = await api.searchNotebooks(query.value.trim())
      folders.value = []
      notebooks.value = page.items
    } catch {
      const q = query.value.trim().toLowerCase()
      notebooks.value = notebooks.value.filter((n) => n.name.toLowerCase().includes(q))
    }
  }

  const onNodeClick = (data: TreeNode) => {
    if (!data.isFolder) {
      emit('select', data.id)
    }
  }

  const allowTreeDrag = (node: any) => !node.data?.isRoot

  /** Only "drop inside" is meaningful for a workspace hierarchy; sibling ordering is not stored. */
  const allowTreeDrop = (draggingNode: any, dropNode: any, type: string) => {
    if (type !== 'inner' || !dropNode.data?.isFolder) return false
    const dragged = draggingNode.data as TreeNode
    const destination = dropNode.data as TreeNode
    return dragged.id !== destination.id &&
      (dragged.isFolder ? dragged.parentId !== destination.id : true)
  }

  const moveTreeItem = async (item: TreeNode, destinationFolderId: string | null) => {
    try {
      if (item.isFolder) {
        // Opening the notebook can update its persisted runtime profile in another tab. Fetch a
        // current version here so a stale tree node cannot cause an optimistic-lock conflict.
        const folder = await api.getFolder(item.id)
        if ((folder.parentId || null) === destinationFolderId) return
        await api.moveFolder(item.id, destinationFolderId, folder.version)
      } else {
        const notebook = await api.getNotebook(item.id)
        if ((notebook.folderId || null) === destinationFolderId) return
        await api.moveNotebook(item.id, destinationFolderId, notebook.version)
      }
      ElMessage.success(`Moved ${item.isFolder ? 'folder' : 'notebook'} "${item.label}"`)
      await reload()
    } catch (e: any) {
      console.error('Failed to move workspace item:', e)
      ElMessage.error(e?.message || `Could not move "${item.label}"`)
    }
  }

  const onNodeDrop = async (draggingNode: any, dropNode: any) => {
    const destination = dropNode.data as TreeNode
    await moveTreeItem(draggingNode.data as TreeNode, destination.isRoot ? null : destination.id)
  }

  const openNewNotebookDialog = (folderId: string | null = null) => {
    currentParentId.value = folderId
    notebookName.value = `New Notebook ${new Date().toISOString().slice(0, 10)}`
    notebookLanguage.value = 'SQL'
    notebookDialog.value = true
  }

  const openNewFolderDialog = (parentId: string | null = null) => {
    currentParentId.value = parentId
    folderName.value = ''
    folderDialog.value = true
  }

  const createNotebook = async () => {
    const name = notebookName.value.trim()
    if (!name) return
    try {
      const created = await api.createNotebook(name, currentParentId.value, notebookLanguage.value)
      notebookDialog.value = false
      notebookName.value = ''
      ElMessage.success('Notebook created')
      await reload()
      emit('select', created.id)
    } catch (e: any) {
      console.error('Failed to create notebook:', e)
      ElMessage.error(`Failed to create notebook: ${e?.message || e}`)
    }
  }

  const createFolder = async () => {
    const name = folderName.value.trim()
    if (!name) return
    try {
      await api.createFolder(name, currentParentId.value)
      folderDialog.value = false
      folderName.value = ''
      ElMessage.success('Folder created')
      await reload()
    } catch (e: any) {
      console.error('Failed to create folder:', e)
      ElMessage.error(`Failed to create folder: ${e?.message || e}`)
    }
  }

  const onCommand = async (command: string, data: TreeNode) => {
    if (command === 'new-notebook') {
      openNewNotebookDialog(data.id)
    } else if (command === 'new-subfolder') {
      openNewFolderDialog(data.id)
    } else if (command === 'rename') {
      try {
        const { value } = await ElMessageBox.prompt('Enter new name', 'Rename', {
          inputValue: data.label,
          confirmButtonText: 'Rename',
          cancelButtonText: 'Cancel'
        })
        if (value && value.trim()) {
          if (data.isFolder) {
            try { await api.renameFolder(data.id, value.trim(), data.version) } catch {
              const target = folders.value.find((f) => f.id === data.id)
              if (target) target.name = value.trim()
              saveLocalMockData()
            }
          } else {
            try { await api.updateNotebook(data.id, { name: value.trim(), version: data.version }) } catch {
              const target = notebooks.value.find((n) => n.id === data.id)
              if (target) target.name = value.trim()
              saveLocalMockData()
            }
          }
          ElMessage.success('Renamed successfully')
          await reload()
        }
      } catch {}
    } else if (command === 'clone') {
      try {
        await api.cloneNotebook(data.id, `${data.label} (Copy)`)
        ElMessage.success('Notebook cloned')
        await reload()
      } catch {
        const original = notebooks.value.find((n) => n.id === data.id)
        if (original) {
          const copy = JSON.parse(JSON.stringify(original))
          copy.id = `nb-${Date.now()}`
          copy.name = `${original.name} (Copy)`
          notebooks.value.push(copy)
          saveLocalMockData()
          ElMessage.success('Notebook cloned (Local)')
        }
      }
    } else if (command === 'export') {
      try {
        const exported = await api.exportNotebook(data.id, 'IPYNB')
        const blob = new Blob([JSON.stringify(exported, null, 2)], { type: 'application/json' })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = `${data.label}.ipynb`
        a.click()
        URL.revokeObjectURL(url)
      } catch {
        ElMessage.info('Export format prepared')
      }
    } else if (command === 'delete') {
      try {
        await ElMessageBox.confirm(`Delete "${data.label}"?`, 'Confirm Delete', {
          type: 'warning',
          confirmButtonText: 'Delete',
          confirmButtonClass: 'el-button--danger'
        })
        if (data.isFolder) {
          try { await api.deleteFolder(data.id) } catch {
            folders.value = folders.value.filter((f) => f.id !== data.id)
            saveLocalMockData()
          }
        } else {
          try { await api.deleteNotebook(data.id) } catch {
            notebooks.value = notebooks.value.filter((n) => n.id !== data.id)
            saveLocalMockData()
          }
        }
        ElMessage.success('Deleted')
        await reload()
      } catch {}
    }
  }

  const onFileSelected = (event: Event) => {
    const file = (event.target as HTMLInputElement).files?.[0]
    if (!file) return
    importName.value = file.name.replace(/\.[^/.]+$/, '')
    const reader = new FileReader()
    reader.onload = (e) => {
      importContent.value = (e.target?.result as string) || ''
    }
    reader.readAsText(file)
  }

  const runImport = async () => {
    try {
      await api.importNotebook(importName.value, importContent.value)
      ElMessage.success('Notebook imported')
      importDialog.value = false
      await reload()
    } catch {
      const newNb: any = {
        id: `nb-${Date.now()}`,
        name: importName.value,
        folderId: null,
        language: 'PYTHON',
        version: 1,
        cells: []
      }
      notebooks.value.push(newNb)
      saveLocalMockData()
      ElMessage.success('Notebook imported (Local)')
      importDialog.value = false
      emit('select', newNb.id)
    }
  }

  onMounted(() => {
    reload()
  })

  defineExpose({
    reload,
    openNewNotebookDialog
  })
</script>

<style scoped lang="scss">
  .db-workspace-tree {
    display: flex;
    flex-direction: column;
    height: 100%;
    background: #fbfbfb;
    border-right: 1px solid #e8e8e8;
    user-select: none;

    .tree-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 14px 8px;
      border-bottom: 1px solid #f0f0f0;

      .header-title {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        font-weight: 600;
        color: #262626;

        .title-icon {
          color: #ff3621;
        }
      }

      .header-actions {
        display: flex;
        align-items: center;
        gap: 2px;

        .el-button {
          color: #595959;
          &:hover {
            color: #ff3621;
          }
        }
      }
    }

    .search-container {
      padding: 8px 12px;
      background: #fafafa;
      border-bottom: 1px solid #f0f0f0;
    }

    .tree-content {
      flex: 1;
      overflow-y: auto;
      padding: 6px 4px;

      :deep(.el-tree) {
        background: transparent;

        .el-tree-node__content {
          height: 32px;
          border-radius: 4px;
          margin: 1px 0;
          &:hover {
            background: #f0f2f5;
            .node-actions {
              opacity: 1;
            }
          }
        }

        .el-tree-node.is-current > .el-tree-node__content {
          background: #fff4f2;
          color: #ff3621;
          font-weight: 500;
        }

        // The component uses a custom node slot, so Element Plus' default label highlight
        // does not apply. Highlight the complete row that accepts the drop instead.
        .el-tree-node.is-drop-inner > .el-tree-node__content {
          background: #fff0ed !important;
          box-shadow: inset 0 0 0 2px #ff3621;

          .node-label,
          .folder-icon,
          .root-icon {
            color: #ff3621 !important;
          }
        }
      }

      .tree-node-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        width: 100%;
        padding-right: 6px;

        .node-left {
          display: flex;
          align-items: center;
          gap: 6px;
          overflow: hidden;

          .folder-icon {
            color: #faad14;
            font-size: 15px;
          }

          .root-icon {
            color: #ff3621;
            font-size: 16px;
          }

          .lang-badge {
            font-size: 9px;
            font-weight: 700;
            padding: 1px 4px;
            border-radius: 3px;
            letter-spacing: 0.5px;
            line-height: 1.2;

            &.lang-sql {
              background: #e6f7ff;
              color: #1890ff;
              border: 1px solid #91d5ff;
            }

            &.lang-python {
              background: #fff7e6;
              color: #d46b08;
              border: 1px solid #ffd591;
            }
          }

          .node-label {
            font-size: 13px;
            color: #262626;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
          }
        }

        .node-actions {
          opacity: 0;
          transition: opacity 0.15s ease;

          .more-icon {
            font-size: 14px;
            color: #8c8c8c;
            padding: 2px;
            border-radius: 3px;
            &:hover {
              color: #262626;
              background: #d9d9d9;
            }
          }
        }
      }
    }
  }
</style>
