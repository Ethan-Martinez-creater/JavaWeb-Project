<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { orderApi } from '../../api/resources'
import type { Order, PageResponse } from '../../api/types'
import ErrorNotice from '../../components/ErrorNotice.vue'

const result = ref<PageResponse<Order> | null>(null)
const pending = ref(false)
const failure = ref<unknown>(null)
const page = ref(0)

async function load(): Promise<void> {
    pending.value = true
    failure.value = null
    try {
        result.value = await orderApi.list(page.value)
    } catch (error) {
        failure.value = error
    } finally {
        pending.value = false
    }
}

onMounted(() => void load())
function changePage(currentPage: number): void {
    page.value = currentPage - 1
    void load()
}
function statusLabel(status: Order['status']): string {
    return { PENDING_PAYMENT: '待支付', PAID: '已支付', CLOSED: '已关闭' }[status]
}
function statusType(status: Order['status']): 'warning' | 'success' | 'info' {
    return ({ PENDING_PAYMENT: 'warning', PAID: 'success', CLOSED: 'info' } as const)[status]
}
function formatMoney(cents: number): string {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'CNY' }).format(
        cents / 100,
    )
}
function formatDate(value: string): string {
    return new Date(value).toLocaleString()
}
</script>

<template>
    <section class="page-content">
        <div class="page-title-row">
            <div>
                <p class="eyebrow">YOUR RESERVATIONS</p>
                <h1>我的订单</h1>
                <p class="lead">每张订单对应一张票。订单状态由服务端返回。</p>
            </div>
            <el-button plain :loading="pending" @click="load">刷新订单</el-button>
        </div>
        <ErrorNotice v-if="failure" :error="failure" />
        <div v-loading="pending" class="order-list">
            <article
                v-for="order in result?.items ?? []"
                :key="order.id"
                :data-testid="`order-${order.id}`"
                class="order-card"
            >
                <div class="order-card-main">
                    <div class="order-heading">
                        <strong>订单 {{ order.orderNo }}</strong
                        ><el-tag :type="statusType(order.status)">{{
                            statusLabel(order.status)
                        }}</el-tag>
                    </div>
                    <p>创建于 {{ formatDate(order.createdAt) }}</p>
                    <p v-if="order.status === 'PENDING_PAYMENT'">
                        请在 {{ formatDate(order.expiresAt) }} 前完成模拟支付。
                    </p>
                    <p v-if="order.status === 'CLOSED'">订单已关闭，库存已由服务端处理。</p>
                </div>
                <div class="order-card-side">
                    <strong>{{ formatMoney(order.totalPriceCents) }}</strong
                    ><RouterLink :to="`/me/orders/${order.id}`" class="text-link"
                        >订单详情 →</RouterLink
                    >
                </div>
            </article>
        </div>
        <el-empty
            v-if="!pending && !failure && result?.items.length === 0"
            description="还没有订单，去发现一场喜欢的活动吧"
        />
        <div v-if="result && result.total > result.size" class="pager-row">
            <span>共 {{ result.total }} 笔订单</span
            ><el-pagination
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
    gap: 14px;
    margin-bottom: 22px;
}
.order-list {
    display: grid;
    gap: 12px;
    min-height: 80px;
}
.order-card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 18px;
    padding: 20px 22px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: #fff;
}
.order-heading {
    display: flex;
    align-items: center;
    gap: 12px;
    color: var(--ink);
    font-size: 13px;
}
.order-card-main p {
    margin: 8px 0 0;
    color: var(--muted);
    font-size: 11px;
}
.order-card-side {
    display: grid;
    gap: 8px;
    justify-items: end;
    white-space: nowrap;
}
.order-card-side strong {
    color: var(--ink);
    font-size: 17px;
}
.text-link {
    font-size: 11px;
}
@media (max-width: 560px) {
    .order-card {
        align-items: flex-start;
        flex-direction: column;
    }
    .order-card-side {
        width: 100%;
        grid-template-columns: 1fr auto;
        align-items: center;
        justify-items: start;
    }
}
</style>
