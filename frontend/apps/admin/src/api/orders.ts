import { tokenStore } from '../stores/token'
import { demoOrders } from '../data/demoOrders'

export type OrderLine = {
  platformSkuId: string
  internalSkuCode: string | null
  quantity: number
  paidAmountFen: number
}

export type OrderSummary = {
  id: string
  platform: string
  shopId: string
  platformOrderId: string
  status: string
  paidAt: string
  lines: OrderLine[]
  exceptionCode: string
  receiverPhone: string
}

type OrderListResponse = OrderSummary[] | { items: OrderSummary[] }

function authorizationHeaders(): HeadersInit {
  return tokenStore.token ? { Authorization: `Bearer ${tokenStore.token}` } : {}
}

export async function listOrders(params: URLSearchParams): Promise<{ items: OrderSummary[] }> {
  if (import.meta.env.VITE_DEMO_MODE === 'true') {
    const platform = params.get('platform')
    return {
      items: platform ? demoOrders.filter((order) => order.platform === platform) : demoOrders,
    }
  }
  const query = params.toString()
  const response = await fetch(`/api/orders${query ? `?${query}` : ''}`, {
    headers: authorizationHeaders(),
  })
  if (!response.ok) {
    throw new Error(`ORDER_LIST_${response.status}`)
  }
  const body = (await response.json()) as OrderListResponse
  return { items: Array.isArray(body) ? body : body.items }
}

export async function getOrder(id: string): Promise<OrderSummary> {
  if (import.meta.env.VITE_DEMO_MODE === 'true') {
    const order = demoOrders.find((candidate) => candidate.id === id)
    if (!order) {
      throw new Error('ORDER_DETAIL_404')
    }
    return order
  }
  const response = await fetch(`/api/orders/${encodeURIComponent(id)}`, {
    headers: authorizationHeaders(),
  })
  if (!response.ok) {
    throw new Error(`ORDER_DETAIL_${response.status}`)
  }
  return response.json() as Promise<OrderSummary>
}
