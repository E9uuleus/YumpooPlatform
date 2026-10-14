import { ref } from 'vue'

/** 表格单元格弹层同一时间只打开一个；旧弹层的关闭事件晚于新弹层打开时不得清掉新弹层。 */
export function useWorkItemCellPopover() {
  const openCell = ref<string>()
  function setCellOpen(key: string, visible: boolean): void {
    if (visible) openCell.value = key
    else if (openCell.value === key) openCell.value = undefined
  }
  function closeCell(): void { openCell.value = undefined }
  return { openCell, setCellOpen, closeCell }
}
