import { HttpResponse, http } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { tokenStore } from '../stores/token'
import { server } from '../test/server'
import {
  ProductApiError,
  changeProductStatus,
  confirmProductImport,
  createProductBrand,
  createProductCategory,
  createProductExport,
  listProductBrands,
  listProductCategories,
  listProducts,
} from './products'

describe('product API client', () => {
  afterEach(() => {
    tokenStore.token = ''
  })

  it('encodes product filters and forwards the bearer token', async () => {
    tokenStore.token = 'product-token'
    server.use(
      http.get('/api/products/spus', ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('keyword')).toBe('SKU & bottle')
        expect(url.searchParams.get('status')).toBe('ACTIVE')
        expect(url.searchParams.get('page')).toBe('2')
        expect(url.searchParams.get('size')).toBe('50')
        expect(request.headers.get('Authorization')).toBe('Bearer product-token')
        return HttpResponse.json({ items: [], page: 2, size: 50, total: 0 })
      }),
    )

    await expect(listProducts({
      keyword: 'SKU & bottle',
      status: 'ACTIVE',
      page: 2,
      size: 50,
    })).resolves.toMatchObject({ total: 0, page: 2 })
  })

  it('converts a stale-version conflict into ProductApiError', async () => {
    server.use(
      http.post('/api/products/spus/PRODUCT-1/status', () => HttpResponse.json(
        { code: 'STALE_PRODUCT_VERSION', message: '商品已被其他人修改' },
        { status: 409 },
      )),
    )

    const failure = changeProductStatus('PRODUCT-1', {
      status: 'ACTIVE',
      version: 0,
      reason: '审核通过',
    })

    await expect(failure).rejects.toEqual(expect.objectContaining({
      name: 'ProductApiError',
      code: 'STALE_PRODUCT_VERSION',
      status: 409,
      message: '商品已被其他人修改',
    } satisfies Partial<ProductApiError>))
  })

  it('forwards Idempotency-Key for import confirmation and filtered export', async () => {
    const keys: string[] = []
    server.use(
      http.post('/api/products/imports/IMPORT-1/confirm', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        return HttpResponse.json({ id: 'IMPORT-1' }, { status: 202 })
      }),
      http.post('/api/products/exports', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        expect(new URL(request.url).searchParams.get('barcode')).toBe('690000000001')
        return HttpResponse.json({ id: 'EXPORT-1' }, { status: 202 })
      }),
    )

    await confirmProductImport('IMPORT-1', 'confirm-key-1')
    await expect(createProductExport(
      { barcode: '690000000001' },
      'export-key-1',
    )).resolves.toEqual({ id: 'EXPORT-1' })
    expect(keys).toEqual(['confirm-key-1', 'export-key-1'])
  })

  it('searches and creates product reference data', async () => {
    server.use(
      http.get('/api/products/reference/brands', ({ request }) => {
        expect(new URL(request.url).searchParams.get('query')).toBe('星')
        return HttpResponse.json([{ id: 'BRAND-1', name: '星链' }])
      }),
      http.post('/api/products/reference/brands', async ({ request }) => {
        expect(await request.json()).toEqual({ name: '远航' })
        return HttpResponse.json({ id: 'BRAND-2', name: '远航' }, { status: 201 })
      }),
      http.get('/api/products/reference/categories', ({ request }) => {
        expect(new URL(request.url).searchParams.get('query')).toBe('杯')
        return HttpResponse.json([{
          id: 'CATEGORY-1',
          name: '杯具',
          parentId: null,
          path: '/CATEGORY-1',
        }])
      }),
      http.post('/api/products/reference/categories', async ({ request }) => {
        expect(await request.json()).toEqual({ name: '保温杯', parentId: 'CATEGORY-1' })
        return HttpResponse.json({
          id: 'CATEGORY-2',
          name: '保温杯',
          parentId: 'CATEGORY-1',
          path: '/CATEGORY-1/CATEGORY-2',
        }, { status: 201 })
      }),
    )

    await expect(listProductBrands('星')).resolves.toEqual([
      { id: 'BRAND-1', name: '星链' },
    ])
    await expect(createProductBrand('远航')).resolves.toMatchObject({ id: 'BRAND-2' })
    await expect(listProductCategories('杯')).resolves.toMatchObject([
      { id: 'CATEGORY-1', path: '/CATEGORY-1' },
    ])
    await expect(createProductCategory('保温杯', 'CATEGORY-1')).resolves.toMatchObject({
      id: 'CATEGORY-2',
      parentId: 'CATEGORY-1',
    })
  })
})
