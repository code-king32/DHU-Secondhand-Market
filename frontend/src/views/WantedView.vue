<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listWanted, createWanted, closeWanted, type Wanted } from '@/api/community'
import { useAuthStore } from '@/stores/auth'
import { CATEGORY_LABELS } from '@/constants'

const auth = useAuthStore()
const rows = ref<Wanted[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const q = ref('')
const category = ref('')
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ title: '', description: '', maxPrice: 0, category: 'OTHER' })
const categories = Object.entries(CATEGORY_LABELS)

async function load() {
  loading.value = true
  try {
    const data = await listWanted({
      q: q.value || undefined,
      category: category.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
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
  if (!form.title.trim() || !form.description.trim()) {
    ElMessage.warning('请填写标题和描述')
    return
  }
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

    <el-row :gutter="16">
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
    <el-empty v-if="!loading && rows.length === 0" description="暂无求购" />

    <el-pagination
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next, total"
      class="pager"
      @current-change="load"
    />

    <el-dialog v-model="dialogVisible" title="发布求购" width="480px">
      <el-form label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="100" />
        </el-form-item>
        <el-form-item label="预算">
          <el-input-number v-model="form.maxPrice" :min="0" :max="9999999.99" :precision="2" :step="1" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="[value, label] in categories" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
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
