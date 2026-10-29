import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue') },
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      children: [
        { path: '', name: 'home', component: () => import('@/views/HomeView.vue') },
        { path: 'goods/new', name: 'goods-new', component: () => import('@/views/GoodsEditView.vue'), meta: { requiresAuth: true } },
        { path: 'goods/:id', name: 'goods-detail', component: () => import('@/views/GoodsDetailView.vue') },
        { path: 'goods/:id/edit', name: 'goods-edit', component: () => import('@/views/GoodsEditView.vue'), meta: { requiresAuth: true } },
        { path: 'users/:id', name: 'user-profile', component: () => import('@/views/UserProfileView.vue') },
        { path: 'wanted', name: 'wanted', component: () => import('@/views/WantedView.vue') },
        { path: 'trades/:id', name: 'trade-detail', component: () => import('@/views/TradeDetailView.vue'), meta: { requiresAuth: true } },
        { path: 'mine/goods', name: 'my-goods', component: () => import('@/views/MyGoodsView.vue'), meta: { requiresAuth: true } },
        { path: 'mine/trades', name: 'my-trades', component: () => import('@/views/MyTradesView.vue'), meta: { requiresAuth: true } },
        { path: 'mine/favorites', name: 'my-favorites', component: () => import('@/views/MyFavoritesView.vue'), meta: { requiresAuth: true } },
        { path: 'mine/reports', name: 'my-reports', component: () => import('@/views/MyReportsView.vue'), meta: { requiresAuth: true } },
        { path: 'mine/account', name: 'my-account', component: () => import('@/views/MyAccountView.vue'), meta: { requiresAuth: true } },
        { path: 'admin/reports', name: 'admin-reports', component: () => import('@/views/admin/AdminReportsView.vue'), meta: { requiresAuth: true, requiresAdmin: true } },
        { path: 'admin/appeals', name: 'admin-appeals', component: () => import('@/views/admin/AdminAppealsView.vue'), meta: { requiresAuth: true, requiresAdmin: true } },
      ],
    },
  ],
})

export default router
