import { describe, expect, it } from 'vitest'
import type { RouteRecordRaw } from 'vue-router'
import { routes } from '../router'
import { TOURS, type TourId } from './tours'

const sources = import.meta.glob('../**/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>
const declared = new Set(Object.values(sources).flatMap(source => [...source.matchAll(/data-tour="([^"]+)"/g)].map(match => `[data-tour="${match[1]}"]`)))
const routeTours = (records: RouteRecordRaw[]): unknown[] =>
  records.flatMap(record => [...(record.meta?.onboardingTour ? [record.meta.onboardingTour] : []), ...routeTours(record.children ?? [])])
const tours = Object.entries(TOURS) as [TourId, typeof TOURS[TourId]][]

describe('导览防漂移', () => {
  it('每个步骤的目标与备用目标都在源码中声明', () => {
    for (const [id, tour] of tours) {
      for (const step of tour.steps) {
        if (step.target) expect(declared, `${id}: ${step.title}`).toContain(step.target)
        if (step.fallback) expect(declared, `${id}: ${step.title} fallback`).toContain(step.fallback.target)
      }
    }
  })

  it('路由引用的导览都存在，除欢迎外每个导览都有路由触发', () => {
    const referenced = routeTours(routes)
    for (const id of referenced) expect(Object.keys(TOURS)).toContain(id)
    for (const [id] of tours) if (id !== 'welcome') expect(referenced, id).toContain(id)
  })

  it('步骤标题在导览内唯一，since 不超过版本，升版的导览有当前版本新增的步骤', () => {
    for (const [id, tour] of tours) {
      expect(new Set(tour.steps.map(step => step.title)).size, id).toBe(tour.steps.length)
      expect(Math.max(...tour.steps.map(step => step.since ?? 1)), id).toBeLessThanOrEqual(tour.version)
      if (tour.version > 1) expect(tour.steps.some(step => step.since === tour.version), id).toBe(true)
    }
  })
})
