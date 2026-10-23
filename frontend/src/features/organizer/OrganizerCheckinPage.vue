<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { organizerApi } from '../../api/resources'
import type { CheckinResult } from '../../api/types'
import ErrorNotice from '../../components/ErrorNotice.vue'

const checkinCode = ref('')
const result = ref<CheckinResult | null>(null)
const pending = ref(false)
const failure = ref<unknown>(null)

async function checkIn(): Promise<void> {
    const code = checkinCode.value.trim()
    if (!/^[A-Za-z0-9_-]{43}$/.test(code)) {
        failure.value = new Error('请粘贴完整的 43 位核销码。')
        return
    }
    pending.value = true
    failure.value = null
    result.value = null
    try {
        result.value = await organizerApi.checkin(code)
        checkinCode.value = ''
        ElMessage.success('核销成功，服务端已将此票标记为 USED。')
    } catch (error) {
        failure.value = error
    } finally {
        pending.value = false
    }
}
</script>

<template>
    <section class="page-content checkin-page">
        <p class="eyebrow">AT THE DOOR</p>
        <h1>现场核销</h1>
        <p class="lead">
            演示时可手动粘贴本人票页刚生成的核销码。服务端会验证主办方归属、场次时间和票据状态。
        </p>
        <ErrorNotice v-if="failure" :error="failure" />
        <el-card class="checkin-card" shadow="never">
            <form class="form-stack" @submit.prevent="checkIn">
                <label class="field-label" for="checkin-code-input">核销码</label>
                <el-input
                    id="checkin-code-input"
                    v-model="checkinCode"
                    data-testid="checkin-code-input"
                    type="textarea"
                    :rows="3"
                    maxlength="43"
                    autocomplete="off"
                    spellcheck="false"
                    placeholder="粘贴 43 位一次性核销码"
                />
                <el-button
                    data-testid="checkin-submit"
                    class="wide-button"
                    type="primary"
                    native-type="submit"
                    :loading="pending"
                    >验证并核销</el-button
                >
            </form>
            <section v-if="result" class="checkin-result" aria-live="polite">
                <span class="success-mark" aria-hidden="true">✓</span>
                <div>
                    <strong>核销成功</strong>
                    <p data-testid="checkin-result-status">票据状态：{{ result.status }}</p>
                    <small
                        >票据编号 {{ result.ticketId }} ·
                        {{ new Date(result.usedAt).toLocaleString() }}</small
                    >
                </div>
            </section>
        </el-card>
        <p class="checkin-footnote">旧码轮换后会立即失效；每张票只能成功核销一次。</p>
    </section>
</template>

<style scoped>
.checkin-page {
    max-width: 680px;
    margin: 0 auto;
}
.checkin-card {
    margin-top: 26px;
    padding: 18px;
    border: 1px solid var(--line);
    border-radius: 14px;
}
.checkin-result {
    display: flex;
    align-items: center;
    gap: 13px;
    margin-top: 22px;
    padding: 15px;
    border-radius: 10px;
    background: #e5f4ea;
    color: #276842;
}
.checkin-result p {
    margin: 4px 0;
    font-size: 12px;
}
.checkin-result small {
    color: #668571;
    font-size: 10px;
}
.success-mark {
    display: grid;
    place-items: center;
    width: 33px;
    height: 33px;
    border-radius: 50%;
    background: #c8e8d1;
    font-size: 17px;
    font-weight: 800;
}
.checkin-footnote {
    margin-top: 14px;
    color: #8b999e;
    font-size: 11px;
}
</style>
