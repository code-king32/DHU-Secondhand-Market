<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listGoods, type Goods } from '@/api/goods'
import { CATEGORY_LABELS } from '@/constants'

const router = useRouter()
const goods = ref<Goods[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const keyword = ref('')
const category = ref('')
const loading = ref(false)

const categories = Object.entries(CATEGORY_LABELS)

async function load() {
  loading.value = true
  try {
    const data = await listGoods({
      keyword: keyword.value || undefined,
      category: category.value || undefined,
      page: page.value,
      size: size.value,
    })
    goods.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
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

    <el-row :gutter="16">
      <el-col v-for="item in goods" :key="item.id" :span="6">
        <el-card class="goods-card" @click="router.push(`/goods/${item.id}`)">
          <template #header>{{ item.title }}</template>
          <p class="category">{{ CATEGORY_LABELS[item.category] ?? item.category }}</p>
          <p class="price">¥{{ item.price }}</p>
        </el-card>
      </el-col>
    </el-row>
    <el-empty v-if="!loading && goods.length === 0" description="暂无在售商品" />

    <el-pagination
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next, total"
      class="pager"
      @current-change="load"
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
