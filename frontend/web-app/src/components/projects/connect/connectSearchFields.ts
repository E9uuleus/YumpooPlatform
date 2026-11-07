import { ConnectCandidateField } from '@yumpoo/api-client'
import type { WorkItemFieldKey } from '../workItemFieldIdentity'

export interface ConnectSearchField { value: ConnectCandidateField; label: string; field: WorkItemFieldKey }

/** Fields a candidate search can match; icons and colours come from the shared work item field identities. */
export const connectSearchFields: ConnectSearchField[] = [
  { value: ConnectCandidateField.Name, label: '名称', field: 'title' },
  { value: ConnectCandidateField.Assignee, label: '处理人', field: 'assignee' },
  { value: ConnectCandidateField.Status, label: '状态', field: 'status' },
  { value: ConnectCandidateField.Priority, label: '优先级', field: 'priority' },
  { value: ConnectCandidateField.Content, label: '类别', field: 'content' },
]
export const defaultConnectSearchFields: ConnectCandidateField[] = [ConnectCandidateField.Name, ConnectCandidateField.Assignee]
export function isDefaultConnectSearch(fields: ConnectCandidateField[]): boolean {
  return fields.length === defaultConnectSearchFields.length && defaultConnectSearchFields.every(field => fields.includes(field))
}
