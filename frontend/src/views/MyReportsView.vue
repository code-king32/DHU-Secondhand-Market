<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listMyReports,
  createAppeal,
  listAppeals,
  type Report,
  type Appeal,
} from '@/api/community'
import {
  REPORT_STATUS_LABELS,
  APPEAL_STATUS_LABELS,
  TARGET_TYPE_LABELS,
  reportStatusTag,
} from '@/constants'

const rows = ref<Report[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const appealVisible = ref(false)
const appealReason = ref('')
const current = ref<Report | null>(null)
const appealSaving = ref(false)

const appealsVisible = ref(false)
const appeals = ref<Appeal[]>([])

async function load() {
  loading.value = true
  try {
    const data = await listMyReports({ page: page.value, size: size.value })
    rows.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function openAppeal(r: Report) {
  current.value = r
  appealReason.value = ''
  appealVisible.value = true
}

async function submitAppeal() {
  if (!current.value) return
  if (!appealReason.value.trim()) {
    ElMessage.warning('请填写申诉理由')
    return
  }
  appealSaving.value = true
  try {
    await createAppeal(current.value.id, appealReason.value.trim())
    ElMessage.success('申诉已提交')
    appealVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    appealSaving.value = false
  }
}

async function openAppeals(r: Report) {
  try {
    appeals.value = await listAppeals(r.id)
  } catch {
    appeals.value = []
  }
  appealsVisible.value = true
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>我的举报</template>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="对象" width="120">
        <template #default="{ row }">
          {{ TARGET_TYPE_LABELS[row.targetType] ?? row.targetType }} #{{ row.targetId }}
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="理由" min-width="200" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="reportStatusTag(row.status)">
            {{ REPORT_STATUS_LABELS[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button v-if="row.status !== 'OPEN'" size="small" @click="openAppeal(row)">申诉</el-button>
          <el-button v-if="row.status !== 'OPEN'" size="small" @click="openAppeals(row)">查看申诉</el-button>
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

    <el-dialog v-model="appealVisible" title="提交申诉" width="480px">
      <el-input v-model="appealReason" type="textarea" :rows="4" maxlength="1000" placeholder="申诉理由" />
      <template #footer>
        <el-button @click="appealVisible = false">取消</el-button>
        <el-button type="primary" :loading="appealSaving" @click="submitAppeal">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="appealsVisible" title="申诉记录" width="560px">
      <el-empty v-if="appeals.length === 0" description="暂无申诉" />
      <div v-for="a in appeals" :key="a.id" class="appeal">
        <p>{{ a.reason }}</p>
        <span class="meta">
          {{ APPEAL_STATUS_LABELS[a.status] ?? a.status }} · {{ a.createdAt }}
        </span>
      </div>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.pager {
  margin-top: 16px;
}
.appeal {
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;
}
.meta {
  color: #909399;
  font-size: 13px;
}
</style>
