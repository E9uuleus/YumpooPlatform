import type {
  ArchiveProductRequest,
  CreateProductRequest,
  ListProductsRequest,
  ReassignProductOwnerRequest,
  RestoreProductRequest,
  UpdateProductRequest,
} from '../src/generated/apis/ProductsApi.js'
import { ProductStatusFilter } from '../src/generated/models/ProductStatusFilter.js'

const productId = '23000000-0000-4000-8000-000000000002'
const ownerUserId = '23000000-0000-4000-8000-000000000004'

const create: CreateProductRequest = {
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '23000000-0000-4000-8000-000000000001',
  productCreateRequest: { code: 'YUMPOO', name: 'Yumpoo', ownerUserId },
}
const list: ListProductsRequest = { status: ProductStatusFilter.All, page: 0, size: 20 }
const update: UpdateProductRequest = {
  productId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"0"',
  productUpdateRequest: { name: 'Yumpoo Platform', description: null },
}
const archive: ArchiveProductRequest = {
  productId,
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '23000000-0000-4000-8000-000000000011',
  ifMatch: '"1"',
}
const restore: RestoreProductRequest = { ...archive, idempotencyKey: '23000000-0000-4000-8000-000000000012' }
const reassign: ReassignProductOwnerRequest = {
  ...archive,
  idempotencyKey: '23000000-0000-4000-8000-000000000013',
  productOwnerReassignmentRequest: {
    newOwnerUserId: '23000000-0000-4000-8000-000000000005',
    reason: '负责人岗位调整交接',
  },
}

void create
void list
void update
void archive
void restore
void reassign
