<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { organizerApi } from '../../api/resources'
import type { EventDetail } from '../../api/types'
import ErrorNotice from '../../components/ErrorNotice.vue'

const route = useRoute()
const router = useRouter()
const event = ref<EventDetail | null>(null)
const title = ref('')
const description = ref('')
const selectedSessionId = ref('')
const sessionTitle = ref('')
const startsAt = ref(defaultLocalTime(30))
const endsAt = ref(defaultLocalTime(120))
const location = ref('')
const skuName = ref('')
const priceCents = ref(0)
const totalStock = ref(100)
const pending = ref(false)
const saving = ref(false)
const failure = ref<unknown>(null)
const isNew = computed(() => route.name === 'organizer-event-new')
const eventId = computed(() => Number(route.params.id))
const isDraft = computed(() => event.value?.status === 'DRAFT')
const sessions = computed(() => event.value?.sessions ?? [])
const selectedSession = computed(() =>
    sessions.value.find((session) => String(session.id) === selectedSessionId.value),
)
const canPublish = computed(
    () =>
        sessions.value.length > 0 &&
        sessions.value.every((session) => session.ticketSkus.length > 0),
)

async function load(): Promise<void> {
    if (isNew.value) return
    if (!Number.isSafeInteger(eventId.value) || eventId.value <= 0) return
    pending.value = true
    failure.value = null
    try {
        event.value = await organizerApi.detail(eventId.value)
        title.value = event.value.title
        description.value = event.value.description
        if (!sessions.value.some((session) => String(session.id) === selectedSessionId.value)) {
            selectedSessionId.value = sessions.value[0] ? String(sessions.value[0].id) : ''
        }
    } catch (error) {
        failure.value = error
    } finally {
        pending.value = false
    }
}

async function saveEvent(): Promise<void> {
    if (saving.value) return
    if (!title.value.trim() || !description.value.trim()) return
    saving.value = true
    failure.value = null
    try {
        if (!event.value) {
            const created = await organizerApi.create(title.value.trim(), description.value.trim())
            event.value = created
            ElMessage.success('草稿已创建。接下来添加场次与票种。')
            await router.replace({ name: 'organizer-event-edit', params: { id: created.id } })
            return
        }
        event.value = await organizerApi.edit(
            event.value.id,
            title.value.trim(),
            description.value.trim(),
        )
        ElMessage.success('草稿信息已保存。')
    } catch (error) {
        failure.value = error
    } finally {
        saving.value = false
    }
}

async function addSession(): Promise<void> {
    if (saving.value) return
    if (!event.value || !sessionTitle.value.trim() || !location.value.trim()) return
    const start = new Date(startsAt.value)
    const end = new Date(endsAt.value)
    if (
        !Number.isFinite(start.getTime()) ||
        !Number.isFinite(end.getTime()) ||
        start <= new Date() ||
        end <= start
    ) {
        failure.value = new Error('场次必须在未来开始，结束时间须晚于开始时间。')
        return
    }
    saving.value = true
    failure.value = null
    try {
        const session = await organizerApi.addSession(event.value.id, {
            title: sessionTitle.value.trim(),
            startsAt: start.toISOString(),
            endsAt: end.toISOString(),
            location: location.value.trim(),
        })
        await load()
        selectedSessionId.value = String(session.id)
        sessionTitle.value = ''
        location.value = ''
        ElMessage.success('场次已添加。')
    } catch (error) {
        failure.value = error
    } finally {
        saving.value = false
    }
}

async function addSku(): Promise<void> {
    if (saving.value) return
    if (!selectedSession.value || !skuName.value.trim()) return
    if (
        !Number.isSafeInteger(priceCents.value) ||
        priceCents.value < 0 ||
        !Number.isSafeInteger(totalStock.value) ||
        totalStock.value < 0
    ) {
        failure.value = new Error('价格和库存必须是非负整数，价格单位为分。')
        return
    }
    saving.value = true
    failure.value = null
    try {
        await organizerApi.addSku(selectedSession.value.id, {
            name: skuName.value.trim(),
            priceCents: priceCents.value,
            totalStock: totalStock.value,
        })
        await load()
        skuName.value = ''
        ElMessage.success('票种已添加，库存数据已保存到服务端。')
    } catch (error) {
        failure.value = error
    } finally {
        saving.value = false
    }
}

async function publish(): Promise<void> {
    if (saving.value) return
    if (!event.value) return
    saving.value = true
    failure.value = null
    try {
        event.value = await organizerApi.publish(event.value.id)
        ElMessage.success('活动已发布，可以从公开活动页查看。')
    } catch (error) {
        failure.value = error
    } finally {
        saving.value = false
    }
}

function defaultLocalTime(minutesAhead: number): string {
    const date = new Date(Date.now() + minutesAhead * 60_000)
    date.setMinutes(date.getMinutes() - date.getTimezoneOffset())
    return date.toISOString().slice(0, 16)
}

function formatDate(value: string): string {
    return new Date(value).toLocaleString()
}
function formatMoney(cents: number): string {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'CNY' }).format(
        cents / 100,
    )
}

onMounted(() => void load())
</script>

<template>
    <section v-loading="pending" class="page-content editor-page">
        <RouterLink class="back-link" to="/organizer/events">← 返回主办方活动</RouterLink>
        <div class="page-title-row">
            <div>
                <p class="eyebrow">EVENT BUILDER</p>
                <h1>{{ isNew ? '创建活动草稿' : '活动设置' }}</h1>
                <p class="lead">先完善基本信息，再添加未来场次和票种。</p>
            </div>
            <el-tag v-if="event" :type="isDraft ? 'warning' : 'success'">{{
                event.status === 'DRAFT' ? '草稿' : '已发布'
            }}</el-tag>
        </div>
        <ErrorNotice v-if="failure" :error="failure" />

        <section class="editor-card">
            <div class="card-heading">
                <span class="step-number">01</span>
                <div>
                    <h2>基本信息</h2>
                    <p>只有草稿可以修改。</p>
                </div>
            </div>
            <form class="form-grid" @submit.prevent="saveEvent">
                <label class="field-label" for="event-title-input">活动名称</label>
                <el-input
                    id="event-title-input"
                    v-model="title"
                    data-testid="event-title-input"
                    maxlength="120"
                    required
                    :disabled="event !== null && !isDraft"
                />
                <label class="field-label" for="event-description-input">活动介绍</label>
                <el-input
                    id="event-description-input"
                    v-model="description"
                    data-testid="event-description-input"
                    type="textarea"
                    :rows="4"
                    maxlength="10000"
                    required
                    :disabled="event !== null && !isDraft"
                />
                <div class="form-actions">
                    <el-button
                        data-testid="save-event"
                        type="primary"
                        native-type="submit"
                        :loading="saving"
                        :disabled="event !== null && !isDraft"
                        >{{ event ? '保存草稿' : '创建并继续' }}</el-button
                    >
                </div>
            </form>
        </section>

        <template v-if="event && isDraft">
            <section class="editor-card">
                <div class="card-heading">
                    <span class="step-number">02</span>
                    <div>
                        <h2>活动场次</h2>
                        <p>时间按浏览器本地时间填写，再转换为 UTC 发送给服务端。</p>
                    </div>
                </div>
                <div v-if="sessions.length" class="existing-list">
                    <article v-for="session in sessions" :key="session.id" class="existing-item">
                        <strong>{{ session.title }}</strong
                        ><span>{{ formatDate(session.startsAt) }} · {{ session.location }}</span
                        ><small>{{ session.ticketSkus.length }} 个票种</small>
                    </article>
                </div>
                <form class="form-grid session-form" @submit.prevent="addSession">
                    <label class="field-label" for="session-title-input">场次名称</label
                    ><el-input
                        id="session-title-input"
                        v-model="sessionTitle"
                        data-testid="session-title-input"
                        maxlength="120"
                        required
                    />
                    <label class="field-label" for="session-starts-at">开始时间（本地）</label
                    ><el-input
                        id="session-starts-at"
                        v-model="startsAt"
                        data-testid="session-starts-at"
                        type="datetime-local"
                        required
                    />
                    <label class="field-label" for="session-ends-at">结束时间（本地）</label
                    ><el-input
                        id="session-ends-at"
                        v-model="endsAt"
                        data-testid="session-ends-at"
                        type="datetime-local"
                        required
                    />
                    <label class="field-label" for="session-location">地点</label
                    ><el-input
                        id="session-location"
                        v-model="location"
                        data-testid="session-location"
                        maxlength="160"
                        required
                    />
                    <div class="form-actions">
                        <el-button
                            data-testid="add-session"
                            type="primary"
                            native-type="submit"
                            :loading="saving"
                            >添加场次</el-button
                        >
                    </div>
                </form>
            </section>

            <section v-if="sessions.length" class="editor-card">
                <div class="card-heading">
                    <span class="step-number">03</span>
                    <div>
                        <h2>票种与库存</h2>
                        <p>单笔订单只预约一张票，库存数量为整数。</p>
                    </div>
                </div>
                <label class="field-label" for="session-select">选择场次</label>
                <select id="session-select" v-model="selectedSessionId" class="native-select">
                    <option
                        v-for="session in sessions"
                        :key="session.id"
                        :value="String(session.id)"
                    >
                        {{ session.title }}
                    </option>
                </select>
                <div
                    v-if="selectedSession?.ticketSkus.length"
                    class="existing-list sku-existing-list"
                >
                    <article
                        v-for="sku in selectedSession.ticketSkus"
                        :key="sku.id"
                        class="existing-item"
                    >
                        <strong>{{ sku.name }}</strong
                        ><span
                            >{{ formatMoney(sku.priceCents) }} · {{ sku.availableStock }} /
                            {{ sku.totalStock }} 张</span
                        >
                    </article>
                </div>
                <form class="form-grid sku-form" @submit.prevent="addSku">
                    <label class="field-label" for="sku-name-input">票种名称</label
                    ><el-input
                        id="sku-name-input"
                        v-model="skuName"
                        data-testid="sku-name-input"
                        maxlength="100"
                        required
                    />
                    <label class="field-label" for="sku-price-cents">价格（分）</label
                    ><el-input-number
                        id="sku-price-cents"
                        v-model="priceCents"
                        data-testid="sku-price-cents"
                        :min="0"
                        :max="100000000"
                        :precision="0"
                        controls-position="right"
                    />
                    <label class="field-label" for="sku-stock">总库存（张）</label
                    ><el-input-number
                        id="sku-stock"
                        v-model="totalStock"
                        data-testid="sku-stock"
                        :min="0"
                        :max="1000000"
                        :precision="0"
                        controls-position="right"
                    />
                    <div class="form-actions">
                        <el-button
                            data-testid="add-sku"
                            type="primary"
                            native-type="submit"
                            :loading="saving"
                            >添加票种</el-button
                        >
                    </div>
                </form>
            </section>

            <section class="publish-row">
                <div>
                    <strong>发布前检查</strong>
                    <p>每个场次需要至少一个票种，并且至少有一场在未来开始。</p>
                </div>
                <el-button
                    data-testid="publish-event"
                    type="primary"
                    :disabled="!canPublish"
                    :loading="saving"
                    @click="publish"
                    >发布活动</el-button
                >
            </section>
        </template>

        <section v-else-if="event" class="published-note">
            <el-alert
                title="此活动已经发布，活动信息与票种配置已锁定。"
                type="success"
                :closable="false"
                show-icon
            /><RouterLink :to="`/events/${event.id}`" class="text-link">查看公开活动 →</RouterLink>
        </section>
    </section>
</template>

<style scoped>
.back-link {
    display: inline-block;
    margin-bottom: 18px;
    color: var(--teal);
    font-size: 12px;
    font-weight: 700;
}
.page-title-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 15px;
    margin-bottom: 24px;
}
.editor-card {
    margin-bottom: 16px;
    padding: 23px 26px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: #fff;
}
.card-heading {
    display: flex;
    align-items: center;
    gap: 13px;
    margin-bottom: 18px;
}
.card-heading h2 {
    margin: 0 0 4px;
    font-size: 17px;
}
.card-heading p {
    margin: 0;
    color: var(--muted);
    font-size: 11px;
}
.step-number {
    display: grid;
    place-items: center;
    width: 33px;
    height: 33px;
    border-radius: 9px;
    color: var(--teal);
    background: #e1f2ef;
    font-size: 12px;
    font-weight: 800;
}
.form-grid {
    display: grid;
    grid-template-columns: 150px minmax(0, 1fr);
    align-items: center;
    gap: 10px 15px;
}
.form-grid > .field-label {
    margin: 0;
}
.form-actions {
    grid-column: 2;
    margin-top: 5px;
}
.native-select {
    width: min(100%, 460px);
    height: 38px;
    margin-bottom: 13px;
    padding: 0 10px;
    border: 1px solid #d8e0e2;
    border-radius: 6px;
    background: white;
    color: var(--ink);
}
.existing-list {
    display: grid;
    gap: 7px;
    margin: 0 0 16px 48px;
}
.existing-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
    border-radius: 8px;
    background: #f5f8f8;
    font-size: 11px;
}
.existing-item span,
.existing-item small {
    color: var(--muted);
}
.sku-existing-list {
    margin-left: 0;
}
.publish-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 18px;
    padding: 20px 24px;
    border-radius: 13px;
    background: var(--ink);
    color: white;
}
.publish-row p {
    margin: 5px 0 0;
    color: #b5c8ce;
    font-size: 11px;
}
.published-note {
    display: grid;
    gap: 12px;
    max-width: 600px;
}
@media (max-width: 650px) {
    .form-grid {
        grid-template-columns: 1fr;
        gap: 6px;
    }
    .form-actions {
        grid-column: 1;
        margin: 7px 0 10px;
    }
    .existing-list {
        margin-left: 0;
    }
    .publish-row {
        align-items: flex-start;
        flex-direction: column;
    }
    .page-title-row {
        align-items: flex-start;
    }
}
</style>
