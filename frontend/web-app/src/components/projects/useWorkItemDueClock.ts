import { onBeforeUnmount, onMounted, readonly, ref } from 'vue'

const now = ref(new Date())
let users = 0
let timer: ReturnType<typeof setTimeout> | undefined

function refresh(): void {
  if (timer !== undefined) clearTimeout(timer)
  now.value = new Date()
  timer = setTimeout(refresh, 60_000 - now.value.getTime() % 60_000)
}

export function useWorkItemDueClock() {
  onMounted(() => {
    if (users++ !== 0) return
    refresh()
    document.addEventListener('visibilitychange', refresh)
    window.addEventListener('focus', refresh)
  })
  onBeforeUnmount(() => {
    if (--users !== 0) return
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
    document.removeEventListener('visibilitychange', refresh)
    window.removeEventListener('focus', refresh)
  })
  return readonly(now)
}
