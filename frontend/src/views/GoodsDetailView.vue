<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getGoods, type Goods } from '@/api/goods'
import { applyTrade } from '@/api/trades'
import { favorite, createReport } from '@/api/community'
import { useAuthStore } from '@/stores/auth'
import { CATEGORY_LABELS, GOODS_STATUS_LABELS, goodsStatusTag } from '@/constants'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = Number(route.params.id)
const goods = ref<Goods | null>(null)
const loading = ref(false)

const isOwner = computed(() => auth.user?.id === goods.value?.sellerId)

async function load() {
  loading.value = true
  try {
    goods.value = await getGoods(id)
  } finally {
    loading.value = false
  }
}

function requireLogin() {
  if (!auth.user) {
    ElMessage.warning('请先登录')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return false
  }
  return true
}

async function doApply() {
  if (!requireLogin()) return
  const ok = await ElMessageBox.confirm('确认申请购买该商品？', '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  try {
    await applyTrade(id)
    ElMessage.success('申请已提交')
    router.push('/mine/trades')
  } catch {
    // 拦截器已提示
  }
}

async function doFavorite() {
  if (!requireLogin()) return
  try {
    await favorite(id)
    ElMessage.success('已收藏')
  } catch {
    // 拦截器已提示
  }
}

async function doReport() {
  if (!requireLogin()) return
  const value = await ElMessageBox.prompt('请填写举报理由', '举报商品', { inputType: 'textarea' })
    .then(({ value }) => value)
    .catch(() => '')
  if (!value || !value.trim()) {
    ElMessage.warning('理由不能为空')
    return
  }
  try {
    await createReport({ targetType: 'GOODS', targetId: id, reason: value.trim() })
    ElMessage.success('举报已提交')
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <template v-if="goods">
      <el-card>
        <template #header>
          <div class="head">
            <span>{{ goods.title }}</span>
            <el-tag :type="goodsStatusTag(goods.status)">
              {{ GOODS_STATUS_LABELS[goods.status] ?? goods.status }}
            </el-tag>
          </div>
        </template>
        <div class="price">¥{{ goods.price }}</div>
        <p class="cat">{{ CATEGORY_LABELS[goods.category] ?? goods.category }}</p>
        <p class="desc">{{ goods.description }}</p>
        <div v-if="goods.imageUrls.length" class="imgs">
          <img v-for="(u, i) in goods.imageUrls" :key="i" :src="u" alt="商品图片" />
        </div>
        <div class="meta">
          <router-link :to="`/users/${goods.sellerId}`" class="link">查看卖家主页</router-link>
          <span>发布于 {{ goods.createdAt }}</span>
        </div>
      </el-card>

      <div class="actions">
        <template v-if="isOwner">
          <el-button type="primary" @click="router.push(`/goods/${goods.id}/edit`)">编辑商品</el-button>
        </template>
        <template v-else>
          <template v-if="goods.status === 'ON_SALE'">
            <el-button type="primary" @click="doApply">申请购买</el-button>
            <el-button @click="doFavorite">收藏</el-button>
          </template>
          <el-button type="danger" plain @click="doReport">举报</el-button>
        </template>
      </div>
    </template>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.price {
  color: #f56c6c;
  font-size: 24px;
  font-weight: 700;
}
.cat {
  color: #909399;
}
.desc {
  margin: 12px 0;
  white-space: pre-wrap;
}
.imgs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.imgs img {
  width: 160px;
  height: 120px;
  object-fit: cover;
  border-radius: 4px;
}
.meta {
  display: flex;
  justify-content: space-between;
  margin-top: 12px;
  color: #909399;
  font-size: 13px;
}
.link {
  color: #409eff;
}
.actions {
  margin-top: 16px;
  display: flex;
  gap: 12px;
}
</style>
