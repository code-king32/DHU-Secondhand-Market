<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listGoods, type Goods } from '@/api/goods'
import { CATEGORY_LABELS } from '@/constants'
import { usePageList, PAGE_SIZES } from '@/composables/usePageList'
import LoadError from '@/components/LoadError.vue'

const router = useRouter()
const keyword = ref('')
const category = ref('')
const { items: goods, total, page, size, loading, error, load, reset, changePage, changeSize } = usePageList<Goods>(
  (page, size) => listGoods({
    keyword: keyword.value || undefined,
    category: category.value || undefined,
    page,
    size,
  }),
  { size: 12 },
)

const categories = Object.entries(CATEGORY_LABELS)

function search() {
  reset()
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索标题或描述"
        clearable
        class="search"
        @keyup.enter="search"
      />
      <el-select v-model="category" placeholder="全部分类" clearable style="width: 160px" @change="search">
        <el-option v-for="[value, label] in categories" :key="value" :label="label" :value="value" />
      </el-select>
      <el-button type="primary" @click="search">搜索</el-button>
    </div>

    <LoadError v-if="error" @retry="load" />
    <el-row v-else-if="goods.length" :gutter="16">
      <el-col v-for="item in goods" :key="item.id" :xs="24" :sm="12" :md="8" :lg="6">
        <el-card class="goods-card" @click="router.push(`/goods/${item.id}`)">
          <template #header>{{ item.title }}</template>
          <el-image
            v-if="item.imageUrls.length"
            :src="item.imageUrls[0]"
            fit="cover"
            class="goods-thumb"
            lazy
          />
          <div v-else class="goods-thumb goods-thumb--empty">暂无图片</div>
          <p class="category">{{ CATEGORY_LABELS[item.category] ?? item.category }}</p>
          <p class="price">¥{{ item.price }}</p>
        </el-card>
      </el-col>
    </el-row>
    <el-empty v-else-if="!loading" description="暂无在售商品" />

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
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}
.search {
  width: 300px;
}
.goods-card {
  margin-bottom: 16px;
  cursor: pointer;
}
.goods-thumb {
  width: 100%;
  height: 140px;
  border-radius: 4px;
  margin-bottom: 8px;
}
.goods-thumb--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
  color: #c0c4cc;
  font-size: 13px;
}
.category {
  color: #909399;
  font-size: 13px;
}
.price {
  color: #f56c6c;
  font-weight: 600;
}
.pager {
  margin-top: 16px;
}
</style>
