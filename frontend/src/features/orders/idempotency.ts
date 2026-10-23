export interface KeyStorage {
    getItem(key: string): string | null
    setItem(key: string, value: string): void
    removeItem(key: string): void
}

const memoryKeys = new Map<string, string>()

export function isReservationStorageAvailable(
    storage: KeyStorage | null = browserSessionStorage(),
): boolean {
    if (!storage) return false
    const probeKey = `eventflow:reservation:probe:${randomReservationKey()}`
    const probeValue = randomReservationKey()
    try {
        storage.setItem(probeKey, probeValue)
        return storage.getItem(probeKey) === probeValue
    } catch {
        return false
    } finally {
        try {
            storage.removeItem(probeKey)
        } catch {
            // The availability check must not fail the booking flow.
        }
    }
}

export function reservationKeyName(userId: number, skuId: number): string {
    return `eventflow:reservation:${userId}:${skuId}`
}

export function getReservationKey(
    userId: number,
    skuId: number,
    storage: KeyStorage | null = browserSessionStorage(),
): string {
    if (
        !Number.isSafeInteger(userId) ||
        userId <= 0 ||
        !Number.isSafeInteger(skuId) ||
        skuId <= 0
    ) {
        throw new Error('A signed-in user and a valid ticket type are required.')
    }
    const storageKey = reservationKeyName(userId, skuId)
    const existing = safeRead(storage, storageKey) ?? memoryKeys.get(storageKey)
    if (existing && /^[A-Za-z0-9_-]{8,64}$/.test(existing)) return existing

    const key = randomReservationKey()
    memoryKeys.set(storageKey, key)
    try {
        storage?.setItem(storageKey, key)
    } catch {
        // The in-memory copy still protects retries until the current page is closed.
    }
    return key
}

export function clearReservationKey(
    userId: number,
    skuId: number,
    storage: KeyStorage | null = browserSessionStorage(),
): void {
    const storageKey = reservationKeyName(userId, skuId)
    memoryKeys.delete(storageKey)
    try {
        storage?.removeItem(storageKey)
    } catch {
        // Storage can be disabled by a browser privacy setting.
    }
}

function safeRead(storage: KeyStorage | null, key: string): string | null {
    try {
        return storage?.getItem(key) ?? null
    } catch {
        return null
    }
}

function randomReservationKey(): string {
    // getRandomValues remains available on local HTTP service names, unlike randomUUID.
    const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
    return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

function browserSessionStorage(): KeyStorage | null {
    try {
        return globalThis.sessionStorage
    } catch {
        return null
    }
}
