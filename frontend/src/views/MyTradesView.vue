<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listTrades, type Trade } from '@/api/trades'
import { useAuthStore } from '@/stores/auth'
import { TRADE_STATUS_LABELS, tradeStatusTag } from '@/constants'

const router = useRouter()
const auth = useAuthStore()
const role = ref<string | number>('ALL')
const rows = ref<Trade[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await listTrades({ role: String(role.value), page: page.value, size: size.value })
    rows.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function onTabChange() {
  page.value = 1
  load()
}

function myRole(t: Trade) {
  return auth.user?.id === t.buyerId ? '买入' : '卖出'
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>我的交易</template>
    <el-tabs v-model="role" @tab-change="onTabChange">
      <el-tab-pane label="全部" name="ALL" />
      <el-tab-pane label="买入" name="BUYER" />
      <el-tab-pane label="卖出" name="SELLER" />
    </el-tabs>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="交易号" width="90" />
      <el-table-column prop="goodsId" label="商品号" width="90" />
      <el-table-column label="身份" width="80">
        <template #default="{ row }">{{ myRole(row) }}</template>
      </el-table-column>
      <el-table-column label="金额" width="110">
        <template #default="{ row }">¥{{ row.agreedPrice }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="tradeStatusTag(row.status)">
            {{ TRADE_STATUS_LABELS[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" @click="router.push(`/trades/${row.id}`)">查看</el-button>
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
