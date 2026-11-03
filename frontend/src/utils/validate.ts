import type { FormInstance, FormItemRule } from 'element-plus'

// 前端校验规则与后端 Bean Validation 保持一致：
// backend/.../identity/dto/{RegisterRequest,LoginRequest}.java、
// backend/.../marketplace/MarketDtos.java、backend/.../community/CommunityController.java
// whitespace: true 对应后端的 @NotBlank（纯空白也视为未填写）。
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export const EMAIL_RULES: FormItemRule[] = [
  { required: true, whitespace: true, message: '请输入邮箱', trigger: 'blur' },
  { pattern: EMAIL_PATTERN, message: '邮箱格式不正确', trigger: 'blur' },
  { max: 254, message: '邮箱不能超过 254 个字符', trigger: 'blur' },
]

export const CODE_RULES: FormItemRule[] = [
  { required: true, whitespace: true, message: '请输入验证码', trigger: 'blur' },
  { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
]

export const REGISTER_PASSWORD_RULES: FormItemRule[] = [
  { required: true, whitespace: true, message: '请输入密码', trigger: 'blur' },
  { min: 8, max: 72, message: '密码长度为 8~72 位', trigger: 'blur' },
]

export const LOGIN_PASSWORD_RULES: FormItemRule[] = [
  { required: true, whitespace: true, message: '请输入密码', trigger: 'blur' },
  { max: 72, message: '密码不能超过 72 位', trigger: 'blur' },
]

export const NICKNAME_RULES: FormItemRule[] = [
  { required: true, whitespace: true, message: '请输入昵称', trigger: 'blur' },
  { max: 50, message: '昵称不能超过 50 个字符', trigger: 'blur' },
]

export const TITLE_RULES: FormItemRule[] = [
  { required: true, whitespace: true, message: '请输入标题', trigger: 'blur' },
  { max: 100, message: '标题不能超过 100 个字符', trigger: 'blur' },
]

// 描述/理由/处理说明等「必填 + 长度上限」的文本字段。
export function textRules(max: number, requiredMessage: string): FormItemRule[] {
  return [
    { required: true, whitespace: true, message: requiredMessage, trigger: 'blur' },
    { max, message: `内容不能超过 ${max} 个字符`, trigger: 'blur' },
  ]
}

// 商品描述最长 5000，求购描述最长 2000。
export const GOODS_DESCRIPTION_RULES = textRules(5000, '请输入描述')
export const WANTED_DESCRIPTION_RULES = textRules(2000, '请输入描述')

// 校验整个表单：通过返回 true，未通过返回 false（错误信息由字段级展示）。
export async function isValid(formRef: FormInstance | undefined): Promise<boolean> {
  if (!formRef) return false
  return formRef.validate().then(() => true).catch(() => false)
}

// 只校验单个字段（如发送验证码前只校验邮箱）。
export async function isValidField(formRef: FormInstance | undefined, prop: string): Promise<boolean> {
  if (!formRef) return false
  return formRef.validateField(prop).then(() => true).catch(() => false)
}
