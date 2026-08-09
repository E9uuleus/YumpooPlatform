interface DesktopBridge {
  readonly client: 'electron'
}
interface Window {
  readonly yumpooDesktop?: DesktopBridge
}
