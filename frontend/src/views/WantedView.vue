<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance } from 'element-plus'
import { listWanted, createWanted, closeWanted, type Wanted } from '@/api/community'
import { useAuthStore } from '@/stores/auth'
import { CATEGORY_LABELS } from '@/constants'
import { usePageList } from '@/composables/usePageList'
import LoadError from '@/components/LoadError.vue'
import { TITLE_RULES, WANTED_DESCRIPTION_RULES, isValid } from '@/utils/validate'

const auth = useAuthStore()
const q = ref('')
const category = ref('')
const { items: rows, total, page, size, loading, error, load, reset } = usePageList<Wanted>(
  (page, size) => listWanted({ q: q.value || undefined, category: category.value || undefined, page, size }),
  { size: 12 },
)

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ title: '', description: '', maxPrice: 0, category: 'OTHER' })
const formRef = ref<FormInstance>()
const rules = { title: TITLE_RULES, description: WANTED_DESCRIPTION_RULES }
const categories = Object.entries(CATEGORY_LABELS)

function search() {
  reset()
}

function openCreate() {
  if (!auth.user) {
    ElMessage.warning('请先登录')
    return
  }
  form.title = ''
  form.description = ''
  form.maxPrice = 0
  form.category = 'OTHER'
  dialogVisible.value = true
}

async function submit() {
  if (!(await isValid(formRef.value))) return
  saving.value = true
  try {
    await createWanted({
      title: form.title.trim(),
      description: form.description.trim(),
      maxPrice: form.maxPrice,
      category: form.category,
    })
    ElMessage.success('求购已发布')
    dialogVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function closeItem(w: Wanted) {
  try {
    await closeWanted(w.id)
    ElMessage.success('已关闭求购')
    load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-input v-model="q" placeholder="搜索求购" clearable class="search" @keyup.enter="search" />
      <el-select v-model="category" placeholder="全部分类" clearable style="width: 160px" @change="search">
        <el-option v-for="[value, label] in categories" :key="value" :label="label" :value="value" />
      </el-select>
      <el-button type="primary" @click="search">搜索</el-button>
      <el-button @click="openCreate">发布求购</el-button>
    </div>

    <LoadError v-if="error" @retry="load" />
    <el-row v-else-if="rows.length" :gutter="16">
      <el-col v-for="w in rows" :key="w.id" :span="6">
        <el-card class="wanted-card">
          <template #header>{{ w.title }}</template>
          <p class="cat">{{ CATEGORY_LABELS[w.category] ?? w.category }}</p>
          <p class="price">预算 ¥{{ w.maxPrice }}</p>
          <p class="desc">{{ w.description }}</p>
          <el-button
            v-if="auth.user?.id === w.userId && w.status === 'OPEN'"
            size="small"
            type="warning"
            @click="closeItem(w)"
          >
            关闭
          </el-button>
        </el-card>
      </el-col>
    </el-row>
    <el-empty v-else-if="!loading" description="暂无求购" />

    <el-pagination
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next, total"
      class="pager"
      @current-change="load"
    />

    <el-dialog v-model="dialogVisible" title="发布求购" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="100" />
        </el-form-item>
        <el-form-item label="预算" prop="maxPrice">
          <el-input-number v-model="form.maxPrice" :min="0" :max="9999999.99" :precision="2" :step="1" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="[value, label] in categories" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">发布</el-button>
      </template>
    </el-dialog>
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
.wanted-card {
  margin-bottom: 16px;
}
.cat {
  color: #909399;
  font-size: 13px;
}
.price {
  color: #f56c6c;
  font-weight: 600;
}
.desc {
  color: #606266;
  font-size: 13px;
  margin-bottom: 8px;
}
.pager {
  margin-top: 16px;
}
</style>
