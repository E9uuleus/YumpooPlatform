import { markRaw, type Component } from 'vue'
import { CollectionTag, Flag, List, Tickets, User } from '@element-plus/icons-vue'
import { ConnectCandidateField } from '@yumpoo/api-client'

export interface ConnectSearchField { value: ConnectCandidateField; label: string; icon: Component; tone: string }

/** Fields a candidate search can match, with the coloured icon tiles the item card also uses. */
export const connectSearchFields: ConnectSearchField[] = [
  { value: ConnectCandidateField.Name, label: '名称', icon: markRaw(Tickets), tone: 'var(--yp-label-purple)' },
  { value: ConnectCandidateField.Assignee, label: '处理人', icon: markRaw(User), tone: 'var(--yp-label-chili-blue)' },
  { value: ConnectCandidateField.Status, label: '状态', icon: markRaw(List), tone: 'var(--yp-label-green)' },
  { value: ConnectCandidateField.Priority, label: '优先级', icon: markRaw(Flag), tone: 'var(--yp-label-orange)' },
  { value: ConnectCandidateField.Content, label: '类别', icon: markRaw(CollectionTag), tone: 'var(--yp-label-egg-yolk)' },
]
export const defaultConnectSearchFields: ConnectCandidateField[] = [ConnectCandidateField.Name, ConnectCandidateField.Assignee]
export function isDefaultConnectSearch(fields: ConnectCandidateField[]): boolean {
  return fields.length === defaultConnectSearchFields.length && defaultConnectSearchFields.every(field => fields.includes(field))
}
