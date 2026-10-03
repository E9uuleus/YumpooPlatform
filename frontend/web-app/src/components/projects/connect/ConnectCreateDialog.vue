<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch, type DefineComponent } from 'vue'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElOption as ElOptionRaw, ElSelect as ElSelectRaw } from 'element-plus'
import type { ConnectColumn, ConnectCreateOptions, WorkItemConnection } from '@yumpoo/api-client'
import type { ApiProblem } from '../../../api/problems'
import InlineProblem from '../../InlineProblem.vue'
import { workItemLabelColorValue } from '../workItemLabelColors'
import type { ConnectSourceItem } from './connectColumnKeys'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'

const props = defineProps<{ open: boolean; column: ConnectColumn; targetProjectId: string; sourceItem: ConnectSourceItem; initialTitle: string }>()
const emit = defineEmits<{ 'update:open': [open: boolean]; created: [connection: WorkItemConnection] }>()
const context = useConnectContext()
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const title = ref(''), contentId = ref(''), loading = ref(false), busy = ref(false)
const options = ref<ConnectCreateOptions>(), problem = ref<ApiProblem>(), input = ref<InstanceType<typeof ElInput>>()
const projectName = computed(() => options.value?.targetProjectName ?? props.column.targets.find(target => target.projectId === props.targetProjectId)?.name ?? '目标项目')
const valid = computed(() => title.value.trim().length > 0 && Array.from(title.value.trim()).length <= 300 && options.value?.categories.some(category => category.id === contentId.value))
let controller: AbortController | undefined, revision = 0
async function loadOptions() {
  controller?.abort(); controller = new AbortController()
  const current = ++revision
  options.value = undefined; contentId.value = ''; problem.value = undefined; loading.value = true
  try {
    const result = await context.createOptions(props.column.id, props.targetProjectId, controller.signal)
    if (current !== revision) return
    options.value = result; contentId.value = result.defaultContentId
  } catch (reason) { if (current === revision) problem.value = await toConnectProblem(reason) }
  finally { if (current === revision) loading.value = false }
}
watch(() => [props.open, props.column.id, props.targetProjectId, props.sourceItem.id], () => {
  revision++; controller?.abort()
  if (props.open) { title.value = props.initialTitle || props.sourceItem.title; void loadOptions() }
}, { immediate: true })
onBeforeUnmount(() => { revision++; controller?.abort() })
function focusTitle() { void nextTick(() => input.value?.focus()) }
function close() { if (!busy.value) emit('update:open', false) }
async function submit() {
  if (!valid.value || busy.value || loading.value) return
  busy.value = true; problem.value = undefined
  const current = revision
  try {
    const connection = await context.createAndLink(props.sourceItem.id, { columnId: props.column.id, targetProjectId: props.targetProjectId, title: title.value.trim(), contentId: contentId.value })
    if (current !== revision) return
    ElMessage.success(`已在「${projectName.value}」中新建并关联`); emit('created', connection); emit('update:open', false)
  } catch (reason) { if (current === revision) problem.value = await toConnectProblem(reason, { operation: 'createAndLink' }) }
  finally { if (current === revision) busy.value = false }
}
</script>

<template>
  <el-dialog
    :model-value="open"
    :title="`在「${projectName}」中新建并关联`"
    width="min(480px, calc(100vw - 32px))"
    align-center
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
    :before-close="close"
    @opened="focusTitle"
  >
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <el-form
      label-position="top"
      :disabled="busy"
      class="connect-create-form"
      @submit.prevent="submit"
    >
      <el-form-item
        label="标题"
        required
      >
        <el-input
          ref="input"
          v-model="title"
          maxlength="300"
          aria-label="工作项标题"
        />
      </el-form-item>
      <el-form-item
        label="工作项类别"
        required
      >
        <el-select
          v-model="contentId"
          :loading="loading"
          :disabled="loading || !options"
          aria-label="工作项类别"
          class="connect-create-form__category"
        >
          <el-option
            v-for="category in options?.categories ?? []"
            :key="category.id"
            :value="category.id"
            :label="category.name"
          >
            <span
              class="connect-create-form__dot"
              :style="{ background: workItemLabelColorValue(category.colorToken) }"
            />{{ category.name }}
          </el-option>
        </el-select>
        <el-button
          v-if="problem && !options"
          text
          @click="loadOptions"
        >
          重新加载类别
        </el-button>
      </el-form-item>
      <p>新工作项状态为「未开始」，不设处理人与优先级，由「{{ projectName }}」的成员继续处理。创建后你可以在卡片中查看进度。</p>
    </el-form>
    <template #footer>
      <el-button
        :disabled="busy"
        @click="close"
      >
        取消
      </el-button><el-button
        type="primary"
        :disabled="!valid || loading"
        :loading="busy"
        @click="submit"
      >
        创建并关联
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.connect-create-form { margin-top: 16px; }
.connect-create-form__category { width: 100%; }
.connect-create-form__dot { display: inline-block; width: 8px; height: 8px; margin-right: 8px; border-radius: var(--yp-radius-pill); }
.connect-create-form p { padding: 12px; margin: 0; border-radius: var(--yp-radius-sm); background: var(--yp-bg-selected); color: var(--yp-text-secondary); font-size: 12px; line-height: 1.7; }
</style>
