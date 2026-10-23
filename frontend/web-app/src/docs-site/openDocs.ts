import { AuthenticationClientType } from '@yumpoo/api-client'
import type { Router } from 'vue-router'
import { useSession } from '../composables/useSession'

export function inDesktopShell(): boolean {
  return window.yumpooDesktop?.client === 'electron'
    || useSession().authentication.value?.client.type === AuthenticationClientType.Electron
}

/** 浏览器里新开标签页；桌面壳拒绝 window.open，在同一窗口打开，文档顶栏的「进入工作台」可以返回。 */
export function openDocs(router: Router, href: string): void {
  if (inDesktopShell()) void router.push(href)
  else window.open(href, '_blank', 'noopener')
}
