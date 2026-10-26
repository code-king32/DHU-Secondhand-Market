<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listMyGoods, shelfGoods, type Goods } from '@/api/goods'
import { CATEGORY_LABELS, GOODS_STATUS_LABELS, goodsStatusTag } from '@/constants'

const router = useRouter()
const rows = ref<Goods[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await listMyGoods({ page: page.value, size: size.value })
    rows.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function editable(g: Goods) {
  return g.status === 'ON_SALE' || g.status === 'OFF_SHELF'
}

async function toggle(g: Goods) {
  const onSale = g.status === 'OFF_SHELF'
  try {
    await shelfGoods(g.id, onSale)
    ElMessage.success(onSale ? '已上架' : '已下架')
    load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>
      <div class="head">
        <span>我的商品</span>
        <el-button type="primary" size="small" @click="router.push('/goods/new')">发布商品</el-button>
      </div>
    </template>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="160" />
      <el-table-column label="分类" width="110">
        <template #default="{ row }">{{ CATEGORY_LABELS[row.category] ?? row.category }}</template>
      </el-table-column>
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
      <el-table-column label="操作" width="230">
        <template #default="{ row }">
          <el-button size="small" @click="router.push(`/goods/${row.id}`)">详情</el-button>
          <template v-if="editable(row)">
            <el-button size="small" @click="router.push(`/goods/${row.id}/edit`)">编辑</el-button>
            <el-button
              size="small"
              :type="row.status === 'OFF_SHELF' ? 'success' : 'warning'"
              @click="toggle(row)"
            >
              {{ row.status === 'OFF_SHELF' ? '上架' : '下架' }}
            </el-button>
          </template>
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
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pager {
  margin-top: 16px;
}
</style>
