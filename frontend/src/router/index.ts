import { createRouter, createWebHistory } from 'vue-router'
import type { Role } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { decideAccess } from './access'

declare module 'vue-router' {
    interface RouteMeta {
        requiresAuth?: boolean
        roles?: Role[]
    }
}

export const router = createRouter({
    history: createWebHistory(),
    routes: [
        {
            path: '/',
            name: 'events',
            component: () => import('../features/events/EventListPage.vue'),
        },
        {
            path: '/events/:id',
            name: 'event-detail',
            component: () => import('../features/events/EventDetailPage.vue'),
        },
        { path: '/login', name: 'login', component: () => import('../features/auth/AuthPage.vue') },
        {
            path: '/forbidden',
            name: 'forbidden',
            component: () => import('../features/auth/ForbiddenPage.vue'),
        },
        {
            path: '/register',
            name: 'register',
            component: () => import('../features/auth/AuthPage.vue'),
        },
        {
            path: '/me/orders',
            name: 'orders',
            component: () => import('../features/orders/OrdersPage.vue'),
            meta: { roles: ['USER', 'ADMIN'] },
        },
        {
            path: '/me/orders/:id',
            name: 'order-detail',
            component: () => import('../features/orders/OrderDetailPage.vue'),
            meta: { roles: ['USER', 'ADMIN'] },
        },
        {
            path: '/me/tickets',
            name: 'tickets',
            component: () => import('../features/tickets/TicketsPage.vue'),
            meta: { roles: ['USER', 'ADMIN'] },
        },
        {
            path: '/me/notifications',
            name: 'notifications',
            component: () => import('../features/notifications/NotificationsPage.vue'),
            meta: { roles: ['USER', 'ADMIN'] },
        },
        {
            path: '/organizer/events',
            name: 'organizer-events',
            component: () => import('../features/organizer/OrganizerEventsPage.vue'),
            meta: { roles: ['ORGANIZER', 'ADMIN'] },
        },
        {
            path: '/organizer/events/new',
            name: 'organizer-event-new',
            component: () => import('../features/organizer/OrganizerEventEditorPage.vue'),
            meta: { roles: ['ORGANIZER', 'ADMIN'] },
        },
        {
            path: '/organizer/events/:id/edit',
            name: 'organizer-event-edit',
            component: () => import('../features/organizer/OrganizerEventEditorPage.vue'),
            meta: { roles: ['ORGANIZER', 'ADMIN'] },
        },
        {
            path: '/organizer/check-in',
            name: 'organizer-checkin',
            component: () => import('../features/organizer/OrganizerCheckinPage.vue'),
            meta: { roles: ['ORGANIZER', 'ADMIN'] },
        },
        { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
})

router.beforeEach((to) => {
    const auth = useAuthStore()
    const decision = decideAccess(
        to.meta,
        to.fullPath,
        auth.isAuthenticated,
        auth.user?.roles ?? [],
    )
    if (decision.kind === 'login') {
        return { name: 'login', query: { redirect: decision.redirect } }
    }
    if (decision.kind === 'forbidden') return { name: 'forbidden' }
    return true
})
