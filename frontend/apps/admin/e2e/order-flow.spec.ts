import { expect, test } from '@playwright/test'
import { demoOrders } from '../src/data/demoOrders'

const reservedOrder = demoOrders[0]!

test.beforeEach(async ({ page }) => {
  await page.route(/\/api\/orders(?:\/[^/?]+)?(?:\?.*)?$/, async (route) => {
    const path = new URL(route.request().url()).pathname
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(path === `/api/orders/${reservedOrder.id}` ? reservedOrder : demoOrders),
    })
  })
})

test('operator opens a reserved fulfillment', async ({ page }) => {
  await page.goto('/orders')
  await page.getByRole('button', { name: `查看订单 ${reservedOrder.id}` }).click()

  const drawer = page.getByRole('dialog', { name: '订单详情' })
  await expect(drawer.getByTestId('detail-order-status')).toHaveText('已锁库存')
  await expect(drawer.getByText('138****0000', { exact: true })).toBeVisible()
  await page.screenshot({ path: 'implementation-orders-drawer-native.png' })
})

test('order workbench remains usable at mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/orders')

  await expect(page.getByRole('heading', { name: '订单管理' })).toBeVisible()
  await expect(page.getByRole('button', { name: reservedOrder.id, exact: true })).toBeVisible()
  const hasBodyOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(hasBodyOverflow).toBe(false)
  await page.screenshot({ path: 'implementation-orders-mobile.png' })
})
