export function isWorkItemViewControl(target: EventTarget | null): boolean {
  return target instanceof Element && Boolean(target.closest(
    '[data-work-item-view-control], .work-item-view-control, .work-item-grouping-options, .monday-sortable-column-header',
  ))
}
