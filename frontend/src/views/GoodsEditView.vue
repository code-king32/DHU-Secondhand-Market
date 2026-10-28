<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createGoods, updateGoods, getGoodsManagement, uploadGoodsImage } from '@/api/goods'
import { CATEGORY_LABELS } from '@/constants'

const route = useRoute()
const router = useRouter()
const isEdit = !!route.params.id
const id = isEdit ? Number(route.params.id) : 0
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()
const imageUrls = ref<string[]>([])

const form = reactive({
  title: '',
  description: '',
  price: 0,
  category: 'OTHER',
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
    imageUrls.value = [...g.imageUrls]
  } finally {
    loading.value = false
  }
})

async function selectImages(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = '' // 允许移除后重新选择同一个文件
  if (!files.length || uploading.value || saving.value) return
  if (files.length + imageUrls.value.length > 9) {
    ElMessage.warning('每件商品最多上传 9 张图片，请重新选择')
    return
  }
  const invalid = files.find((file) => !/\.(jpe?g|png)$/i.test(file.name) || file.size > 5 * 1024 * 1024 || !file.size)
  if (invalid) {
    ElMessage.warning('请选择非空的 JPG、PNG 图片，每张不超过 5 MB')
    return
  }
  uploading.value = true
  try {
    for (const file of files) {
      const result = await uploadGoodsImage(file)
      imageUrls.value.push(result.url)
    }
  } catch {
    // 已上传成功的图片保留，失败的文件可重新选择。
  } finally {
    uploading.value = false
  }
}

async function submit() {
  if (uploading.value || saving.value || loading.value) return
  if (!form.title.trim() || !form.description.trim()) {
    ElMessage.warning('请填写标题和描述')
    return
  }
  const payload = {
    title: form.title.trim(),
    description: form.description.trim(),
    price: form.price,
    category: form.category,
    imageUrls: [...imageUrls.value],
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
      <el-form-item label="商品图片">
        <div class="image-picker" :aria-busy="uploading">
          <div v-if="imageUrls.length" class="image-list">
            <div v-for="(url, index) in imageUrls" :key="`${index}-${url}`" class="image-item">
              <el-image :src="url" :alt="`商品图片 ${index + 1}`" fit="cover"
                :preview-src-list="imageUrls" :initial-index="index" preview-teleported />
              <el-button size="small" :disabled="uploading || saving" :aria-label="`移除商品图片 ${index + 1}`"
                @click="imageUrls.splice(index, 1)">移除</el-button>
            </div>
          </div>
          <input ref="fileInput" type="file" accept=".jpg,.jpeg,.png" multiple hidden
            :disabled="loading || uploading || saving" @change="selectImages" />
          <el-button :loading="uploading" :disabled="loading || saving || imageUrls.length >= 9"
            @click="fileInput?.click()">{{ uploading ? '正在上传' : '选择图片' }}</el-button>
          <p class="image-tip" role="status">{{ imageUrls.length }}/9 张 · 支持 JPG、PNG，每张不超过 5 MB，点击图片可预览</p>
        </div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" :disabled="loading || uploading" @click="submit">{{ isEdit ? '保存' : '发布' }}</el-button>
        <el-button @click="router.back()">取消</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
.image-picker { width: 100%; }
.image-list { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; }
.image-item { display: flex; flex-direction: column; gap: 6px; width: 104px; }
.image-item .el-image { width: 104px; height: 104px; border: 1px solid #dcdfe6; border-radius: 6px; }
.image-tip { margin: 8px 0 0; color: #606266; font-size: 12px; line-height: 1.5; }
</style>
