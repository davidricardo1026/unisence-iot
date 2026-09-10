<script setup lang="ts">
import {ElMessage} from 'element-plus'
import {useUserStore} from '@/store/modules/user'
import {getEnabledAuthTypes} from '@/api/auth'
import {rejectInvisibleUnicode} from '@/utils/validation'

const router = useRouter()
const userStore = useUserStore()

const loginForm = reactive({
  userCode: '',
  password: '',
  identityType: 'local'
})

const rules = {
  userCode: [
    {required: true, message: '请输入用户名', trigger: 'blur'},
    {min: 3, max: 30, message: '用户名长度在 3 到 30 个字符之间', trigger: 'blur'},
    {validator: rejectInvisibleUnicode, trigger: ['blur', 'change']}
  ],
  password: [
    {required: true, message: '请输入密码', trigger: 'blur'},
    {min: 5, max: 30, message: '密码长度在 5 到 30 个字符之间', trigger: 'blur'},
    {validator: rejectInvisibleUnicode, trigger: ['blur', 'change']}
  ]
}

const loginFormRef = ref()
const loading = ref(false)
const appTitle = import.meta.env.VITE_APP_TITLE || '统一感知物联网平台'
const enabledTypes = ref<string[]>(['local'])

onMounted(async () => {
  try {
    const res = await getEnabledAuthTypes()
    if (res && res.data && res.data.length > 0) {
      enabledTypes.value = res.data
      if (!enabledTypes.value.includes(loginForm.identityType)) {
        loginForm.identityType = enabledTypes.value[0]
      }
    }
  } catch (err) {
    console.warn('获取启用登录策略失败，使用默认 local 策略', err)
  }
})

async function handleLogin() {
  if (!loginFormRef.value) return

  await loginFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    loading.value = true
    try {
      await userStore.login({
        userCode: loginForm.userCode,
        password: loginForm.password,
        identityType: loginForm.identityType
      })
      ElMessage.success('登录成功')
      router.push('/')
    } catch (err: unknown) {
      console.error(err)
      ElMessage.error(getErrorMessage(err, '登录失败，请检查用户名和密码'))
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div
      class="flex min-h-screen items-center justify-center bg-gray-50 px-4 py-12 sm:px-6 lg:px-8 bg-gradient-to-br from-blue-500/10 via-white to-sky-500/10"
  >
    <div
        class="w-full max-w-md space-y-8 bg-white p-8 rounded-2xl shadow-xl border border-gray-100 transition-all duration-300 hover:shadow-2xl"
    >
      <!-- Title and Logo -->
      <div class="text-center">
        <div class="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-blue-500/10 text-blue-600">
          <el-icon :size="32">
            <Monitor/>
          </el-icon>
        </div>
        <h2 class="mt-6 text-3xl font-bold tracking-tight text-gray-900">
          {{ appTitle }}
        </h2>
      </div>

      <!-- Form -->
      <el-form
          ref="loginFormRef"
          :model="loginForm"
          :rules="rules"
          label-position="top"
          class="mt-8 space-y-6"
          size="large"
          @keyup.enter="handleLogin"
      >
        <el-form-item
            label="用户名"
            prop="userCode"
        >
          <el-input
              v-model="loginForm.userCode"
              placeholder="请输入用户名/账号"
              autocomplete="username"
          >
            <template #prefix>
              <el-icon class="text-gray-400">
                <User/>
              </el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item
            label="密码"
            prop="password"
        >
          <el-input
              v-model="loginForm.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
          >
            <template #prefix>
              <el-icon class="text-gray-400">
                <Lock/>
              </el-icon>
            </template>
          </el-input>
        </el-form-item>

        <div
            v-if="enabledTypes.length > 1"
            class="flex items-center justify-between mb-4"
        >
          <span class="text-xs text-gray-400 font-medium">选择登录方式:</span>
          <el-radio-group
              v-model="loginForm.identityType"
              size="small"
          >
            <el-radio-button
                v-for="type in enabledTypes"
                :key="type"
                :value="type"
            >
              {{ type === 'local' ? '本地密码' : type }}
            </el-radio-button>
          </el-radio-group>
        </div>

        <div class="pt-2">
          <el-button
              type="primary"
              class="w-full h-12 bg-blue-600 hover:bg-blue-700 active:bg-blue-800 border-none text-white rounded-lg text-base font-medium transition-all duration-200"
              :loading="loading"
              @click="handleLogin"
          >
            立即登录
          </el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
/* 可以根据需要定制样式 */
</style>
