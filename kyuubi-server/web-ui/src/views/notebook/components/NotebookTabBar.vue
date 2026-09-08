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
  <div class="db-tab-bar">
    <div class="tab-list">
      <div
        v-for="tab in tabs"
        :key="tab.id"
        class="db-tab-item"
        :class="{ active: tab.id === activeTabId }"
        @click="$emit('select-tab', tab.id)">
        <span
          class="tab-lang-badge"
          :class="tab.language === 'PYTHON' ? 'badge-python' : 'badge-sql'">
          {{ tab.language === 'PYTHON' ? 'PY' : 'SQL' }}
        </span>
        <span class="tab-title" :title="tab.name">{{ tab.name }}</span>
        <el-icon
          class="tab-close-icon"
          @click.stop="$emit('close-tab', tab.id)">
          <Close />
        </el-icon>
      </div>
    </div>
    <div class="tab-add-btn" title="Create New Notebook" @click="$emit('new-tab')">
      <el-icon><Plus /></el-icon>
    </div>
  </div>
</template>

<script setup lang="ts">
  export interface NotebookTab {
    id: string
    name: string
    language?: string
  }

  defineProps<{
    tabs: NotebookTab[]
    activeTabId: string | null
  }>()

  defineEmits<{
    (e: 'select-tab', tabId: string): void
    (e: 'close-tab', tabId: string): void
    (e: 'new-tab'): void
  }>()
</script>

<style scoped lang="scss">
  .db-tab-bar {
    display: flex;
    align-items: center;
    background: #f0f2f5;
    border-bottom: 1px solid #d9d9d9;
    height: 38px;
    padding: 0 8px;
    overflow-x: auto;
    user-select: none;

    .tab-list {
      display: flex;
      align-items: flex-end;
      height: 100%;
      gap: 3px;
    }

    .db-tab-item {
      display: flex;
      align-items: center;
      gap: 6px;
      height: 32px;
      padding: 0 12px;
      background: #e6e9ed;
      border: 1px solid #d9d9d9;
      border-bottom: none;
      border-radius: 6px 6px 0 0;
      font-size: 12px;
      color: #595959;
      cursor: pointer;
      max-width: 220px;
      transition: all 0.15s ease;

      &:hover {
        background: #f5f5f5;
        color: #262626;
      }

      &.active {
        background: #ffffff;
        color: #ff3621;
        font-weight: 500;
        border-color: #d9d9d9;
        height: 34px;
        box-shadow: 0 -2px 5px rgba(0, 0, 0, 0.03);

        .tab-close-icon {
          opacity: 0.8;
        }
      }

      .tab-lang-badge {
        font-size: 8px;
        font-weight: 700;
        padding: 0 3px;
        border-radius: 2px;
        line-height: 1.2;

        &.badge-sql {
          background: #e6f7ff;
          color: #1890ff;
        }
        &.badge-python {
          background: #fff7e6;
          color: #d46b08;
        }
      }

      .tab-title {
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .tab-close-icon {
        font-size: 11px;
        padding: 2px;
        border-radius: 50%;
        opacity: 0.5;
        transition: all 0.15s ease;

        &:hover {
          background: #ffccc7;
          color: #ff4d4f;
          opacity: 1;
        }
      }
    }

    .tab-add-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 26px;
      height: 26px;
      margin-left: 6px;
      border-radius: 4px;
      color: #595959;
      cursor: pointer;
      transition: background 0.15s ease;

      &:hover {
        background: #d9d9d9;
        color: #262626;
      }
    }
  }
</style>
