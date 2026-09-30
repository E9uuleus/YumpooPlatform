export type Rgb = readonly [number, number, number]

const channel = (value: number) => Math.max(0, Math.min(255, Math.round(value)))

/** Parses the color formats getComputedStyle returns (hex, rgb()/rgba(), color(srgb …)). */
export function parseColor(value: string): Rgb | null {
  const color = value.trim().toLowerCase()
  const hex = /^#([\da-f]{3,8})$/u.exec(color)?.[1]
  if (hex && [3, 4, 6, 8].includes(hex.length)) {
    const full = hex.length <= 4 ? [...hex].map(digit => digit + digit).join('') : hex
    return [0, 2, 4].map(offset => Number.parseInt(full.slice(offset, offset + 2), 16)) as unknown as Rgb
  }
  const numbers = (color.match(/-?[\d.]+/gu) ?? []).map(Number)
  if (color.startsWith('rgb') && numbers.length >= 3) return [channel(numbers[0]!), channel(numbers[1]!), channel(numbers[2]!)]
  if (color.startsWith('color(srgb') && numbers.length >= 3) {
    return [channel(numbers[0]! * 255), channel(numbers[1]! * 255), channel(numbers[2]! * 255)]
  }
  return null
}

export function rgba([red, green, blue]: Rgb, alpha: number): string {
  return `rgba(${red}, ${green}, ${blue}, ${Math.max(0, Math.min(1, alpha)).toFixed(3)})`
}

/**
 * Resolves color tokens as the browser sees them inside `scope`, so var() chains and color-mix() arrive as plain RGB.
 * Unresolvable tokens (for example in tests) fall back to `fallback`.
 */
export function readColorTokens<K extends string>(tokens: Record<K, string>, fallback: Rgb, scope?: Element | null): Record<K, Rgb> {
  const host = scope ?? document.documentElement
  const probe = document.createElement('span')
  probe.style.display = 'none'
  host.appendChild(probe)
  const colors = {} as Record<K, Rgb>
  for (const key of Object.keys(tokens) as K[]) {
    probe.style.color = `var(${tokens[key]})`
    colors[key] = parseColor(getComputedStyle(probe).color) ?? fallback
  }
  probe.remove()
  return colors
}

/** Calls `onChange` whenever the resolved theme on <html> changes. */
export function watchTheme(onChange: () => void): () => void {
  if (typeof MutationObserver === 'undefined') return () => undefined
  const observer = new MutationObserver(onChange)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme'] })
  return () => observer.disconnect()
}
