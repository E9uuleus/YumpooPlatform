<script setup lang="ts">
defineProps<{ type: string }>()
</script>
<template>
  <svg
    viewBox="0 0 48 40"
    fill="none"
    aria-hidden="true"
  >
    <text
      v-if="type === 'NUMBER'"
      x="24"
      y="28"
      text-anchor="middle"
      fill="currentColor"
      font-size="23"
      font-weight="600"
    >123</text>
    <g
      v-else-if="type === 'COLUMN'"
      fill="currentColor"
    ><rect
      x="7"
      y="21"
      width="8"
      height="13"
      rx="2"
      opacity=".5"
    /><rect
      x="20"
      y="8"
      width="8"
      height="26"
      rx="2"
    /><rect
      x="33"
      y="15"
      width="8"
      height="19"
      rx="2"
      opacity=".7"
    /></g>
    <g
      v-else-if="type === 'BAR'"
      fill="currentColor"
    ><rect
      x="7"
      y="7"
      width="20"
      height="7"
      rx="2"
      opacity=".5"
    /><rect
      x="7"
      y="17"
      width="34"
      height="7"
      rx="2"
    /><rect
      x="7"
      y="27"
      width="27"
      height="7"
      rx="2"
      opacity=".7"
    /></g>
    <g v-else-if="type === 'LINE' || type === 'AREA'"><path
      v-if="type === 'AREA'"
      d="M5 28L17 16L29 22L43 7V34H5Z"
      fill="currentColor"
      opacity=".2"
    /><path
      d="M5 28L17 16L29 22L43 7"
      stroke="currentColor"
      stroke-width="3"
      stroke-linecap="round"
      stroke-linejoin="round"
    /></g>
    <g
      v-else-if="type === 'BUBBLE'"
      fill="currentColor"
    ><circle
      cx="13"
      cy="27"
      r="7"
      opacity=".5"
    /><circle
      cx="29"
      cy="14"
      r="10"
      opacity=".85"
    /><circle
      cx="38"
      cy="29"
      r="5"
    /></g>
    <g
      v-else-if="type === 'DONUT'"
      stroke="currentColor"
      stroke-width="8"
    ><circle
      cx="24"
      cy="20"
      r="13"
      opacity=".3"
    /><path d="M24 7a13 13 0 0 1 13 13" /></g>
    <g
      v-else
      fill="currentColor"
    ><circle
      cx="23"
      cy="21"
      r="15"
      opacity=".35"
    /><path d="M26 3a16 16 0 0 1 16 16H26Z" /></g>
  </svg>
</template>
