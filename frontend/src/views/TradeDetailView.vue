<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getTrade, acceptTrade, confirmTrade, cancelTrade, type Trade } from '@/api/trades'
import { getGoods, type Goods } from '@/api/goods'
import { createReview } from '@/api/community'
import { useAuthStore } from '@/stores/auth'
import { TRADE_STATUS_LABELS, tradeStatusTag } from '@/constants'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = Number(route.params.id)
const trade = ref<Trade | null>(null)
const goods = ref<Goods | null>(null)
const loading = ref(false)

const reviewVisible = ref(false)
const rating = ref(5)
const comment = ref('')
const submitting = ref(false)

const isBuyer = computed(() => auth.user?.id === trade.value?.buyerId)
const isSeller = computed(() => auth.user?.id === trade.value?.sellerId)

async function load() {
  loading.value = true
  try {
    trade.value = await getTrade(id)
    // 补充商品信息（公开详情对 ON_SALE/TRADING/SOLD 均可见）
    try {
      goods.value = await getGoods(trade.value.goodsId)
    } catch {
      goods.value = null
    }
  } finally {
    loading.value = false
  }
}

async function act(fn: () => Promise<Trade>, msg: string) {
  try {
    await fn()
    ElMessage.success(msg)
    load()
  } catch {
    // 拦截器已提示
  }
}

async function submitReview() {
  submitting.value = true
  try {
    await createReview({ tradeId: id, rating: rating.value, comment: comment.value })
    ElMessage.success('评价已提交')
    reviewVisible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <el-card v-if="trade">
      <template #header>
        <div class="head">
          <span>交易 #{{ trade.id }}</span>
          <el-tag :type="tradeStatusTag(trade.status)">
            {{ TRADE_STATUS_LABELS[trade.status] ?? trade.status }}
          </el-tag>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="商品">
          <router-link v-if="goods" :to="`/goods/${trade.goodsId}`">{{ goods.title }}</router-link>
          <span v-else>#{{ trade.goodsId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="成交价">¥{{ trade.agreedPrice }}</el-descriptions-item>
        <el-descriptions-item label="买家">#{{ trade.buyerId }}</el-descriptions-item>
        <el-descriptions-item label="卖家">#{{ trade.sellerId }}</el-descriptions-item>
        <el-descriptions-item label="买家确认">{{ trade.buyerConfirmed ? '已确认' : '未确认' }}</el-descriptions-item>
        <el-descriptions-item label="卖家确认">{{ trade.sellerConfirmed ? '已确认' : '未确认' }}</el-descriptions-item>
      </el-descriptions>

      <div class="actions">
        <template v-if="isSeller && trade.status === 'PENDING'">
          <el-button type="primary" @click="act(() => acceptTrade(id), '已接受申请')">接受申请</el-button>
        </template>
        <template
          v-if="
            trade.status === 'ACCEPTED' &&
            ((isBuyer && !trade.buyerConfirmed) || (isSeller && !trade.sellerConfirmed))
          "
        >
          <el-button type="success" @click="act(() => confirmTrade(id), '已确认')">确认完成</el-button>
        </template>
        <template v-if="(trade.status === 'PENDING' || trade.status === 'ACCEPTED') && (isBuyer || isSeller)">
          <el-button type="danger" plain @click="act(() => cancelTrade(id), '已取消交易')">取消交易</el-button>
        </template>
        <template v-if="trade.status === 'COMPLETED' && (isBuyer || isSeller)">
          <el-button @click="reviewVisible = true">评价对方</el-button>
        </template>
        <el-button @click="router.back()">返回</el-button>
      </div>
    </el-card>

    <el-dialog v-model="reviewVisible" title="评价交易" width="480px">
      <div class="review-form">
        <p>评分：<el-rate v-model="rating" /></p>
        <el-input v-model="comment" type="textarea" :rows="4" maxlength="1000" placeholder="评价内容" />
      </div>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReview">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.actions {
  margin-top: 16px;
  display: flex;
  gap: 12px;
}
.review-form p {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
