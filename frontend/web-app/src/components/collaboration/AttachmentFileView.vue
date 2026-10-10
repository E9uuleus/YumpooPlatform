<script setup lang="ts">
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { computed } from 'vue'
import { attachmentExtension, formatAttachmentSize } from './descriptionImages'

const props = defineProps(nodeViewProps)
const name = computed(() => String(props.node.attrs.name))
const size = computed(() => formatAttachmentSize(props.node.attrs.size))
</script>

<template>
  <node-view-wrapper
    class="attachment-file"
    :class="{ 'attachment-file--selected': selected }"
    contenteditable="false"
  >
    <a
      data-type="attachment"
      :href="String(node.attrs.href)"
    >
      <span
        class="attachment-file__icon"
        aria-hidden="true"
      >{{ attachmentExtension(name) }}</span>
      <span class="attachment-file__text">
        <span class="attachment-file__name">{{ name }}</span>
        <span
          v-if="size"
          class="attachment-file__size"
        >{{ size }}</span>
      </span>
    </a>
  </node-view-wrapper>
</template>
