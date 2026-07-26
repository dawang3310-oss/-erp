import { HttpResponse, http } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { tokenStore } from '../stores/token'
import { server } from '../test/server'
import {
  ProductApiError,
  changeProductStatus,
  confirmProductImport,
  createProductExport,
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
})
