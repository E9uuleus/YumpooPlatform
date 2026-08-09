import type { DesktopBridge } from '@yumpoo/preload-contract'
import { contextBridge } from 'electron'

const desktopBridge: DesktopBridge = Object.freeze({
  client: 'electron',
})

contextBridge.exposeInMainWorld('yumpooDesktop', desktopBridge)
