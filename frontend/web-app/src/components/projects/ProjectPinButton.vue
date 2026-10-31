<script setup lang="ts">
defineProps<{ pinned: boolean; name: string }>()
const emit = defineEmits<{ toggle: [] }>()
</script>

<template>
  <button
    type="button"
    class="project-pin-button"
    :class="{ 'project-pin-button--active': pinned }"
    :aria-label="pinned ? `取消置顶项目 ${name}` : `置顶项目 ${name}`"
    :aria-pressed="pinned"
    @click.stop="emit('toggle')"
  >
    <svg
      width="16"
      height="16"
      viewBox="0 0 20 20"
      aria-hidden="true"
    >
      <path
        d="M10 2.8l1.91 4.97 5.32.28-4.14 3.35 1.38 5.15L10 13.65l-4.47 2.9 1.38-5.15-4.14-3.35 5.32-.28L10 2.8Z"
        :fill="pinned ? 'currentColor' : 'none'"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linejoin="round"
      />
    </svg>
  </button>
</template>

<style scoped>
.project-pin-button {
  display: grid;
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-muted);
  background: transparent;
  cursor: pointer;
  transition: color var(--yp-motion-fast) var(--yp-ease-standard), background var(--yp-motion-fast) var(--yp-ease-standard);
}

.project-pin-button:hover {
  color: var(--yp-text-primary);
  background: color-mix(in srgb, var(--yp-text-primary) 8%, transparent);
}

.project-pin-button--active,
.project-pin-button--active:hover {
  color: var(--yp-status-yellow);
}

.project-pin-button:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 1px;
}
</style>
