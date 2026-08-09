import type { DesktopBridge } from '@yumpoo/preload-contract'

declare global {
  interface Window {
    readonly yumpooDesktop?: DesktopBridge
  }
}

export {}
