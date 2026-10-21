<script setup lang="ts">
import { ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi } from '../../api/resources'
import type { Order } from '../../api/types'
import ErrorNotice from '../../components/ErrorNotice.vue'
import { createRequestGeneration } from '../../utils/requestGeneration'

const route = useRoute()
const routeRequests = createRequestGeneration()
const detailRequests = createRequestGeneration()
const actionRequests = createRequestGeneration()
const order = ref<Order | null>(null)
const pending = ref(false)
const actionPending = ref(false)
const failure = ref<unknown>(null)
let currentRouteGeneration = 0

async function load(id: number, routeGeneration: number): Promise<void> {
    const detailGeneration = detailRequests.begin()
    pending.value = true
    failure.value = null
    try {
        const nextOrder = await orderApi.detail(id)
        if (
            !routeRequests.isCurrent(routeGeneration) ||
            !detailRequests.isCurrent(detailGeneration) ||
            Number(route.params.id) !== id
        ) {
            return
        }
        order.value = nextOrder
    } catch (error) {
        if (
            routeRequests.isCurrent(routeGeneration) &&
            detailRequests.isCurrent(detailGeneration)
        ) {
            failure.value = error
        }
    } finally {
        if (
            routeRequests.isCurrent(routeGeneration) &&
            detailRequests.isCurrent(detailGeneration)
        ) {
            pending.value = false
        }
    }
}

function handleOrderRouteChange(rawId: string | string[] | undefined): void {
    currentRouteGeneration = routeRequests.begin()
    detailRequests.begin()
    actionRequests.begin()
    order.value = null
    failure.value = null
    pending.value = false
    actionPending.value = false

    const id = Number(rawId)
    if (!Number.isSafeInteger(id) || id <= 0) {
        failure.value = new Error('订单编号无效。')
        return
    }
    void load(id, currentRouteGeneration)
}

async function pay(): Promise<void> {
    const currentOrder = order.value
    if (
        !currentOrder ||
        actionPending.value ||
        currentOrder.id !== Number(route.params.id) ||
        pending.value
    ) {
        return
    }
    const routeGeneration = currentRouteGeneration
    const actionGeneration = actionRequests.begin()
    actionPending.value = true
    failure.value = null
    try {
        const paidOrder = await orderApi.pay(currentOrder.id)
        if (
            !routeRequests.isCurrent(routeGeneration) ||
            !actionRequests.isCurrent(actionGeneration) ||
            Number(route.params.id) !== currentOrder.id
        ) {
            return
        }
        order.value = paidOrder
        ElMessage.success('模拟支付成功，服务端已签发电子票。')
    } catch (error) {
        if (
            routeRequests.isCurrent(routeGeneration) &&
            actionRequests.isCurrent(actionGeneration)
        ) {
            failure.value = error
        }
    } finally {
        if (
            routeRequests.isCurrent(routeGeneration) &&
            actionRequests.isCurrent(actionGeneration)
        ) {
            actionPending.value = false
        }
    }
}

async function cancel(): Promise<void> {
    const currentOrder = order.value
    if (
        !currentOrder ||
        actionPending.value ||
        currentOrder.id !== Number(route.params.id) ||
        pending.value
    ) {
        return
    }
    const routeGeneration = currentRouteGeneration
    const actionGeneration = actionRequests.begin()
    actionPending.value = true
    failure.value = null
    try {
        const closedOrder = await orderApi.cancel(currentOrder.id)
        if (
            !routeRequests.isCurrent(routeGeneration) ||
            !actionRequests.isCurrent(actionGeneration) ||
            Number(route.params.id) !== currentOrder.id
        ) {
            return
        }
        order.value = closedOrder
        ElMessage.success('订单已关闭，库存状态已由服务端更新。')
    } catch (error) {
        if (
            routeRequests.isCurrent(routeGeneration) &&
            actionRequests.isCurrent(actionGeneration)
        ) {
            failure.value = error
        }
    } finally {
        if (
            routeRequests.isCurrent(routeGeneration) &&
            actionRequests.isCurrent(actionGeneration)
        ) {
            actionPending.value = false
        }
    }
}

watch(
    () => route.fullPath,
    () => handleOrderRouteChange(route.params.id),
    { immediate: true, flush: 'sync' },
)
onBeforeRouteLeave(() => {
    routeRequests.begin()
    detailRequests.begin()
    actionRequests.begin()
})
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
    <section v-loading="pending" class="page-content order-detail-page">
        <RouterLink class="back-link" to="/me/orders">← 返回我的订单</RouterLink>
        <ErrorNotice v-if="failure" :error="failure" />
        <template v-if="order">
            <p class="eyebrow">ORDER DETAIL</p>
            <div class="order-detail-heading">
                <div>
                    <h1>订单 {{ order.orderNo }}</h1>
                    <p class="lead">
                        {{ statusLabel(order.status) }} · {{ formatMoney(order.totalPriceCents) }}
                    </p>
                </div>
                <el-tag :type="statusType(order.status)" size="large">{{
                    statusLabel(order.status)
                }}</el-tag>
            </div>
            <el-card class="detail-card" shadow="never">
                <dl class="detail-grid">
                    <dt>订单编号</dt>
                    <dd>{{ order.orderNo }}</dd>
                    <dt>数量</dt>
                    <dd>{{ order.item.quantity }} 张</dd>
                    <dt>票种编号</dt>
                    <dd>{{ order.item.skuId }}</dd>
                    <dt>单价</dt>
                    <dd>{{ formatMoney(order.item.priceCents) }}</dd>
                    <dt>创建时间</dt>
                    <dd>{{ formatDate(order.createdAt) }}</dd>
                    <dt>支付截止</dt>
                    <dd>{{ formatDate(order.expiresAt) }}</dd>
                    <template v-if="order.paidAt"
                        ><dt>支付时间</dt>
                        <dd>{{ formatDate(order.paidAt) }}</dd></template
                    ><template v-if="order.closedAt"
                        ><dt>关闭时间</dt>
                        <dd>{{ formatDate(order.closedAt) }}</dd></template
                    >
                </dl>
                <div v-if="order.status === 'PENDING_PAYMENT'" class="payment-panel">
                    <div>
                        <strong>模拟支付</strong>
                        <p>此按钮只调用本项目模拟支付接口，不会连接真实支付服务。</p>
                    </div>
                    <div class="payment-actions">
                        <el-button
                            data-testid="cancel-order"
                            plain
                            :loading="actionPending"
                            @click="cancel"
                            >取消订单</el-button
                        ><el-button
                            data-testid="pay-order"
                            type="primary"
                            :loading="actionPending"
                            @click="pay"
                            >模拟支付</el-button
                        >
                    </div>
                </div>
                <div v-else-if="order.status === 'PAID'" class="paid-panel">
                    <div>
                        <strong>支付已完成，电子票已签发。</strong>
                        <p>支付通知由 RabbitMQ 异步生成，稍后可在通知页刷新查看。</p>
                    </div>
                    <RouterLink to="/me/tickets"
                        ><el-button type="primary">查看电子票</el-button></RouterLink
                    >
                </div>
                <div v-else class="closed-panel">
                    <strong>订单已关闭</strong>
                    <p>该订单无法再次支付。预约幂等键不会创建替代订单。</p>
                </div>
            </el-card>
        </template>
    </section>
</template>

<style scoped>
.back-link {
    display: inline-block;
    margin-bottom: 20px;
    color: var(--teal);
    font-size: 12px;
    font-weight: 700;
}
.order-detail-page {
    max-width: 860px;
    margin: 0 auto;
}
.order-detail-heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 18px;
}
.order-detail-heading h1 {
    margin-bottom: 6px;
    font-size: 31px;
}
.detail-card {
    padding: 15px;
    border: 1px solid var(--line);
    border-radius: 14px;
}
.detail-grid {
    display: grid;
    grid-template-columns: 140px 1fr;
    gap: 13px 18px;
    margin: 0;
}
.detail-grid dt {
    color: #8b9a9f;
    font-size: 11px;
}
.detail-grid dd {
    margin: 0;
    color: var(--ink);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
}
.payment-panel,
.paid-panel,
.closed-panel {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 15px;
    margin-top: 24px;
    padding: 18px;
    border-radius: 10px;
    background: #f1f7f6;
}
.payment-panel strong,
.paid-panel strong,
.closed-panel strong {
    color: var(--ink);
    font-size: 13px;
}
.payment-panel p,
.paid-panel p,
.closed-panel p {
    margin: 5px 0 0;
    color: var(--muted);
    font-size: 10px;
}
.payment-actions {
    display: flex;
    gap: 9px;
}
.closed-panel {
    background: #f5f6f6;
}
@media (max-width: 600px) {
    .detail-grid {
        grid-template-columns: 95px 1fr;
    }
    .payment-panel,
    .paid-panel {
        align-items: flex-start;
        flex-direction: column;
    }
    .payment-actions {
        width: 100%;
    }
    .payment-actions .el-button {
        flex: 1;
    }
}
</style>
