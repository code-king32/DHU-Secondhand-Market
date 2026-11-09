<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listFavorites, unfavorite, type Favorite } from '@/api/community'
import { GOODS_STATUS_LABELS, goodsStatusTag } from '@/constants'
import { usePageList, PAGE_SIZES } from '@/composables/usePageList'
import LoadError from '@/components/LoadError.vue'

const router = useRouter()
const { items: rows, total, page, size, loading, error, load, changePage, changeSize } = usePageList<Favorite>(
  (page, size) => listFavorites({ page, size }),
)

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
    <LoadError v-if="error" @retry="load" />
    <el-table v-else :data="rows" v-loading="loading" border>
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
      v-model:page-size="size"
      :page-sizes="PAGE_SIZES"
      :total="total"
      layout="prev, pager, next, sizes, total"
      class="pager"
      @current-change="changePage"
      @size-change="changeSize"
    />
  </el-card>
</template>

<style scoped>
.pager {
  margin-top: 16px;
}
</style>
