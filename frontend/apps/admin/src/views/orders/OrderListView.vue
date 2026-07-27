<script setup lang="ts">
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { computed, ref, watch } from 'vue'
import { getOrder, listOrders, type OrderSummary } from '../../api/orders'
import { router } from '../../router'
import OrderDetailView from './OrderDetailView.vue'

const platform = ref('')
const status = ref('')
const orderKeyword = ref('')
const receiverKeyword = ref('')
const orders = ref<OrderSummary[]>([])
const loading = ref(false)
const loadError = ref('')
const selectedOrder = ref<OrderSummary | null>(null)

const platformLabels: Record<string, string> = {
  PINDUODUO: '拼多多',
  TAOBAO_TMALL: '淘宝/天猫',
  JD: '京东',
  DOUYIN: '抖音',
  WECHAT_VIDEO: '视频号',
  XIAOHONGSHU: '小红书',
  KUAISHOU: '快手',
  DEWU: '得物',
}

const statusLabels: Record<string, string> = {
  READY_TO_FULFILL: '已锁库存',
  SKU_NOT_MAPPED: '异常订单',
  WAITING_ALLOCATION: '待配货',
  PENDING_REVIEW: '待审核',
  ALLOCATED: '已配货',
}

const statusTabs = [
  { label: '全部订单', value: '' },
  { label: '待审核', value: 'PENDING_REVIEW' },
  { label: '待配货', value: 'WAITING_ALLOCATION' },
  { label: '已锁库存', value: 'READY_TO_FULFILL' },
]

const platformOptions = [
  { label: '全部', value: '' },
  { label: '拼多多', value: 'PINDUODUO' },
  { label: '淘宝/天猫', value: 'TAOBAO_TMALL' },
  { label: '京东', value: 'JD' },
  { label: '抖音', value: 'DOUYIN' },
]

const visibleOrders = computed(() =>
  orders.value.filter((order) => {
    const matchesStatus = !status.value || order.status === status.value
    const matchesOrder =
      !orderKeyword.value ||
      order.id.toLowerCase().includes(orderKeyword.value.toLowerCase()) ||
      order.platformOrderId.toLowerCase().includes(orderKeyword.value.toLowerCase())
    const matchesReceiver =
      !receiverKeyword.value || order.receiverPhone.includes(receiverKeyword.value)
    return matchesStatus && matchesOrder && matchesReceiver
  }),
)

async function loadOrders() {
  loading.value = true
  loadError.value = ''
  const params = new URLSearchParams()
  if (platform.value) {
    params.set('platform', platform.value)
  }
  try {
    orders.value = (await listOrders(params)).items
  } catch {
    loadError.value = '订单加载失败，请稍后重试'
    orders.value = []
  } finally {
    loading.value = false
  }
}

watch(platform, loadOrders, { immediate: true })

async function openOrder(orderId: string) {
  try {
    selectedOrder.value = await getOrder(orderId)
  } catch {
    loadError.value = '订单详情加载失败，请稍后重试'
  }
}

function resetFilters() {
  platform.value = ''
  status.value = ''
  orderKeyword.value = ''
  receiverKeyword.value = ''
}

function orderAmount(order: OrderSummary) {
  const totalFen = order.lines.reduce((sum, line) => sum + line.paidAmountFen, 0)
  return `¥${(totalFen / 100).toFixed(2)}`
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value)).replace(/\//g, '-')
}

function statusClass(order: OrderSummary) {
  if (order.exceptionCode) return 'danger'
  if (order.status === 'READY_TO_FULFILL') return 'success'
  if (order.status === 'WAITING_ALLOCATION') return 'warning'
  if (order.status === 'ALLOCATED') return 'info'
  return 'neutral'
}
</script>

<template>
  <section class="page-panel" :class="{ 'drawer-open': selectedOrder }" aria-labelledby="orders-title">
    <header class="page-heading">
      <div>
        <h1 id="orders-title">订单管理</h1>
      </div>
    </header>

    <div class="status-tabs" role="tablist" aria-label="订单状态">
      <button
        v-for="tab in statusTabs"
        :key="tab.value"
        type="button"
        role="tab"
        :aria-selected="status === tab.value"
        :class="{ active: status === tab.value }"
        @click="status = tab.value"
      >
        {{ tab.label }}
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="false"
        @click="router.push('/exceptions/orders')"
      >
        异常订单
        <span class="tab-count">{{ orders.filter((order) => order.exceptionCode).length }}</span>
      </button>
    </div>

    <form class="filter-panel" @submit.prevent>
      <div class="filter-row platform-row">
        <span class="filter-label">平台</span>
        <label class="sr-only">
          平台
          <select v-model="platform">
            <option v-for="option in platformOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <div class="platform-segments">
          <button
            v-for="option in platformOptions"
            :key="option.value"
            type="button"
            :aria-pressed="platform === option.value"
            :class="{ active: platform === option.value }"
            @click="platform = option.value"
          >
            {{ option.label }}
          </button>
        </div>
        <label class="inline-field shop-filter">
          <span>店铺</span>
          <select>
            <option>全部店铺</option>
          </select>
        </label>
      </div>
      <div class="filter-row fields-row">
        <label class="inline-field">
          <span>订单状态</span>
          <select v-model="status">
            <option value="">全部状态</option>
            <option value="PENDING_REVIEW">待审核</option>
            <option value="WAITING_ALLOCATION">待配货</option>
            <option value="READY_TO_FULFILL">已锁库存</option>
          </select>
        </label>
        <label class="inline-field">
          <span>订单号</span>
          <input v-model="orderKeyword" placeholder="请输入订单号" />
        </label>
        <label class="inline-field receiver-field">
          <span>收件人</span>
          <input v-model="receiverKeyword" placeholder="请输入收件人姓名/电话" />
        </label>
      </div>
      <div class="filter-row actions-row">
        <label class="inline-field date-field">
          <span>下单时间</span>
          <input type="date" aria-label="开始日期" />
          <b>—</b>
          <input type="date" aria-label="结束日期" />
        </label>
        <button type="submit" class="primary-button"><SearchOutlined />查询</button>
        <button type="button" class="secondary-button" @click="resetFilters"><ReloadOutlined />重置</button>
      </div>
    </form>

    <div v-if="loading" class="table-state">正在加载订单…</div>
    <div v-else-if="loadError" class="table-state error-state" role="alert">{{ loadError }}</div>
    <div v-else class="table-wrap">
      <table class="orders-table">
        <thead>
          <tr>
            <th>订单号</th>
            <th>平台/店铺</th>
            <th class="center">商品</th>
            <th class="money">订单金额</th>
            <th>收件人</th>
            <th>履约状态</th>
            <th>下单时间</th>
            <th class="center">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="order in visibleOrders"
            :key="order.id"
            :class="{ selected: selectedOrder?.id === order.id }"
          >
            <td><button type="button" class="order-link" @click="openOrder(order.id)">{{ order.id }}</button></td>
            <td>{{ platformLabels[order.platform] ?? order.platform }} · {{ order.shopId }}</td>
            <td class="center">{{ order.lines.reduce((sum, line) => sum + line.quantity, 0) }}</td>
            <td class="money">{{ orderAmount(order) }}</td>
            <td>{{ order.receiverPhone }}</td>
            <td>
              <span class="status-tag" :class="statusClass(order)">{{ statusLabels[order.status] ?? order.status }}</span>
              <span v-if="order.exceptionCode" class="exception-tag">SKU 未映射</span>
            </td>
            <td class="date-cell">{{ formatTime(order.paidAt) }}</td>
            <td class="center">
              <button type="button" class="view-button" :aria-label="`查看订单 ${order.id}`" @click="openOrder(order.id)">
                查看
              </button>
            </td>
          </tr>
          <tr v-if="!visibleOrders.length">
            <td colspan="8" class="empty-cell">没有符合条件的订单</td>
          </tr>
        </tbody>
      </table>
      <footer class="table-footer">
        <span>共 {{ visibleOrders.length }} 条</span>
        <select aria-label="每页条数"><option>20 条/页</option></select>
        <button type="button" disabled aria-label="上一页">‹</button>
        <button type="button" class="current-page">1</button>
        <button type="button" disabled aria-label="下一页">›</button>
      </footer>
    </div>

    <OrderDetailView
      v-if="selectedOrder"
      :order="selectedOrder"
      @close="selectedOrder = null"
    />
  </section>
</template>
