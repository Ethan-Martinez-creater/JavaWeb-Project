import type { ApiErrorPayload } from './types'

export class ApiFailure extends Error {
    constructor(
        readonly status: number,
        readonly code: string,
        message: string,
        readonly requestId: string | null = null,
        readonly timestamp: string | null = null,
    ) {
        super(message)
        this.name = 'ApiFailure'
    }
}

type ApiAuthHooks = {
    getToken: () => string | null
    onUnauthorized: (requestToken: string) => void
}

let authHooks: ApiAuthHooks = {
    getToken: () => null,
    onUnauthorized: () => undefined,
}

export function configureApiAuth(hooks: ApiAuthHooks): void {
    authHooks = hooks
}

export function toApiFailure(status: number, payload: unknown): ApiFailure {
    const body = isApiErrorPayload(payload) ? payload : {}
    const fallback = status === 429 ? '请求过于频繁，请稍后再试。' : '请求未能完成，请重试。'
    return new ApiFailure(
        status,
        typeof body.code === 'string' ? body.code : `HTTP_${status}`,
        typeof body.message === 'string' ? body.message : fallback,
        typeof body.requestId === 'string' ? body.requestId : null,
        typeof body.timestamp === 'string' ? body.timestamp : null,
    )
}

export function userMessage(error: unknown): string {
    if (!(error instanceof ApiFailure)) {
        if (error instanceof Error && error.message) return error.message
        return '请求未能完成，请检查输入后重试。'
    }
    if (error.status === 401) return '登录已过期，请重新登录后继续。'
    if (error.status === 403) return '当前账号没有执行此操作的权限。'
    if (error.status === 429) return '操作太频繁，请稍后再试。'
    if (error.status === 409 && /stock|sold|inventory/i.test(error.code)) {
        return '该票种已售罄或库存刚刚发生变化，请刷新活动详情。'
    }
    if (error.code === 'ORDER_NOT_PAYABLE') return '订单已过期或已关闭，无法支付。'
    if (error.code === 'NOT_FOUND') return '记录不存在或已不属于当前账号。'
    return error.message
}

export interface RequestOptions {
    method?: 'GET' | 'POST' | 'PATCH'
    body?: unknown
    signal?: AbortSignal
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const token = authHooks.getToken()
    const headers = new Headers({ Accept: 'application/json' })
    if (options.body !== undefined) headers.set('Content-Type', 'application/json')
    if (token) headers.set('Authorization', `Bearer ${token}`)

    let response: Response
    try {
        response = await fetch(path, {
            method: options.method ?? 'GET',
            headers,
            body: options.body === undefined ? undefined : JSON.stringify(options.body),
            signal: options.signal
                ? AbortSignal.any([options.signal, AbortSignal.timeout(15_000)])
                : AbortSignal.timeout(15_000),
            credentials: 'same-origin',
        })
    } catch (error) {
        if (hasErrorName(error, 'TimeoutError')) {
            throw new ApiFailure(0, 'REQUEST_TIMEOUT', '请求超时，请查看订单后重试。')
        }
        if (hasErrorName(error, 'AbortError')) {
            throw new ApiFailure(0, 'REQUEST_ABORTED', '请求已取消。')
        }
        throw new ApiFailure(0, 'NETWORK_ERROR', '无法连接服务器，请检查网络后重试。')
    }

    if (!response.ok) {
        let payload: unknown
        try {
            payload = await response.json()
        } catch {
            payload = undefined
        }
        const failure = toApiFailure(response.status, payload)
        if (response.status === 401 && token) authHooks.onUnauthorized(token)
        throw failure
    }

    if (response.status === 204) return undefined as T
    const text = await response.text()
    return (text.length === 0 ? undefined : JSON.parse(text)) as T
}

function isApiErrorPayload(value: unknown): value is ApiErrorPayload {
    return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasErrorName(error: unknown, name: string): boolean {
    return typeof error === 'object' && error !== null && 'name' in error && error.name === name
}
