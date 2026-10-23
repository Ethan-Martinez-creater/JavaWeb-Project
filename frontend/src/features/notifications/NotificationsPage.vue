<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { notificationApi } from '../../api/resources'
import type { Notification, PageResponse } from '../../api/types'
import ErrorNotice from '../../components/ErrorNotice.vue'

const result = ref<PageResponse<Notification> | null>(null)
const pending = ref(false)
const failure = ref<unknown>(null)
const page = ref(0)

async function load(): Promise<void> {
    pending.value = true
    failure.value = null
    try {
        result.value = await notificationApi.list(page.value)
    } catch (error) {
        failure.value = error
    } finally {
        pending.value = false
    }
}

function changePage(currentPage: number): void {
    page.value = currentPage - 1
    void load()
}

onMounted(() => void load())
</script>

<template>
    <section class="page-content">
        <div class="page-title-row">
            <div>
                <p class="eyebrow">INBOX</p>
                <h1>站内通知</h1>
                <p class="lead">通知通过 RabbitMQ 异步保存，支付或关单后可能稍晚出现。</p>
            </div>
            <el-button data-testid="refresh-notifications" plain :loading="pending" @click="load"
                >刷新通知</el-button
            >
        </div>
        <ErrorNotice v-if="failure" :error="failure" />
        <div
            v-loading="pending"
            data-testid="notification-list"
            class="notification-list"
            aria-live="polite"
        >
            <article
                v-for="notification in result?.items ?? []"
                :key="notification.id"
                class="notification-card"
            >
                <span class="notification-icon" aria-hidden="true">{{
                    notification.eventType === 'ORDER_PAID' ? '✓' : '↗'
                }}</span>
                <div>
                    <div class="notification-heading">
                        <strong>{{
                            notification.eventType === 'ORDER_PAID'
                                ? '支付已确认'
                                : notification.eventType === 'ORDER_CLOSED'
                                  ? '订单已关闭'
                                  : notification.eventType
                        }}</strong
                        ><time>{{ new Date(notification.createdAt).toLocaleString() }}</time>
                    </div>
                    <p>{{ notification.message }}</p>
                    <RouterLink :to="`/me/orders/${notification.orderId}`" class="text-link"
                        >查看订单 →</RouterLink
                    >
                </div>
            </article>
        </div>
        <el-empty
            v-if="!pending && !failure && result?.items.length === 0"
            description="暂时没有通知，支付或关单后会在这里更新"
        />
        <div v-if="result && result.total > result.size" class="pager-row">
            <span>共 {{ result.total }} 条通知</span>
            <el-pagination
                data-testid="notification-pagination"
                :current-page="page + 1"
                :page-size="result.size"
                :total="result.total"
                layout="prev, pager, next"
                @current-change="changePage"
            />
        </div>
    </section>
</template>

<style scoped>
.page-title-row {
    display: flex;
    align-items: end;
    justify-content: space-between;
    gap: 15px;
    margin-bottom: 22px;
}
.notification-list {
    display: grid;
    gap: 10px;
    min-height: 80px;
}
.notification-card {
    display: flex;
    gap: 13px;
    padding: 18px;
    border: 1px solid var(--line);
    border-radius: 11px;
    background: #fff;
}
.notification-icon {
    display: grid;
    flex: 0 0 auto;
    place-items: center;
    width: 32px;
    height: 32px;
    border-radius: 10px;
    background: #e1f2ef;
    color: var(--teal);
    font-weight: 800;
}
.notification-card > div {
    flex: 1;
    min-width: 0;
}
.notification-heading {
    display: flex;
    justify-content: space-between;
    gap: 10px;
    color: var(--ink);
    font-size: 12px;
}
.notification-heading time {
    color: #91a0a4;
    font-size: 9px;
    white-space: nowrap;
}
.notification-card p {
    margin: 7px 0;
    color: #657982;
    font-size: 11px;
    line-height: 1.6;
}
.text-link {
    font-size: 10px;
}
.pager-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 18px;
    color: var(--muted);
    font-size: 11px;
}
@media (max-width: 650px) {
    .page-title-row {
        align-items: flex-start;
    }
    .notification-heading {
        flex-direction: column;
    }
}
</style>
