export type Role = 'USER' | 'ORGANIZER' | 'ADMIN'

export interface User {
    id: number
    email: string
    roles: Role[]
}

export interface LoginResponse {
    accessToken: string
    tokenType: 'Bearer'
    expiresInSeconds: number
}

export interface PageResponse<T> {
    items: T[]
    page: number
    size: number
    total: number
}

export interface EventSummary {
    id: number
    organizerId: number
    title: string
    description: string
    status: 'DRAFT' | 'PUBLISHED'
    createdAt: string
    updatedAt: string
}

export interface TicketSku {
    id: number
    sessionId: number
    name: string
    priceCents: number
    totalStock: number
    availableStock: number
}

export interface EventSession {
    id: number
    eventId: number
    title: string
    startsAt: string
    endsAt: string
    location: string
    ticketSkus: TicketSku[]
}

export interface EventDetail extends EventSummary {
    sessions: EventSession[]
}

export interface OrderItem {
    id: number
    skuId: number
    priceCents: number
    quantity: number
}

export interface Ticket {
    id: number
    orderItemId: number
    orderId: number
    eventId: number
    sessionId: number
    skuId: number
    status: 'UNUSED' | 'USED'
    usedAt: string | null
    createdAt: string
}

export interface Order {
    id: number
    orderNo: string
    status: 'PENDING_PAYMENT' | 'PAID' | 'CLOSED'
    totalPriceCents: number
    expiresAt: string
    paidAt: string | null
    closedAt: string | null
    createdAt: string
    item: OrderItem
    ticket: Ticket | null
}

export interface Notification {
    id: number
    orderId: number
    eventType: 'ORDER_PAID' | 'ORDER_CLOSED' | string
    message: string
    createdAt: string
}

export interface CheckinCode {
    ticketId: number
    checkinCode: string
}

export interface CheckinResult {
    ticketId: number
    status: string
    usedAt: string
}

export interface ApiErrorPayload {
    code?: unknown
    message?: unknown
    requestId?: unknown
    timestamp?: unknown
}
