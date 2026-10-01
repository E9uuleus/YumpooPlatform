/** Deterministic PRNG (mulberry32) so decorative layouts look the same on every visit and in static frames. */
export function createRandom(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let value = Math.imul(state ^ (state >>> 15), 1 | state)
    value = (value + Math.imul(value ^ (value >>> 7), 61 | value)) ^ value
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296
  }
}

/** Smooth, slowly drifting direction field (radians) used to move particles without visible repetition. */
export function flowAngle(x: number, y: number, time: number): number {
  const primary = Math.sin(x * 0.011 + time * 0.00022) * Math.cos(y * 0.013 - time * 0.00017)
  const detail = Math.sin((x + y) * 0.007 + time * 0.0003) * 0.5
  return (primary + detail) * Math.PI
}
