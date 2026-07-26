import { createRouter, createWebHistory } from 'vue-router'
import OrderExceptionView from './views/exceptions/OrderExceptionView.vue'
import OrderListView from './views/orders/OrderListView.vue'
import ProductDetailView from './views/products/ProductDetailView.vue'
import ProductEditorView from './views/products/ProductEditorView.vue'
import ProductImportView from './views/products/ProductImportView.vue'
import ProductListView from './views/products/ProductListView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/orders' },
    {
      path: '/orders',
      name: 'orders',
      component: OrderListView,
      meta: { section: 'orders', sectionLabel: '订单', title: '订单管理' },
    },
    {
      path: '/exceptions/orders',
      name: 'order-exceptions',
      component: OrderExceptionView,
      meta: { section: 'orders', sectionLabel: '订单', title: '异常订单' },
    },
    {
      path: '/products',
      name: 'products',
      component: ProductListView,
      meta: { section: 'products', sectionLabel: '商品', title: '商品列表' },
    },
    {
      path: '/products/new',
      name: 'product-new',
      component: ProductEditorView,
      meta: { section: 'products', sectionLabel: '商品', title: '新建商品' },
    },
    {
      path: '/products/imports',
      name: 'product-imports',
      component: ProductImportView,
      meta: { section: 'products', sectionLabel: '商品', title: '商品导入' },
    },
    {
      path: '/products/:id/edit',
      name: 'product-edit',
      component: ProductEditorView,
      meta: { section: 'products', sectionLabel: '商品', title: '编辑商品' },
    },
    {
      path: '/products/:id',
      name: 'product-detail',
      component: ProductDetailView,
      meta: { section: 'products', sectionLabel: '商品', title: '商品详情' },
    },
    { path: '/:pathMatch(.*)*', redirect: '/orders' },
  ],
})
