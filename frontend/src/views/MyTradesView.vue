<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listTrades, type Trade } from '@/api/trades'
import { useAuthStore } from '@/stores/auth'
import { TRADE_STATUS_LABELS, tradeStatusTag } from '@/constants'
import { usePageList } from '@/composables/usePageList'
import LoadError from '@/components/LoadError.vue'

const router = useRouter()
const auth = useAuthStore()
const role = ref<string | number>('ALL')
const { items: rows, total, page, size, loading, error, load, reset } = usePageList<Trade>(
  (page, size) => listTrades({ role: String(role.value), page, size }),
)

function onTabChange() {
  reset()
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
    <LoadError v-if="error" @retry="load" />
    <el-table v-else :data="rows" v-loading="loading" border>
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
