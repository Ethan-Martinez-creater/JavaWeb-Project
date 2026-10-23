import { afterEach, describe, expect, it, vi } from 'vitest'
import {
    clearReservationKey,
    getReservationKey,
    isReservationStorageAvailable,
    reservationKeyName,
} from './idempotency'

afterEach(() => vi.unstubAllGlobals())

function memoryStorage() {
    const values = new Map<string, string>()
    return {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
    }
}

describe('reservation idempotency keys', () => {
    it('supports HTTP browser contexts that do not expose randomUUID', () => {
        vi.stubGlobal('crypto', {
            getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto),
        })
        const storage = memoryStorage()
        expect(isReservationStorageAvailable(storage)).toBe(true)
        const key = getReservationKey(19, 37, storage)
        expect(key).toMatch(/^[a-f0-9]{32}$/)
        expect(getReservationKey(19, 37, storage)).toBe(key)
        expect(getReservationKey(19, 38, storage)).not.toBe(key)
    })

    it('detects when session storage cannot retain a key', () => {
        expect(isReservationStorageAvailable(memoryStorage())).toBe(true)
        expect(
            isReservationStorageAvailable({
                getItem: () => null,
                setItem: () => {
                    throw new Error('storage blocked')
                },
                removeItem: () => undefined,
            }),
        ).toBe(false)
    })

    it('reuses a key after an ambiguous retry and separates different ticket types', () => {
        const storage = memoryStorage()
        const first = getReservationKey(17, 31, storage)

        expect(getReservationKey(17, 31, storage)).toBe(first)
        expect(getReservationKey(17, 32, storage)).not.toBe(first)
        expect(first).toMatch(/^[A-Za-z0-9_-]{8,64}$/)
        expect(reservationKeyName(17, 31)).toBe('eventflow:reservation:17:31')
    })

    it('removes a key only after a reservation response succeeds', () => {
        const storage = memoryStorage()
        const first = getReservationKey(17, 31, storage)
        clearReservationKey(17, 31, storage)

        expect(getReservationKey(17, 31, storage)).not.toBe(first)
    })
})
