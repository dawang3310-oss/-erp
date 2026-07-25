<script setup lang="ts">
import { ArrowLeftOutlined, LinkOutlined } from '@ant-design/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { listOrders, type OrderSummary } from '../../api/orders'
import { router } from '../../router'

const orders = ref<OrderSummary[]>([])
const loading = ref(true)
const loadError = ref('')
const resolvedIds = ref(new Set<string>())

const exceptions = computed(() =>
  orders.value.filter((order) => order.exceptionCode === 'SKU_NOT_MAPPED'),
)

onMounted(async () => {
  try {
    orders.value = (await listOrders(new URLSearchParams())).items
  } catch {
    loadError.value = '异常订单加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
})

function resolve(orderId: string) {
  resolvedIds.value = new Set(resolvedIds.value).add(orderId)
}
</script>

<template>
  <section class="page-panel" aria-labelledby="exceptions-title">
    <header class="page-heading page-heading-with-action">
      <div>
        <h1 id="exceptions-title">异常订单</h1>
        <p>处理平台商品尚未映射到内部 SKU 的订单。</p>
      </div>
      <button type="button" class="secondary-button" @click="router.push('/orders')">
        <ArrowLeftOutlined />返回订单
      </button>
    </header>

    <div class="exception-notice">
      <strong>SKU 映射异常</strong>
      <span>未完成映射的订单不会自动审单、锁定库存或进入履约。</span>
    </div>

    <div v-if="loading" class="table-state">正在加载异常订单…</div>
    <div v-else-if="loadError" class="table-state error-state" role="alert">{{ loadError }}</div>
    <div v-else class="table-wrap">
      <table class="orders-table exception-table">
        <thead>
          <tr>
            <th>订单号</th>
            <th>平台/店铺</th>
            <th>平台 SKU</th>
            <th>异常类型</th>
            <th>处理状态</th>
            <th class="center">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="order in exceptions" :key="order.id">
            <td><span class="order-link">{{ order.id }}</span></td>
            <td>{{ order.platform }} · {{ order.shopId }}</td>
            <td class="sku-code">{{ order.lines[0]?.platformSkuId || '—' }}</td>
            <td><span class="exception-tag">SKU 未映射</span></td>
            <td>
              <span v-if="resolvedIds.has(order.id)" class="status-tag success">已建立映射</span>
              <span v-else class="status-tag warning">待映射</span>
            </td>
            <td class="center">
              <button type="button" class="primary-link" :disabled="resolvedIds.has(order.id)" @click="resolve(order.id)">
                <LinkOutlined />{{ resolvedIds.has(order.id) ? '已处理' : '建立映射' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <footer class="table-footer"><span>共 {{ exceptions.length }} 条异常</span></footer>
    </div>
  </section>
</template>
