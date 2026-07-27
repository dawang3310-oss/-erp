import type {
  ProductDetail,
  ProductInput,
  ProductStatus,
  ProductUpdate,
} from '../../api/products'

export type ProductFormSku = {
  rowId: string
  id?: string
  originalSkuCode?: string
  skuCode: string
  name: string
  barcode: string
  unit: string
  specificationsText: string
  status: ProductStatus
  version: number
}

export type ProductFormState = {
  spuCode: string
  name: string
  brandId: string
  categoryId: string
  attributesText: string
  skus: ProductFormSku[]
}

type ProductSkuErrors = Partial<Record<
  'skuCode' | 'name' | 'barcode' | 'unit' | 'specificationsText',
  string
>>

export type ProductFormErrors = {
  spuCode?: string
  name?: string
  attributesText?: string
  skus: Record<string, ProductSkuErrors>
}

let rowSequence = 0

export function createEmptySku(): ProductFormSku {
  rowSequence += 1
  return {
    rowId: `sku-row-${rowSequence}`,
    skuCode: '',
    name: '',
    barcode: '',
    unit: '',
    specificationsText: '{}',
    status: 'DRAFT',
    version: 0,
  }
}

export function createEmptyProductForm(): ProductFormState {
  return {
    spuCode: '',
    name: '',
    brandId: '',
    categoryId: '',
    attributesText: '{}',
    skus: [createEmptySku()],
  }
}

function toJsonText(value: Record<string, string>) {
  return JSON.stringify(value, null, 2)
}

export function productDetailToForm(detail: ProductDetail): ProductFormState {
  return {
    spuCode: detail.spuCode,
    name: detail.name,
    brandId: detail.brandId ?? '',
    categoryId: detail.categoryId ?? '',
    attributesText: toJsonText(detail.attributes),
    skus: detail.skus.map((sku) => ({
      rowId: `persisted-${sku.id}`,
      id: sku.id,
      originalSkuCode: sku.skuCode,
      skuCode: sku.skuCode,
      name: sku.name,
      barcode: sku.barcode ?? '',
      unit: sku.unit,
      specificationsText: toJsonText(sku.specifications),
      status: sku.status,
      version: sku.version,
    })),
  }
}

function parseStringMap(value: string): Record<string, string> | null {
  try {
    const parsed: unknown = JSON.parse(value || '{}')
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
      return null
    }
    const entries = Object.entries(parsed)
    if (entries.some(([, item]) => typeof item !== 'string')) {
      return null
    }
    return Object.fromEntries(entries) as Record<string, string>
  } catch {
    return null
  }
}

function duplicateValues(
  skus: ProductFormSku[],
  field: 'skuCode' | 'barcode',
): Set<string> {
  const counts = new Map<string, number>()
  for (const sku of skus) {
    const normalized = sku[field].trim().toLocaleLowerCase()
    if (!normalized) {
      continue
    }
    counts.set(normalized, (counts.get(normalized) ?? 0) + 1)
  }
  return new Set(
    [...counts.entries()].filter(([, count]) => count > 1).map(([value]) => value),
  )
}

export function validateProductForm(form: ProductFormState): ProductFormErrors {
  const errors: ProductFormErrors = { skus: {} }
  if (!form.spuCode.trim()) {
    errors.spuCode = '请输入 SPU 编码'
  }
  if (!form.name.trim()) {
    errors.name = '请输入商品名称'
  }
  if (!parseStringMap(form.attributesText)) {
    errors.attributesText = '请输入 JSON 对象'
  }

  const duplicateSkuCodes = duplicateValues(form.skus, 'skuCode')
  const duplicateBarcodes = duplicateValues(form.skus, 'barcode')
  for (const sku of form.skus) {
    const skuErrors: ProductSkuErrors = {}
    errors.skus[sku.rowId] = skuErrors
    const normalizedSkuCode = sku.skuCode.trim().toLocaleLowerCase()
    const normalizedBarcode = sku.barcode.trim().toLocaleLowerCase()
    if (!sku.skuCode.trim()) {
      skuErrors.skuCode = '请输入 SKU 编码'
    } else if (
      sku.originalSkuCode !== undefined
      && sku.skuCode.trim() !== sku.originalSkuCode
    ) {
      skuErrors.skuCode = '已保存的 SKU 编码不能修改'
    } else if (duplicateSkuCodes.has(normalizedSkuCode)) {
      skuErrors.skuCode = 'SKU 编码不能重复'
    }
    if (!sku.name.trim()) {
      skuErrors.name = '请输入 SKU 名称'
    }
    if (!sku.unit.trim()) {
      skuErrors.unit = '请输入单位'
    }
    if (normalizedBarcode && duplicateBarcodes.has(normalizedBarcode)) {
      skuErrors.barcode = '商品条码不能重复'
    }
    if (!parseStringMap(sku.specificationsText)) {
      skuErrors.specificationsText = '请输入 JSON 对象'
    }
  }
  return errors
}

export function hasProductFormErrors(errors: ProductFormErrors) {
  return Boolean(
    errors.spuCode
    || errors.name
    || errors.attributesText
    || Object.values(errors.skus).some((sku) => Object.keys(sku).length > 0),
  )
}

function optionalId(value: string) {
  return value.trim() || null
}

function normalizedNewSku(sku: ProductFormSku) {
  return {
    skuCode: sku.skuCode.trim(),
    name: sku.name.trim(),
    barcode: optionalId(sku.barcode),
    unit: sku.unit.trim(),
    specifications: parseStringMap(sku.specificationsText) ?? {},
  }
}

export function toProductInput(form: ProductFormState): ProductInput {
  return {
    spuCode: form.spuCode.trim(),
    name: form.name.trim(),
    brandId: optionalId(form.brandId),
    categoryId: optionalId(form.categoryId),
    attributes: parseStringMap(form.attributesText) ?? {},
    skus: form.skus.map(normalizedNewSku),
  }
}

export function toProductUpdate(form: ProductFormState, version: number): ProductUpdate {
  const persisted = form.skus.filter((sku) => sku.id)
  const created = form.skus.filter((sku) => !sku.id)
  return {
    name: form.name.trim(),
    brandId: optionalId(form.brandId),
    categoryId: optionalId(form.categoryId),
    attributes: parseStringMap(form.attributesText) ?? {},
    skus: persisted.map((sku) => ({
      id: sku.id!,
      name: sku.name.trim(),
      barcode: optionalId(sku.barcode),
      unit: sku.unit.trim(),
      specifications: parseStringMap(sku.specificationsText) ?? {},
      status: sku.status,
      version: sku.version,
    })),
    newSkus: created.map(normalizedNewSku),
    version,
  }
}
