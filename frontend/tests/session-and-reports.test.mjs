import assert from 'node:assert/strict'
import { after, beforeEach, test } from 'node:test'
import { createServer } from 'vite'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createRenderer, nextTick, ssrContextKey } from 'vue'
import { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

// 加载实际应用模块，用内存路由和 HTTP 适配器模拟服务器响应。
const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
const { default: http } = await server.ssrLoadModule('/src/api/http.ts')
const { useAuthStore } = await server.ssrLoadModule('/src/stores/auth.ts')
const { installAuthHandling } = await server.ssrLoadModule('/src/router/auth.ts')
const { default: Reports } = await server.ssrLoadModule('/src/views/admin/AdminReportsView.vue')
const oldError = ElMessage.error
const oldSuccess = ElMessage.success
ElMessage.error = () => {}
ElMessage.success = () => {}

const user = { id: 1, nickname: '测试用户', role: 'USER', emailVerified: true, status: 0 }
const emptyPage = { items: [], total: 0, page: 1, size: 20 }
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: {} },
    { path: '/login', component: {} },
    { path: '/mine/goods', component: {}, meta: { requiresAuth: true } },
    { path: '/admin/reports', component: {}, meta: { requiresAuth: true, requiresAdmin: true } },
  ],
})
const pinia = createPinia()
const auth = useAuthStore(pinia)
installAuthHandling(router, pinia)

function respond(data, status = 200) {
  return async (config) => {
    const response = { status, statusText: '', headers: {}, config, data }
    if (status >= 400) throw new AxiosError('请求失败', 'ERR_BAD_REQUEST', config, undefined, response)
    return response
  }
}

beforeEach(async () => {
  http.defaults.adapter = respond(user)
  await router.push('/')
  auth.user = null
})

after(async () => {
  ElMessage.error = oldError
  ElMessage.success = oldSuccess
  await server.close()
})

test('缓存的用户信息不能绕过会话过期校验', async () => {
  auth.user = user
  http.defaults.adapter = respond({ message: '请先登录' }, 401)
  await router.push('/mine/goods?page=2')
  assert.equal(auth.user, null)
  assert.equal(router.currentRoute.value.path, '/login')
  assert.equal(router.currentRoute.value.query.redirect, '/mine/goods?page=2')
})

test('有效会话可以正常进入受保护页面', async () => {
  await router.push('/mine/goods')
  assert.equal(router.currentRoute.value.path, '/mine/goods')
  assert.equal(auth.user.id, user.id)
})

test('页面请求遇到 401 后清理状态并保留登录后的返回地址', async () => {
  await router.push('/mine/goods?page=2')
  http.defaults.adapter = respond({ message: '登录状态已失效' }, 401)
  const navigated = new Promise((resolve) => {
    const remove = router.afterEach(() => { remove(); resolve() })
  })
  await assert.rejects(http.get('/goods/mine'))
  await navigated
  assert.equal(auth.user, null)
  assert.equal(router.currentRoute.value.path, '/login')
  assert.equal(router.currentRoute.value.query.redirect, '/mine/goods?page=2')
})

test('公开页面收到 401 只清理登录状态，不强制跳转', async () => {
  auth.user = user
  http.defaults.adapter = respond({}, 401)
  await assert.rejects(http.get('/favorites'))
  assert.equal(auth.user, null)
  assert.equal(router.currentRoute.value.path, '/')
})

test('登录密码错误不会误判为现有会话过期', async () => {
  auth.user = user
  http.defaults.adapter = respond({}, 401)
  await assert.rejects(http.post('/auth/login', {}))
  assert.equal(auth.user.id, user.id)
  assert.equal(router.currentRoute.value.path, '/')
})

test('普通 403 权限错误不会清除登录状态', async () => {
  await router.push('/mine/goods')
  http.defaults.adapter = respond({}, 403)
  await assert.rejects(http.get('/admin/reports'))
  assert.equal(auth.user.id, user.id)
  assert.equal(router.currentRoute.value.path, '/mine/goods')
})

test('管理员角色变化后不能靠缓存继续访问管理页', async () => {
  auth.user = { ...user, role: 'ADMIN' }
  await router.push('/admin/reports')
  assert.equal(auth.user.role, 'USER')
  assert.equal(router.currentRoute.value.path, '/')
})

const renderer = createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
})

for (const [targetType, action] of [['GOODS', 'HIDE_GOODS'], ['USER', 'DISABLE_USER']]) {
  test(`${targetType} 举报从成立切为驳回后，提交不包含旧处置`, async () => {
    const requests = []
    http.defaults.adapter = async (config) => {
      requests.push(config)
      return respond(emptyPage)(config)
    }
    // 只挂载实际组件的状态与事件逻辑，省去 Element Plus 的 DOM 渲染。
    const app = renderer.createApp({ ...Reports, render: () => null })
    app.provide(ssrContextKey, {})
    const vm = app.mount({})
    try {
      const state = vm.$.setupState
      state.openResolve({ id: 7, targetType })
      state.form.action = action
      state.form.decision = 'REJECTED'
      state.form.note = '证据不足'
      // 在 watcher 刷新前也必须提交合法请求。
      await state.submitResolve()
      const submitted = requests.find((request) => request.method === 'put')
      assert.equal(submitted.url, '/admin/reports/7/resolution')
      assert.deepEqual(JSON.parse(submitted.data), {
        decision: 'REJECTED', note: '证据不足', action: 'NONE',
      })
      await nextTick()
      assert.equal(state.form.action, 'NONE')
      state.form.decision = 'RESOLVED'
      await nextTick()
      assert.equal(state.form.action, 'NONE')
    } finally {
      app.unmount()
    }
  })
}
