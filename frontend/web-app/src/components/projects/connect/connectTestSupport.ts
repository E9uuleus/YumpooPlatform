import { ref, shallowRef } from 'vue'
import { vi } from 'vitest'
import { connectColumnsContext, type ConnectColumns } from './useConnectColumns'
import { connectColumn, connection, connectionCatalog } from './connectTestFixtures'
import { workItemsApi } from '../../../api/client'

export function connectionTestContext() {
  const context: ConnectColumns = {
    catalog: shallowRef(structuredClone(connectionCatalog)), cells: shallowRef(new Map()), catalogLoading: ref(false),
    catalogError: ref(), cellError: ref(), loadCatalog: vi.fn(async () => {}), ensureCells: vi.fn(async () => {}), refreshCells: vi.fn(async () => {}),
    createColumn: vi.fn(async () => connectColumn), updateColumn: vi.fn(async () => connectColumn), deleteColumn: vi.fn(async () => 3),
    link: vi.fn(async () => connection), createAndLink: vi.fn(async () => connection), unlink: vi.fn(async () => {}), getConnection: vi.fn(async () => connection),
    searchTargets: vi.fn((query, page, signal) => workItemsApi.searchConnectTargetProjects({ query, page, size: 20 }, { signal: signal ?? null })),
    createOptions: vi.fn(async () => ({ targetProjectId: connection.target.projectId, targetProjectName: connection.target.projectName, categories: [connection.target.category], defaultContentId: connection.target.category.id })),
    searchCandidates: vi.fn(async () => ({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })),
    incoming: vi.fn(async () => ({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })),
  }
  return { context, global: { provide: { [connectColumnsContext as symbol]: context } } }
}
