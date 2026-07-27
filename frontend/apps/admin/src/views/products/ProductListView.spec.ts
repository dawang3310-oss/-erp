import { render, screen } from '@testing-library/vue'
import userEvent from '@testing-library/user-event'
import { HttpResponse, delay, http } from 'msw'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/server'
import ProductListView from './ProductListView.vue'

const product = {
  id: 'PRODUCT-1',
  spuCode: 'SPU-1',
  name: '智能水杯',
  brandId: 'BRAND-1',
  brandName: '星链',
  categoryId: 'CATEGORY-1',
  categoryName: '杯具',
  status: 'ACTIVE',
  skuCount: 2,
  channelMappingCount: 3,
  mainImageUrl: null,
  updatedAt: '2026-07-26T08:00:00Z',
  version: 4,
}

async function renderAt(path = '/products') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/products', component: ProductListView },
      { path: '/products/new', component: { template: '<div />' } },
      { path: '/products/imports', component: { template: '<div />' } },
      { path: '/products/:id', component: { template: '<div />' } },
      { path: '/products/:id/edit', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  render(ProductListView, { global: { plugins: [router] } })
  return router
}

describe('ProductListView', () => {
  it('loads, filters, preserves route query, paginates, and links to product flows', async () => {
    const requestedUrls: URL[] = []
    server.use(
      http.get('/api/products/spus', ({ request }) => {
        requestedUrls.push(new URL(request.url))
        return HttpResponse.json({
          items: [product],
          page: Number(new URL(request.url).searchParams.get('page') ?? 0),
          size: 20,
          total: 21,
        })
      }),
    )
    const router = await renderAt('/products?keyword=初始关键字')
    const user = userEvent.setup()

    expect(await screen.findByText('SPU-1')).toBeVisible()
    expect(screen.getByRole('link', { name: '新建商品' })).toHaveAttribute('href', '/products/new')
    expect(screen.getByRole('link', { name: '导入商品' })).toHaveAttribute('href', '/products/imports')
    expect(screen.getByRole('link', { name: '查看 SPU-1' })).toHaveAttribute(
      'href',
      '/products/PRODUCT-1',
    )
    expect(screen.getByRole('link', { name: '编辑 SPU-1' })).toHaveAttribute(
      'href',
      '/products/PRODUCT-1/edit',
    )

    await user.clear(screen.getByLabelText('SKU/商品名称'))
    await user.type(screen.getByLabelText('SKU/商品名称'), 'SKU & bottle')
    await user.type(screen.getByLabelText('条码'), '690000000001')
    await user.type(screen.getByLabelText('品牌ID'), 'BRAND-1')
    await user.type(screen.getByLabelText('类目ID'), 'CATEGORY-1')
    await user.selectOptions(screen.getByLabelText('商品状态'), 'ACTIVE')
    await user.click(screen.getByRole('button', { name: '查询' }))

    const filtered = requestedUrls.at(-1)!
    expect(filtered.searchParams.get('keyword')).toBe('SKU & bottle')
    expect(filtered.searchParams.get('barcode')).toBe('690000000001')
    expect(filtered.searchParams.get('brandId')).toBe('BRAND-1')
    expect(filtered.searchParams.get('categoryId')).toBe('CATEGORY-1')
    expect(filtered.searchParams.get('status')).toBe('ACTIVE')
    expect(router.currentRoute.value.query.keyword).toBe('SKU & bottle')

    await user.click(screen.getByRole('button', { name: '下一页' }))
    expect(requestedUrls.at(-1)!.searchParams.get('page')).toBe('1')
  })

  it('renders loading and empty states', async () => {
    server.use(
      http.get('/api/products/spus', async () => {
        await delay(80)
        return HttpResponse.json({ items: [], page: 0, size: 20, total: 0 })
      }),
    )

    await renderAt()

    expect(screen.getByText('正在加载商品…')).toBeVisible()
    expect(await screen.findByText('没有符合条件的商品')).toBeVisible()
  })

  it('renders a retryable load error', async () => {
    server.use(
      http.get('/api/products/spus', () => HttpResponse.json(
        { code: 'PRODUCT_LIST_FAILED', message: '暂时不可用' },
        { status: 500 },
      )),
    )

    await renderAt()

    expect(await screen.findByRole('alert')).toHaveTextContent('商品加载失败')
    expect(screen.getByRole('button', { name: '重新加载' })).toBeVisible()
  })

  it('requires a reason and prompts refresh after a stale lifecycle update', async () => {
    const reasons: string[] = []
    server.use(
      http.get('/api/products/spus', () => HttpResponse.json({
        items: [product],
        page: 0,
        size: 20,
        total: 1,
      })),
      http.post('/api/products/spus/PRODUCT-1/status', async ({ request }) => {
        const body = await request.json() as { reason: string }
        reasons.push(body.reason)
        return HttpResponse.json(
          { code: 'STALE_PRODUCT_VERSION', message: '商品已被其他人修改' },
          { status: 409 },
        )
      }),
    )
    await renderAt()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '停用 SPU-1' }))
    const confirm = screen.getByRole('dialog', { name: '停用商品' })
    const submit = screen.getByRole('button', { name: '确认停用' })
    expect(submit).toBeDisabled()
    await user.type(screen.getByLabelText('操作原因'), '渠道暂时停售')
    expect(submit).toBeEnabled()
    await user.click(submit)

    expect(reasons).toEqual(['渠道暂时停售'])
    expect(await screen.findByRole('alert')).toHaveTextContent('商品已被其他人修改')
    expect(screen.getByRole('button', { name: '刷新列表' })).toBeVisible()
    expect(confirm).not.toBeInTheDocument()
  })
})
