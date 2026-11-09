<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { updateNickname, deleteAccount } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { NICKNAME_RULES, LOGIN_PASSWORD_RULES, isValid } from '@/utils/validate'

const router = useRouter()
const auth = useAuthStore()

const nicknameForm = reactive({ nickname: auth.user?.nickname ?? '' })
const nicknameFormRef = ref<FormInstance>()
const nicknameRules = { nickname: NICKNAME_RULES }
const nicknameSaving = ref(false)

const deleteVisible = ref(false)
const deleteForm = reactive({ password: '' })
const deleteFormRef = ref<FormInstance>()
const deleteRules = { password: LOGIN_PASSWORD_RULES }
const deleteSaving = ref(false)

async function saveNickname() {
  if (!(await isValid(nicknameFormRef.value))) return
  const value = nicknameForm.nickname.trim()
  nicknameSaving.value = true
  try {
    const user = await updateNickname(value)
    auth.user = user
    ElMessage.success('昵称已更新')
  } catch {
    // 拦截器已提示
  } finally {
    nicknameSaving.value = false
  }
}

async function doDelete() {
  if (!(await isValid(deleteFormRef.value))) return
  deleteSaving.value = true
  try {
    await deleteAccount(deleteForm.password)
    auth.user = null
    ElMessage.success('账号已注销')
    router.push('/login')
  } catch {
    // 拦截器已提示
  } finally {
    deleteSaving.value = false
  }
}
</script>

<template>
  <el-card>
    <template #header>账号设置</template>
    <el-descriptions v-if="auth.user" :column="2" border class="info">
      <el-descriptions-item label="邮箱">{{ auth.user.email }}</el-descriptions-item>
      <el-descriptions-item label="角色">{{ auth.user.role === 'ADMIN' ? '管理员' : '普通用户' }}</el-descriptions-item>
      <el-descriptions-item label="邮箱认证">{{ auth.user.emailVerified ? '已认证' : '未认证' }}</el-descriptions-item>
      <el-descriptions-item label="注册时间">{{ auth.user.createdAt }}</el-descriptions-item>
    </el-descriptions>

    <el-form ref="nicknameFormRef" :model="nicknameForm" :rules="nicknameRules" label-width="80px" class="nickname-form">
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="nicknameForm.nickname" maxlength="50" style="max-width: 300px" />
        <el-button type="primary" :loading="nicknameSaving" @click="saveNickname">保存昵称</el-button>
      </el-form-item>
    </el-form>

    <el-divider />
    <el-button type="danger" plain @click="deleteVisible = true">注销账号</el-button>

    <el-dialog v-model="deleteVisible" title="注销账号" width="420px">
      <p class="warn">注销后将无法登录，账号记录会保留但不可恢复。请谨慎操作。</p>
      <el-form ref="deleteFormRef" :model="deleteForm" :rules="deleteRules">
        <el-form-item prop="password">
          <el-input v-model="deleteForm.password" type="password" show-password placeholder="请输入当前密码确认" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deleteVisible = false">取消</el-button>
        <el-button type="danger" :loading="deleteSaving" @click="doDelete">确认注销</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.info {
  margin-bottom: 20px;
}
.nickname-form :deep(.el-form-item__content) {
  display: flex;
  gap: 12px;
}
.warn {
  color: #f56c6c;
  margin-bottom: 12px;
}
</style>
