import { request } from './client'
import type {
    CheckinCode,
    CheckinResult,
    EventDetail,
    EventSummary,
    LoginResponse,
    Notification,
    Order,
    PageResponse,
    Ticket,
    User,
} from './types'

function pageQuery(page: number, size: number): string {
    return new URLSearchParams({ page: String(page), size: String(size) }).toString()
}

export const authApi = {
    register: (email: string, password: string) =>
        request<User>('/api/v1/auth/register', { method: 'POST', body: { email, password } }),
    login: (email: string, password: string) =>
        request<LoginResponse>('/api/v1/auth/login', { method: 'POST', body: { email, password } }),
    me: () => request<User>('/api/v1/auth/me'),
}

export const eventApi = {
    list: (keyword: string, page: number, size = 12) => {
        const query = new URLSearchParams({ page: String(page), size: String(size) })
        if (keyword.trim()) query.set('keyword', keyword.trim())
        return request<PageResponse<EventSummary>>(`/api/v1/events?${query.toString()}`)
    },
    detail: (id: number) => request<EventDetail>(`/api/v1/events/${id}`),
}

export const organizerApi = {
    list: (page = 0, size = 100) =>
        request<PageResponse<EventSummary>>(`/api/v1/organizer/events?${pageQuery(page, size)}`),
    detail: (id: number) => request<EventDetail>(`/api/v1/organizer/events/${id}`),
    create: (title: string, description: string) =>
        request<EventDetail>('/api/v1/organizer/events', {
            method: 'POST',
            body: { title, description },
        }),
    edit: (id: number, title: string, description: string) =>
        request<EventDetail>(`/api/v1/organizer/events/${id}`, {
            method: 'PATCH',
            body: { title, description },
        }),
    addSession: (
        eventId: number,
        session: { title: string; startsAt: string; endsAt: string; location: string },
    ) =>
        request<EventDetail['sessions'][number]>(`/api/v1/organizer/events/${eventId}/sessions`, {
            method: 'POST',
            body: session,
        }),
    addSku: (sessionId: number, sku: { name: string; priceCents: number; totalStock: number }) =>
        request<EventDetail['sessions'][number]['ticketSkus'][number]>(
            `/api/v1/organizer/sessions/${sessionId}/ticket-skus`,
            { method: 'POST', body: sku },
        ),
    publish: (eventId: number) =>
        request<EventDetail>(`/api/v1/organizer/events/${eventId}/publish`, { method: 'POST' }),
    checkin: (checkinCode: string) =>
        request<CheckinResult>('/api/v1/organizer/tickets/check-in', {
            method: 'POST',
            body: { checkinCode },
        }),
}

export const orderApi = {
    create: (skuId: number, idempotencyKey: string) =>
        request<Order>('/api/v1/orders', {
            method: 'POST',
            body: { skuId, idempotencyKey },
        }),
    list: (page = 0, size = 20) =>
        request<PageResponse<Order>>(`/api/v1/orders?${pageQuery(page, size)}`),
    detail: (id: number) => request<Order>(`/api/v1/orders/${id}`),
    pay: (id: number) => request<Order>(`/api/v1/orders/${id}/pay`, { method: 'POST' }),
    cancel: (id: number) => request<Order>(`/api/v1/orders/${id}/cancel`, { method: 'POST' }),
}

export const ticketApi = {
    list: (page = 0, size = 20) =>
        request<PageResponse<Ticket>>(`/api/v1/me/tickets?${pageQuery(page, size)}`),
    rotateCode: (ticketId: number) =>
        request<CheckinCode>(`/api/v1/me/tickets/${ticketId}/checkin-code`, { method: 'POST' }),
}

export const notificationApi = {
    list: (page = 0, size = 20) =>
        request<PageResponse<Notification>>(`/api/v1/me/notifications?${pageQuery(page, size)}`),
}
