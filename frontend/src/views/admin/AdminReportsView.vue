<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listAdminReports,
  resolveReport,
  getReportAudit,
  setUserStatus,
  type Report,
  type Audit,
} from '@/api/community'
import {
  REPORT_STATUS_LABELS,
  TARGET_TYPE_LABELS,
  reportStatusTag,
} from '@/constants'

const rows = ref<Report[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const loading = ref(false)

const resolveVisible = ref(false)
const current = ref<Report | null>(null)
const resolving = ref(false)
const form = reactive({ decision: 'RESOLVED', note: '', action: 'NONE' })

const auditVisible = ref(false)
const audits = ref<Audit[]>([])

const statusUserId = ref<number | null>(null)
const statusSaving = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await listAdminReports({ status: status.value || undefined, page: page.value, size: size.value })
    rows.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function filterStatus() {
  page.value = 1
  load()
}

function actionOptions() {
  if (form.decision !== 'RESOLVED') return [{ value: 'NONE', label: '不处置' }]
  if (current.value?.targetType === 'GOODS') {
    return [
      { value: 'NONE', label: '不处置' },
      { value: 'HIDE_GOODS', label: '隐藏商品' },
    ]
  }
  if (current.value?.targetType === 'USER') {
    return [
      { value: 'NONE', label: '不处置' },
      { value: 'DISABLE_USER', label: '禁用账号' },
    ]
  }
  return [{ value: 'NONE', label: '不处置' }]
}

function openResolve(r: Report) {
  current.value = r
  form.decision = 'RESOLVED'
  form.note = ''
  form.action = 'NONE'
  resolveVisible.value = true
}

async function submitResolve() {
  if (!current.value) return
  if (!form.note.trim()) {
    ElMessage.warning('请填写处理说明')
    return
  }
  resolving.value = true
  try {
    await resolveReport(current.value.id, {
      decision: form.decision,
      note: form.note.trim(),
      action: form.action,
    })
    ElMessage.success('已处理')
    resolveVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    resolving.value = false
  }
}

async function openAudit(r: Report) {
  try {
    audits.value = await getReportAudit(r.id)
  } catch {
    audits.value = []
  }
  auditVisible.value = true
}

async function applyUserStatus(s: number) {
  if (!statusUserId.value) {
    ElMessage.warning('请输入用户 ID')
    return
  }
  statusSaving.value = true
  try {
    await setUserStatus(statusUserId.value, s)
    ElMessage.success('账号状态已更新')
  } catch {
    // 拦截器已提示
  } finally {
    statusSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card class="tool-card">
    <template #header>账号状态管理</template>
    <div class="status-row">
      <span>用户 ID：</span>
      <el-input-number v-model="statusUserId" :min="1" />
      <el-button type="danger" :loading="statusSaving" @click="applyUserStatus(1)">禁用</el-button>
      <el-button type="success" :loading="statusSaving" @click="applyUserStatus(0)">恢复正常</el-button>
    </div>
  </el-card>

  <el-card class="list-card">
    <template #header>
      <div class="head">
        <span>举报处理</span>
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 160px" @change="filterStatus">
          <el-option label="待处理" value="OPEN" />
          <el-option label="已处理" value="RESOLVED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
      </div>
    </template>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="对象" width="130">
        <template #default="{ row }">
          {{ TARGET_TYPE_LABELS[row.targetType] ?? row.targetType }} #{{ row.targetId }}
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="理由" min-width="180" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="reportStatusTag(row.status)">
            {{ REPORT_STATUS_LABELS[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button v-if="row.status === 'OPEN'" size="small" type="primary" @click="openResolve(row)">处理</el-button>
          <el-button size="small" @click="openAudit(row)">审计</el-button>
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

    <el-dialog v-model="resolveVisible" title="处理举报" width="520px">
      <el-form label-width="100px">
        <el-form-item label="处理结果">
          <el-radio-group v-model="form.decision">
            <el-radio value="RESOLVED">成立</el-radio>
            <el-radio value="REJECTED">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.decision === 'RESOLVED'" label="处置措施">
          <el-select v-model="form.action" style="width: 100%">
            <el-option v-for="o in actionOptions()" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input v-model="form.note" type="textarea" :rows="4" maxlength="1000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" :loading="resolving" @click="submitResolve">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="auditVisible" title="处理审计" width="560px">
      <el-empty v-if="audits.length === 0" description="暂无审计记录" />
      <div v-for="a in audits" :key="a.id" class="audit">
        <p>{{ a.action }}</p>
        <p class="detail">{{ a.detail }}</p>
        <span class="meta">操作人 #{{ a.actorId }} · {{ a.createdAt }}</span>
      </div>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.tool-card {
  margin-bottom: 16px;
}
.status-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.list-card .head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pager {
  margin-top: 16px;
}
.audit {
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;
}
.detail {
  color: #606266;
  margin: 6px 0;
}
.meta {
  color: #909399;
  font-size: 13px;
}
</style>
