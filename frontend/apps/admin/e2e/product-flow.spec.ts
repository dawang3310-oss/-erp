import { randomUUID } from 'node:crypto'
import { expect, test, type APIRequestContext } from '@playwright/test'
import { createE2eJwt } from './support/e2eAuth'
import startProductEnvironment from './support/productEnvironment'
import { createProductWorkbook } from './support/productWorkbook'

type ProductStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED'

type ProductSummary = {
  id: string
  spuCode: string
  status: ProductStatus
  version: number
}

type ProductPage = {
  items: ProductSummary[]
}

type RunProducts = {
  prefix: string
  spuCodes: string[]
}

const productsByTest = new Map<string, RunProducts>()
let stopProductEnvironment: (() => Promise<void>) | undefined

test.use({
  extraHTTPHeaders: {
    Authorization: `Bearer ${createE2eJwt()}`,
  },
})

test.beforeAll(async () => {
  test.setTimeout(120_000)
  stopProductEnvironment = await startProductEnvironment()
})

test.afterAll(async () => {
  await stopProductEnvironment?.()
})

async function findProduct(request: APIRequestContext, spuCode: string) {
  const response = await request.get(`/api/products/spus?keyword=${encodeURIComponent(spuCode)}`)
  if (!response.ok()) {
    throw new Error(`查询 ${spuCode} 失败（${response.status()}）：${await response.text()}`)
  }
  const page = await response.json() as ProductPage
  return page.items.find((item) => item.spuCode === spuCode)
}

async function archiveRunProducts(request: APIRequestContext, run: RunProducts) {
  for (const spuCode of run.spuCodes) {
    if (!spuCode.startsWith(`${run.prefix}-`)) {
      throw new Error(`拒绝清理不属于本次运行的商品：${spuCode}`)
    }
    const product = await findProduct(request, spuCode)
    if (!product) continue
    if (product.status === 'ARCHIVED') continue
    const response = await request.post(
      `/api/products/spus/${encodeURIComponent(product.id)}/status`,
      {
        data: {
          status: 'ARCHIVED',
          version: product.version,
          reason: `E2E 清理 ${run.prefix}`,
        },
      },
    )
    if (!response.ok()) {
      throw new Error(
        `归档 ${product.spuCode} 失败（${response.status()}）：${await response.text()}`,
      )
    }
    const archived = await findProduct(request, spuCode)
    expect(archived?.status).toBe('ARCHIVED')
  }
}

test.afterEach(async ({ request }, testInfo) => {
  const run = productsByTest.get(testInfo.testId)
  if (!run) return
  try {
    await archiveRunProducts(request, run)
  } finally {
    productsByTest.delete(testInfo.testId)
  }
})

test('product administrator completes create, lifecycle, import, and search flow', async ({
  page,
  request,
}, testInfo) => {
  const runKey = randomUUID().replaceAll('-', '').slice(0, 12)
  const prefix = `E2E-${runKey}`
  const createdSpuCode = `${prefix}-SPU`
  const createdSkuOne = `${prefix}-SKU-A`
  const createdSkuTwo = `${prefix}-SKU-B`
  const importedSpuCode = `${prefix}-IMPORT-SPU`
  const importedSkuCode = `${prefix}-IMPORT-SKU`
  const run = { prefix, spuCodes: [createdSpuCode, importedSpuCode] }
  productsByTest.set(testInfo.testId, run)

  for (const spuCode of run.spuCodes) {
    expect(await findProduct(request, spuCode)).toBeUndefined()
  }

  await page.goto('/products')
  await page.getByRole('link', { name: '新建商品' }).click()

  await page.getByLabel('SPU 编码').fill(createdSpuCode)
  await page.getByLabel('商品名称').fill('E2E 双规格水杯')
  await page.getByLabel('SKU 编码 1').fill(createdSkuOne)
  await page.getByLabel('SKU 名称 1').fill('E2E 水杯蓝色')
  await page.getByLabel('单位 1').fill('件')
  await page.getByRole('button', { name: '添加 SKU' }).click()
  await page.getByLabel('SKU 编码 2').fill(createdSkuTwo)
  await page.getByLabel('SKU 名称 2').fill('E2E 水杯白色')
  await page.getByLabel('单位 2').fill('件')
  await page.getByRole('button', { name: '保存商品' }).click()

  await expect(page.getByRole('heading', { name: 'E2E 双规格水杯' })).toBeVisible()
  await expect(page.getByText(createdSkuOne, { exact: true })).toBeVisible()
  await expect(page.getByText(createdSkuTwo, { exact: true })).toBeVisible()

  await page.getByRole('link', { name: '返回列表' }).click()
  await page.getByLabel('SKU/商品名称').fill(createdSkuTwo)
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByRole('link', { name: `查看 ${createdSpuCode}` })).toBeVisible()
  await page.getByRole('link', { name: `查看 ${createdSpuCode}` }).click()
  await expect(page.getByText(createdSkuTwo, { exact: true })).toBeVisible()

  await page.getByRole('link', { name: '返回列表' }).click()
  await page.getByRole('button', { name: `上架 ${createdSpuCode}` }).click()
  await page.getByLabel('操作原因').fill('E2E 上架后验证停用')
  await page.getByRole('button', { name: '确认上架' }).click()
  await page.getByRole('button', { name: `停用 ${createdSpuCode}` }).click()
  await page.getByLabel('操作原因').fill('E2E 生命周期验收')
  await page.getByRole('button', { name: '确认停用' }).click()
  await expect(page.getByRole('table').getByText('已停用', { exact: true })).toBeVisible()

  await page.getByRole('link', { name: '导入商品' }).click()
  await page.getByLabel('选择商品文件').setInputFiles({
    name: 'e2e-products.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: createProductWorkbook(importedSpuCode, importedSkuCode),
  })
  await page.getByRole('button', { name: '上传并预检' }).click()
  await expect(page.getByText('预检完成，等待确认')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByText('预计新建')).toBeVisible()
  await expect(page.getByLabel('导入统计').getByText('1', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '确认执行导入' }).click()
  await expect(page.getByText('导入成功')).toBeVisible({ timeout: 30_000 })

  await page.getByRole('link', { name: '查看商品列表' }).click()
  await page.getByLabel('SKU/商品名称').fill(importedSkuCode)
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByRole('link', { name: `查看 ${importedSpuCode}` })).toBeVisible()
  await expect.poll(async () => {
    const products = await Promise.all(run.spuCodes.map((code) => findProduct(request, code)))
    return products.filter(Boolean).length
  }).toBe(run.spuCodes.length)
})
