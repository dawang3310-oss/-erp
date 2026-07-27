import { render, screen, waitFor } from '@testing-library/vue'
import userEvent from '@testing-library/user-event'
import { HttpResponse, delay, http } from 'msw'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/server'
import ProductDetailView from './ProductDetailView.vue'

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
  images: [{
    id: 'IMAGE-1',
    objectKey: 'products/PRODUCT-1/IMAGE-1.jpg',
    sourceUrl: 'https://example.test/image.jpg',
    mediaType: 'image/jpeg',
    displayOrder: 0,
  }],
  auditHistory: [{
    action: 'PRODUCT_CREATED',
    actor: 'admin',
    reason: '首次建档',
    beforeJson: null,
    afterJson: '{"name":"智能水杯"}',
    createdAt: '2026-07-26T08:00:00Z',
  }],
  createdAt: '2026-07-26T08:00:00Z',
  updatedAt: '2026-07-26T08:00:00Z',
  version: 4,
}

async function renderDetail() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/products/:id', component: ProductDetailView },
      { path: '/products/:id/edit', component: { template: '<div />' } },
      { path: '/products', component: { template: '<div />' } },
    ],
  })
  await router.push('/products/PRODUCT-1')
  await router.isReady()
  render(ProductDetailView, { global: { plugins: [router] } })
}

describe('ProductDetailView', () => {
  it('renders product, SKU, image, and audit information', async () => {
    server.use(
      http.get('/api/products/spus/PRODUCT-1', () => HttpResponse.json(detail)),
    )
    await renderDetail()

    expect(await screen.findByRole('heading', { name: '智能水杯' })).toBeVisible()
    expect(screen.getByText('SPU-1')).toBeVisible()
    expect(screen.getByText('SKU-1')).toBeVisible()
    expect(screen.getByRole('img', { name: '商品图片 1' })).toHaveAttribute(
      'src',
      'https://example.test/image.jpg',
    )
    expect(screen.getByText('PRODUCT_CREATED')).toBeVisible()
    expect(screen.getByText('首次建档')).toBeVisible()
    expect(screen.getByRole('link', { name: '编辑商品' })).toHaveAttribute(
      'href',
      '/products/PRODUCT-1/edit',
    )
  })

  it('rejects invalid images and exposes upload progress for a valid image', async () => {
    let uploadCount = 0
    server.use(
      http.get('/api/products/spus/PRODUCT-1', () => HttpResponse.json(detail)),
      http.post('/api/products/spus/PRODUCT-1/images', async ({ request }) => {
        expect(request.headers.get('content-type')).toContain('multipart/form-data')
        uploadCount += 1
        await delay(80)
        return HttpResponse.json({ id: 'IMAGE-2' }, { status: 201 })
      }),
    )
    await renderDetail()
    const user = userEvent.setup({ applyAccept: false })
    const input = await screen.findByLabelText('上传商品图片')

    await user.upload(input, new File(['text'], 'readme.txt', { type: 'text/plain' }))
    expect(screen.getByRole('alert')).toHaveTextContent('仅支持 JPG、PNG 或 WebP')

    const oversized = new File(
      [new Uint8Array(10 * 1024 * 1024 + 1)],
      'large.png',
      { type: 'image/png' },
    )
    await user.upload(input, oversized)
    expect(screen.getByRole('alert')).toHaveTextContent('不能超过 10 MiB')

    await user.upload(input, new File(['image'], 'cup.png', { type: 'image/png' }))
    expect(screen.getByText('正在上传图片…')).toBeVisible()
    await waitFor(() => expect(uploadCount).toBe(1))
    await waitFor(() => expect(screen.queryByText('正在上传图片…')).not.toBeInTheDocument())
  })
})
