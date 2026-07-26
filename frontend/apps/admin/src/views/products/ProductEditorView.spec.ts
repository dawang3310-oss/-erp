import { render, screen, waitFor } from '@testing-library/vue'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/server'
import ProductEditorView from './ProductEditorView.vue'

const detail = {
  id: 'PRODUCT-1',
  spuCode: 'SPU-1',
  name: '智能水杯',
  brandId: 'BRAND-1',
  brandName: '星链',
  categoryId: 'CATEGORY-1',
  categoryName: '杯具',
  attributes: { 材质: '不锈钢' },
  status: 'ACTIVE',
  skus: [{
    id: 'SKU-ID-1',
    skuCode: 'SKU-1',
    name: '黑色',
    barcode: '690000000001',
    specifications: { 颜色: '黑色' },
    unit: '件',
    status: 'ACTIVE',
    version: 2,
  }],
  images: [],
  auditHistory: [],
  createdAt: '2026-07-26T08:00:00Z',
  updatedAt: '2026-07-26T08:00:00Z',
  version: 4,
}

async function renderAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/products/new', name: 'product-new', component: ProductEditorView },
      { path: '/products/:id/edit', name: 'product-edit', component: ProductEditorView },
      { path: '/products/:id', component: { template: '<div>商品详情占位</div>' } },
      { path: '/products', component: { template: '<div>商品列表占位</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  render(ProductEditorView, { global: { plugins: [router] } })
  return router
}

describe('ProductEditorView', () => {
  it('adds and removes SKU rows, creates a product, and navigates to detail', async () => {
    let payload: Record<string, unknown> | undefined
    server.use(
      http.post('/api/products/spus', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>
        return HttpResponse.json({ id: 'PRODUCT-NEW' }, { status: 201 })
      }),
    )
    const router = await renderAt('/products/new')
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '添加 SKU' }))
    expect(screen.getByLabelText('SKU 编码 2')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '删除 SKU 2' }))
    expect(screen.queryByLabelText('SKU 编码 2')).not.toBeInTheDocument()

    await user.type(screen.getByLabelText('SPU 编码'), 'SPU-NEW')
    await user.type(screen.getByLabelText('商品名称'), '旅行保温杯')
    await user.type(screen.getByLabelText('SKU 编码 1'), 'SKU-NEW-1')
    await user.type(screen.getByLabelText('SKU 名称 1'), '黑色 500ml')
    await user.type(screen.getByLabelText('单位 1'), '件')
    await user.click(screen.getByRole('button', { name: '保存商品' }))

    await waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/products/PRODUCT-NEW'))
    expect(payload).toMatchObject({
      spuCode: 'SPU-NEW',
      name: '旅行保温杯',
      skus: [{ skuCode: 'SKU-NEW-1', unit: '件' }],
    })
  })

  it('loads an edit form, locks persisted codes, and displays a 409 conflict', async () => {
    server.use(
      http.get('/api/products/spus/PRODUCT-1', () => HttpResponse.json(detail)),
      http.put('/api/products/spus/PRODUCT-1', () => HttpResponse.json(
        { code: 'STALE_PRODUCT_VERSION', message: '商品已被其他人修改' },
        { status: 409 },
      )),
    )
    await renderAt('/products/PRODUCT-1/edit')
    const user = userEvent.setup()

    expect(await screen.findByDisplayValue('SPU-1')).toBeDisabled()
    expect(screen.getByDisplayValue('SKU-1')).toBeDisabled()
    await user.clear(screen.getByLabelText('商品名称'))
    await user.type(screen.getByLabelText('商品名称'), '智能水杯二代')
    await user.click(screen.getByRole('button', { name: '保存商品' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('商品已被其他人修改')
    expect(screen.getByRole('button', { name: '重新加载商品' })).toBeVisible()
  })

  it('places a server SKU conflict next to the matching row', async () => {
    server.use(
      http.post('/api/products/spus', () => HttpResponse.json(
        { code: 'PRODUCT_CONFLICT', message: 'DUPLICATE_SKU: SKU-EXISTS' },
        { status: 409 },
      )),
    )
    await renderAt('/products/new')
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('SPU 编码'), 'SPU-NEW')
    await user.type(screen.getByLabelText('商品名称'), '旅行保温杯')
    await user.type(screen.getByLabelText('SKU 编码 1'), 'SKU-EXISTS')
    await user.type(screen.getByLabelText('SKU 名称 1'), '黑色')
    await user.type(screen.getByLabelText('单位 1'), '件')
    await user.click(screen.getByRole('button', { name: '保存商品' }))

    expect(await screen.findByText('SKU 编码已存在')).toBeVisible()
  })
})
