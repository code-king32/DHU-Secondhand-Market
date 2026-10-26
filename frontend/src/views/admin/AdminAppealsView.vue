<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listAdminAppeals, resolveAppeal, type Appeal } from '@/api/community'
import { APPEAL_STATUS_LABELS, reportStatusTag } from '@/constants'

const rows = ref<Appeal[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const loading = ref(false)

const resolveVisible = ref(false)
const current = ref<Appeal | null>(null)
const resolving = ref(false)
const form = reactive({ decision: 'UPHELD', note: '' })

async function load() {
  loading.value = true
  try {
    const data = await listAdminAppeals({ status: status.value || undefined, page: page.value, size: size.value })
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

function openResolve(a: Appeal) {
  current.value = a
  form.decision = 'UPHELD'
  form.note = ''
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
    await resolveAppeal(current.value.id, { decision: form.decision, note: form.note.trim() })
    ElMessage.success('已处理')
    resolveVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    resolving.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>
      <div class="head">
        <span>申诉处理</span>
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 160px" @change="filterStatus">
          <el-option label="待处理" value="OPEN" />
          <el-option label="已成立" value="UPHELD" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
      </div>
    </template>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="reportId" label="举报号" width="90" />
      <el-table-column prop="reason" label="申诉理由" min-width="200" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="reportStatusTag(row.status)">
            {{ APPEAL_STATUS_LABELS[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button v-if="row.status === 'OPEN'" size="small" type="primary" @click="openResolve(row)">处理</el-button>
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

    <el-dialog v-model="resolveVisible" title="处理申诉" width="520px">
      <el-form label-width="100px">
        <el-form-item label="处理结果">
          <el-radio-group v-model="form.decision">
            <el-radio value="UPHELD">成立（重新核查）</el-radio>
            <el-radio value="REJECTED">驳回</el-radio>
          </el-radio-group>
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
