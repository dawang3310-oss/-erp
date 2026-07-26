import { describe, expect, it } from 'vitest'
import {
  createEmptyProductForm,
  productDetailToForm,
  toProductInput,
  toProductUpdate,
  validateProductForm,
} from './productForm'

describe('product form model', () => {
  it('requires SPU, product name, and at least one complete SKU', () => {
    const form = createEmptyProductForm()

    expect(validateProductForm(form)).toMatchObject({
      spuCode: '请输入 SPU 编码',
      name: '请输入商品名称',
      skus: {
        [form.skus[0].rowId]: {
          skuCode: '请输入 SKU 编码',
          name: '请输入 SKU 名称',
          unit: '请输入单位',
        },
      },
    })
  })

  it('rejects duplicate SKU codes and duplicate nonblank barcodes', () => {
    const form = createEmptyProductForm()
    Object.assign(form, { spuCode: 'SPU-1', name: '智能水杯' })
    Object.assign(form.skus[0], {
      skuCode: 'SKU-1',
      name: '黑色',
      barcode: '690000000001',
      unit: '件',
    })
    form.skus.push({
      ...form.skus[0],
      rowId: 'row-2',
      skuCode: ' sku-1 ',
      name: '白色',
    })

    const errors = validateProductForm(form)

    expect(errors.skus[form.skus[0].rowId].skuCode).toContain('重复')
    expect(errors.skus['row-2'].skuCode).toContain('重复')
    expect(errors.skus[form.skus[0].rowId].barcode).toContain('重复')
    expect(errors.skus['row-2'].barcode).toContain('重复')
  })

  it('rejects malformed attribute and specification JSON', () => {
    const form = createEmptyProductForm()
    Object.assign(form, {
      spuCode: 'SPU-1',
      name: '智能水杯',
      attributesText: '{"材质":',
    })
    Object.assign(form.skus[0], {
      skuCode: 'SKU-1',
      name: '黑色',
      unit: '件',
      specificationsText: '["黑色"]',
    })

    expect(validateProductForm(form)).toMatchObject({
      attributesText: '请输入 JSON 对象',
      skus: {
        [form.skus[0].rowId]: {
          specificationsText: '请输入 JSON 对象',
        },
      },
    })
  })

  it('keeps persisted SKU codes immutable and builds an optimistic update payload', () => {
    const detail = {
      id: 'PRODUCT-1',
      spuCode: 'SPU-1',
      name: '智能水杯',
      brandId: null,
      brandName: null,
      categoryId: null,
      categoryName: null,
      attributes: { 材质: '不锈钢' },
      status: 'ACTIVE' as const,
      skus: [{
        id: 'SKU-ID-1',
        skuCode: 'SKU-1',
        name: '黑色',
        barcode: null,
        specifications: { 颜色: '黑色' },
        unit: '件',
        status: 'ACTIVE' as const,
        version: 2,
      }],
      images: [],
      auditHistory: [],
      createdAt: '2026-07-26T08:00:00Z',
      updatedAt: '2026-07-26T08:00:00Z',
      version: 4,
    }
    const form = productDetailToForm(detail)
    form.skus[0].skuCode = 'SKU-CHANGED'

    expect(validateProductForm(form).skus[form.skus[0].rowId].skuCode).toBe(
      '已保存的 SKU 编码不能修改',
    )

    form.skus[0].skuCode = 'SKU-1'
    form.skus.push({
      rowId: 'row-new',
      skuCode: 'SKU-2',
      name: '白色',
      barcode: '',
      unit: '件',
      specificationsText: '{"颜色":"白色"}',
      status: 'DRAFT',
      version: 0,
    })

    const payload = toProductUpdate(form, 4)
    expect(payload).toMatchObject({
      name: '智能水杯',
      version: 4,
      skus: [{ id: 'SKU-ID-1', version: 2 }],
      newSkus: [{ skuCode: 'SKU-2', specifications: { 颜色: '白色' } }],
    })
    expect(payload.skus[0]).not.toHaveProperty('skuCode')
  })

  it('builds a normalized create payload', () => {
    const form = createEmptyProductForm()
    Object.assign(form, {
      spuCode: ' SPU-1 ',
      name: ' 智能水杯 ',
      brandId: ' ',
      categoryId: 'CATEGORY-1',
      attributesText: '{"材质":"不锈钢"}',
    })
    Object.assign(form.skus[0], {
      skuCode: ' SKU-1 ',
      name: ' 黑色 ',
      barcode: ' ',
      unit: ' 件 ',
      specificationsText: '{}',
    })

    expect(toProductInput(form)).toEqual({
      spuCode: 'SPU-1',
      name: '智能水杯',
      brandId: null,
      categoryId: 'CATEGORY-1',
      attributes: { 材质: '不锈钢' },
      skus: [{
        skuCode: 'SKU-1',
        name: '黑色',
        barcode: null,
        unit: '件',
        specifications: {},
      }],
    })
  })
})
