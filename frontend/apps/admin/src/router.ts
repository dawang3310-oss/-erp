import { createRouter, createWebHistory } from 'vue-router'
import OrderExceptionView from './views/exceptions/OrderExceptionView.vue'
import OrderListView from './views/orders/OrderListView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/orders' },
    { path: '/orders', name: 'orders', component: OrderListView },
    { path: '/exceptions/orders', name: 'order-exceptions', component: OrderExceptionView },
    { path: '/:pathMatch(.*)*', redirect: '/orders' },
  ],
})
