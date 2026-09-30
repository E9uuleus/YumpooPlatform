<script setup lang="ts">
import { Box, Plus, Search } from '@element-plus/icons-vue'
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
import YpPageHeader from '../../components/yp/YpPageHeader.vue'
import YpSegmented from '../../components/yp/YpSegmented.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import YpSurface from '../../components/yp/YpSurface.vue'
import { useSession } from '../../composables/useSession'

const router = useRouter()
const ElOption = ElOptionRaw as unknown as DefineComponent
const session = useSession()
const result = ref<ProductPage>()
const status = ref<ProductStatusFilter>(ProductStatusFilter.Active)
const statusOptions = [
  { value: ProductStatusFilter.Active, label: '进行中' },
  { value: ProductStatusFilter.Archived, label: '已归档' },
  { value: ProductStatusFilter.All, label: '全部' },
]
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
    <yp-page-header
      eyebrow="产品治理"
      title="产品"
      description="查看产品状态、负责人并完成生命周期治理。"
    >
      <template
        v-if="session.isCompanyAdmin.value"
        #actions
      >
        <el-button
          type="primary"
          :icon="Plus"
          @click="createOpen = true"
        >
          创建产品
        </el-button>
      </template>
    </yp-page-header>
    <inline-problem
      v-if="error"
      :problem="error"
    />
    <div class="product-toolbar">
      <el-input
        v-model="query"
        class="product-toolbar__search"
        clearable
        :prefix-icon="Search"
        aria-label="搜索产品名称或编码"
        placeholder="按名称或编码前缀搜索"
        @input="scheduleSearch"
        @clear="search"
        @keyup.enter="search"
      />
      <yp-segmented
        v-model="status"
        label="产品状态"
        :options="statusOptions"
        @change="changeStatus"
      />
    </div>
    <yp-surface
      v-if="loading || result?.items.length"
      v-loading="loading"
      class="product-table"
      flush
    >
      <el-table
        :data="result?.items ?? []"
        @row-click="row => openProduct(row.id)"
      >
        <el-table-column
          label="产品"
          min-width="280"
        >
          <template #default="scope">
            <button
              class="product-link"
              type="button"
              @click.stop="openProduct(scope.row.id)"
            >
              <span
                class="product-link__icon"
                aria-hidden="true"
              >
                <el-icon><box /></el-icon>
              </span>
              <span class="product-link__text">
                <span class="product-link__name">{{ scope.row.name }}</span>
                <small>{{ scope.row.code }}</small>
              </span>
            </button>
          </template>
        </el-table-column>
        <el-table-column
          label="负责人"
          width="200"
        >
          <template #default="scope">
            <yp-assignee
              :user-id="scope.row.ownerUserId"
              :display-name="scope.row.ownerDisplayName ?? '-'"
              size="table"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="120"
        >
          <template #default="scope">
            <yp-status-tag
              domain="product-status"
              :status="scope.row.status"
              effect="soft"
            />
          </template>
        </el-table-column>
      </el-table>
      <template
        v-if="result && result.totalElements"
        #footer
      >
        <el-pagination
          layout="prev, pager, next, total"
          :current-page="page + 1"
          :page-size="size"
          :total="result.totalElements"
          @current-change="next => { page = next - 1; load() }"
        />
      </template>
    </yp-surface>
    <yp-surface v-else>
      <yp-empty-state
        reason="no-results"
        description="没有符合条件的产品。"
        compact
      />
    </yp-surface>

    <el-dialog
      v-model="createOpen"
      title="创建产品"
      width="min(520px, 92vw)"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
      >
        <el-form-item
          label="编码"
          prop="code"
        >
          <el-input
            v-model="form.code"
            maxlength="32"
          />
        </el-form-item>
        <el-form-item
          label="名称"
          prop="name"
        >
          <el-input
            v-model="form.name"
            maxlength="80"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item
          label="负责人"
          prop="ownerUserId"
        >
          <el-select
            v-model="form.ownerUserId"
            filterable
          >
            <el-option
              v-for="owner in owners"
              :key="owner.userId"
              :label="owner.displayName"
              :value="owner.userId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="creating"
          @click="createProduct"
        >
          创建
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.product-page {
  display: grid;
  gap: var(--yp-space-4);
}

.product-page > .yp-page-header {
  margin-bottom: var(--yp-space-1);
}

.product-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--yp-space-3);
}

.product-toolbar__search {
  width: min(360px, 100%);
}

.product-table :deep(.el-table__row) {
  cursor: pointer;
}

.product-link {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: var(--yp-space-3);
  padding: 0;
  border: 0;
  color: inherit;
  background: none;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.product-link__icon {
  display: grid;
  width: 28px;
  height: 28px;
  flex: none;
  place-items: center;
  border-radius: var(--yp-radius-sm);
  color: var(--yp-link);
  background: var(--yp-bg-selected);
  font-size: 16px;
}

.product-link__text {
  display: grid;
  min-width: 0;
}

.product-link__name {
  overflow: hidden;
  color: var(--yp-text-primary);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-link:hover .product-link__name,
.product-link:focus-visible .product-link__name {
  color: var(--yp-link);
}

.product-link small {
  color: var(--yp-text-muted);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-caption-line);
}

@media (max-width: 640px) {
  .product-toolbar__search {
    width: 100%;
  }
}
</style>
