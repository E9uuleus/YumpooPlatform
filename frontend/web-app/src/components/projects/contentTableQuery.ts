import {
  ContentSortDirection,
  ContentSortField,
  type ContentViewFilters,
  type ContentViewSort,
} from '@yumpoo/api-client'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'

export interface ContentTableQuery {
  filters: ContentViewFilters
  sort: ContentViewSort[]
}

const controlledKeys = [
  'custom', 'q', 'status', 'priority', 'assigneeUserId', 'dueFrom', 'dueTo',
  'updatedAfter', 'sort',
] as const

export function cloneTableQuery(value: ContentTableQuery): ContentTableQuery {
  return {
    filters: {
      ...value.filters,
      statusCodes: new Set(value.filters.statusCodes),
      priorities: new Set(value.filters.priorities),
      assigneeUserIds: new Set(value.filters.assigneeUserIds),
      dueFrom: cloneDate(value.filters.dueFrom),
      dueTo: cloneDate(value.filters.dueTo),
      updatedAfter: cloneDate(value.filters.updatedAfter),
    },
    sort: value.sort.map(item => ({ ...item })),
  }
}

export function sharedTableQuery(filters: ContentViewFilters,
  sort: ContentViewSort[]): ContentTableQuery {
  return cloneTableQuery({ filters, sort })
}

export function hasCustomTableQuery(query: LocationQuery | undefined): boolean {
  return first(query?.custom) === '1'
}

export function parseTableQuery(query: LocationQuery | undefined): ContentTableQuery {
  const sorts = values(query?.sort).flatMap(value => {
    const [field, direction, extra] = value.split(',')
    if (extra !== undefined || !sortFields.has(field as ContentSortField)
      || !sortDirections.has(direction as ContentSortDirection)) return []
    return [{ field: field as ContentSortField, direction: direction as ContentSortDirection }]
  }).slice(0, 3)
  return {
    filters: {
      query: first(query?.q)?.trim() || null,
      statusCodes: new Set(values(query?.status)),
      priorities: new Set(values(query?.priority) as ContentViewFilters['priorities'] extends Set<infer T> ? T[] : never),
      assigneeUserIds: new Set(values(query?.assigneeUserId)),
      dueFrom: parseDate(first(query?.dueFrom), true),
      dueTo: parseDate(first(query?.dueTo), true),
      updatedAfter: parseDate(first(query?.updatedAfter), false),
    },
    sort: sorts,
  }
}

export function encodeTableQuery(value: ContentTableQuery,
  current: LocationQuery | undefined): LocationQueryRaw {
  const query = withoutTableQuery(current)
  query.custom = '1'
  const keyword = value.filters.query?.trim()
  if (keyword) query.q = keyword
  addMany(query, 'status', value.filters.statusCodes)
  addMany(query, 'priority', value.filters.priorities)
  addMany(query, 'assigneeUserId', value.filters.assigneeUserIds)
  if (value.filters.dueFrom) query.dueFrom = day(value.filters.dueFrom)
  if (value.filters.dueTo) query.dueTo = day(value.filters.dueTo)
  if (value.filters.updatedAfter) query.updatedAfter = value.filters.updatedAfter.toISOString()
  if (value.sort.length) query.sort = value.sort.map(item => `${item.field},${item.direction}`)
  return query
}

export function withoutTableQuery(current: LocationQuery | undefined): LocationQueryRaw {
  const result: LocationQueryRaw = { ...(current ?? {}) }
  for (const key of controlledKeys) delete result[key]
  return result
}

function addMany(query: LocationQueryRaw, key: string, source: Set<unknown>): void {
  const result = Array.from(source, String)
  if (result.length) query[key] = result
}

function values(value: LocationQuery[string] | undefined): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string')
  return typeof value === 'string' ? [value] : []
}

function first(value: LocationQuery[string] | undefined): string | undefined {
  return values(value)[0]
}

function parseDate(value: string | undefined, naturalDay: boolean): Date | null {
  if (!value) return null
  const parsed = new Date(naturalDay ? `${value}T00:00:00.000Z` : value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

function cloneDate(value: Date | null): Date | null {
  return value ? new Date(value) : null
}

function day(value: Date): string {
  return value.toISOString().slice(0, 10)
}

const sortFields = new Set(Object.values(ContentSortField))
const sortDirections = new Set(Object.values(ContentSortDirection))
