import { inject, type InjectionKey, type Ref } from 'vue'
import type { OperationsAlertSummary, OperationsOverview } from '@yumpoo/api-client'
import type { useOperationsQuery } from '../../composables/useOperationsQuery'
type OperationsContext = ReturnType<typeof useOperationsQuery<OperationsOverview>> & {
  interval: Ref<number>
  refreshVersion: Ref<number>
}
export const operationsContext: InjectionKey<OperationsContext> = Symbol('operations')
export const operationsAlertSummaryContext: InjectionKey<Readonly<Ref<OperationsAlertSummary | undefined>>> = Symbol('operations-alert-summary')
export function useOperations() {
  const context = inject(operationsContext)
  if (!context) throw new Error('OperationsLayout missing')
  return context
}
