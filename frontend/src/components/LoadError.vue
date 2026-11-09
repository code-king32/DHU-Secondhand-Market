<script setup lang="ts">
defineProps<{ description?: string }>()
const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <el-empty :description="description || '加载失败，请稍后重试'">
    <el-button type="primary" @click="emit('retry')">重试</el-button>
  </el-empty>
</template>
