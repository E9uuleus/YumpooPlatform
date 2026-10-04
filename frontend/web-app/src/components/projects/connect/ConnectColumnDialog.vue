<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElButton, ElCheckbox, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElTag } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import type { ConnectColumn, ConnectTargetProject } from '@yumpoo/api-client'
import { type ApiProblem } from '../../../api/problems'
import InlineProblem from '../../InlineProblem.vue'
import { builtInColumnNames, connectColumnAutoName } from './connectColumnKeys'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'

const props = defineProps<{ open: boolean; projectId: string; mode: 'create' | 'edit'; column?: ConnectColumn | undefined; existingNames: string[] }>()
const emit = defineEmits<{ 'update:open': [open: boolean]; saved: [column: ConnectColumn] }>()
const context = useConnectContext()
const name = ref(''), query = ref('')
const selected = ref<ConnectTargetProject[]>([]), projects = ref<ConnectTargetProject[]>([])
const nameInput = ref<InstanceType<typeof ElInput>>()
const loading = ref(false), submitting = ref(false), page = ref(0), totalPages = ref(0)
const problem = ref<ApiProblem>()
let timer: ReturnType<typeof setTimeout> | undefined, controller: AbortController | undefined, revision = 0
const nameError = computed(() => {
  const value = name.value.trim()
  if (!value) return '请输入列名称'
  if (Array.from(value).length > 40) return '列名称最多 40 个字符'
  const used = [...builtInColumnNames, ...props.existingNames.filter(existing => props.mode !== 'edit' || existing !== props.column?.name)]
  return used.some(existing => existing.toLocaleLowerCase() === value.toLocaleLowerCase()) ? '列名称已存在，请换一个名称' : ''
})
const valid = computed(() => !nameError.value && selected.value.length > 0 && selected.value.length <= 20)
function cancelSearch() { revision++; controller?.abort(); clearTimeout(timer); loading.value = false }
async function search(append = false) {
  if (!props.open || submitting.value || (append && (loading.value || page.value + 1 >= totalPages.value))) return
  const current = ++revision; controller?.abort(); controller = new AbortController()
  const requestedPage = append ? page.value + 1 : 0
  loading.value = true
  try {
    const result = await context.searchTargets(query.value.trim(), requestedPage, controller.signal)
    if (current !== revision) return
    const items = result.items.filter(project => project.id !== props.projectId)
    projects.value = append ? [...new Map([...projects.value, ...items].map(project => [project.id, project])).values()] : items
    page.value = result.page; totalPages.value = result.totalPages
  } catch (reason) { if (current === revision) problem.value = await toConnectProblem(reason) }
  finally { if (current === revision) loading.value = false }
}
watch(() => [props.open, props.projectId, props.column?.id], () => {
  cancelSearch()
  if (!props.open) return
  name.value = props.mode === 'edit' ? props.column?.name ?? '' : connectColumnAutoName([], props.existingNames)
  query.value = ''; problem.value = undefined; projects.value = []; page.value = 0; totalPages.value = 0
  selected.value = props.mode === 'edit' ? props.column?.targets.map(target => ({ id: target.projectId, name: target.name, code: target.code })) ?? [] : []
  void search()
}, { immediate: true })
watch(query, () => { cancelSearch(); projects.value = []; page.value = 0; totalPages.value = 0; timer = setTimeout(() => { void search() }, 300) })
onBeforeUnmount(cancelSearch)
function choose(project: ConnectTargetProject, checked: boolean) {
  if (submitting.value) return
  if (!checked) selected.value = selected.value.filter(value => value.id !== project.id)
  else if (selected.value.length < 20 && !selected.value.some(value => value.id === project.id)) selected.value = [...selected.value, project]
}
function scroll(event: Event) {
  const element = event.currentTarget as HTMLElement
  if (element.scrollTop + element.clientHeight >= element.scrollHeight - 40) void search(true)
}
function close() { if (!submitting.value) emit('update:open', false) }
function focusName() { void nextTick(() => nameInput.value?.focus()) }
async function submit() {
  if (!valid.value || submitting.value) return
  cancelSearch()
  const current = revision
  submitting.value = true; problem.value = undefined
  try {
    const input = { name: name.value.trim(), targetProjectIds: new Set(selected.value.map(project => project.id)) }
    const column = props.mode === 'edit' && props.column
      ? await context.updateColumn(context.catalog.value?.items.find(column => column.id === props.column?.id) ?? props.column, input)
      : await context.createColumn(input)
    if (current !== revision || !props.open) return
    emit('saved', column); emit('update:open', false)
    ElMessage.success(props.mode === 'edit' ? '连接列已更新' : '已添加连接列')
  } catch (reason) {
    if (props.open && current === revision) problem.value = await toConnectProblem(reason, {
      projectName: id => selected.value.find(project => project.id === id)?.name ?? props.column?.targets.find(target => target.projectId === id)?.name ?? '目标项目',
    })
  } finally { submitting.value = false }
}
</script>

<template>
  <el-dialog
    :model-value="open"
    :title="mode === 'edit' ? '连接列设置' : '添加连接列'"
    width="min(640px, calc(100vw - 32px))"
    align-center
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    :before-close="close"
    @opened="focusName"
  >
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <el-form
      label-position="top"
      :disabled="submitting"
      class="connect-column-form"
      @submit.prevent="submit"
    >
      <el-form-item
        label="列名称"
        required
        :error="nameError"
      >
        <el-input
          ref="nameInput"
          v-model="name"
          maxlength="40"
          aria-label="列名称"
        />
      </el-form-item>
      <el-form-item
        label="可连接的项目（1–20 个）"
        required
      >
        <el-input
          v-model="query"
          :prefix-icon="Search"
          maxlength="80"
          placeholder="搜索项目名称或编码"
          aria-label="搜索项目名称或编码"
          clearable
        />
        <div class="connect-column-form__selected">
          <el-tag
            v-for="project in selected"
            :key="project.id"
            :closable="!submitting"
            @close="choose(project, false)"
          >
            {{ project.name }}
          </el-tag>
          <span v-if="!selected.length">尚未选择</span>
        </div>
        <p
          v-if="selected.length === 20"
          class="connect-column-form__limit"
          role="status"
        >
          最多选择 20 个项目
        </p>
        <div
          class="connect-column-form__projects"
          :aria-busy="loading"
          @scroll.passive="scroll"
        >
          <div
            v-for="project in projects"
            :key="project.id"
            class="connect-column-form__project"
            @click="choose(project, !selected.some(value => value.id === project.id))"
          >
            <el-checkbox
              :model-value="selected.some(value => value.id === project.id)"
              :disabled="submitting || (selected.length >= 20 && !selected.some(value => value.id === project.id))"
              :aria-label="project.name"
              @click.stop
              @change="choose(project, Boolean($event))"
            />
            <span class="connect-column-form__name">{{ project.name }}</span><code>{{ project.code }}</code>
          </div>
          <p
            v-if="loading"
            role="status"
          >
            正在加载项目…
          </p>
          <p
            v-else-if="!projects.length"
            role="status"
          >
            没有匹配的项目
          </p>
          <el-button
            v-if="page + 1 < totalPages"
            text
            :loading="loading"
            @click="search(true)"
          >
            加载更多项目
          </el-button>
        </div>
      </el-form-item>
      <p class="connect-column-form__notice">
        将创建双向连接：所选项目的表格会自动出现以本项目命名的列，两边成员都能查看和维护连接。
      </p>
    </el-form>
    <template #footer>
      <el-button
        :disabled="submitting"
        @click="close"
      >
        取消
      </el-button><el-button
        type="primary"
        :loading="submitting"
        :disabled="!valid"
        @click="submit"
      >
        {{ mode === 'edit' ? '保存' : '添加列' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.connect-column-form { margin-top: 16px; }
.connect-column-form__selected { display: flex; flex-wrap: wrap; width: 100%; min-height: 30px; gap: 6px; padding: 12px 0; color: var(--yp-text-muted); font-size: 12px; }
.connect-column-form__limit { margin: 0 0 8px; color: var(--yp-text-secondary); font-size: 12px; }
.connect-column-form__projects { width: 100%; max-height: 240px; overflow: auto; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-sm); }
.connect-column-form__project { display: flex; min-height: 40px; align-items: center; gap: 10px; padding: 0 12px; border-bottom: 1px solid var(--yp-border-subtle); cursor: pointer; }
.connect-column-form__project:last-of-type { border-bottom: 0; }
.connect-column-form__project:hover { background: var(--yp-bg-hover); }
.connect-column-form__name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--yp-text-primary); }
.connect-column-form__project code { color: var(--yp-text-muted); font-size: 12px; }
.connect-column-form__projects p { padding: 0 12px; color: var(--yp-text-muted); font-size: 13px; }
.connect-column-form__notice { margin: 0; padding: 12px; border-radius: var(--yp-radius-sm); background: var(--yp-bg-selected); color: var(--yp-text-secondary); font-size: 12px; line-height: 1.7; }
</style>
