<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { organizerApi } from '../../api/resources'
import type { EventSummary, PageResponse } from '../../api/types'
import ErrorNotice from '../../components/ErrorNotice.vue'

const result = ref<PageResponse<EventSummary> | null>(null)
const pending = ref(false)
const failure = ref<unknown>(null)
const page = ref(0)

async function load(): Promise<void> {
    pending.value = true
    failure.value = null
    try {
        result.value = await organizerApi.list(page.value)
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
                <p class="eyebrow">ORGANIZER WORKSPACE</p>
                <h1>管理你的活动</h1>
                <p class="lead">只显示当前主办方有权访问的活动。</p>
            </div>
            <RouterLink to="/organizer/events/new"
                ><el-button data-testid="create-event" type="primary"
                    >创建活动</el-button
                ></RouterLink
            >
        </div>
        <ErrorNotice v-if="failure" :error="failure" />
        <div v-loading="pending" class="organizer-event-list">
            <article
                v-for="event in result?.items ?? []"
                :key="event.id"
                class="organizer-event-row"
            >
                <div class="organizer-event-badge">
                    {{ event.status === 'DRAFT' ? '草稿' : '已发布' }}
                </div>
                <div class="organizer-event-copy">
                    <h2>{{ event.title }}</h2>
                    <p>{{ event.description }}</p>
                    <span>活动编号 {{ event.id }}</span>
                </div>
                <RouterLink
                    :data-testid="`event-link-${event.id}`"
                    :to="`/organizer/events/${event.id}/edit`"
                    class="text-link"
                    >查看与编辑 →</RouterLink
                >
            </article>
        </div>
        <el-empty
            v-if="!pending && !failure && result?.items.length === 0"
            description="还没有活动，创建一个草稿开始吧"
        />
        <div v-if="result && result.total > result.size" class="pager-row">
            <span>共 {{ result.total }} 场活动</span>
            <el-pagination
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
    gap: 18px;
    margin-bottom: 24px;
}
.organizer-event-list {
    display: grid;
    gap: 12px;
    min-height: 80px;
}
.organizer-event-row {
    display: grid;
    grid-template-columns: 70px 1fr auto;
    align-items: center;
    gap: 20px;
    padding: 19px 22px;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: #fff;
}
.organizer-event-badge {
    width: fit-content;
    padding: 6px 9px;
    border-radius: 20px;
    color: #765428;
    background: #fbf2df;
    font-size: 10px;
    font-weight: 700;
}
.organizer-event-copy h2 {
    margin: 0 0 4px;
    font-size: 16px;
}
.organizer-event-copy p {
    margin: 0 0 6px;
    overflow: hidden;
    color: var(--muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.organizer-event-copy span {
    color: #9aa8ac;
    font-size: 10px;
}
.text-link {
    font-size: 12px;
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
@media (max-width: 640px) {
    .organizer-event-row {
        grid-template-columns: 1fr auto;
        gap: 10px;
    }
    .organizer-event-badge {
        grid-column: 1 / -1;
    }
    .page-title-row {
        align-items: flex-start;
    }
}
</style>
