<script setup lang="ts">
import { useEditor } from '@tiptap/vue-3'
import { readCsrfToken, type ProjectMember, type WorkItemUpdate } from '@yumpoo/api-client'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workItemUpdatesApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import { useSession } from '../../composables/useSession'
import DiscussionComposer from './DiscussionComposer.vue'
import { discussionExtensions, discussionHasDraft } from './discussionEditor'
import InlineProblem from '../InlineProblem.vue'
import YpAssignee from '../yp/YpAssignee.vue'
const props = defineProps<{ workItemId: string; parentUpdateId: string; members: ProjectMember[]; canPublish: boolean; draft: string }>()
const emit = defineEmits<{ published: [item: WorkItemUpdate]; draft: [html: string]; busy: [value: boolean] }>()
const session = useSession()
const user = computed(() => session.authentication.value?.user)
const composer = ref<InstanceType<typeof DiscussionComposer>>()
const publishing = ref(false)
const text = ref('')
const problem = ref<ApiProblem>()
let disposed = false
let key = crypto.randomUUID()
let keyBody = props.draft
const editor = useEditor({
  content: props.draft,
  editable: props.canPublish,
  extensions: discussionExtensions(() => props.members, () => composer.value?.closePanel()),
  editorProps: { attributes: { role: 'textbox', 'aria-label': '回复正文', 'aria-multiline': 'true' } },
  onCreate: ({ editor: current }) => { text.value = current.getText() },
  onUpdate: ({ editor: current }) => {
    text.value = current.getText()
    const html = current.getHTML()
    if (html !== keyBody) { key = crypto.randomUUID(); keyBody = html }
    emit('draft', discussionHasDraft(current) ? html : '')
  },
})
watch(() => [props.canPublish, publishing.value], () => editor.value?.setEditable(props.canPublish && !publishing.value))
async function publish() {
  if (!editor.value || !props.canPublish || publishing.value || !text.value.trim()) return
  const csrf = readCsrfToken()
  if (!csrf) { problem.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  publishing.value = true; emit('busy', true); problem.value = undefined
  try {
    const item = await workItemUpdatesApi.publishWorkItemUpdate({
      workItemId: props.workItemId, xXSRFTOKEN: csrf, idempotencyKey: key,
      workItemUpdateCreateRequest: { bodyHtml: editor.value.getHTML(), parentUpdateId: props.parentUpdateId },
    })
    if (disposed) return
    editor.value.commands.clearContent(true)
    composer.value?.reset()
    emit('published', item)
  } catch (reason) { if (!disposed) problem.value = await toApiProblem(reason) }
  finally { if (!disposed) { publishing.value = false; emit('busy', false) } }
}
function focus() { editor.value?.commands.focus() }
function discard() { editor.value?.commands.clearContent(true); composer.value?.reset(); problem.value = undefined }
defineExpose({ focus, discard, editor, publish })
onBeforeUnmount(() => { disposed = true })
</script>
<template>
  <div class="discussion-reply-composer">
    <yp-assignee
      :user-id="user?.id"
      :display-name="user?.displayName ?? '当前用户'"
      :show-name="false"
    />
    <div class="discussion-reply-composer__input">
      <discussion-composer
        ref="composer"
        compact
        :editor="editor"
        :busy="publishing"
        :submit-disabled="!text.trim()"
        placeholder="写下回复，输入 @ 提及项目成员…"
        submit-label="回复"
        @submit="publish"
      />
      <inline-problem
        v-if="problem"
        :problem="problem"
      />
    </div>
  </div>
</template>
<style scoped>
.discussion-reply-composer { display: flex; align-items: flex-start; gap: 12px; }
.discussion-reply-composer > :first-child { margin-top: 8px; flex: 0 0 auto; }
.discussion-reply-composer__input { min-width: 0; flex: 1; }
</style>
