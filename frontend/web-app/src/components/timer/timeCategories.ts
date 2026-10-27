import type { Component } from 'vue'
import type { TimeCategory, WorkItemLabelColorToken } from '@yumpoo/api-client'
import { Briefcase, ChatDotRound, Reading, Suitcase } from '@element-plus/icons-vue'

/** 固定工时分类；记录可关联一个项目，但不关联工作项。 */
export interface TimeCategoryOption { code: TimeCategory; key: string; label: string; colorToken: WorkItemLabelColorToken; icon: Component }

/**
 * What a time record is kept against, as one string: a work item id, or `category:<code>`.
 * Lists, totals and selections key on it so work items and categories sit side by side.
 */
export type SubjectKey = string

const PREFIX = 'category:'
/** Groups category rows that name no project apart from projects in the team timesheets. */
export const NON_PROJECT_LABEL = '未关联项目'
/** Groups non-project rows apart from projects in the timesheets; never a real project id. */
export const NON_PROJECT_KEY = '__non_project__'
export const NON_PROJECT_COLOR = 'var(--yp-label-american-gray)'

/** `category:<code>`, or `category:<code>:<projectId>` when the record is linked to a project. */
export const categoryKey = (code: TimeCategory, projectId?: string | null): SubjectKey =>
  `${PREFIX}${code}${projectId ? `:${projectId}` : ''}`

// Wire values rather than the generated enums, so this catalogue stays plain data wherever the client is mocked.
export const TIME_CATEGORIES: readonly TimeCategoryOption[] = ([
  ['BUSINESS_TRIP', '出差', 'RIVER', Suitcase],
  ['LEARNING', '学习', 'SALADISH', Reading],
  ['BUSINESS', '商务', 'BERRY', Briefcase],
  ['MEETING', '会议', 'ORCHID', ChatDotRound],
] as const).map(([code, label, colorToken, icon]) => ({ code: code as TimeCategory, key: categoryKey(code as TimeCategory), label,
  colorToken: colorToken as WorkItemLabelColorToken, icon }))

export function timeCategory(code: TimeCategory | string | null | undefined): TimeCategoryOption | undefined {
  return TIME_CATEGORIES.find(option => option.code === code)
}

/** The category a key names, or undefined for a work item id. */
export function categoryOf(key: SubjectKey | null | undefined): TimeCategoryOption | undefined {
  return key?.startsWith(PREFIX) ? timeCategory(key.slice(PREFIX.length).split(':')[0]) : undefined
}

/** The project a category key is linked to, or null. */
export function categoryProjectOf(key: SubjectKey): string | null {
  return key.startsWith(PREFIX) ? key.slice(PREFIX.length).split(':')[1] ?? null : null
}

/** The timeline slot a key occupies: its category whatever project it names, else the work item; a slot never overlaps itself. */
export function subjectSlot(key: SubjectKey): string {
  const category = categoryOf(key)
  return category ? category.key : key
}

/** The key of a session's subject; null when the work item is not disclosed to the caller. */
export function sessionKey(session: { workItemId: string | null; timeCategory?: TimeCategory | null; categoryProjectId?: string | null }): SubjectKey | null {
  return session.timeCategory ? categoryKey(session.timeCategory, session.categoryProjectId) : session.workItemId
}

/** Request fields naming the subject, for fill, reassign and timer commands. */
export function subjectFields(key: SubjectKey): { workItemId: string } | { timeCategory: TimeCategory; categoryProjectId?: string } {
  const category = categoryOf(key)
  if (!category) return { workItemId: key }
  const projectId = categoryProjectOf(key)
  return projectId ? { timeCategory: category.code, categoryProjectId: projectId } : { timeCategory: category.code }
}

/** The last stopped timer group, work items first then categories, as `startMany` takes it back. */
export function resumeSubjects(current: { resumeItems: { workItemId: string }[];
  resumeCategorySubjects?: { timeCategory: TimeCategory; projectId?: string | null }[] } | undefined): SubjectKey[] {
  return [...(current?.resumeItems ?? []).map(item => item.workItemId),
    ...(current?.resumeCategorySubjects ?? []).map(subject => categoryKey(subject.timeCategory, subject.projectId))]
}

/** What a running timer is called: its category, or the work item title the caller may see. */
export function timerTitle(timer: { session: { timeCategory?: TimeCategory | null }; workItemTitle: string | null }, fallback = '不可见工作项'): string {
  return timeCategory(timer.session.timeCategory)?.label ?? timer.workItemTitle ?? fallback
}

/** A pickable work item or category, as the day view's pickers hand it on. */
export interface SubjectOption { key: SubjectKey; title: string; projectName: string | null }

export const categoryOption = (option: TimeCategoryOption): SubjectOption => ({ key: option.key, title: option.label, projectName: null })

/** Categories whose label contains the query, keyed to `projectId` when the list is narrowed to one project. */
export function matchCategories(query: string, projectId: string | null = null): TimeCategoryOption[] {
  const text = query.trim()
  return TIME_CATEGORIES.filter(option => !text || option.label.includes(text))
    .map(option => projectId ? { ...option, key: categoryKey(option.code, projectId) } : option)
}
