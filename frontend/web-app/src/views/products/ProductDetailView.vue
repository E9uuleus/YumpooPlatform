<script setup lang="ts">
import {
  GovernanceOverrideCreateAction, GovernanceOverrideRequestTargetTypeEnum,
  ProductStatus, readCsrfToken, type Product, type SafeBlocker,
} from '@yumpoo/api-client'
import { ElButton, ElInput, ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { administrationApi, productsApi } from '../../api/client'
import { isProblemStatus, localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'

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
  <section class="product-detail" v-loading="loading">
    <button class="back-link" type="button" @click="router.push({ name: 'products' })">← 返回产品列表</button>
    <inline-problem v-if="error" :problem="error" />
    <div v-if="conflict" class="conflict" role="alert">服务器版本已更新。你的输入已保留；请核对最新详情后再次保存。</div>
    <template v-if="product">
      <header><div><p>{{ product.code }}</p><h1>{{ product.name }}</h1></div><yp-status-tag domain="product-status" :status="product.status" effect="soft" /></header>
      <section class="card">
        <h2>基本信息</h2>
        <label>名称<el-input v-model="draft.name" maxlength="80" :disabled="!product.capabilities?.canUpdate" /></label>
        <label>描述<el-input v-model="draft.description" type="textarea" maxlength="500" show-word-limit :disabled="!product.capabilities?.canUpdate" /></label>
        <div class="owner"><span>负责人</span><yp-assignee :user-id="product.ownerUserId" :display-name="product.ownerDisplayName ?? '-'" /></div>
        <el-button v-if="product.capabilities?.canUpdate" type="primary" :loading="saving" @click="save">保存修改</el-button>
      </section>
      <section class="card danger">
        <h2>生命周期</h2>
        <el-button v-if="product.status === ProductStatus.Active && product.capabilities?.canArchive" :loading="saving" @click="archive">归档产品</el-button>
        <el-button v-if="product.status === ProductStatus.Archived && product.capabilities?.canRestore" type="primary" :loading="saving" @click="restore">恢复产品</el-button>
        <div v-if="blockers.length" class="blockers">
          <strong>归档被以下事实阻断</strong>
          <ul><li v-for="blocker in blockers" :key="blocker.code">{{ blocker.code }}：{{ blocker.count }}</li></ul>
          <template v-if="product.capabilities?.canOverrideArchive">
            <label>治理覆盖理由（10–500 字）<el-input v-model="overrideReason" type="textarea" minlength="10" maxlength="500" show-word-limit /></label>
            <el-button type="danger" :disabled="overrideReason.trim().length < 10" :loading="saving" @click="overrideArchive">显式覆盖并归档</el-button>
          </template>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.product-detail { max-width: 860px; margin: 0 auto; padding: 28px; display: grid; gap: 18px; }
.back-link { justify-self: start; border: 0; background: none; color: var(--yp-color-primary); cursor: pointer; }
header { display: flex; align-items: center; justify-content: space-between; }
header p { margin: 0; color: var(--yp-color-text-secondary); } header h1 { margin: 4px 0; }
.card { display: grid; gap: 16px; padding: 22px; background: var(--yp-color-surface); border: 1px solid var(--yp-color-border); border-radius: 12px; }
.card h2 { margin: 0; } .card label { display: grid; gap: 8px; } .owner { display: flex; align-items: center; gap: 16px; }
.danger { border-color: color-mix(in srgb, var(--el-color-danger) 30%, var(--yp-color-border)); }
.blockers { display: grid; gap: 12px; padding-top: 12px; border-top: 1px solid var(--yp-color-border); }
.conflict { padding: 12px 16px; border-radius: 8px; background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); }
</style>
