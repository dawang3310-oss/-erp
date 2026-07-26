<script setup lang="ts">
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  ProductApiError,
  changeProductStatus,
  listProducts,
  type ProductStatus,
  type ProductSummary,
} from '../../api/products'

type LifecycleAction = {
  product: ProductSummary
  target: ProductStatus
  label: string
}

const route = useRoute()
const router = useRouter()
const keyword = ref('')
const barcode = ref('')
const brandId = ref('')
const categoryId = ref('')
const status = ref<ProductStatus | ''>('')
const page = ref(0)
const size = 20
const products = ref<ProductSummary[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const staleMessage = ref('')
const actionError = ref('')
const lifecycleAction = ref<LifecycleAction | null>(null)
const reason = ref('')
const submittingAction = ref(false)

const statusLabels: Record<ProductStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '销售中',
  DISABLED: '已停用',
  ARCHIVED: '已归档',
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const canGoPrevious = computed(() => page.value > 0 && !loading.value)
const canGoNext = computed(() => page.value + 1 < totalPages.value && !loading.value)

function queryText(value: unknown) {
  return typeof value === 'string' ? value : ''
}

function syncFromRoute() {
  keyword.value = queryText(route.query.keyword)
  barcode.value = queryText(route.query.barcode)
  brandId.value = queryText(route.query.brandId)
  categoryId.value = queryText(route.query.categoryId)
  const routeStatus = queryText(route.query.status)
  status.value = ['DRAFT', 'ACTIVE', 'DISABLED', 'ARCHIVED'].includes(routeStatus)
    ? routeStatus as ProductStatus
    : ''
  const routePage = Number(queryText(route.query.page) || 0)
  page.value = Number.isInteger(routePage) && routePage >= 0 ? routePage : 0
}

async function loadProductPage() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await listProducts({
      keyword: keyword.value,
      barcode: barcode.value,
      brandId: brandId.value,
      categoryId: categoryId.value,
      status: status.value || undefined,
      page: page.value,
      size,
    })
    products.value = result.items
    total.value = result.total
  } catch {
    products.value = []
    total.value = 0
    loadError.value = '商品加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

watch(
  () => route.fullPath,
  () => {
    syncFromRoute()
    void loadProductPage()
  },
  { immediate: true },
)

function currentQuery(targetPage: number) {
  return {
    ...(keyword.value.trim() ? { keyword: keyword.value.trim() } : {}),
    ...(barcode.value.trim() ? { barcode: barcode.value.trim() } : {}),
    ...(brandId.value.trim() ? { brandId: brandId.value.trim() } : {}),
    ...(categoryId.value.trim() ? { categoryId: categoryId.value.trim() } : {}),
    ...(status.value ? { status: status.value } : {}),
    ...(targetPage > 0 ? { page: String(targetPage) } : {}),
  }
}

async function navigateToQuery(targetPage: number) {
  const target = router.resolve({ path: '/products', query: currentQuery(targetPage) })
  if (target.fullPath === route.fullPath) {
    page.value = targetPage
    await loadProductPage()
    return
  }
  await router.push(target)
}

function applyFilters() {
  void navigateToQuery(0)
}

function resetFilters() {
  keyword.value = ''
  barcode.value = ''
  brandId.value = ''
  categoryId.value = ''
  status.value = ''
  void navigateToQuery(0)
}

function goToPage(targetPage: number) {
  if (targetPage < 0 || targetPage >= totalPages.value) {
    return
  }
  void navigateToQuery(targetPage)
}

function actionsFor(product: ProductSummary) {
  if (product.status === 'DRAFT') {
    return [
      { target: 'ACTIVE' as const, label: '上架' },
      { target: 'ARCHIVED' as const, label: '归档' },
    ]
  }
  if (product.status === 'ACTIVE') {
    return [
      { target: 'DISABLED' as const, label: '停用' },
      { target: 'ARCHIVED' as const, label: '归档' },
    ]
  }
  if (product.status === 'DISABLED') {
    return [
      { target: 'ACTIVE' as const, label: '启用' },
      { target: 'ARCHIVED' as const, label: '归档' },
    ]
  }
  return []
}

function openLifecycle(product: ProductSummary, target: ProductStatus, label: string) {
  lifecycleAction.value = { product, target, label }
  reason.value = ''
  actionError.value = ''
}

function closeLifecycle() {
  lifecycleAction.value = null
  reason.value = ''
}

async function submitLifecycle() {
  const action = lifecycleAction.value
  if (!action || !reason.value.trim()) {
    return
  }
  submittingAction.value = true
  actionError.value = ''
  try {
    await changeProductStatus(action.product.id, {
      status: action.target,
      version: action.product.version,
      reason: reason.value.trim(),
    })
    closeLifecycle()
    await loadProductPage()
  } catch (error) {
    closeLifecycle()
    if (error instanceof ProductApiError && error.status === 409) {
      staleMessage.value = error.message
    } else {
      actionError.value = '商品状态更新失败，请稍后重试'
    }
  } finally {
    submittingAction.value = false
  }
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value)).replace(/\//g, '-')
}
</script>

<template>
  <section class="page-panel product-list-page" aria-labelledby="products-title">
    <header class="page-heading page-heading-with-action">
      <div>
        <h1 id="products-title">商品列表</h1>
        <p>统一管理 SPU、SKU、状态及渠道映射。</p>
      </div>
      <div class="heading-actions">
        <RouterLink class="secondary-button" to="/products/imports">导入商品</RouterLink>
        <RouterLink class="primary-button" to="/products/new">新建商品</RouterLink>
      </div>
    </header>

    <form class="filter-panel product-filter-panel" @submit.prevent="applyFilters">
      <div class="product-filter-grid">
        <label class="stacked-field">
          <span>SKU/商品名称</span>
          <input v-model="keyword" placeholder="输入 SKU、SPU 或商品名称" />
        </label>
        <label class="stacked-field">
          <span>条码</span>
          <input v-model="barcode" placeholder="输入商品条码" />
        </label>
        <label class="stacked-field">
          <span>品牌ID</span>
          <input v-model="brandId" placeholder="输入品牌 ID" />
        </label>
        <label class="stacked-field">
          <span>类目ID</span>
          <input v-model="categoryId" placeholder="输入类目 ID" />
        </label>
        <label class="stacked-field">
          <span>商品状态</span>
          <select v-model="status">
            <option value="">全部状态</option>
            <option value="DRAFT">草稿</option>
            <option value="ACTIVE">销售中</option>
            <option value="DISABLED">已停用</option>
            <option value="ARCHIVED">已归档</option>
          </select>
        </label>
      </div>
      <div class="product-filter-actions">
        <button type="submit" class="primary-button"><SearchOutlined aria-hidden="true" />查询</button>
        <button type="button" class="secondary-button" @click="resetFilters">
          <ReloadOutlined aria-hidden="true" />重置
        </button>
      </div>
    </form>

    <div v-if="staleMessage" class="inline-notice warning-notice" role="alert">
      <span>{{ staleMessage }}，请刷新列表后重试。</span>
      <button type="button" class="text-button" @click="staleMessage = ''; loadProductPage()">
        刷新列表
      </button>
    </div>
    <div v-if="actionError" class="inline-notice error-state" role="alert">
      {{ actionError }}
    </div>

    <div v-if="loading" class="table-state">正在加载商品…</div>
    <div v-else-if="loadError" class="table-state error-state" role="alert">
      <span>{{ loadError }}</span>
      <button type="button" class="text-button" @click="loadProductPage">重新加载</button>
    </div>
    <div v-else class="table-wrap product-table-wrap">
      <table class="products-table">
        <thead>
          <tr>
            <th>商品</th>
            <th>品牌 / 类目</th>
            <th>SKU</th>
            <th>渠道映射</th>
            <th>状态</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="product in products" :key="product.id">
            <td data-label="商品">
              <RouterLink
                class="product-name-link"
                :to="`/products/${product.id}`"
                :aria-label="`查看 ${product.spuCode}`"
              >
                {{ product.name }}
              </RouterLink>
              <small>{{ product.spuCode }}</small>
            </td>
            <td data-label="品牌 / 类目">
              {{ product.brandName || '未设置品牌' }}
              <small>{{ product.categoryName || '未设置类目' }}</small>
            </td>
            <td data-label="SKU">{{ product.skuCount }} 个</td>
            <td data-label="渠道映射">{{ product.channelMappingCount }} 个</td>
            <td data-label="状态">
              <span class="status-tag" :class="product.status.toLowerCase()">
                {{ statusLabels[product.status] }}
              </span>
            </td>
            <td data-label="更新时间" class="date-cell">{{ formatTime(product.updatedAt) }}</td>
            <td data-label="操作">
              <div class="product-row-actions">
                <RouterLink
                  class="text-link"
                  :to="`/products/${product.id}/edit`"
                  :aria-label="`编辑 ${product.spuCode}`"
                >
                  编辑
                </RouterLink>
                <button
                  v-for="action in actionsFor(product)"
                  :key="action.target"
                  type="button"
                  class="text-button"
                  :class="{ danger: action.target === 'ARCHIVED' }"
                  :aria-label="`${action.label} ${product.spuCode}`"
                  @click="openLifecycle(product, action.target, action.label)"
                >
                  {{ action.label }}
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!products.length">
            <td colspan="7" class="empty-cell">没有符合条件的商品</td>
          </tr>
        </tbody>
      </table>
      <footer class="table-footer">
        <span>共 {{ total }} 条</span>
        <span>第 {{ page + 1 }} / {{ totalPages }} 页</span>
        <button
          type="button"
          aria-label="上一页"
          :disabled="!canGoPrevious"
          @click="goToPage(page - 1)"
        >
          ‹
        </button>
        <button type="button" class="current-page" disabled>{{ page + 1 }}</button>
        <button
          type="button"
          aria-label="下一页"
          :disabled="!canGoNext"
          @click="goToPage(page + 1)"
        >
          ›
        </button>
      </footer>
    </div>

    <div
      v-if="lifecycleAction"
      class="modal-backdrop"
      role="presentation"
      @click.self="closeLifecycle"
    >
      <form
        class="confirm-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="`${lifecycleAction.label}商品`"
        @submit.prevent="submitLifecycle"
      >
        <h2>{{ lifecycleAction.label }}商品</h2>
        <p>
          {{ lifecycleAction.product.name }}（{{ lifecycleAction.product.spuCode }}）
        </p>
        <label class="stacked-field">
          <span>操作原因</span>
          <textarea v-model="reason" rows="3" placeholder="请填写操作原因"></textarea>
        </label>
        <div class="dialog-actions">
          <button type="button" class="secondary-button" @click="closeLifecycle">取消</button>
          <button
            type="submit"
            class="primary-button"
            :disabled="!reason.trim() || submittingAction"
          >
            {{ submittingAction ? '正在提交…' : `确认${lifecycleAction.label}` }}
          </button>
        </div>
      </form>
    </div>
  </section>
</template>
