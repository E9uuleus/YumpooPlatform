/** A work item one window changed, so the other windows of the same origin (the desktop panels, browser tabs) reload it. */
export interface WorkItemChange { projectId: string; workItemId?: string }

const CHANNEL = 'yumpoo-work-items'

/** Tells the other windows; the sender is never notified, and it updates itself. */
export function announceWorkItemChanged(change: WorkItemChange): void {
  if (typeof BroadcastChannel === 'undefined') return
  const channel = new BroadcastChannel(CHANNEL)
  channel.postMessage(change)
  channel.close()
}

export function onWorkItemChanged(listener: (change: WorkItemChange) => void): () => void {
  if (typeof BroadcastChannel === 'undefined') return () => undefined
  const channel = new BroadcastChannel(CHANNEL)
  channel.onmessage = (event: MessageEvent<WorkItemChange>) => { if (event.data?.projectId) listener(event.data) }
  return () => channel.close()
}

/** Reloads one project's view after another window changed its items; a burst of changes becomes a single reload. */
export function onProjectWorkItemsChanged(projectId: () => string, reload: () => void, delay = 300): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined
  const off = onWorkItemChanged(change => {
    if (change.projectId !== projectId()) return
    clearTimeout(timer)
    timer = setTimeout(reload, delay)
  })
  return () => { off(); clearTimeout(timer) }
}
