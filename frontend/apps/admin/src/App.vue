<script setup lang="ts">
import {
  BellOutlined,
  CarOutlined,
  DesktopOutlined,
  DollarOutlined,
  InboxOutlined,
  MenuFoldOutlined,
  MenuOutlined,
  OrderedListOutlined,
  SearchOutlined,
  SettingOutlined,
  ShoppingCartOutlined,
  ShopOutlined,
  SwapOutlined,
  TagsOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

const route = useRoute()
const collapsed = ref(false)

const navItems = [
  { label: '工作台', icon: DesktopOutlined },
  { label: '订单', icon: OrderedListOutlined, to: '/orders', section: 'orders' },
  { label: '商品', icon: TagsOutlined, to: '/products', section: 'products' },
  { label: '库存', icon: InboxOutlined },
  { label: '采购', icon: ShoppingCartOutlined },
  { label: '履约', icon: CarOutlined },
  { label: '售后', icon: SwapOutlined },
  { label: '财务', icon: DollarOutlined },
  { label: '快递对账', icon: ShopOutlined },
  { label: '系统设置', icon: SettingOutlined },
]

const sectionLabel = computed(() => String(route.meta.sectionLabel ?? '工作台'))
const pageTitle = computed(() => String(route.meta.title ?? '工作台'))
</script>

<template>
  <div class="erp-shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="sidebar">
      <div class="brand" aria-label="星链 ERP">
        <span class="brand-mark">星</span>
        <strong>星链 ERP</strong>
      </div>
      <nav aria-label="主导航">
        <template v-for="item in navItems" :key="item.label">
          <RouterLink
            v-if="item.to"
            :to="item.to"
            class="nav-item"
            :class="{ active: route.meta.section === item.section }"
          >
            <component :is="item.icon" aria-hidden="true" />
            <span class="nav-label">{{ item.label }}</span>
          </RouterLink>
          <button v-else type="button" class="nav-item nav-placeholder">
            <component :is="item.icon" />
            <span class="nav-label">{{ item.label }}</span>
          </button>
        </template>
      </nav>
      <button type="button" class="collapse-button" @click="collapsed = !collapsed">
        <MenuFoldOutlined />
        <span class="collapse-label">{{ collapsed ? '展开' : '收起' }}</span>
      </button>
    </aside>

    <div class="app-frame">
      <header class="topbar">
        <div class="topbar-left">
          <button type="button" class="icon-button" aria-label="切换导航" @click="collapsed = !collapsed">
            <MenuOutlined />
          </button>
          <div class="breadcrumbs" aria-label="面包屑">
            <span>工作台</span>
            <b>/</b>
            <span>{{ sectionLabel }}</span>
            <b>/</b>
            <strong>{{ pageTitle }}</strong>
          </div>
        </div>
        <div class="topbar-actions">
          <button type="button" class="icon-button" aria-label="搜索"><SearchOutlined /></button>
          <button type="button" class="icon-button notification-button" aria-label="通知">
            <BellOutlined />
            <span class="notification-count">12</span>
          </button>
          <button type="button" class="user-menu">
            <UserOutlined />
            <span>管理员</span>
          </button>
        </div>
      </header>

      <main class="app-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
