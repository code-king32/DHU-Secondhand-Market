<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listFavorites, unfavorite, type Favorite } from '@/api/community'
import { GOODS_STATUS_LABELS, goodsStatusTag } from '@/constants'

const router = useRouter()
const rows = ref<Favorite[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await listFavorites({ page: page.value, size: size.value })
    rows.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

async function remove(f: Favorite) {
  try {
    await unfavorite(f.goodsId)
    ElMessage.success('已取消收藏')
    load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>我的收藏</template>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="goodsId" label="商品号" width="90" />
      <el-table-column prop="title" label="标题" min-width="180" />
      <el-table-column label="价格" width="110">
        <template #default="{ row }">¥{{ row.price }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="goodsStatusTag(row.status)">
            {{ GOODS_STATUS_LABELS[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" @click="router.push(`/goods/${row.goodsId}`)">查看</el-button>
          <el-button size="small" type="danger" plain @click="remove(row)">取消收藏</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next, total"
      class="pager"
      @current-change="load"
    />
  </el-card>
</template>

<style scoped>
.pager {
  margin-top: 16px;
}
</style>
