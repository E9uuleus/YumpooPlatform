import { nativeImage } from 'electron'

export function applicationIcon() {
  const size = 32
  const pixels = Buffer.alloc(size * size * 4)
  const distance = (x: number, y: number, ax: number, ay: number, bx: number, by: number) => {
    const t = Math.max(0, Math.min(1, ((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / ((bx - ax) ** 2 + (by - ay) ** 2)))
    return Math.hypot(x - ax - t * (bx - ax), y - ay - t * (by - ay))
  }
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const rounded = Math.hypot(Math.max(Math.abs(x - 15.5) - 9.5, 0), Math.max(Math.abs(y - 15.5) - 9.5, 0))
    const alpha = Math.max(0, Math.min(1, 6 - rounded))
    const stroke = Math.min(distance(x, y, 9, 8, 16, 17), distance(x, y, 23, 8, 16, 17), distance(x, y, 16, 17, 16, 25))
    const white = Math.max(0, Math.min(1, 2.1 - stroke))
    const i = (y * size + x) * 4
    pixels[i] = 234 + 21 * white
    pixels[i + 1] = 115 + 140 * white
    pixels[i + 2] = 255 * white
    pixels[i + 3] = 255 * alpha
  }
  return nativeImage.createFromBitmap(pixels, { width: size, height: size, scaleFactor: 1 })
}
