import type { Role } from '../api/types'

export interface RouteAccess {
    requiresAuth?: boolean
    roles?: readonly Role[]
}

export type AccessDecision =
    { kind: 'allow' } | { kind: 'login'; redirect: string } | { kind: 'forbidden' }

export function decideAccess(
    route: RouteAccess,
    destination: string,
    authenticated: boolean,
    roles: readonly Role[],
): AccessDecision {
    if ((route.requiresAuth || route.roles?.length) && !authenticated) {
        return { kind: 'login', redirect: destination }
    }
    if (route.roles?.length && !route.roles.some((role) => roles.includes(role))) {
        return { kind: 'forbidden' }
    }
    return { kind: 'allow' }
}
