<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const router = useRouter()
const isOrganizer = computed(() => auth.hasRole('ORGANIZER') || auth.hasRole('ADMIN'))
const isUser = computed(() => auth.hasRole('USER') || auth.hasRole('ADMIN'))

async function logout(): Promise<void> {
    auth.logout()
    await router.push({ name: 'events' })
    ElMessage.success('已安全退出。本次会话凭据已清除。')
}
</script>

<template>
    <div class="app-frame">
        <header class="topbar">
            <div class="topbar-inner">
                <RouterLink class="brand" :to="{ name: 'events' }" aria-label="EventFlow 首页">
                    <span class="brand-mark" aria-hidden="true">E</span>
                    <span>EventFlow</span>
                </RouterLink>

                <nav class="main-nav" aria-label="主导航">
                    <RouterLink :to="{ name: 'events' }">发现活动</RouterLink>
                    <template v-if="isUser">
                        <RouterLink data-testid="nav-orders" :to="{ name: 'orders' }"
                            >我的订单</RouterLink
                        >
                        <RouterLink data-testid="nav-tickets" :to="{ name: 'tickets' }"
                            >电子票</RouterLink
                        >
                        <RouterLink data-testid="nav-notifications" :to="{ name: 'notifications' }"
                            >通知</RouterLink
                        >
                    </template>
                    <template v-if="isOrganizer">
                        <RouterLink
                            data-testid="nav-organizer-events"
                            :to="{ name: 'organizer-events' }"
                        >
                            主办方工作台
                        </RouterLink>
                        <RouterLink data-testid="nav-checkin" :to="{ name: 'organizer-checkin' }">
                            现场核销
                        </RouterLink>
                    </template>
                </nav>

                <div class="account-area">
                    <template v-if="auth.isAuthenticated">
                        <div class="account-copy">
                            <span class="account-email">{{ auth.user?.email }}</span>
                            <span class="account-roles">{{ auth.user?.roles.join(' / ') }}</span>
                        </div>
                        <el-button data-testid="logout" plain @click="logout">退出</el-button>
                    </template>
                    <template v-else>
                        <RouterLink class="account-link" :to="{ name: 'login' }">登录</RouterLink>
                        <RouterLink class="account-signup" :to="{ name: 'register' }"
                            >注册</RouterLink
                        >
                    </template>
                </div>
            </div>
        </header>

        <main class="page-shell">
            <RouterView />
        </main>

        <footer class="site-footer">
            <span>EventFlow · 活动预约与票务演示</span>
            <span>模拟支付 · 真实库存与订单状态</span>
        </footer>
    </div>
</template>
