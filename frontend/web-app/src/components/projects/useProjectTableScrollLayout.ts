import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type CSSProperties, type Ref } from 'vue'
import type { ApiProblem } from '../../api/problems'

interface ScrollLayoutOptions {
  tableRef: Ref<{ $el: HTMLElement; doLayout: () => void } | undefined>
  tableSentinel: Ref<HTMLElement | undefined>
  embedded: Readonly<Ref<boolean>>
  detailOpen: Ref<boolean>
  selectedView: Readonly<Ref<'table' | 'kanban'>>
  grouped: Readonly<Ref<boolean>>
  groupingSwitching: Ref<boolean>
  tableLoading: Ref<boolean>
  tableSorting: Ref<boolean>
  error: Ref<ApiProblem | undefined>
  loadingMoreError: Ref<ApiProblem | undefined>
  tableNextCursor: Ref<string | null>
  loadTable: (cursor: string, append: boolean) => Promise<void>
}

export function useProjectTableScrollLayout({
  tableRef, tableSentinel, embedded, detailOpen, selectedView, grouped, groupingSwitching,
  tableLoading, tableSorting, error, loadingMoreError, tableNextCursor, loadTable,
}: ScrollLayoutOptions) {
  const horizontalPageScrollbar = ref<HTMLElement>()
  const verticalPageScrollbar = ref<HTMLElement>()
  const horizontalOverflow = ref(false)
  const horizontalScrollExtent = ref(1)
  const verticalScrollExtent = ref(1)
  const pageScrollbarLeft = ref(0)
  const pageScrollbarTop = ref(0)
  let tableScrollElement: HTMLElement | undefined
  let pageScrollbarSyncQueued = false
  let responsiveTableLayoutFrame: number | undefined
  let projectPageResizeObserver: ResizeObserver | undefined

  const horizontalPageScrollbarStyle = computed<CSSProperties>(() => ({
    left: `${pageScrollbarLeft.value}px`,
    right: 'calc(var(--yp-work-items-drawer-inset, 0px) + 12px)',
  }))
  const verticalPageScrollbarStyle = computed<CSSProperties>(() => ({
    top: `${pageScrollbarTop.value}px`,
    bottom: horizontalOverflow.value ? '12px' : '0px',
    right: 'var(--yp-work-items-drawer-inset, 0px)',
  }))

  function resolveTableScrollElement(): HTMLElement | undefined {
    return tableRef.value?.$el?.querySelector<HTMLElement>('.el-scrollbar__wrap') ?? undefined
  }

  function syncPageScrollbarPositions(): void {
    if (!tableScrollElement) return
    const horizontal = horizontalPageScrollbar.value
    const vertical = verticalPageScrollbar.value
    if (horizontal && Math.abs(horizontal.scrollLeft - tableScrollElement.scrollLeft) > 0.5) {
      horizontal.scrollLeft = tableScrollElement.scrollLeft
    }
    if (vertical && Math.abs(vertical.scrollTop - tableScrollElement.scrollTop) > 0.5) {
      vertical.scrollTop = tableScrollElement.scrollTop
    }
  }

  function syncTableHeaderScrollPosition(): void {
    if (!tableScrollElement) return
    const headerWrapper = tableRef.value?.$el?.querySelector<HTMLElement>('.el-table__header-wrapper')
    if (headerWrapper && Math.abs(headerWrapper.scrollLeft - tableScrollElement.scrollLeft) > 0.5) {
      headerWrapper.scrollLeft = tableScrollElement.scrollLeft
    }
  }

  function onTableScroll(): void {
    syncTableHeaderScrollPosition()
    syncPageScrollbarPositions()
  }

  function bindTableScrollElement(next: HTMLElement | undefined): void {
    if (tableScrollElement === next) return
    tableScrollElement?.removeEventListener('scroll', onTableScroll)
    tableScrollElement = next
    tableScrollElement?.addEventListener('scroll', onTableScroll, { passive: true })
  }

  function syncPageScrollbars(): void {
    bindTableScrollElement(resolveTableScrollElement())
    if (embedded.value) return

    const contextNavigation = document.querySelector<HTMLElement>('.app-shell--workspace .context-navigation')
    const appMain = document.querySelector<HTMLElement>('.app-shell--workspace .app-main')
    const contextRect = contextNavigation?.getBoundingClientRect()
    const appMainRect = appMain?.getBoundingClientRect()
    const contextVisible = Boolean(contextNavigation && contextRect && contextRect.width > 0
      && getComputedStyle(contextNavigation).display !== 'none')
    pageScrollbarLeft.value = Math.max(0, Math.round(contextVisible ? contextRect!.right : (appMainRect?.left ?? 0)))

    pageScrollbarTop.value = Math.max(0, Math.round(tableScrollElement?.getBoundingClientRect().top ?? 0))
    const horizontal = horizontalPageScrollbar.value
    const vertical = verticalPageScrollbar.value
    if (!tableScrollElement || !horizontal || !vertical) return

    const nextHorizontalOverflow = tableScrollElement.scrollWidth - tableScrollElement.clientWidth > 1
    const horizontalOverflowChanged = horizontalOverflow.value !== nextHorizontalOverflow
    horizontalOverflow.value = nextHorizontalOverflow

    horizontalScrollExtent.value = Math.max(
      horizontal.clientWidth,
      horizontal.clientWidth + tableScrollElement.scrollWidth - tableScrollElement.clientWidth,
    )
    verticalScrollExtent.value = Math.max(
      vertical.clientHeight,
      vertical.clientHeight + tableScrollElement.scrollHeight - tableScrollElement.clientHeight,
    )
    void nextTick(syncPageScrollbarPositions)
    if (horizontalOverflowChanged) void nextTick(schedulePageScrollbarSync)
  }

  function schedulePageScrollbarSync(): void {
    if (pageScrollbarSyncQueued) return
    pageScrollbarSyncQueued = true
    void nextTick(() => {
      pageScrollbarSyncQueued = false
      syncPageScrollbars()
      fillTableViewport()
    })
  }

  function fillTableViewport(): void {
    if (selectedView.value !== 'table' || grouped.value || groupingSwitching.value
      || tableLoading.value || tableSorting.value || error.value || loadingMoreError.value || !tableNextCursor.value) return
    const root = resolveTableScrollElement()?.getBoundingClientRect()
    const end = tableSentinel.value?.getBoundingClientRect()
    // 首批数据可能未填满视口，标记持续可见时 IntersectionObserver 不会再次通知。
    if (root && end && root.height > 0 && end.height > 0 && end.bottom >= root.top && end.top <= root.bottom + 320)
      void loadTable(tableNextCursor.value, true)
  }

  function syncResponsiveTableLayout(): void {
    tableRef.value?.doLayout()
    schedulePageScrollbarSync()
  }

  function scheduleResponsiveTableLayout(): void {
    if (responsiveTableLayoutFrame !== undefined) return
    responsiveTableLayoutFrame = window.requestAnimationFrame(() => {
      responsiveTableLayoutFrame = undefined
      syncResponsiveTableLayout()
    })
  }

  function flushResponsiveTableLayout(): void {
    if (responsiveTableLayoutFrame !== undefined) {
      window.cancelAnimationFrame(responsiveTableLayoutFrame)
      responsiveTableLayoutFrame = undefined
    }
    syncResponsiveTableLayout()
  }

  function observeProjectPageResizeTargets(): void {
    projectPageResizeObserver?.disconnect()
    if (typeof ResizeObserver === 'undefined') return
    projectPageResizeObserver = new ResizeObserver(scheduleResponsiveTableLayout)
    const appMain = document.querySelector<HTMLElement>('.app-shell--workspace .app-main')
    if (appMain) projectPageResizeObserver.observe(appMain)
    if (tableRef.value?.$el) projectPageResizeObserver.observe(tableRef.value.$el)
    if (horizontalPageScrollbar.value) projectPageResizeObserver.observe(horizontalPageScrollbar.value)
    if (verticalPageScrollbar.value) projectPageResizeObserver.observe(verticalPageScrollbar.value)
  }

  function onHorizontalPageScroll(): void {
    if (!tableScrollElement || !horizontalPageScrollbar.value) return
    if (Math.abs(tableScrollElement.scrollLeft - horizontalPageScrollbar.value.scrollLeft) > 0.5) {
      tableScrollElement.scrollLeft = horizontalPageScrollbar.value.scrollLeft
    }
    syncTableHeaderScrollPosition()
  }

  function onVerticalPageScroll(): void {
    if (!tableScrollElement || !verticalPageScrollbar.value) return
    if (Math.abs(tableScrollElement.scrollTop - verticalPageScrollbar.value.scrollTop) > 0.5) {
      tableScrollElement.scrollTop = verticalPageScrollbar.value.scrollTop
    }
  }

  watch(detailOpen, scheduleResponsiveTableLayout, { flush: 'post' })
  watch(tableRef, () => {
    observeProjectPageResizeTargets()
    scheduleResponsiveTableLayout()
  }, { flush: 'post' })

  onMounted(() => {
    if (!embedded.value) document.body.classList.add('yp-project-overview-scroll')
    scheduleResponsiveTableLayout()
    observeProjectPageResizeTargets()
    window.addEventListener('resize', scheduleResponsiveTableLayout)
  })
  onBeforeUnmount(() => {
    if (responsiveTableLayoutFrame !== undefined) window.cancelAnimationFrame(responsiveTableLayoutFrame)
    projectPageResizeObserver?.disconnect()
    window.removeEventListener('resize', scheduleResponsiveTableLayout)
    bindTableScrollElement(undefined)
    if (!embedded.value) document.body.classList.remove('yp-project-overview-scroll')
  })

  return {
    horizontalPageScrollbar, verticalPageScrollbar, horizontalOverflow, horizontalScrollExtent, verticalScrollExtent,
    pageScrollbarLeft, horizontalPageScrollbarStyle, verticalPageScrollbarStyle, onHorizontalPageScroll, onVerticalPageScroll,
    resolveTableScrollElement, syncPageScrollbars, schedulePageScrollbarSync, scheduleResponsiveTableLayout, flushResponsiveTableLayout,
  }
}
