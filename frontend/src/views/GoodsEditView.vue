<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createGoods, updateGoods, getGoodsManagement } from '@/api/goods'
import { CATEGORY_LABELS } from '@/constants'

const route = useRoute()
const router = useRouter()
const isEdit = !!route.params.id
const id = isEdit ? Number(route.params.id) : 0
const loading = ref(false)
const saving = ref(false)

const form = reactive({
  title: '',
  description: '',
  price: 0,
  category: 'OTHER',
  imageText: '',
})

const categories = Object.entries(CATEGORY_LABELS)

onMounted(async () => {
  if (!isEdit) return
  loading.value = true
  try {
    const g = await getGoodsManagement(id)
    form.title = g.title
    form.description = g.description
    form.price = g.price
    form.category = g.category
    form.imageText = g.imageUrls.join('\n')
  } finally {
    loading.value = false
  }
})

async function submit() {
  if (!form.title.trim() || !form.description.trim()) {
    ElMessage.warning('请填写标题和描述')
    return
  }
  const imageUrls = form.imageText.split('\n').map((s) => s.trim()).filter(Boolean)
  const payload = {
    title: form.title.trim(),
    description: form.description.trim(),
    price: form.price,
    category: form.category,
    imageUrls,
  }
  saving.value = true
  try {
    if (isEdit) {
      await updateGoods(id, payload)
      ElMessage.success('已保存')
    } else {
      await createGoods(payload)
      ElMessage.success('发布成功')
    }
    router.push('/mine/goods')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-card v-loading="loading">
    <template #header>{{ isEdit ? '编辑商品' : '发布商品' }}</template>
    <el-form label-width="90px" style="max-width: 600px">
      <el-form-item label="标题">
        <el-input v-model="form.title" maxlength="100" placeholder="商品标题" />
      </el-form-item>
      <el-form-item label="价格">
        <el-input-number v-model="form.price" :min="0" :max="1000000" :precision="2" :step="1" />
      </el-form-item>
      <el-form-item label="分类">
        <el-select v-model="form.category" style="width: 200px">
          <el-option v-for="[value, label] in categories" :key="value" :label="label" :value="value" />
        </el-select>
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="6" maxlength="5000" placeholder="商品描述" />
      </el-form-item>
      <el-form-item label="图片地址">
        <el-input v-model="form.imageText" type="textarea" :rows="3" placeholder="每行一个图片 URL（最多 9 张）" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="submit">{{ isEdit ? '保存' : '发布' }}</el-button>
        <el-button @click="router.back()">取消</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>
