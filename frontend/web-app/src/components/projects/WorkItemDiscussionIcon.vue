<script setup lang="ts">
defineProps<{ count?: number | undefined }>()
</script>

<template>
  <span
    class="work-item-discussion-icon"
    :class="{ 'work-item-discussion-icon--active': (count ?? 0) > 0 }"
    aria-hidden="true"
  >
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
      <path
        d="M12 21C16.9706 21 21 16.9706 21 12C21 7.02944 16.9706 3 12 3C7.02944 3 3 7.02944 3 12C3 13.8214 3.54139 15.5165 4.4741 16.9366L3.25 21L7.54583 19.8665C8.89531 20.5902 10.4079 21 12 21Z"
        stroke="currentColor"
        stroke-width="1.7"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
    <span v-if="(count ?? 0) > 0" class="work-item-discussion-icon__count">{{ count! > 99 ? '99+' : count }}</span>
  </span>
</template>

<style scoped>
.work-item-discussion-icon {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  color: var(--yp-text-muted);
  vertical-align: middle;
}
.work-item-discussion-icon--active { color: var(--yp-action-primary); }
.work-item-discussion-icon__count {
  position: absolute;
  right: -1px;
  bottom: -1px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 3px;
  box-sizing: border-box;
  border-radius: 999px;
  background: var(--yp-action-primary);
  color: var(--yp-text-inverse);
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
}
</style>
