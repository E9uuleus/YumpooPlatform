import type { DesktopBridge, DesktopMenuBridge } from '@yumpoo/preload-contract'

declare global {
  interface Window {
    yumpooMenu?: DesktopMenuBridge
    readonly yumpooDesktop?: DesktopBridge
  }
}

export {}
