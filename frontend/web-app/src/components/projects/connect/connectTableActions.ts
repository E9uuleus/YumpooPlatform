import type { InjectionKey, Ref } from 'vue'
import type { ConnectColumn } from '@yumpoo/api-client'

/** Table-level actions that cells deep inside the main and subitem tables can reach without prop drilling. */
export interface ConnectTableActions {
  canManage: Readonly<Ref<boolean>>
  openSettings: (column: ConnectColumn) => void
}
export const connectTableActions: InjectionKey<ConnectTableActions> = Symbol('connectTableActions')
