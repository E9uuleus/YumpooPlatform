import { shallowRef } from 'vue'
import type { HierarchyParent } from '../../timer/candidateHierarchy'
import type { SubjectKey } from '../../timer/timeCategories'

/** `key` is the work item id or a category key. */
export interface ShelfItem { key: SubjectKey; title: string; itemNo: string | null; projectName: string | null; colorToken: string | null
  completed?: boolean | undefined; parent?: HierarchyParent | null | undefined }

export const SHELF_DRAG_TYPE = 'application/x-yumpoo-work-item'

/** The item being dragged from the shelf; `dataTransfer` cannot be read during `dragover`, so the timeline looks here. */
export const draggedItem = shallowRef<ShelfItem | null>(null)
