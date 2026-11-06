<script setup lang="ts">
defineProps<{ variant: 'description' | 'discussion'; title: string; description: string }>()
</script>

<template>
  <span class="detail-empty">
    <svg
      class="detail-empty__art"
      :class="`detail-empty__art--${variant}`"
      viewBox="0 0 200 132"
      aria-hidden="true"
    >
      <template v-if="variant === 'description'">
        <rect
          class="art-sheet art-sheet--back"
          x="58"
          y="18"
          width="92"
          height="104"
          rx="8"
        />
        <g class="art-float">
          <rect
            class="art-sheet"
            x="44"
            y="10"
            width="96"
            height="112"
            rx="8"
          />
          <rect
            class="art-accent"
            x="56"
            y="24"
            width="44"
            height="8"
            rx="4"
          />
          <rect
            class="art-line art-line--1"
            x="56"
            y="42"
            width="72"
            height="5"
            rx="2.5"
          />
          <rect
            class="art-line art-line--2"
            x="56"
            y="54"
            width="60"
            height="5"
            rx="2.5"
          />
          <rect
            class="art-line art-line--3"
            x="56"
            y="66"
            width="66"
            height="5"
            rx="2.5"
          />
          <rect
            class="art-image"
            x="56"
            y="80"
            width="72"
            height="30"
            rx="5"
          />
          <circle
            class="art-sun"
            cx="68"
            cy="90"
            r="4"
          />
          <path
            class="art-hill"
            d="M58 108l16-14 10 8 12-12 30 18z"
          />
        </g>
        <g class="art-pen">
          <rect
            class="art-pen__body"
            x="-49"
            y="-4"
            width="40"
            height="8"
            rx="2"
          />
          <rect
            class="art-pen__cap"
            x="-49"
            y="-4"
            width="9"
            height="8"
            rx="2"
          />
          <path
            class="art-pen__tip"
            d="M0 0l-9-4v8z"
          />
        </g>
        <path
          class="art-spark art-spark--1"
          d="M164 22l2.5 6 6 2.5-6 2.5-2.5 6-2.5-6-6-2.5 6-2.5z"
        />
        <path
          class="art-spark art-spark--2"
          d="M30 96l1.8 4.2 4.2 1.8-4.2 1.8-1.8 4.2-1.8-4.2-4.2-1.8 4.2-1.8z"
        />
      </template>
      <template v-else>
        <rect
          class="art-sheet art-sheet--back"
          x="40"
          y="40"
          width="128"
          height="76"
          rx="10"
        />
        <g class="art-float">
          <rect
            class="art-sheet"
            x="28"
            y="30"
            width="128"
            height="80"
            rx="10"
          />
          <circle
            class="art-avatar"
            cx="50"
            cy="52"
            r="10"
          />
          <rect
            class="art-line art-line--1"
            x="66"
            y="45"
            width="44"
            height="5"
            rx="2.5"
          />
          <rect
            class="art-line art-line--2"
            x="66"
            y="55"
            width="72"
            height="5"
            rx="2.5"
          />
          <rect
            class="art-line art-line--3"
            x="40"
            y="74"
            width="100"
            height="5"
            rx="2.5"
          />
          <rect
            class="art-line art-line--4"
            x="40"
            y="86"
            width="64"
            height="5"
            rx="2.5"
          />
        </g>
        <g class="art-bubble">
          <path
            class="art-bubble__shape"
            d="M136 8h40a10 10 0 0 1 10 10v16a10 10 0 0 1-10 10h-26l-10 8v-8h-4a10 10 0 0 1-10-10V18a10 10 0 0 1 10-10z"
          />
          <circle
            class="art-dot art-dot--1"
            cx="146"
            cy="26"
            r="3"
          />
          <circle
            class="art-dot art-dot--2"
            cx="156"
            cy="26"
            r="3"
          />
          <circle
            class="art-dot art-dot--3"
            cx="166"
            cy="26"
            r="3"
          />
        </g>
        <g class="art-mention">
          <circle
            class="art-mention__shape"
            cx="20"
            cy="104"
            r="12"
          />
          <text
            class="art-mention__at"
            x="20"
            y="108.5"
            text-anchor="middle"
          >@</text>
        </g>
      </template>
    </svg>
    <strong class="detail-empty__title">{{ title }}</strong>
    <small class="detail-empty__description">{{ description }}</small>
  </span>
</template>

<style scoped>
.detail-empty { display: grid; justify-items: center; gap: 6px; padding: var(--yp-space-6) var(--yp-space-4) var(--yp-space-4); text-align: center; animation: detail-empty-rise 420ms var(--yp-ease-standard) backwards; }
.detail-empty__art { width: 168px; height: 111px; overflow: visible; transition: transform 220ms var(--yp-ease-standard); }
.detail-empty__title { margin-top: var(--yp-space-2); color: var(--yp-text-primary); font-size: 15px; font-weight: 600; }
.detail-empty__description { max-width: 320px; color: var(--yp-text-muted); font-size: 12px; line-height: 1.6; }

.art-sheet { fill: var(--yp-bg-surface); stroke: var(--yp-border-default); stroke-width: 1.5; }
.art-sheet--back { fill: var(--yp-bg-sunken); stroke: var(--yp-border-subtle); }
.art-accent { fill: color-mix(in srgb, var(--yp-link) 45%, var(--yp-bg-surface)); }
.art-line, .art-avatar { fill: var(--yp-skeleton-block-strong); }
.art-image { fill: var(--yp-bg-selected); }
.art-sun, .art-spark { fill: var(--yp-label-egg-yolk); }
.art-hill { fill: color-mix(in srgb, var(--yp-link) 35%, var(--yp-bg-selected)); }
.art-pen__body { fill: var(--yp-link); }
.art-pen__cap { fill: var(--yp-brand-gradient-end); }
.art-pen__tip { fill: var(--yp-text-primary); }
.art-bubble__shape { fill: var(--yp-link); }
.art-dot { fill: var(--yp-on-solid); }
.art-mention__shape { fill: var(--yp-bg-selected); stroke: color-mix(in srgb, var(--yp-link) 40%, transparent); }
.art-mention__at { fill: var(--yp-link); font-family: var(--yp-font-family); font-size: 13px; font-weight: 700; }

/* 入场：文字行依次“写出”；循环：卡片轻浮、笔来回书写、气泡打字、星光闪烁。 */
.art-line { transform-box: fill-box; transform-origin: left center; animation: art-line-draw 600ms var(--yp-ease-standard) backwards; }
.art-line--2 { animation-delay: 120ms; }
.art-line--3 { animation-delay: 240ms; }
.art-line--4 { animation-delay: 360ms; }
.art-float { animation: art-float 3.6s ease-in-out infinite alternate; }
.art-pen { transform: translate(112px, 63px) rotate(140deg); animation: art-pen-write 2.4s ease-in-out infinite; }
.art-bubble { animation: art-float 3s ease-in-out infinite alternate-reverse; }
.art-mention { animation: art-float 4.2s ease-in-out infinite alternate; }
.art-dot { transform-box: fill-box; transform-origin: center; animation: art-dot 1.2s ease-in-out infinite; }
.art-dot--2 { animation-delay: 150ms; }
.art-dot--3 { animation-delay: 300ms; }
.art-spark { transform-box: fill-box; transform-origin: center; animation: art-twinkle 2.8s ease-in-out infinite; }
.art-spark--2 { animation-delay: 1.2s; }

@keyframes detail-empty-rise { from { opacity: 0; transform: translateY(8px); } }
@keyframes art-line-draw { from { transform: scaleX(0); } }
@keyframes art-float { to { transform: translateY(-3px); } }
@keyframes art-pen-write {
  0%, 100% { transform: translate(112px, 63px) rotate(140deg); }
  50% { transform: translate(126px, 61px) rotate(136deg); }
}
@keyframes art-dot { 0%, 60%, 100% { opacity: .55; transform: translateY(0); } 30% { opacity: 1; transform: translateY(-3px); } }
@keyframes art-twinkle { 0%, 100% { opacity: .35; transform: scale(.8); } 50% { opacity: 1; transform: scale(1); } }
@media (prefers-reduced-motion: reduce) {
  .detail-empty, .detail-empty__art * { animation: none; }
}
</style>
