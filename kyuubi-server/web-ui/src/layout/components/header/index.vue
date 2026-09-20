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
  <div class="header-container">
    <div class="left-container">
      <div class="toggle-btn" @click="_changeCollapse">
        <el-icon :size="18">
          <component :is="isCollapse ? 'Expand' : 'Fold'" />
        </el-icon>
      </div>
      <div class="workspace-breadcrumb">
        <span class="workspace-label">VTNexus</span>
        <span class="divider">/</span>
        <span class="page-title">{{ currentPageTitle }}</span>
      </div>
    </div>
    <div class="right-container">
      <template v-if="authStore.isAuthenticated">
        <el-dropdown trigger="click">
          <div class="user-chip">
            <span class="user-avatar">{{ userInitial }}</span>
            <span class="user-name">{{ authStore.user }}</span>
            <el-icon class="el-icon--right"><arrow-down /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="handleLogout">Sign out</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
      <el-button v-else type="primary" size="small" class="sign-in-btn" @click="showLoginModal">Sign in</el-button>
      <el-dropdown trigger="click" @command="handleClick">
        <span class="locale-btn">
          {{ currentLocale }}
          <el-icon class="el-icon--right"><arrow-down /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="(locale, key) in locales"
              :key="key"
              :command="locale.key">
              {{ locale.label }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { useStore } from '@/pinia/layout'
  import { storeToRefs } from 'pinia'
  import { useLocales } from './use-locales'
  import { LOCALES } from './types'
  import { reactive, computed } from 'vue'
  import { useRoute } from 'vue-router'
  import { useAuthStore } from '@/pinia/auth/auth'

  const route = useRoute()
  const locales = reactive(LOCALES)
  const { changeLocale, currentLocale } = useLocales()
  const store = useStore()
  const { isCollapse } = storeToRefs(store)
  const { changeCollapse } = store

  const currentPageTitle = computed(() => {
    const path = route.path
    if (path.includes('/workspace')) return 'Workspace'
    if (path.includes('/notebook')) return 'Notebook'
    if (path.includes('/editor')) return 'SQL Editor'
    if (path.includes('/management/engine')) return 'Compute / Engines'
    if (path.includes('/management/session')) return 'Sessions'
    if (path.includes('/management/server')) return 'Cluster Instances'
    return 'Dashboard'
  })

  const userInitial = computed(() => {
    return (authStore.user || 'U').charAt(0).toUpperCase()
  })

  function _changeCollapse() {
    changeCollapse()
  }

  function handleClick(command: string) {
    changeLocale(command)
  }

  const authStore = useAuthStore()
  const handleLogout = () => {
    authStore.logout()
  }

  const showLoginModal = () => {
    window.dispatchEvent(new CustomEvent('auth-required'))
  }
</script>

<style lang="scss" scoped>
  .header-container {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
    height: 100%;
  }

  .left-container {
    display: flex;
    align-items: center;
    gap: 12px;

    .toggle-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      margin-left: 14px;
      border-radius: 6px;
      cursor: pointer;
      color: #64748b;
      transition: all 0.15s ease;

      &:hover {
        background: #f1f5f9;
        color: #0f172a;
      }
    }

    .workspace-breadcrumb {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;

      .workspace-label {
        font-weight: 500;
        color: #64748b;
      }

      .divider {
        color: #cbd5e1;
      }

      .page-title {
        font-weight: 600;
        color: #0f172a;
      }
    }
  }

  .right-container {
    display: flex;
    align-items: center;
    gap: 12px;

    .user-chip {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 4px 10px;
      border-radius: 20px;
      background: #f8fafc;
      border: 1px solid #e2e8f0;
      cursor: pointer;
      font-size: 13px;
      color: #334155;
      transition: all 0.15s ease;

      &:hover {
        background: #f1f5f9;
        border-color: #cbd5e1;
      }

      .user-avatar {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 22px;
        height: 22px;
        border-radius: 50%;
        background: #ff3621;
        color: #ffffff;
        font-size: 11px;
        font-weight: 600;
      }

      .user-name {
        font-weight: 500;
        max-width: 140px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .locale-btn {
      font-size: 12px;
      color: #64748b;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 4px 8px;
      border-radius: 4px;

      &:hover {
        color: #0f172a;
        background: #f1f5f9;
      }
    }

    .sign-in-btn {
      background: #ff3621;
      border-color: #ff3621;
      font-weight: 500;
    }
  }
</style>
