import { ElMessageBox } from 'element-plus'

/** 负责人转交确认：项目只有一位负责人，原负责人保留为普通成员；取消时 reject。 */
export function confirmOwnerTransfer(name: string, selfOwner: boolean): Promise<unknown> {
  return ElMessageBox.confirm(
    selfOwner
      ? `确定将负责人转交给「${name}」？转交后你将成为普通成员。`
      : `确定将负责人转交给「${name}」？转交后原负责人保留为普通成员。`,
    '转交负责人',
    { confirmButtonText: '确认转交', cancelButtonText: '取消', type: 'warning' },
  )
}
