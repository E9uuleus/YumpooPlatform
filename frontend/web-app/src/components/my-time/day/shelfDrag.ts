import { shallowRef } from 'vue'

export interface ShelfItem { workItemId: string; title: string; itemNo: string | null; projectName: string | null; colorToken: string | null }

export const SHELF_DRAG_TYPE = 'application/x-yumpoo-work-item'

/** The item being dragged from the shelf; `dataTransfer` cannot be read during `dragover`, so the timeline looks here. */
export const draggedItem = shallowRef<ShelfItem | null>(null)
