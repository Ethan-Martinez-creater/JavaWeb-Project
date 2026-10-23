<script setup lang="ts">
import { computed } from 'vue'
import { ApiFailure, userMessage } from '../api/client'

const props = defineProps<{ error: unknown }>()
const message = computed(() => userMessage(props.error))
const diagnostics = computed(() => (props.error instanceof ApiFailure ? props.error : null))
</script>

<template>
    <section class="error-notice">
        <el-alert :title="message" type="error" :closable="false" show-icon />
        <details v-if="diagnostics" class="error-details">
            <summary>查看错误诊断</summary>
            <dl>
                <dt>错误代码</dt>
                <dd>{{ diagnostics.code }}</dd>
                <dt>服务消息</dt>
                <dd>{{ diagnostics.message }}</dd>
                <template v-if="diagnostics.requestId">
                    <dt>请求编号</dt>
                    <dd>{{ diagnostics.requestId }}</dd>
                </template>
                <template v-if="diagnostics.timestamp">
                    <dt>服务时间</dt>
                    <dd>{{ diagnostics.timestamp }}</dd>
                </template>
            </dl>
        </details>
    </section>
</template>
