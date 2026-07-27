import { render, screen } from '@testing-library/vue'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/server'
import OrderExceptionView from './OrderExceptionView.vue'

describe('OrderExceptionView', () => {
  it('shows only SKU-not-mapped orders with the platform SKU to resolve', async () => {
    server.use(
      http.get('/api/orders', () =>
        HttpResponse.json([
          {
            id: 'SO-EXCEPTION',
            platform: 'DOUYIN',
            shopId: '华东旗舰店',
            platformOrderId: 'DY-90001',
            status: 'SKU_NOT_MAPPED',
            paidAt: '2026-07-23T01:58:33Z',
            lines: [
              {
                platformSkuId: 'DY-UNKNOWN-01',
                internalSkuCode: null,
                quantity: 1,
                paidAmountFen: 9900,
              },
            ],
            exceptionCode: 'SKU_NOT_MAPPED',
            receiverPhone: '138****0000',
          },
          {
            id: 'SO-NORMAL',
            platform: 'PINDUODUO',
            shopId: '华东旗舰店',
            platformOrderId: 'PDD-10001',
            status: 'READY_TO_FULFILL',
            paidAt: '2026-07-23T02:23:45Z',
            lines: [],
            exceptionCode: '',
            receiverPhone: '138****0000',
          },
        ]),
      ),
    )

    render(OrderExceptionView)

    expect(await screen.findByText('SO-EXCEPTION')).toBeVisible()
    expect(screen.getByText('DY-UNKNOWN-01')).toBeVisible()
    expect(screen.getByText('待映射')).toBeVisible()
    expect(screen.queryByText('SO-NORMAL')).not.toBeInTheDocument()
  })
})
