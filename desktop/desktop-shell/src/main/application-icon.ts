import path from 'node:path'
import { nativeImage } from 'electron'

export function applicationIcon() {
  const icon = nativeImage.createFromPath(path.join(__dirname, '../../assets/application.png'))
  if (icon.isEmpty()) throw new Error('应用图标资源缺失或损坏')
  return icon
}
