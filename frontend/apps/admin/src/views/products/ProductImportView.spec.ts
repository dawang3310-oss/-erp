import { fireEvent, render, screen, waitFor } from '@testing-library/vue'
import userEvent from '@testing-library/user-event'
import { HttpResponse, delay, http } from 'msw'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/server'
import ProductImportView from './ProductImportView.vue'

const jobBase = {
  id: 'IMPORT-1',
  filename: 'products.xlsx',
  createCount: 12,
  updateCount: 3,
  skipCount: 2,
  conflictCount: 1,
  failureCount: 0,
  errorObjectKey: null,
  createdAt: '2026-07-26T08:00:00Z',
  startedAt: null,
  finishedAt: null,
}

async function renderAt(path = '/products/imports') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/products/imports', component: ProductImportView }],
  })
  await router.push(path)
  await router.isReady()
  render(ProductImportView, { global: { plugins: [router] } })
  return router
}

describe('ProductImportView', () => {
  it('preflights once, confirms once, and exposes the partial-success error workbook', async () => {
    let confirmed = false
    let confirmationCount = 0
    const idempotencyKeys: string[] = []
    server.use(
      http.post('/api/products/imports', async () => {
        await delay(60)
        return HttpResponse.json({ id: 'IMPORT-1' }, { status: 202 })
      }),
      http.get('/api/products/imports/IMPORT-1', () => HttpResponse.json({
        ...jobBase,
        status: confirmed ? 'PARTIALLY_SUCCEEDED' : 'PREFLIGHT_READY',
        failureCount: confirmed ? 2 : 0,
        errorObjectKey: confirmed ? 'imports/IMPORT-1/errors.xlsx' : null,
        finishedAt: confirmed ? '2026-07-26T08:03:00Z' : null,
      })),
      http.post('/api/products/imports/IMPORT-1/confirm', async ({ request }) => {
        confirmationCount += 1
        idempotencyKeys.push(request.headers.get('Idempotency-Key') ?? '')
        await delay(80)
        confirmed = true
        return HttpResponse.json({ id: 'IMPORT-1' }, { status: 202 })
      }),
    )
    const router = await renderAt()
    const user = userEvent.setup()
    const file = new File(['workbook'], 'products.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })

    await user.upload(screen.getByLabelText('选择商品文件'), file)
    await fireEvent.click(screen.getByRole('button', { name: '上传并预检' }))
    expect(screen.getByLabelText('选择商品文件')).toBeDisabled()

    expect(await screen.findByText('预检完成，等待确认')).toBeVisible()
    expect(screen.getByText('预计新建')).toBeVisible()
    expect(screen.getByText('12')).toBeVisible()
    expect(screen.queryByText('已导入 12 个商品')).not.toBeInTheDocument()
    expect(router.currentRoute.value.query.importJobId).toBe('IMPORT-1')

    const confirmButton = screen.getByRole('button', { name: '确认执行导入' })
    await fireEvent.click(confirmButton)
    await fireEvent.click(confirmButton)
    expect(confirmButton).toBeDisabled()
    expect(screen.getByLabelText('选择商品文件')).toBeDisabled()
    expect(screen.getByRole('button', { name: '上传并预检' })).toBeDisabled()

    expect(await screen.findByText('部分成功')).toBeVisible()
    expect(screen.getByRole('button', { name: '下载错误明细' })).toBeVisible()
    expect(confirmationCount).toBe(1)
    expect(idempotencyKeys).toHaveLength(1)
    expect(idempotencyKeys[0]).toMatch(/\S+/)
  })

  it('resumes an import task from the job ID in the URL', async () => {
    server.use(
      http.get('/api/products/imports/IMPORT-RESUME', () => HttpResponse.json({
        ...jobBase,
        id: 'IMPORT-RESUME',
        filename: 'resumed.xlsx',
        status: 'RUNNING',
        startedAt: '2026-07-26T08:01:00Z',
      })),
    )

    await renderAt('/products/imports?importJobId=IMPORT-RESUME')

    expect(await screen.findByText('正在导入')).toBeVisible()
    expect(screen.getByText('任务号：IMPORT-RESUME')).toBeVisible()
    expect(screen.getByText('resumed.xlsx')).toBeVisible()
  })

  it('reuses the confirmation idempotency key when the response is lost', async () => {
    const keys: string[] = []
    let accepted = false
    server.use(
      http.get('/api/products/imports/IMPORT-RETRY', () => HttpResponse.json({
        ...jobBase,
        id: 'IMPORT-RETRY',
        status: accepted ? 'SUCCEEDED' : 'PREFLIGHT_READY',
        finishedAt: accepted ? '2026-07-26T08:03:00Z' : null,
      })),
      http.post('/api/products/imports/IMPORT-RETRY/confirm', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        if (keys.length === 1) {
          return HttpResponse.json(
            { code: 'GATEWAY_TIMEOUT', message: '响应丢失' },
            { status: 504 },
          )
        }
        accepted = true
        return HttpResponse.json({ id: 'IMPORT-RETRY' }, { status: 202 })
      }),
    )
    await renderAt('/products/imports?importJobId=IMPORT-RETRY')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '确认执行导入' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('响应丢失')
    await user.click(screen.getByRole('button', { name: '确认执行导入' }))

    expect(await screen.findByText('导入成功')).toBeVisible()
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
  })

  it('offers manual recovery when loading a resumed task fails', async () => {
    let requests = 0
    server.use(
      http.get('/api/products/imports/IMPORT-RECOVER', () => {
        requests += 1
        if (requests === 1) {
          return HttpResponse.json(
            { code: 'TEMPORARY_FAILURE', message: '任务暂时不可用' },
            { status: 503 },
          )
        }
        return HttpResponse.json({
          ...jobBase,
          id: 'IMPORT-RECOVER',
          filename: 'recovered.xlsx',
          status: 'RUNNING',
          startedAt: '2026-07-26T08:01:00Z',
        })
      }),
    )
    await renderAt('/products/imports?importJobId=IMPORT-RECOVER')
    const user = userEvent.setup()

    expect(await screen.findByRole('alert')).toHaveTextContent('任务暂时不可用')
    await user.click(screen.getByRole('button', { name: '手动刷新' }))

    expect(await screen.findByText('正在导入')).toBeVisible()
    expect(screen.getByText('recovered.xlsx')).toBeVisible()
  })

  it('creates a filtered export once and enables download only after success', async () => {
    let exportRequests = 0
    const requestedUrls: URL[] = []
    const idempotencyKeys: string[] = []
    server.use(
      http.post('/api/products/exports', async ({ request }) => {
        exportRequests += 1
        requestedUrls.push(new URL(request.url))
        idempotencyKeys.push(request.headers.get('Idempotency-Key') ?? '')
        await delay(80)
        return HttpResponse.json({ id: 'EXPORT-1' }, { status: 202 })
      }),
      http.get('/api/products/exports/EXPORT-1', () => HttpResponse.json({
        id: 'EXPORT-1',
        status: 'SUCCEEDED',
        objectKey: 'exports/EXPORT-1.xlsx',
        createdAt: '2026-07-26T08:00:00Z',
        finishedAt: '2026-07-26T08:02:00Z',
      })),
    )
    const router = await renderAt()
    const user = userEvent.setup()

    await user.click(screen.getByRole('tab', { name: '商品导出' }))
    await user.type(screen.getByLabelText('商品/SKU 关键词'), '保温杯')
    await user.type(screen.getByLabelText('条码'), '690000000001')
    await user.selectOptions(screen.getByLabelText('商品状态'), 'ACTIVE')
    const createButton = screen.getByRole('button', { name: '创建导出任务' })
    await fireEvent.click(createButton)
    await fireEvent.click(createButton)
    expect(createButton).toBeDisabled()

    expect(await screen.findByText('导出文件已生成')).toBeVisible()
    expect(screen.getByRole('button', { name: '下载导出文件' })).toBeVisible()
    expect(exportRequests).toBe(1)
    expect(idempotencyKeys[0]).toMatch(/\S+/)
    expect(requestedUrls[0].searchParams.get('keyword')).toBe('保温杯')
    expect(requestedUrls[0].searchParams.get('barcode')).toBe('690000000001')
    expect(requestedUrls[0].searchParams.get('status')).toBe('ACTIVE')
    await waitFor(() => expect(router.currentRoute.value.query.exportJobId).toBe('EXPORT-1'))
  })

  it('resumes a completed export task from the URL', async () => {
    server.use(
      http.get('/api/products/exports/EXPORT-RESUME', () => HttpResponse.json({
        id: 'EXPORT-RESUME',
        status: 'SUCCEEDED',
        objectKey: 'exports/EXPORT-RESUME.xlsx',
        createdAt: '2026-07-26T08:00:00Z',
        finishedAt: '2026-07-26T08:02:00Z',
      })),
    )

    await renderAt('/products/imports?mode=export&exportJobId=EXPORT-RESUME')

    expect(await screen.findByText('导出文件已生成')).toBeVisible()
    expect(screen.getByText('任务号：EXPORT-RESUME')).toBeVisible()
    expect(screen.getByRole('button', { name: '下载导出文件' })).toBeVisible()
  })

  it('reuses the export idempotency key after a failed response', async () => {
    const keys: string[] = []
    const urls: URL[] = []
    server.use(
      http.post('/api/products/exports', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '')
        urls.push(new URL(request.url))
        if (keys.length === 1) {
          return HttpResponse.json(
            { code: 'GATEWAY_TIMEOUT', message: '导出响应丢失' },
            { status: 504 },
          )
        }
        return HttpResponse.json({ id: 'EXPORT-RETRY' }, { status: 202 })
      }),
      http.get('/api/products/exports/EXPORT-RETRY', () => HttpResponse.json({
        id: 'EXPORT-RETRY',
        status: 'SUCCEEDED',
        objectKey: 'exports/EXPORT-RETRY.xlsx',
        createdAt: '2026-07-26T08:00:00Z',
        finishedAt: '2026-07-26T08:02:00Z',
      })),
    )
    await renderAt('/products/imports?mode=export')
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('商品/SKU 关键词'), '原始条件')
    await user.click(screen.getByRole('button', { name: '创建导出任务' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('导出响应丢失')
    expect(screen.getByLabelText('商品/SKU 关键词')).toBeDisabled()
    expect(screen.getByRole('button', { name: '放弃本次重试并修改条件' })).toBeVisible()
    await user.click(screen.getByRole('button', { name: '创建导出任务' }))

    expect(await screen.findByText('导出文件已生成')).toBeVisible()
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
    expect(urls.map((url) => url.searchParams.get('keyword'))).toEqual(['原始条件', '原始条件'])
  })
})
