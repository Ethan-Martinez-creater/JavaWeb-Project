<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { eventApi } from '../../api/resources'
import type { EventSummary, PageResponse } from '../../api/types'
import ErrorNotice from '../../components/ErrorNotice.vue'

const keyword = ref('')
const page = ref(0)
const result = ref<PageResponse<EventSummary> | null>(null)
const pending = ref(false)
const failure = ref<unknown>(null)

async function load(): Promise<void> {
    pending.value = true
    failure.value = null
    try {
        result.value = await eventApi.list(keyword.value, page.value)
    } catch (error) {
        failure.value = error
    } finally {
        pending.value = false
    }
}

function search(): void {
    page.value = 0
    void load()
}

function changePage(currentPage: number): void {
    page.value = currentPage - 1
    void load()
}

onMounted(() => void load())
</script>

<template>
    <section class="page-content">
        <div class="hero-row">
            <div>
                <p class="eyebrow">MAKE TIME FOR SOMETHING GOOD</p>
                <h1>发现值得奔赴的现场</h1>
                <p class="lead">从一场演出、一次分享或一段新的体验开始。</p>
            </div>
            <div class="hero-stamp" aria-hidden="true">
                <span>LIVE</span><strong>好活动<br />在这里</strong>
            </div>
        </div>

        <div class="section-heading">
            <div>
                <h2>正在发生的灵感</h2>
                <p>已发布活动与票种库存来自 EventFlow 服务端。</p>
            </div>
            <form class="search-row" @submit.prevent="search">
                <el-input
                    v-model="keyword"
                    data-testid="event-search"
                    placeholder="搜索活动名称"
                    clearable
                    aria-label="搜索活动"
                />
                <el-button type="primary" native-type="submit" :loading="pending">搜索</el-button>
            </form>
        </div>

        <ErrorNotice v-if="failure" :error="failure" />

        <div v-loading="pending" class="event-grid" aria-live="polite">
            <article
                v-for="(event, index) in result?.items ?? []"
                :key="event.id"
                class="event-card"
                :class="`event-card-tone-${index % 4}`"
                :data-testid="`event-card-${event.id}`"
            >
                <div class="event-card-topline">
                    <span class="event-category">精选活动</span>
                    <span class="event-card-number">{{
                        String(index + 1 + page * 12).padStart(2, '0')
                    }}</span>
                </div>
                <h3>
                    <RouterLink
                        :data-testid="`event-link-${event.id}`"
                        :to="`/events/${event.id}`"
                        >{{ event.title }}</RouterLink
                    >
                </h3>
                <p class="event-description">{{ event.description }}</p>
                <div class="event-card-footer">
                    <span>主办方 #{{ event.organizerId }}</span>
                    <RouterLink
                        :to="`/events/${event.id}`"
                        class="arrow-link"
                        :aria-label="`查看${event.title}`"
                        >查看活动 <span aria-hidden="true">↗</span></RouterLink
                    >
                </div>
            </article>
        </div>

        <el-empty
            v-if="!pending && !failure && result?.items.length === 0"
            description="暂时没有找到符合条件的活动"
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

        <section class="organizer-callout">
            <div>
                <span class="eyebrow">FOR ORGANIZERS</span>
                <h2>把你的下一场活动带到这里</h2>
            </div>
            <RouterLink to="/login" class="text-link"
                >主办方登录 <span aria-hidden="true">→</span></RouterLink
            >
        </section>
    </section>
</template>
