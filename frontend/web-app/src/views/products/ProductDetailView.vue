<script setup lang="ts">
import {
  GovernanceOverrideCreateAction, GovernanceOverrideRequestTargetTypeEnum,
  ProductStatus, readCsrfToken, type Product, type SafeBlocker,
} from '@yumpoo/api-client'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { administrationApi, productsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import YpSurface from '../../components/yp/YpSurface.vue'

const route = useRoute()
const router = useRouter()
const product = ref<Product>()
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiProblem>()
const conflict = ref(false)
const blockers = ref<SafeBlocker[]>([])
const overrideReason = ref('')
const draft = reactive({ name: '', description: '' })
const productId = computed(() => route.params.productId as string)
const etag = computed(() => product.value?.etag ?? `"${product.value?.rowVersion ?? 0}"`)

function syncDraft(next: Product): void {
  draft.name = next.name
  draft.description = next.description ?? ''
}

async function load(sync = true): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    product.value = await productsApi.getProduct({ productId: productId.value })
    if (sync) syncDraft(product.value)
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

function csrf(): string | undefined {
  const value = readCsrfToken()
  if (!value) error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。')
  return value
}

async function save(): Promise<void> {
  const token = csrf()
  if (!token || !product.value || !draft.name.trim()) return
  saving.value = true
  error.value = undefined
  conflict.value = false
  try {
    await productsApi.updateProduct({
      productId: productId.value, xXSRFTOKEN: token, ifMatch: etag.value,
      productUpdateRequest: { name: draft.name.trim(), description: draft.description.trim() || null },
    })
    await load(true)
    ElMessage.success('产品信息已更新')
  } catch (reason) {
    const problem = await toApiProblem(reason)
    error.value = problem
    if (isProblemStatus(problem, 412)) { conflict.value = true; await load(false) }
  } finally { saving.value = false }
}

async function archive(): Promise<void> {
  const token = csrf()
  if (!token || !product.value) return
  saving.value = true
  error.value = undefined
  blockers.value = []
  try {
    await productsApi.archiveProduct({ productId: productId.value, xXSRFTOKEN: token, idempotencyKey: crypto.randomUUID(), ifMatch: etag.value })
    await load(true)
    ElMessage.success('产品已归档')
  } catch (reason) {
    const problem = await toApiProblem(reason)
    error.value = problem
    if (problem.kind === 'response' && isProblemStatus(problem, 409)) blockers.value = problem.error.details.blockers ?? []
    if (isProblemStatus(problem, 412)) { conflict.value = true; await load(false) }
  } finally { saving.value = false }
}

async function overrideArchive(): Promise<void> {
  const token = csrf()
  if (!token || !product.value || overrideReason.value.trim().length < 10) return
  saving.value = true
  error.value = undefined
  try {
    await administrationApi.createGovernanceOverride({
      xXSRFTOKEN: token, ifMatch: etag.value, idempotencyKey: crypto.randomUUID(),
      governanceOverrideRequest: {
        action: GovernanceOverrideCreateAction.ProductArchiveWithBlockers,
        targetType: GovernanceOverrideRequestTargetTypeEnum.Product,
        targetId: productId.value,
        reason: overrideReason.value.trim(),
      },
    })
    await load(true)
    blockers.value = []
    overrideReason.value = ''
    ElMessage.success('产品已通过治理覆盖归档')
  } catch (reason) {
    const problem = await toApiProblem(reason)
    error.value = problem
    if (isProblemStatus(problem, 412)) { conflict.value = true; await load(false) }
  } finally { saving.value = false }
}

async function restore(): Promise<void> {
  const token = csrf()
  if (!token || !product.value) return
  saving.value = true
  error.value = undefined
  try {
    await productsApi.restoreProduct({ productId: productId.value, xXSRFTOKEN: token, idempotencyKey: crypto.randomUUID(), ifMatch: etag.value })
    await load(true)
    ElMessage.success('产品已恢复')
  } catch (reason) {
    const problem = await toApiProblem(reason)
    error.value = problem
    if (isProblemStatus(problem, 412)) { conflict.value = true; await load(false) }
  } finally { saving.value = false }
}

onMounted(() => load())
</script>

<template>
  <section
    v-loading="loading"
    class="product-detail"
  >
    <yp-page-header
      v-if="product"
      :eyebrow="product.code"
      :title="product.name"
    >
      <template #breadcrumbs>
        <el-button
          class="back-link"
          link
          type="primary"
          :icon="ArrowLeft"
          @click="router.push({ name: 'products' })"
        >
          返回产品列表
        </el-button>
      </template>
      <template #meta>
        <yp-status-tag
          domain="product-status"
          :status="product.status"
          effect="soft"
        />
      </template>
    </yp-page-header>
    <el-button
      v-else
      class="back-link"
      link
      type="primary"
      :icon="ArrowLeft"
      @click="router.push({ name: 'products' })"
    >
      返回产品列表
    </el-button>
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <el-alert
      v-if="conflict"
      class="conflict"
      type="warning"
      title="服务器版本已更新。你的输入已保留；请核对最新详情后再次保存。"
      :closable="false"
      show-icon
    />
    <template v-if="product">
      <yp-surface title="基本信息">
        <el-form
          class="product-form"
          label-position="top"
          @submit.prevent
        >
          <el-form-item label="名称">
            <el-input
              v-model="draft.name"
              maxlength="80"
              :disabled="!product.capabilities?.canUpdate"
            />
          </el-form-item>
          <el-form-item label="描述">
            <el-input
              v-model="draft.description"
              type="textarea"
              maxlength="500"
              show-word-limit
              :disabled="!product.capabilities?.canUpdate"
            />
          </el-form-item>
          <el-form-item label="负责人">
            <yp-assignee
              :user-id="product.ownerUserId"
              :display-name="product.ownerDisplayName ?? '-'"
            />
          </el-form-item>
        </el-form>
        <template
          v-if="product.capabilities?.canUpdate"
          #footer
        >
          <el-button
            type="primary"
            :loading="saving"
            @click="save"
          >
            保存修改
          </el-button>
        </template>
      </yp-surface>
      <yp-surface
        title="生命周期"
        tone="danger"
      >
        <div class="lifecycle-actions">
          <el-button
            v-if="product.status === ProductStatus.Active && product.capabilities?.canArchive"
            :loading="saving"
            @click="archive"
          >
            归档产品
          </el-button>
          <el-button
            v-if="product.status === ProductStatus.Archived && product.capabilities?.canRestore"
            type="primary"
            :loading="saving"
            @click="restore"
          >
            恢复产品
          </el-button>
        </div>
        <div
          v-if="blockers.length"
          class="blockers"
        >
          <strong>归档被以下事实阻断</strong>
          <ul>
            <li
              v-for="blocker in blockers"
              :key="blocker.code"
            >
              {{ blocker.code }}：{{ blocker.count }}
            </li>
          </ul>
          <el-form
            v-if="product.capabilities?.canOverrideArchive"
            class="product-form"
            label-position="top"
            @submit.prevent
          >
            <el-form-item label="治理覆盖理由（10–500 字）">
              <el-input
                v-model="overrideReason"
                type="textarea"
                minlength="10"
                maxlength="500"
                show-word-limit
              />
            </el-form-item>
            <el-button
              type="danger"
              :disabled="overrideReason.trim().length < 10"
              :loading="saving"
              @click="overrideArchive"
            >
              显式覆盖并归档
            </el-button>
          </el-form>
        </div>
      </yp-surface>
    </template>
  </section>
</template>

<style scoped>
.product-detail {
  display: grid;
  width: min(860px, 100%);
  margin: 0 auto;
  gap: var(--yp-space-4);
}

.product-detail > .yp-page-header {
  margin-bottom: var(--yp-space-1);
}

.back-link {
  justify-self: start;
}

.product-form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.lifecycle-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--yp-space-2);
}

.lifecycle-actions:empty {
  display: none;
}

.blockers {
  display: grid;
  gap: var(--yp-space-3);
  margin-top: var(--yp-space-4);
  padding-top: var(--yp-space-4);
  border-top: 1px solid var(--yp-border-subtle);
}

.lifecycle-actions:empty + .blockers {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}

.blockers ul {
  display: grid;
  gap: var(--yp-space-1);
  margin: 0;
  padding: var(--yp-space-3) var(--yp-space-4);
  border-radius: var(--yp-radius-sm);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  font-family: var(--yp-font-mono);
  font-size: var(--yp-type-caption-size);
  list-style: none;
}
</style>
