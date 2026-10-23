import { defineStore } from 'pinia'
import { authApi } from '../api/resources'
import type { Role, User } from '../api/types'

interface AuthState {
    accessToken: string | null
    user: User | null
}

export const useAuthStore = defineStore('auth', {
    state: (): AuthState => ({ accessToken: null, user: null }),
    getters: {
        isAuthenticated: (state) => state.accessToken !== null && state.user !== null,
        hasRole: (state) => (role: Role) => state.user?.roles.includes(role) ?? false,
    },
    actions: {
        async login(email: string, password: string): Promise<User> {
            const tokenResponse = await authApi.login(email, password)
            this.accessToken = tokenResponse.accessToken
            try {
                const user = await authApi.me()
                this.user = user
                return user
            } catch (error) {
                this.clear()
                throw error
            }
        },

        async register(email: string, password: string): Promise<User> {
            return authApi.register(email, password)
        },

        logout(): void {
            this.clear()
        },

        expireIfCurrent(requestToken: string): void {
            // An older request can finish after a new login; it must not erase that newer session.
            if (this.accessToken === requestToken) this.clear()
        },

        clear(): void {
            this.accessToken = null
            this.user = null
        },
    },
})
