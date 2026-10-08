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
  <header>
    <img v-if="!isCollapse" class="main-logo" src="@/assets/images/vtnexus-logo.svg" alt="VTNexus" />
    <img v-else class="collapsed-logo" src="@/assets/images/vtnexus-icon.svg" alt="VTNexus" />
  </header>
  <c-menu :is-collapse="isCollapse" :active-path="activePath" :menus="menus" />
</template>

<script setup lang="ts">
  import { ref, reactive, watch } from 'vue'
  import { useStore } from '@/pinia/layout'
  import { storeToRefs } from 'pinia'
  import { useRoute } from 'vue-router'
  import { MENUS } from './types'
  import cMenu from '@/components/menu/index.vue'

  const menus = reactive(MENUS)
  const store = useStore()
  const { isCollapse } = storeToRefs(store)
  const route = useRoute()
  const activePath = ref(route.path)
  const version = import.meta.env.VITE_APP_VERSION

  watch(
    () => route.path,
    (val) => {
      activePath.value = val
    }
  )
</script>

<style lang="scss" scoped>
  header {
    width: 100%;
    height: 56px;
    padding: 0 16px;
    display: flex;
    align-items: center;
    box-sizing: border-box;
    border-bottom: 1px solid #e2e8f0;
    background: #ffffff;

    img.main-logo {
      height: 32px;
      width: auto;
      object-fit: contain;
    }

    img.collapsed-logo {
      width: 28px;
      height: 28px;
      margin: 0 auto;
    }
  }
  .el-menu {
    margin-top: 0;
  }
</style>
