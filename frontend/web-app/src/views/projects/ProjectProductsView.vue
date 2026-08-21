<script setup lang="ts">
import {
  ProjectProductRelationType,
  readCsrfToken,
  type ProjectDetail,
  type ProjectProductCandidate,
  type ProjectProductLink,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElCheckbox,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption as ElOptionRaw,
  ElSelect as ElSelectRaw,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { computed, onMounted, ref, type DefineComponent } from 'vue'
import { useRoute } from 'vue-router'
import { projectsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import { businessLabel } from '../../design-system/labels'
import ProjectWorkspaceHeader from './ProjectWorkspaceHeader.vue'

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const route = useRoute()
const projectId = String(route.params.projectId)
const project = ref<ProjectDetail>()
const links = ref<ProjectProductLink[]>([])
const candidates = ref<ProjectProductCandidate[]>([])
const query = ref('')
const selectedProductId = ref('')
const relationType = ref(ProjectProductRelationType.Development)
const createAsPrimary = ref(false)
const loading = ref(false)
const changing = ref<string>()
const error = ref<ApiProblem>()
const canManage = computed(() => project.value?.capabilities.canManageProductLinks === true)
const currentPrimary = computed(() => links.value.find(link => link.isPrimary))
const selectedCandidate = computed(() => candidates.value.find(item => item.id === selectedProductId.value))
const relationTypes = [
  ProjectProductRelationType.Development,
  ProjectProductRelationType.Delivery,
  ProjectProductRelationType.Support,
  ProjectProductRelationType.UsedBy,
]

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    const [detail, relationList] = await Promise.all([
      projectsApi.getProject({ projectId }),
      projectsApi.listProjectProducts({ projectId }),
    ])
    project.value = detail
    links.value = relationList.items
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function searchCandidates(): Promise<void> {
  const normalized = query.value.trim()
  if (!normalized) {
    candidates.value = []
    selectedProductId.value = ''
    return
  }
  try {
    candidates.value = (await projectsApi.listProjectProductCandidates({
      projectId,
      query: normalized,
      page: 0,
      size: 20,
    })).items
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

function duplicateSelectedType(): boolean {
  return selectedCandidate.value?.activeRelationTypes.has(relationType.value) === true
}

async function unsetPrimary(link: ProjectProductLink, csrf: string): Promise<void> {
  await projectsApi.updateProjectProductLink({
    projectId,
    linkId: link.id,
    xXSRFTOKEN: csrf,
    ifMatch: link.etag,
    projectProductLinkUpdateRequest: { isPrimary: false },
  })
}

async function add(): Promise<void> {
  const csrf = readCsrfToken()
  if (!csrf || !selectedProductId.value) {
    error.value = localProblem(csrf ? '请选择 Product。' : '缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  if (duplicateSelectedType()) {
    error.value = localProblem('该 Product 已存在相同关系类型。')
    return
  }
  changing.value = selectedProductId.value
  error.value = undefined
  try {
    if (createAsPrimary.value && currentPrimary.value) await unsetPrimary(currentPrimary.value, csrf)
    await projectsApi.createProjectProductLink({
      projectId,
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      projectProductLinkCreateRequest: {
        productId: selectedProductId.value,
        relationType: relationType.value,
        isPrimary: createAsPrimary.value,
      },
    })
    ElMessage.success('Product 关系已建立')
    selectedProductId.value = ''
    candidates.value = []
    query.value = ''
    createAsPrimary.value = false
    await load()
  } catch (reason) {
    error.value = await toApiProblem(reason)
    if (isProblemStatus(error.value, 409) || isProblemStatus(error.value, 412)) await load()
  } finally {
    changing.value = undefined
  }
}

async function makePrimary(link: ProjectProductLink): Promise<void> {
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  changing.value = link.id
  error.value = undefined
  try {
    if (currentPrimary.value && currentPrimary.value.id !== link.id) {
      await unsetPrimary(currentPrimary.value, csrf)
    }
    await projectsApi.updateProjectProductLink({
      projectId,
      linkId: link.id,
      xXSRFTOKEN: csrf,
      ifMatch: link.etag,
      projectProductLinkUpdateRequest: { isPrimary: true },
    })
    ElMessage.success('主 Product 已更新')
    await load()
  } catch (reason) {
    error.value = await toApiProblem(reason)
    if (isProblemStatus(error.value, 409) || isProblemStatus(error.value, 412)) await load()
  } finally {
    changing.value = undefined
  }
}

async function remove(link: ProjectProductLink): Promise<void> {
  let reason: string | null
  try {
    const response = await ElMessageBox.prompt(
      `确认移除「${link.productName}」的 ${businessLabel(link.relationType)} 关系？理由可选。`,
      '移除关联',
      {
        confirmButtonText: '移除',
        cancelButtonText: '取消',
        inputPlaceholder: '可选理由（最多 500 字）',
        inputPattern: /^(?:|(?=\s*\S)[\s\S]{1,500})$/,
        inputErrorMessage: '理由不得超过 500 字',
        type: 'warning',
      },
    )
    reason = response.value.trim() || null
  } catch {
    return
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
    return
  }
  changing.value = link.id
  try {
    await projectsApi.removeProjectProductLink({
      projectId,
      linkId: link.id,
      xXSRFTOKEN: csrf,
      ifMatch: link.etag,
      idempotencyKey: crypto.randomUUID(),
      projectProductLinkRemoveRequest: { reason },
    })
    ElMessage.success('Product 关系已移除')
    await load()
  } catch (failure) {
    error.value = await toApiProblem(failure)
    if (isProblemStatus(error.value, 412)) await load()
  } finally {
    changing.value = undefined
  }
}

function makePrimaryRow(row: unknown): void {
  void makePrimary(row as ProjectProductLink)
}

function removeRow(row: unknown): void {
  void remove(row as ProjectProductLink)
}

onMounted(load)
</script>

<template>
  <div class="project-view-stack">
    <project-workspace-header
      section="products"
      :project="project"
      title="关联产品"
      description="查看 Project 与 Product 的业务关系。"
    />
    <inline-problem
      v-if="error"
      :problem="error"
    />

    <section
      v-if="canManage"
      class="project-content-surface product-link-form"
    >
      <div class="project-section-heading">
        <div>
          <h2>建立关系</h2>
          <p>搜索 ACTIVE Product；同一 Product 可通过不同类型关联。</p>
        </div>
      </div>
      <div class="product-link-form__controls">
        <el-input
          v-model="query"
          placeholder="输入 Product 编码或名称前缀"
          clearable
          @keyup.enter="searchCandidates"
        >
          <template #append>
            <el-button @click="searchCandidates">
              搜索
            </el-button>
          </template>
        </el-input>
        <el-select
          v-model="selectedProductId"
          placeholder="选择 Product"
          filterable
        >
          <el-option
            v-for="candidate in candidates"
            :key="candidate.id"
            :label="`${candidate.name} (${candidate.code})`"
            :value="candidate.id"
          />
        </el-select>
        <el-select
          v-model="relationType"
          aria-label="关系类型"
        >
          <el-option
            v-for="type in relationTypes"
            :key="type"
            :label="businessLabel(type)"
            :value="type"
          />
        </el-select>
        <el-checkbox v-model="createAsPrimary">
          设为主 Product
        </el-checkbox>
        <el-button
          type="primary"
          :disabled="!selectedProductId || duplicateSelectedType()"
          :loading="changing === selectedProductId"
          @click="add"
        >
          建立关系
        </el-button>
      </div>
      <p
        v-if="duplicateSelectedType()"
        class="muted-text"
      >
        该 Product 已存在相同关系类型，请选择其他类型。
      </p>
    </section>

    <section
      v-loading="loading"
      class="project-content-surface"
      :aria-busy="loading"
    >
      <div class="project-section-heading">
        <div>
          <h2>关联 Product</h2>
          <p v-if="canManage">
            主关系切换按“取消旧主 → 设置新主”两步提交。
          </p>
          <p v-else>
            当前角色拥有只读权限。
          </p>
        </div>
      </div>
      <el-table
        v-if="links.length"
        :data="links"
      >
        <el-table-column
          label="Product"
          min-width="240"
        >
          <template #default="scope">
            <strong>{{ scope.row.productName }}</strong><br><small>{{ scope.row.productCode }}</small>
          </template>
        </el-table-column>
        <el-table-column
          label="关系"
          min-width="140"
        >
          <template #default="scope">
            <el-tag effect="plain">
              {{ businessLabel(scope.row.relationType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="Product 状态"
          min-width="130"
        >
          <template #default="scope">
            <yp-status-tag
              domain="product-status"
              :status="scope.row.productStatus"
              size="small"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="主 Product"
          width="120"
        >
          <template #default="scope">
            <el-tag
              v-if="scope.row.isPrimary"
              type="success"
            >
              主 Product
            </el-tag><span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canManage"
          label="操作"
          min-width="190"
          fixed="right"
        >
          <template #default="scope">
            <el-button
              v-if="!scope.row.isPrimary"
              link
              type="primary"
              :loading="changing === scope.row.id"
              @click="makePrimaryRow(scope.row)"
            >
              设为主
            </el-button>
            <el-button
              link
              type="danger"
              :loading="changing === scope.row.id"
              @click="removeRow(scope.row)"
            >
              移除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <yp-empty-state
        v-else-if="!loading"
        reason="no-results"
        description="当前 Project 尚未关联 Product。"
        compact
      />
    </section>
  </div>
</template>

<style scoped>
.product-link-form__controls {
  display: grid;
  grid-template-columns: minmax(240px, 2fr) minmax(200px, 1.5fr) minmax(150px, 1fr) auto auto;
  gap: var(--yp-space-3);
  align-items: center;
}

small,
.muted-text { color: var(--yp-text-secondary); }

@media (max-width: 900px) {
  .product-link-form__controls { grid-template-columns: 1fr; }
}
</style>
