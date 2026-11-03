<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance } from 'element-plus'
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
import { usePageList } from '@/composables/usePageList'
import LoadError from '@/components/LoadError.vue'
import { textRules, isValid } from '@/utils/validate'

const { items: rows, total, page, size, loading, error, load } = usePageList<Report>(
  (page, size) => listMyReports({ page, size }),
)

const appealVisible = ref(false)
const appealForm = reactive({ reason: '' })
const appealFormRef = ref<FormInstance>()
const appealRules = { reason: textRules(1000, '请填写申诉理由') }
const current = ref<Report | null>(null)
const appealSaving = ref(false)

const appealsVisible = ref(false)
const appeals = ref<Appeal[]>([])

function openAppeal(r: Report) {
  current.value = r
  appealForm.reason = ''
  appealVisible.value = true
}

async function submitAppeal() {
  if (!current.value) return
  if (!(await isValid(appealFormRef.value))) return
  appealSaving.value = true
  try {
    await createAppeal(current.value.id, appealForm.reason.trim())
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
    <LoadError v-if="error" @retry="load" />
    <el-table v-else :data="rows" v-loading="loading" border>
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
      <el-form ref="appealFormRef" :model="appealForm" :rules="appealRules">
        <el-form-item prop="reason">
          <el-input v-model="appealForm.reason" type="textarea" :rows="4" maxlength="1000" placeholder="申诉理由" />
        </el-form-item>
      </el-form>
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
