import { describe, expect, it } from 'vitest'
import { createRequestGeneration } from './requestGeneration'

function deferred<Value>() {
    let resolve!: (value: Value) => void
    const promise = new Promise<Value>((complete) => {
        resolve = complete
    })
    return { promise, resolve }
}

describe('request generations', () => {
    it('ignores an older detail response that finishes after the current route response', async () => {
        const requests = createRequestGeneration()
        const olderResponse = deferred<string>()
        const currentResponse = deferred<string>()
        const visibleResults: string[] = []

        const applyWhenCurrent = async (generation: number, response: Promise<string>) => {
            const result = await response
            if (requests.isCurrent(generation)) visibleResults.push(result)
        }

        const olderRequest = applyWhenCurrent(requests.begin(), olderResponse.promise)
        const currentRequest = applyWhenCurrent(requests.begin(), currentResponse.promise)
        currentResponse.resolve('event B')
        await currentRequest
        olderResponse.resolve('event A')
        await olderRequest

        expect(visibleResults).toEqual(['event B'])
    })
})
