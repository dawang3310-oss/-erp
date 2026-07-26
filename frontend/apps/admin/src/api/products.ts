import { tokenStore } from '../stores/token'

export type ProductStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED'

export type ProductFilter = {
  keyword?: string
  barcode?: string
  brandId?: string
  categoryId?: string
  status?: ProductStatus
  page?: number
  size?: number
}

export type ProductSummary = {
  id: string
  spuCode: string
  name: string
  brandId: string | null
  brandName: string | null
  categoryId: string | null
  categoryName: string | null
  status: ProductStatus
  skuCount: number
  channelMappingCount: number
  mainImageUrl: string | null
  updatedAt: string
  version: number
}

export type ProductSku = {
  id: string
  skuCode: string
  name: string
  barcode: string | null
  specifications: Record<string, string>
  unit: string
  status: ProductStatus
  version: number
}

export type ProductImage = {
  id: string
  objectKey: string
  sourceUrl: string | null
  mediaType: string
  displayOrder: number
}

export type ProductAudit = {
  action: string
  actor: string
  reason: string
  beforeJson: string | null
  afterJson: string | null
  createdAt: string
}

export type ProductDetail = {
  id: string
  spuCode: string
  name: string
  brandId: string | null
  brandName: string | null
  categoryId: string | null
  categoryName: string | null
  attributes: Record<string, string>
  status: ProductStatus
  skus: ProductSku[]
  images: ProductImage[]
  auditHistory: ProductAudit[]
  createdAt: string
  updatedAt: string
  version: number
}

export type ProductPage = {
  items: ProductSummary[]
  page: number
  size: number
  total: number
}

export type NewProductSku = {
  skuCode: string
  name: string
  barcode?: string | null
  specifications?: Record<string, string>
  unit: string
}

export type ProductInput = {
  spuCode: string
  name: string
  brandId?: string | null
  categoryId?: string | null
  attributes?: Record<string, string>
  skus: NewProductSku[]
}

export type UpdatedProductSku = {
  id: string
  name: string
  barcode?: string | null
  specifications?: Record<string, string>
  unit: string
  status: ProductStatus
  version: number
}

export type ProductUpdate = {
  name: string
  brandId?: string | null
  categoryId?: string | null
  attributes?: Record<string, string>
  skus: UpdatedProductSku[]
  newSkus: NewProductSku[]
  version: number
}

export type StatusChange = {
  status: ProductStatus
  version: number
  reason: string
}

export type ProductImportJob = {
  id: string
  filename: string
  status: string
  createCount: number
  updateCount: number
  skipCount: number
  conflictCount: number
  failureCount: number
  errorObjectKey: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export type ProductExportJob = {
  id: string
  status: string
  objectKey: string | null
  createdAt: string
  finishedAt: string | null
}

export type ProductReferenceItem = {
  id: string
  name: string
}

export type ProductCategoryItem = ProductReferenceItem & {
  parentId: string | null
  path: string
}

type ErrorBody = {
  code?: string
  message?: string
}

export class ProductApiError extends Error {
  code: string
  status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.name = 'ProductApiError'
    this.code = code
    this.status = status
  }
}

function requestHeaders(
  json = false,
  extra?: HeadersInit,
): Headers {
  const headers = new Headers(extra)
  if (json) {
    headers.set('Content-Type', 'application/json')
  }
  if (tokenStore.token) {
    headers.set('Authorization', `Bearer ${tokenStore.token}`)
  }
  return headers
}

async function toError(response: Response): Promise<ProductApiError> {
  let body: ErrorBody = {}
  try {
    body = await response.json() as ErrorBody
  } catch {
    // A proxy or storage error may not return the ERP JSON contract.
  }
  return new ProductApiError(
    body.code ?? `PRODUCT_API_${response.status}`,
    body.message ?? `商品请求失败（${response.status}）`,
    response.status,
  )
}

async function requestJson<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: requestHeaders(Boolean(init.body && !(init.body instanceof FormData)), init.headers),
  })
  if (!response.ok) {
    throw await toError(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

async function requestBlob(url: string): Promise<Blob> {
  const response = await fetch(url, { headers: requestHeaders() })
  if (!response.ok) {
    throw await toError(response)
  }
  return response.blob()
}

function productQuery(filter: ProductFilter): string {
  const params = new URLSearchParams()
  const textFields = ['keyword', 'barcode', 'brandId', 'categoryId'] as const
  for (const field of textFields) {
    const value = filter[field]?.trim()
    if (value) {
      params.set(field, value)
    }
  }
  if (filter.status) {
    params.set('status', filter.status)
  }
  if (filter.page !== undefined) {
    params.set('page', String(filter.page))
  }
  if (filter.size !== undefined) {
    params.set('size', String(filter.size))
  }
  const query = params.toString()
  return query ? `?${query}` : ''
}

export function listProducts(filter: ProductFilter = {}): Promise<ProductPage> {
  return requestJson(`/api/products/spus${productQuery(filter)}`)
}

export function getProduct(id: string): Promise<ProductDetail> {
  return requestJson(`/api/products/spus/${encodeURIComponent(id)}`)
}

export function createProduct(input: ProductInput): Promise<{ id: string }> {
  return requestJson('/api/products/spus', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function updateProduct(id: string, input: ProductUpdate): Promise<void> {
  return requestJson(`/api/products/spus/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

export function changeProductStatus(id: string, input: StatusChange): Promise<void> {
  return requestJson(`/api/products/spus/${encodeURIComponent(id)}/status`, {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function downloadProductImportTemplate(): Promise<Blob> {
  return requestBlob('/api/products/imports/template')
}

export function createProductImport(file: File): Promise<{ id: string }> {
  const form = new FormData()
  form.set('file', file)
  return requestJson('/api/products/imports', {
    method: 'POST',
    body: form,
  })
}

export function getProductImport(id: string): Promise<ProductImportJob> {
  return requestJson(`/api/products/imports/${encodeURIComponent(id)}`)
}

export function confirmProductImport(
  id: string,
  idempotencyKey: string,
): Promise<{ id: string }> {
  return requestJson(`/api/products/imports/${encodeURIComponent(id)}/confirm`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function downloadProductImportErrors(id: string): Promise<Blob> {
  return requestBlob(`/api/products/imports/${encodeURIComponent(id)}/errors`)
}

export function createProductExport(
  filter: ProductFilter,
  idempotencyKey: string,
): Promise<{ id: string }> {
  return requestJson(`/api/products/exports${productQuery(filter)}`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function getProductExport(id: string): Promise<ProductExportJob> {
  return requestJson(`/api/products/exports/${encodeURIComponent(id)}`)
}

export function downloadProductExport(id: string): Promise<Blob> {
  return requestBlob(`/api/products/exports/${encodeURIComponent(id)}/file`)
}

export function uploadProductImage(
  productId: string,
  file: File,
  version: number,
): Promise<{ id: string }> {
  const form = new FormData()
  form.set('file', file)
  return requestJson(
    `/api/products/spus/${encodeURIComponent(productId)}/images?version=${version}`,
    { method: 'POST', body: form },
  )
}

export function reorderProductImages(
  productId: string,
  imageIds: string[],
  version: number,
): Promise<void> {
  return requestJson(`/api/products/spus/${encodeURIComponent(productId)}/images/order`, {
    method: 'PUT',
    body: JSON.stringify({ imageIds, version }),
  })
}

export function removeProductImage(
  productId: string,
  imageId: string,
  version: number,
  reason: string,
): Promise<void> {
  const params = new URLSearchParams({ version: String(version), reason })
  return requestJson(
    `/api/products/spus/${encodeURIComponent(productId)}/images/${encodeURIComponent(imageId)}?${params}`,
    { method: 'DELETE' },
  )
}

function referenceQuery(query: string) {
  const params = new URLSearchParams()
  if (query.trim()) {
    params.set('query', query.trim())
  }
  const text = params.toString()
  return text ? `?${text}` : ''
}

export function listProductBrands(query = ''): Promise<ProductReferenceItem[]> {
  return requestJson(`/api/products/reference/brands${referenceQuery(query)}`)
}

export function createProductBrand(name: string): Promise<ProductReferenceItem> {
  return requestJson('/api/products/reference/brands', {
    method: 'POST',
    body: JSON.stringify({ name: name.trim() }),
  })
}

export function listProductCategories(query = ''): Promise<ProductCategoryItem[]> {
  return requestJson(`/api/products/reference/categories${referenceQuery(query)}`)
}

export function createProductCategory(
  name: string,
  parentId?: string | null,
): Promise<ProductCategoryItem> {
  return requestJson('/api/products/reference/categories', {
    method: 'POST',
    body: JSON.stringify({ name: name.trim(), parentId: parentId?.trim() || null }),
  })
}
