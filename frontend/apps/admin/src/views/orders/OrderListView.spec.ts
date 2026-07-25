import { render, screen, within } from '@testing-library/vue'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/server'
import OrderListView from './OrderListView.vue'

describe('OrderListView', () => {
  it('loads filtered orders and keeps receiver masked', async () => {
    const requestedPlatforms: string[] = []
    server.use(
      http.get('/api/orders', ({ request }) => {
        requestedPlatforms.push(new URL(request.url).searchParams.get('platform') ?? '')
        return HttpResponse.json([
          {
            id: 'SO-1',
            platform: 'PINDUODUO',
            shopId: 'SHOP-1',
            platformOrderId: 'PDD-10001',
            status: 'READY_TO_FULFILL',
            paidAt: '2026-07-23T02:23:45Z',
            lines: [
              {
                platformSkuId: 'BC-500-BK',
                internalSkuCode: 'BC-500-BK',
                quantity: 1,
                paidAmountFen: 32900,
              },
            ],
            exceptionCode: '',
            receiverPhone: '138****0000',
          },
        ])
      }),
    )

    render(OrderListView)
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText('平台'), 'PINDUODUO')

    expect(await screen.findByText('SO-1')).toBeVisible()
    expect(screen.getByText('138****0000')).toBeVisible()
    expect(requestedPlatforms).toContain('PINDUODUO')
  })

  it('opens a reserved fulfillment with masked receiver details', async () => {
    const order = {
      id: 'SO-1',
      platform: 'PINDUODUO',
      shopId: '华东旗舰店',
      platformOrderId: 'PDD-10001',
      status: 'READY_TO_FULFILL',
      paidAt: '2026-07-23T02:23:45Z',
      lines: [
        {
          platformSkuId: 'BC-500-BK',
          internalSkuCode: 'BC-500-BK',
          quantity: 1,
          paidAmountFen: 9900,
        },
        {
          platformSkuId: 'MP-10K-WH',
          internalSkuCode: 'MP-10K-WH',
          quantity: 1,
          paidAmountFen: 23000,
        },
      ],
      exceptionCode: '',
      receiverPhone: '138****0000',
    }
    server.use(
      http.get('/api/orders', () => HttpResponse.json([order])),
      http.get('/api/orders/SO-1', () => HttpResponse.json(order)),
    )

    render(OrderListView)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '查看订单 SO-1' }))

    const drawer = await screen.findByRole('dialog', { name: '订单详情' })
    expect(within(drawer).getAllByText('已锁库存')).toHaveLength(2)
    expect(within(drawer).getByText('138****0000')).toBeVisible()
    expect(within(drawer).getByText('BC-500-BK')).toBeVisible()
    expect(within(drawer).getByText('MP-10K-WH')).toBeVisible()
  })
})
