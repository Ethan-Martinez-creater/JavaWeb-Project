<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { eventApi, orderApi } from '../../api/resources'
import type { EventDetail, TicketSku } from '../../api/types'
import { createRequestGeneration } from '../../utils/requestGeneration'
import {
    clearReservationKey,
    getReservationKey,
    isReservationStorageAvailable,
} from '../orders/idempotency'
import ErrorNotice from '../../components/ErrorNotice.vue'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const routeRequests = createRequestGeneration()
const detailRequests = createRequestGeneration()
const reservationRequests = createRequestGeneration()
const event = ref<EventDetail | null>(null)
const selectedSkuId = ref('')
const pending = ref(false)
const ordering = ref(false)
const failure = ref<unknown>(null)
const reservationStorageAvailable = ref(isReservationStorageAvailable())
let currentRouteGeneration = 0

const skus = computed<TicketSku[]>(
    () => event.value?.sessions.flatMap((session) => session.ticketSkus) ?? [],
)
const selectedSku = computed(() => skus.value.find((sku) => String(sku.id) === selectedSkuId.value))

async function load(id: number, routeGeneration: number): Promise<void> {
    const detailGeneration = detailRequests.begin()
    pending.value = true
    failure.value = null
    try {
        const nextEvent = await eventApi.detail(id)
        if (
            !routeRequests.isCurrent(routeGeneration) ||
            !detailRequests.isCurrent(detailGeneration) ||
            Number(route.params.id) !== id
        ) {
            return
        }
        event.value = nextEvent
        if (!skus.value.some((sku) => String(sku.id) === selectedSkuId.value)) {
            selectedSkuId.value = skus.value[0] ? String(skus.value[0].id) : ''
        }
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

function handleEventRouteChange(rawId: string | string[] | undefined): void {
    currentRouteGeneration = routeRequests.begin()
    detailRequests.begin()
    reservationRequests.begin()
    event.value = null
    selectedSkuId.value = ''
    failure.value = null
    pending.value = false
    ordering.value = false

    const id = Number(rawId)
    if (!Number.isSafeInteger(id) || id <= 0) {
        failure.value = new Error('活动编号无效。')
        return
    }
    void load(id, currentRouteGeneration)
}

function refresh(): void {
    const id = Number(route.params.id)
    if (Number.isSafeInteger(id) && id > 0) {
        void load(id, currentRouteGeneration)
    }
}

async function reserve(): Promise<void> {
    const currentEvent = event.value
    const currentSku = selectedSku.value
    if (
        ordering.value ||
        pending.value ||
        !currentEvent ||
        currentEvent.id !== Number(route.params.id) ||
        !currentSku
    ) {
        return
    }
    if (!auth.user || (!auth.hasRole('USER') && !auth.hasRole('ADMIN'))) {
        await router.push({ name: 'login', query: { redirect: route.fullPath } })
        return
    }

    const routeGeneration = currentRouteGeneration
    const reservationGeneration = reservationRequests.begin()
    const userId = auth.user.id
    const eventId = currentEvent.id
    ordering.value = true
    failure.value = null
    const skuId = currentSku.id
    const key = getReservationKey(userId, skuId)
    try {
        const order = await orderApi.create(skuId, key)
        // The key is kept for every failed or ambiguous request and cleared only on a response.
        clearReservationKey(userId, skuId)
        if (
            !routeRequests.isCurrent(routeGeneration) ||
            !reservationRequests.isCurrent(reservationGeneration) ||
            Number(route.params.id) !== eventId
        ) {
            return
        }
        ElMessage.success('预约成功，订单为本人保留。请在订单有效期内完成模拟支付。')
        await router.push({ name: 'order-detail', params: { id: order.id } })
    } catch (error) {
        if (
            routeRequests.isCurrent(routeGeneration) &&
            reservationRequests.isCurrent(reservationGeneration)
        ) {
            failure.value = error
        }
    } finally {
        if (
            routeRequests.isCurrent(routeGeneration) &&
            reservationRequests.isCurrent(reservationGeneration)
        ) {
            ordering.value = false
        }
    }
}

watch(
    () => route.fullPath,
    () => handleEventRouteChange(route.params.id),
    { immediate: true, flush: 'sync' },
)
onBeforeRouteLeave(() => {
    routeRequests.begin()
    detailRequests.begin()
    reservationRequests.begin()
})
function formatDate(value: string): string {
    return new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

function formatMoney(cents: number): string {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'CNY' }).format(
        cents / 100,
    )
}
</script>

<template>
    <section v-loading="pending" class="page-content detail-page">
        <RouterLink class="back-link" to="/">← 返回活动列表</RouterLink>
        <ErrorNotice v-if="failure" :error="failure" />
        <template v-if="event">
            <div class="detail-hero">
                <p class="eyebrow">EVENT · {{ event.status }}</p>
                <h1 data-testid="event-title">{{ event.title }}</h1>
                <p class="detail-description">{{ event.description }}</p>
                <div class="detail-meta">
                    <span>主办方 #{{ event.organizerId }}</span
                    ><span>活动编号 {{ event.id }}</span>
                </div>
            </div>

            <div class="detail-columns">
                <section class="detail-section">
                    <div class="section-heading compact-heading">
                        <div>
                            <h2>场次与票种</h2>
                            <p>票量会在每次刷新时从服务端重新读取。</p>
                        </div>
                        <el-button
                            data-testid="refresh-event"
                            plain
                            :loading="pending"
                            :disabled="ordering"
                            @click="refresh"
                            >刷新库存</el-button
                        >
                    </div>
                    <el-empty v-if="event.sessions.length === 0" description="还没有可预约的场次" />
                    <article
                        v-for="session in event.sessions"
                        :key="session.id"
                        class="session-card"
                    >
                        <div class="session-heading">
                            <h3>{{ session.title }}</h3>
                            <span>{{ session.location }}</span>
                        </div>
                        <p class="session-time">
                            {{ formatDate(session.startsAt) }} — {{ formatDate(session.endsAt) }}
                        </p>
                        <div v-for="sku in session.ticketSkus" :key="sku.id" class="sku-row">
                            <div>
                                <strong>{{ sku.name }}</strong
                                ><span class="sku-stock"
                                    >余 {{ sku.availableStock }} / {{ sku.totalStock }} 张</span
                                >
                            </div>
                            <strong class="price">{{ formatMoney(sku.priceCents) }}</strong>
                        </div>
                    </article>
                </section>

                <aside class="reserve-panel">
                    <span class="eyebrow">YOUR NEXT PLAN</span>
                    <h2>选择一张票</h2>
                    <p class="muted">每次预约一张，库存以数据库事务结果为准。</p>
                    <label class="field-label" for="sku-select">票种</label>
                    <select
                        id="sku-select"
                        v-model="selectedSkuId"
                        data-testid="sku-select"
                        class="native-select"
                    >
                        <option
                            v-for="sku in skus"
                            :key="sku.id"
                            :value="String(sku.id)"
                            :disabled="sku.availableStock < 1"
                        >
                            {{ sku.name }} · {{ formatMoney(sku.priceCents) }} · 余
                            {{ sku.availableStock }} 张
                        </option>
                    </select>
                    <div v-if="selectedSku" class="reserve-total">
                        <span>应付金额</span
                        ><strong>{{ formatMoney(selectedSku.priceCents) }}</strong>
                    </div>
                    <el-button
                        class="wide-button"
                        data-testid="reserve-order"
                        type="primary"
                        :disabled="pending || !selectedSku || selectedSku.availableStock < 1"
                        :loading="ordering"
                        @click="reserve"
                    >
                        {{ selectedSku && selectedSku.availableStock < 1 ? '已售罄' : '预约一张' }}
                    </el-button>
                    <p class="reserve-note">
                        下单请求带客户端幂等键；遇到网络错误，请重试原请求或查看订单列表。
                    </p>
                    <p v-if="!reservationStorageAvailable" class="storage-warning" role="status">
                        浏览器阻止了会话存储，幂等键仅保存在当前页面内存。如果预约结果不明确，请先查看订单；刷新或重新登录后不要直接重复预约。
                    </p>
                </aside>
            </div>
        </template>
    </section>
</template>

<style scoped>
.back-link {
    display: inline-block;
    margin: 0 0 20px;
    color: var(--teal);
    font-size: 12px;
    font-weight: 700;
}
.detail-hero {
    padding: 34px 40px 28px;
    border-radius: 16px;
    background: #e6f3f1;
}
.detail-hero h1 {
    margin-bottom: 12px;
}
.detail-description {
    max-width: 700px;
    color: #526e76;
    line-height: 1.8;
    white-space: pre-wrap;
}
.detail-meta {
    display: flex;
    gap: 18px;
    color: #6e858b;
    font-size: 11px;
}
.detail-columns {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 310px;
    gap: 28px;
    margin-top: 28px;
}
.compact-heading {
    align-items: center;
    margin: 0 0 16px;
}
.session-card {
    margin-bottom: 14px;
    padding: 20px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: #fff;
}
.session-heading {
    display: flex;
    justify-content: space-between;
    gap: 16px;
}
.session-heading h3 {
    margin: 0;
    font-size: 16px;
}
.session-heading span,
.session-time {
    color: var(--muted);
    font-size: 11px;
}
.session-time {
    margin: 7px 0 16px;
}
.sku-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 11px 0;
    border-top: 1px solid #edf1f2;
    font-size: 12px;
}
.sku-row > div {
    display: grid;
    gap: 4px;
}
.sku-stock {
    color: #8b9ba0;
    font-size: 10px;
}
.price {
    color: var(--teal);
    font-variant-numeric: tabular-nums;
}
.reserve-panel {
    align-self: start;
    padding: 22px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: #fff;
}
.reserve-panel h2 {
    margin-bottom: 6px;
}
.native-select {
    width: 100%;
    height: 40px;
    margin-top: 7px;
    padding: 0 10px;
    border: 1px solid #d8e0e2;
    border-radius: 6px;
    color: var(--ink);
    background: white;
}
.reserve-total {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    margin-top: 20px;
    padding-top: 16px;
    border-top: 1px solid var(--line);
    color: var(--muted);
    font-size: 12px;
}
.reserve-total strong {
    color: var(--ink);
    font-size: 22px;
}
.reserve-note {
    margin: 13px 0 0;
    color: #93a1a5;
    font-size: 10px;
    line-height: 1.7;
}
.storage-warning {
    margin: 10px 0 0;
    padding: 9px 10px;
    border-radius: 7px;
    background: #fff5e8;
    color: #815b28;
    font-size: 10px;
    line-height: 1.6;
}
@media (max-width: 800px) {
    .detail-columns {
        grid-template-columns: 1fr;
    }
    .reserve-panel {
        order: -1;
    }
}
@media (max-width: 520px) {
    .detail-hero {
        padding: 25px 22px;
    }
    .session-heading {
        flex-direction: column;
        gap: 5px;
    }
    .compact-heading {
        align-items: flex-start;
    }
}
</style>
