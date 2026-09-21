import { workItemLabelColorValue } from '../projects/workItemLabelColors'

export const chartPalette = ['#579bfc', '#00c875', '#a25ddc', '#fdab3d', '#ff5ac4', '#0086c0', '#e2445c', '#66ccff']
const coloredDimensions = new Set(['STATUS', 'CATEGORY', 'PRIORITY', 'CONTENT'])

export function chartColor(dimension: string, token = '', paletteIndex = 0): string {
  if (token.startsWith('#')) return token
  const fallback = chartPalette[paletteIndex % chartPalette.length]!
  if (!coloredDimensions.has(dimension) || !token) return fallback
  const value = workItemLabelColorValue(token), match = value.match(/var\(([^)]+)\)/)
  return match ? getComputedStyle(document.documentElement).getPropertyValue(match[1]!).trim() || fallback : value
}
