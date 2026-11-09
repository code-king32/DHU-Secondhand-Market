<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getUser, type PublicProfile } from '@/api/auth'
import { getReputation, listReviews, type Reputation, type Review } from '@/api/community'
import LoadError from '@/components/LoadError.vue'
import { PAGE_SIZES } from '@/composables/usePageList'
import { formatTime } from '@/utils/format'

const route = useRoute()
const id = Number(route.params.id)
const profile = ref<PublicProfile | null>(null)
const reputation = ref<Reputation | null>(null)
const reviews = ref<Review[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const error = ref(false)

async function load() {
  loading.value = true
  error.value = false
  try {
    profile.value = await getUser(id)
    reputation.value = await getReputation(id)
    const data = await listReviews(id, { page: page.value, size: size.value })
    reviews.value = data.items
    total.value = data.total
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
}

function scrollTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function changePage(p: number) {
  page.value = p
  scrollTop()
  load()
}

function changeSize(s: number) {
  size.value = s
  page.value = 1
  scrollTop()
  load()
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <LoadError v-if="error" @retry="load" />
    <el-card v-if="profile && !error">
      <template #header>用户资料</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="昵称">{{ profile.nickname }}</el-descriptions-item>
        <el-descriptions-item label="校园邮箱认证">{{ profile.emailVerified ? '已认证' : '未认证' }}</el-descriptions-item>
        <el-descriptions-item label="信誉评价">{{ reputation?.reviewCount ?? 0 }} 条</el-descriptions-item>
        <el-descriptions-item label="平均评分">
          {{ reputation ? reputation.averageRating.toFixed(1) : '0.0' }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-if="!error" class="reviews">
      <template #header>收到的评价</template>
      <el-empty v-if="!loading && reviews.length === 0" description="暂无评价" />
      <div v-for="r in reviews" :key="r.id" class="review">
        <el-rate :model-value="r.rating" disabled />
        <p class="comment">{{ r.comment }}</p>
        <span class="meta">来自用户 #{{ r.reviewerId }} · {{ formatTime(r.createdAt) }}</span>
      </div>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :page-sizes="PAGE_SIZES"
        :total="total"
        layout="prev, pager, next, sizes, total"
        class="pager"
        @current-change="changePage"
        @size-change="changeSize"
      />
    </el-card>
  </div>
</template>

<style scoped>
.reviews {
  margin-top: 16px;
}
.review {
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;
}
.comment {
  margin: 8px 0;
}
.meta {
  color: #909399;
  font-size: 13px;
}
.pager {
  margin-top: 16px;
}
</style>
