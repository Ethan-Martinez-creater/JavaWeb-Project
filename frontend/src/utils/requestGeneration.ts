/** Tracks which async request belongs to the currently visible view. */
export function createRequestGeneration() {
    let latestGeneration = 0

    return {
        begin(): number {
            latestGeneration += 1
            return latestGeneration
        },
        isCurrent(generation: number): boolean {
            return generation === latestGeneration
        },
    }
}
