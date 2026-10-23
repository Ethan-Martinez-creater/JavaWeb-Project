<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ErrorNotice from '../../components/ErrorNotice.vue'
import { userMessage } from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const isRegister = computed(() => route.name === 'register')
const email = ref('')
const password = ref('')
const pending = ref(false)
const failure = ref<unknown>(null)
const validationError = ref('')

async function submit(): Promise<void> {
    if (pending.value) return
    validationError.value = ''
    failure.value = null
    const normalizedEmail = email.value.trim()
    if (!normalizedEmail || !normalizedEmail.includes('@') || normalizedEmail.length > 254) {
        validationError.value = '请输入有效的邮箱地址。'
        return
    }
    if (isRegister.value) {
        const passwordBytes = new TextEncoder().encode(password.value).length
        const passwordCharacters = [...password.value].length
        if (passwordCharacters < 10 || passwordBytes > 72) {
            validationError.value = '密码至少 10 个字符，且 UTF-8 编码不能超过 72 字节。'
            return
        }
    }

    pending.value = true
    try {
        if (isRegister.value) {
            await auth.register(normalizedEmail, password.value)
            ElMessage.success('USER 账号已创建，请使用该邮箱登录。')
            await router.replace({ name: 'login', query: { email: normalizedEmail } })
            return
        }
        const user = await auth.login(normalizedEmail, password.value)
        const requested = typeof route.query.redirect === 'string' ? route.query.redirect : ''
        const safeRedirect =
            requested.startsWith('/') && !requested.startsWith('//') ? requested : ''
        const destination =
            safeRedirect || (user.roles.includes('ORGANIZER') ? '/organizer/events' : '/')
        await router.replace(destination)
        ElMessage.success('登录成功。刷新页面后需要重新登录。')
    } catch (error) {
        failure.value = error
    } finally {
        pending.value = false
    }
}
</script>

<template>
    <section class="auth-layout">
        <div class="auth-intro">
            <p class="eyebrow">EVENTFLOW ACCOUNT</p>
            <h1>{{ isRegister ? '开始你的活动体验' : '欢迎回来' }}</h1>
            <p>
                {{
                    isRegister
                        ? '注册会创建普通用户账号。主办方身份由开发环境单独配置，不提供自助升权。'
                        : '普通用户可以注册；演示主办方请使用开发环境中人工配置的账号。'
                }}
            </p>
            <div class="auth-promise">
                <span aria-hidden="true">✓</span>
                <span>账号权限由服务端验证，前端页面不会授予角色。</span>
            </div>
        </div>

        <el-card class="auth-card" shadow="never">
            <h2>{{ isRegister ? '创建普通用户账号' : '登录 EventFlow' }}</h2>
            <p class="muted">邮箱与密码通过现有后端接口验证。</p>
            <ErrorNotice v-if="failure" :error="failure" />
            <el-alert
                v-if="validationError"
                :title="validationError"
                type="warning"
                :closable="false"
            />

            <form class="form-stack" @submit.prevent="submit">
                <label class="field-label" for="auth-email">邮箱</label>
                <el-input
                    id="auth-email"
                    v-model="email"
                    data-testid="auth-email"
                    type="email"
                    autocomplete="email"
                    maxlength="254"
                    placeholder="name@example.com"
                    required
                />

                <label class="field-label" for="auth-password">密码</label>
                <el-input
                    id="auth-password"
                    v-model="password"
                    data-testid="auth-password"
                    type="password"
                    autocomplete="current-password"
                    show-password
                    required
                    :minlength="isRegister ? 10 : undefined"
                    placeholder="至少 10 个字符"
                />

                <el-button
                    class="wide-button"
                    data-testid="auth-submit"
                    native-type="submit"
                    type="primary"
                    :loading="pending"
                >
                    {{ isRegister ? '创建账号' : '登录' }}
                </el-button>
            </form>

            <p class="auth-switch">
                {{ isRegister ? '已有账号？' : '还没有账号？' }}
                <RouterLink :to="{ name: isRegister ? 'login' : 'register' }">
                    {{ isRegister ? '前往登录' : '注册 USER 账号' }}
                </RouterLink>
            </p>
            <p class="privacy-note">访问令牌只保存在当前页面内存，关闭或刷新页面后会退出登录。</p>
            <p v-if="failure && !validationError" class="visually-hidden">
                {{ userMessage(failure) }}
            </p>
        </el-card>
    </section>
</template>
