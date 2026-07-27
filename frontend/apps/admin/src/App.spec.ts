import { render, screen, within } from '@testing-library/vue'
import { describe, expect, it } from 'vitest'
import App from './App.vue'
import { router } from './router'

describe('product navigation', () => {
  it('registers the complete product route skeleton', () => {
    expect(router.getRoutes().map((route) => route.name)).toEqual(expect.arrayContaining([
      'products',
      'product-new',
      'product-detail',
      'product-edit',
      'product-imports',
    ]))
  })

  it('activates the product navigation and derives breadcrumbs from route metadata', async () => {
    await router.push('/products/imports')
    await router.isReady()

    render(App, {
      global: {
        plugins: [router],
      },
    })

    expect(screen.getByRole('link', { name: '商品' })).toHaveClass('active')
    expect(within(screen.getByLabelText('面包屑')).getByText('商品导入')).toBeVisible()
  })
})
