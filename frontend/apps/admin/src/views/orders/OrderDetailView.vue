<script setup lang="ts">
import { CloseOutlined, CopyOutlined } from '@ant-design/icons-vue'
import { computed } from 'vue'
import type { OrderSummary } from '../../api/orders'

const props = defineProps<{
  order: OrderSummary
}>()

defineEmits<{
  close: []
}>()

const statusLabels: Record<string, string> = {
  READY_TO_FULFILL: '已锁库存',
  SKU_NOT_MAPPED: '异常订单',
  WAITING_ALLOCATION: '待配货',
  PENDING_REVIEW: '待审核',
  ALLOCATED: '已配货',
}

const platformLabels: Record<string, string> = {
  PINDUODUO: '拼多多',
  TAOBAO: '淘宝',
  TMALL: '天猫',
  JD: '京东',
  DOUYIN: '抖音',
  XIAOHONGSHU: '小红书',
  KUAISHOU: '快手小店',
  WECHAT_CHANNELS: '微信视频号小店',
  DEWU: '得物',
}

function money(fen: number) {
  return `¥${(fen / 100).toFixed(2)}`
}

const totalFen = computed(() =>
  props.order.lines.reduce((sum, line) => sum + line.paidAmountFen, 0),
)

function productName(sku: string | null, platformSku: string) {
  const code = sku || platformSku
  if (code === 'BC-500-BK') return '智能保温杯 500ml 黑色'
  if (code === 'MP-10K-WH') return '移动电源 10000mAh 白色'
  return code
}

function productImage(sku: string | null, platformSku: string) {
  const code = sku || platformSku
  if (code === 'BC-500-BK') return '/assets/products/smart-bottle-black.png'
  if (code === 'MP-10K-WH') return '/assets/products/power-bank-white.png'
  return ''
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
</script>

<template>
  <Teleport to="body">
    <div class="drawer-scrim" @click.self="$emit('close')">
      <aside class="order-detail" role="dialog" aria-label="订单详情" aria-modal="true">
        <header class="drawer-header">
          <h2>订单详情</h2>
          <button type="button" class="icon-button" aria-label="关闭订单详情" @click="$emit('close')">
            <CloseOutlined />
          </button>
        </header>

        <div class="drawer-body">
          <div class="order-identity">
            <div><span>订单号：</span><strong>{{ order.id }}</strong><button type="button" class="copy-button" aria-label="复制订单号"><CopyOutlined /></button></div>
            <div><span>订单状态：</span><strong class="status-tag success" data-testid="detail-order-status">{{ statusLabels[order.status] ?? order.status }}</strong></div>
          </div>

          <section class="detail-section">
            <h3>基本信息</h3>
            <dl class="detail-grid">
              <dt>平台/店铺</dt><dd>{{ platformLabels[order.platform] ?? order.platform }} · {{ order.shopId }}</dd>
              <dt>下单时间</dt><dd>{{ formatTime(order.paidAt) }}</dd>
              <dt>付款时间</dt><dd>{{ formatTime(order.paidAt) }}</dd>
              <dt>买家备注</dt><dd>无</dd>
            </dl>
          </section>

          <section class="detail-section">
            <h3>收件信息</h3>
            <dl class="detail-grid">
              <dt>收件人</dt><dd><span>李先生　</span><span>{{ order.receiverPhone }}</span></dd>
              <dt>收货地址</dt><dd>江苏省南京市江宁区秣陵街道诚信大道 88 号<br />同曦大厦 12 楼 1201 室</dd>
              <dt>邮编</dt><dd>211100</dd>
            </dl>
          </section>

          <section class="detail-section">
            <h3>商品明细</h3>
            <table class="line-table">
              <thead>
                <tr><th>商品</th><th>SKU</th><th>数量</th><th>单价(元)</th><th>小计(元)</th></tr>
              </thead>
              <tbody>
                <tr v-for="line in order.lines" :key="line.platformSkuId">
                  <td>
                    <div class="product-cell">
                      <img v-if="productImage(line.internalSkuCode, line.platformSkuId)" :src="productImage(line.internalSkuCode, line.platformSkuId)" :alt="productName(line.internalSkuCode, line.platformSkuId)" />
                      <span>{{ productName(line.internalSkuCode, line.platformSkuId) }}</span>
                    </div>
                  </td>
                  <td>{{ line.internalSkuCode || line.platformSkuId }}</td>
                  <td>{{ line.quantity }}</td>
                  <td>{{ (line.paidAmountFen / line.quantity / 100).toFixed(2) }}</td>
                  <td>{{ (line.paidAmountFen / 100).toFixed(2) }}</td>
                </tr>
              </tbody>
            </table>
            <dl class="amount-summary">
              <dt>商品总额：</dt><dd>{{ money(totalFen) }}</dd>
              <dt>运费：</dt><dd>¥0.00</dd>
              <dt>优惠金额：</dt><dd>- ¥0.00</dd>
              <dt>订单金额：</dt><dd class="total-amount">{{ money(totalFen) }}</dd>
            </dl>
          </section>

          <section class="detail-section">
            <h3>履约信息</h3>
            <dl class="detail-grid">
              <dt>履约状态</dt><dd><span class="status-tag success">{{ statusLabels[order.status] ?? order.status }}</span></dd>
              <dt>仓库</dt><dd>南京主仓</dd>
              <dt>库存锁定时间</dt><dd>2026-07-23 10:23:52</dd>
              <dt>预计发货时间</dt><dd>2026-07-23 16:00:00</dd>
            </dl>
          </section>

          <section class="detail-section">
            <h3>操作记录</h3>
            <ol class="timeline">
              <li><time>2026-07-23 10:23:52</time><span>系统</span><strong>库存已锁定（南京主仓）</strong></li>
              <li><time>2026-07-23 10:23:49</time><span>系统</span><strong>支付成功</strong></li>
              <li><time>2026-07-23 10:23:45</time><span>系统</span><strong>订单创建</strong></li>
            </ol>
          </section>
        </div>
      </aside>
    </div>
  </Teleport>
</template>
