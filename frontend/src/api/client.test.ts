import { afterEach, describe, expect, it, vi } from 'vitest'
import { configureApiAuth, request, toApiFailure, userMessage } from './client'

afterEach(() => {
    vi.unstubAllGlobals()
    configureApiAuth({ getToken: () => null, onUnauthorized: () => undefined })
})

describe('API error conversion', () => {
    it('preserves backend diagnostics while providing a readable rate-limit message', () => {
        const failure = toApiFailure(429, {
            code: 'RATE_LIMIT_EXCEEDED',
            message: 'Too many requests',
            requestId: 'req-42',
            timestamp: '2026-10-21T02:00:00Z',
        })

        expect(failure).toMatchObject({
            status: 429,
            code: 'RATE_LIMIT_EXCEEDED',
            requestId: 'req-42',
            timestamp: '2026-10-21T02:00:00Z',
            message: 'Too many requests',
        })
        expect(userMessage(failure)).toContain('稍后')
    })

    it('shows local validation errors rather than replacing them with a network message', () => {
        expect(userMessage(new Error('场次必须在未来开始。'))).toBe('场次必须在未来开始。')
        expect(userMessage({})).toBe('请求未能完成，请检查输入后重试。')
    })

    it('reports a bounded request timeout as an ambiguous request result', async () => {
        const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
            expect(init?.signal).toBeInstanceOf(AbortSignal)
            return Promise.reject(new DOMException('Request timed out', 'TimeoutError'))
        })
        vi.stubGlobal('fetch', fetchMock)

        await expect(
            request('/api/v1/orders', { method: 'POST', body: { skuId: 31 } }),
        ).rejects.toMatchObject({
            status: 0,
            code: 'REQUEST_TIMEOUT',
            message: '请求超时，请查看订单后重试。',
        })
    })

    it('clears only the token that received the unauthorized response', async () => {
        let activeToken = 'new-login-token'
        const expiredTokens: string[] = []
        configureApiAuth({
            getToken: () => activeToken,
            onUnauthorized: (requestToken) => {
                if (requestToken === activeToken) {
                    expiredTokens.push(requestToken)
                    activeToken = ''
                }
            },
        })
        const fetchMock = vi.fn(
            async () =>
                new Response(
                    JSON.stringify({
                        code: 'UNAUTHORIZED',
                        message: 'Invalid token',
                        requestId: 'req-1',
                    }),
                    { status: 401, headers: { 'Content-Type': 'application/json' } },
                ),
        )
        vi.stubGlobal('fetch', fetchMock)

        await expect(request('/api/v1/auth/me')).rejects.toMatchObject({ status: 401 })
        expect(expiredTokens).toEqual(['new-login-token'])
    })

    it('does not clear a newer login when an older request returns 401 late', async () => {
        let activeToken = 'old-token'
        const expiredTokens: string[] = []
        configureApiAuth({
            getToken: () => activeToken,
            onUnauthorized: (requestToken) => {
                if (requestToken === activeToken) {
                    expiredTokens.push(requestToken)
                    activeToken = ''
                }
            },
        })
        let resolveFetch!: (response: Response) => void
        vi.stubGlobal(
            'fetch',
            vi.fn(
                () =>
                    new Promise<Response>((resolve) => {
                        resolveFetch = resolve
                    }),
            ),
        )

        const oldRequest = request('/api/v1/me/orders')
        activeToken = 'new-token'
        resolveFetch(
            new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: 'Expired token' }), {
                status: 401,
                headers: { 'Content-Type': 'application/json' },
            }),
        )

        await expect(oldRequest).rejects.toMatchObject({ status: 401 })
        expect(activeToken).toBe('new-token')
        expect(expiredTokens).toEqual([])
    })
})
