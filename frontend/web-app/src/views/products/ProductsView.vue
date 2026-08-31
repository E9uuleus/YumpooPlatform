<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import {
  AccountStatus, EmploymentStatus, ProductStatusFilter, readCsrfToken,
  type Member, type ProductPage,
} from '@yumpoo/api-client'
import {
  ElButton, ElDialog, ElForm, ElFormItem, ElIcon, ElInput, ElMessage, ElOption as ElOptionRaw,
  ElPagination, ElSelect, ElTable, ElTableColumn, type FormInstance, type FormRules,
} from 'element-plus'
import { onBeforeUnmount, onMounted, reactive, ref, type DefineComponent } from 'vue'
import { useRouter } from 'vue-router'
import { identityAdministrationApi, productsApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import { useSession } from '../../composables/useSession'

const router = useRouter()
const ElOption = ElOptionRaw as unknown as DefineComponent
const session = useSession()
const result = ref<ProductPage>()
const status = ref(ProductStatusFilter.Active)
const query = ref('')
const appliedQuery = ref('')
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<ApiProblem>()
const createOpen = ref(false)
const creating = ref(false)
const formRef = ref<FormInstance>()
const owners = ref<Member[]>([])
const form = reactive({ code: '', name: '', description: '', ownerUserId: '' })
const rules: FormRules = {
  code: [{ required: true, whitespace: true, message: '请输入产品编码', trigger: 'blur' }],
  name: [{ required: true, whitespace: true, message: '请输入产品名称', trigger: 'blur' }],
  ownerUserId: [{ required: true, message: '请选择负责人', trigger: 'change' }],
}
let timer: ReturnType<typeof setTimeout> | undefined

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  try {
    result.value = await productsApi.listProducts({
      status: status.value,
      ...(appliedQuery.value ? { query: appliedQuery.value } : {}),
      page: page.value,
      size: size.value,
    })
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function loadOwners(): Promise<void> {
  if (!session.isCompanyAdmin.value) return
  try {
    owners.value = (await identityAdministrationApi.listMembers({
      employmentStatus: EmploymentStatus.Active,
      accountStatus: AccountStatus.Enabled,
      page: 0,
      size: 100,
    })).items
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

function changeStatus(): void { page.value = 0; void load() }
function search(): void {
  if (timer) clearTimeout(timer)
  appliedQuery.value = query.value.trim()
  page.value = 0
  void load()
}
function scheduleSearch(): void {
  if (timer) clearTimeout(timer)
  timer = setTimeout(search, 300)
}
function openProduct(productId: string): void {
  void router.push({ name: 'product-detail', params: { productId } })
}
function resetForm(): void {
  Object.assign(form, { code: '', name: '', description: '', ownerUserId: '' })
  formRef.value?.clearValidate()
}

async function createProduct(): Promise<void> {
  try { await formRef.value?.validate() } catch { return }
  const csrf = readCsrfToken()
  if (!csrf) { error.value = localProblem('缺少 CSRF 凭据，请刷新后重试。'); return }
  creating.value = true
  error.value = undefined
  try {
    const created = await productsApi.createProduct({
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      productCreateRequest: {
        code: form.code.trim().toUpperCase(),
        name: form.name.trim(),
        description: form.description.trim() || null,
        ownerUserId: form.ownerUserId,
      },
    })
    ElMessage.success('产品已创建')
    createOpen.value = false
    resetForm()
    await load()
    openProduct(created.id)
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    creating.value = false
  }
}

onMounted(async () => { await Promise.all([load(), loadOwners()]) })
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <section class="product-page">
    <header class="product-page__header">
      <div><p>产品治理</p><h1>产品</h1><span>查看产品状态、负责人并完成生命周期治理。</span></div>
      <el-button v-if="session.isCompanyAdmin.value" type="primary" @click="createOpen = true"><el-icon><plus /></el-icon>创建产品</el-button>
    </header>
    <inline-problem v-if="error" :problem="error" />
    <div class="product-toolbar">
      <el-input v-model="query" clearable aria-label="搜索产品名称或编码" placeholder="按名称或编码前缀搜索" @input="scheduleSearch" @clear="search" @keyup.enter="search" />
      <el-select v-model="status" aria-label="产品状态" @change="changeStatus">
        <el-option label="进行中" :value="ProductStatusFilter.Active" />
        <el-option label="已归档" :value="ProductStatusFilter.Archived" />
        <el-option label="全部" :value="ProductStatusFilter.All" />
      </el-select>
    </div>
    <div v-if="loading || result?.items.length" v-loading="loading" class="product-table">
      <el-table :data="result?.items ?? []" @row-click="row => openProduct(row.id)">
        <el-table-column label="产品" min-width="260"><template #default="scope"><button class="product-link" type="button" @click.stop="openProduct(scope.row.id)">{{ scope.row.name }}<small>{{ scope.row.code }}</small></button></template></el-table-column>
        <el-table-column label="负责人" min-width="180"><template #default="scope"><yp-assignee :user-id="scope.row.ownerUserId" :display-name="scope.row.ownerDisplayName ?? '-'" size="table" /></template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="scope"><yp-status-tag domain="product-status" :status="scope.row.status" effect="soft" /></template></el-table-column>
      </el-table>
      <el-pagination v-if="result && result.totalElements" layout="prev, pager, next, total" :current-page="page + 1" :page-size="size" :total="result.totalElements" @current-change="next => { page = next - 1; load() }" />
    </div>
    <yp-empty-state v-else reason="no-results" description="没有符合条件的产品。" compact />

    <el-dialog v-model="createOpen" title="创建产品" width="min(520px, 92vw)" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="编码" prop="code"><el-input v-model="form.code" maxlength="32" /></el-form-item>
        <el-form-item label="名称" prop="name"><el-input v-model="form.name" maxlength="80" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="负责人" prop="ownerUserId"><el-select v-model="form.ownerUserId" filterable><el-option v-for="owner in owners" :key="owner.userId" :label="owner.displayName" :value="owner.userId" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :loading="creating" @click="createProduct">创建</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.product-page { padding: 28px; display: grid; gap: 20px; }
.product-page__header { display: flex; justify-content: space-between; align-items: end; gap: 20px; }
.product-page__header p { margin: 0; color: var(--yp-color-primary); font-weight: 600; }
.product-page__header h1 { margin: 4px 0; }
.product-page__header span { color: var(--yp-color-text-secondary); }
.product-toolbar { display: grid; grid-template-columns: minmax(220px, 480px) 150px; gap: 12px; }
.product-table { background: var(--yp-color-surface); border: 1px solid var(--yp-color-border); border-radius: 12px; overflow: hidden; }
.product-table :deep(.el-pagination) { padding: 16px; justify-content: flex-end; }
.product-link { display: grid; gap: 2px; border: 0; background: none; color: inherit; text-align: left; cursor: pointer; font: inherit; }
.product-link small { color: var(--yp-color-text-secondary); }
@media (max-width: 640px) { .product-page { padding: 18px; } .product-toolbar { grid-template-columns: 1fr; } }
</style>
