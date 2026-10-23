<script setup lang="ts">
import { onMounted, ref } from 'vue'
import QRCode from 'qrcode'
import { ticketApi } from '../../api/resources'
import type { Ticket, PageResponse } from '../../api/types'
import ErrorNotice from '../../components/ErrorNotice.vue'

interface DisplayCode {
    code: string
    image: string
}

const result = ref<PageResponse<Ticket> | null>(null)
const displayCodes = ref<Record<number, DisplayCode>>({})
const pending = ref(false)
const rotatingTicket = ref<number | null>(null)
const failure = ref<unknown>(null)
const page = ref(0)

async function load(): Promise<void> {
    pending.value = true
    failure.value = null
    try {
        result.value = await ticketApi.list(page.value)
        // A rotated code is one-time plaintext. A refresh intentionally removes it from memory.
        displayCodes.value = {}
    } catch (error) {
        failure.value = error
    } finally {
        pending.value = false
    }
}

async function rotate(ticketId: number): Promise<void> {
    if (rotatingTicket.value !== null) return
    rotatingTicket.value = ticketId
    failure.value = null
    const nextDisplayCodes = { ...displayCodes.value }
    delete nextDisplayCodes[ticketId]
    displayCodes.value = nextDisplayCodes
    try {
        const response = await ticketApi.rotateCode(ticketId)
        const image = await QRCode.toDataURL(response.checkinCode, {
            errorCorrectionLevel: 'M',
            margin: 2,
            width: 220,
            color: { dark: '#183342', light: '#ffffff' },
        })
        displayCodes.value = {
            ...displayCodes.value,
            [ticketId]: { code: response.checkinCode, image },
        }
    } catch (error) {
        failure.value = error
        const nextDisplayCodes = { ...displayCodes.value }
        delete nextDisplayCodes[ticketId]
        displayCodes.value = nextDisplayCodes
    } finally {
        rotatingTicket.value = null
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
                <p class="eyebrow">YOUR TICKETS</p>
                <h1>电子票</h1>
                <p class="lead">核销码只会在本次轮换成功后显示一次，刷新页面会清除明文。</p>
            </div>
            <el-button plain :loading="pending" @click="load">刷新电子票</el-button>
        </div>
        <ErrorNotice v-if="failure" :error="failure" />
        <div v-loading="pending" class="ticket-grid">
            <article
                v-for="ticket in result?.items ?? []"
                :key="ticket.id"
                :data-testid="`ticket-${ticket.id}`"
                class="ticket-card"
            >
                <div class="ticket-accent" aria-hidden="true"></div>
                <div class="ticket-card-heading">
                    <div>
                        <p class="eyebrow">E-TICKET · #{{ ticket.id }}</p>
                        <h2>EventFlow 入场票</h2>
                    </div>
                    <el-tag
                        :type="ticket.status === 'USED' ? 'info' : 'success'"
                        data-testid="ticket-status"
                        >{{ ticket.status === 'USED' ? '已核销' : '未使用' }}</el-tag
                    >
                </div>
                <dl class="ticket-meta">
                    <dt>订单</dt>
                    <dd>
                        <RouterLink :to="`/me/orders/${ticket.orderId}`"
                            >#{{ ticket.orderId }}</RouterLink
                        >
                    </dd>
                    <dt>场次编号</dt>
                    <dd>{{ ticket.sessionId }}</dd>
                    <dt>签发时间</dt>
                    <dd>{{ new Date(ticket.createdAt).toLocaleString() }}</dd>
                    <template v-if="ticket.usedAt"
                        ><dt>核销时间</dt>
                        <dd>{{ new Date(ticket.usedAt).toLocaleString() }}</dd></template
                    >
                </dl>
                <div class="ticket-code-area">
                    <el-button
                        v-if="ticket.status === 'UNUSED'"
                        data-testid="rotate-checkin-code"
                        type="primary"
                        plain
                        :loading="rotatingTicket === ticket.id"
                        :disabled="rotatingTicket !== null && rotatingTicket !== ticket.id"
                        @click="rotate(ticket.id)"
                    >
                        {{ displayCodes[ticket.id] ? '轮换核销码' : '生成核销码' }}
                    </el-button>
                    <template v-if="displayCodes[ticket.id]">
                        <p class="code-disclaimer">
                            此码仅在当前页面内存显示。离开页面后请重新轮换。
                        </p>
                        <img
                            v-if="displayCodes[ticket.id].image"
                            :src="displayCodes[ticket.id].image"
                            alt="本次核销码 QR"
                            class="ticket-qr"
                        />
                        <code data-testid="checkin-code">{{ displayCodes[ticket.id].code }}</code>
                    </template>
                    <p v-if="ticket.status !== 'UNUSED'" class="used-ticket-note">
                        此票已使用，不能再次生成核销码。
                    </p>
                </div>
            </article>
        </div>
        <el-empty
            v-if="!pending && !failure && result?.items.length === 0"
            description="支付成功后，电子票会出现在这里"
        />
        <div v-if="result && result.total > result.size" class="pager-row">
            <span>共 {{ result.total }} 张电子票</span>
            <el-pagination
                data-testid="ticket-pagination"
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
.ticket-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 15px;
    min-height: 80px;
}
.ticket-card {
    position: relative;
    overflow: hidden;
    padding: 22px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: #fff;
}
.ticket-accent {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 5px;
    background: linear-gradient(90deg, #0b8283, #a5d5d2);
}
.ticket-card-heading {
    display: flex;
    justify-content: space-between;
    align-items: start;
    gap: 12px;
}
.ticket-card-heading .eyebrow {
    margin: 0 0 6px;
}
.ticket-card-heading h2 {
    margin: 0;
    font-size: 18px;
}
.ticket-meta {
    display: grid;
    grid-template-columns: 90px 1fr;
    gap: 8px 12px;
    margin: 20px 0;
}
.ticket-meta dt {
    color: #86969b;
    font-size: 10px;
}
.ticket-meta dd {
    margin: 0;
    color: #3b5963;
    font-size: 11px;
}
.ticket-code-area {
    display: grid;
    justify-items: start;
    gap: 9px;
    min-height: 42px;
    padding-top: 14px;
    border-top: 1px dashed #dce5e6;
}
.ticket-code-area code {
    padding: 7px;
    border-radius: 5px;
    background: #f2f6f6;
    font-size: 10px;
    overflow-wrap: anywhere;
}
.code-disclaimer,
.used-ticket-note {
    margin: 0;
    color: #819197;
    font-size: 10px;
}
.ticket-qr {
    width: 132px;
    height: 132px;
    padding: 5px;
    border: 1px solid #e4eaeb;
    border-radius: 7px;
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
@media (max-width: 700px) {
    .ticket-grid {
        grid-template-columns: 1fr;
    }
    .page-title-row {
        align-items: flex-start;
    }
}
</style>
