import { describe, expect, it } from 'vitest'
import { CAPSULE_WIDTH, compactVisual, dockShape, hitTest, MENU, orbDetails, orbShape, placeDock, placeMenu, placeOrb, placePanel, snapDock, stadiumRows } from '../src/main/timer-geometry'

const area = { x: 0, y: 0, width: 1280, height: 800 }

describe('timer surface geometry', () => {
  it('draws a symmetric circle and extends it into a pill with rounded ends', () => {
    const circle = stadiumRows(10, 0, 84, 84)
    expect(circle).toHaveLength(84)
    for (let y = 0; y < 42; y++) expect(circle[83 - y]).toMatchObject({ x: circle[y]!.x, width: circle[y]!.width })
    circle.forEach((row, y) => {
      const half = Math.sqrt(Math.max(0, 42 ** 2 - (y + .5 - 42) ** 2))
      expect(row).toEqual({ x: Math.floor(42 - half) + 10, y, width: Math.max(1, Math.ceil(half * 2)), height: 1 })
    })
    const pill = stadiumRows(0, 5, 300, 84)
    expect(pill[42]).toEqual({ x: 0, y: 47, width: 300, height: 1 })
    expect(pill[0]!.x).toBe(circle[0]!.x - 10)
    expect(Math.abs(pill[0]!.x + pill[0]!.width - (300 - pill[0]!.x))).toBeLessThanOrEqual(1)
  })

  it('reserves capsule room on both sides without leaving the work area for every orb size', () => {
    for (const size of [56, 64, 80]) {
      for (const circleX of [0, 600, 1280 - size]) {
        const { bounds, layout } = placeOrb(size, circleX, 400, area)
        expect(bounds.x).toBeGreaterThanOrEqual(0)
        expect(bounds.x + bounds.width).toBeLessThanOrEqual(1280)
        expect(layout.canvas!.width - layout.canvas!.left - size - 10).toBeLessThanOrEqual(CAPSULE_WIDTH)
        const open = orbDetails(layout, true)
        expect(open.detailWidth).toBeGreaterThan(0)
        expect(open.detailWidth).toBeLessThanOrEqual(CAPSULE_WIDTH)
        const rows = orbShape(open)
        expect(Math.min(...rows.map(row => row.x))).toBeGreaterThanOrEqual(0)
        expect(Math.max(...rows.map(row => row.x + row.width))).toBeLessThanOrEqual(bounds.width)
      }
    }
    const edge = placeOrb(64, 0, 400, area)
    expect(orbDetails(edge.layout, true)).toMatchObject({ side: 'right', detailWidth: 216 })
    expect(orbDetails(orbDetails(edge.layout, true), false)).toMatchObject({ side: null, detailWidth: 0 })
  })

  it('shrinks the orb on a tiny display and keeps it on a negative-origin monitor', () => {
    const tiny = { x: -300, y: -200, width: 70, height: 70 }
    const { bounds, layout } = placeOrb(80, -290, -140, tiny)
    expect(layout.size).toBe(50)
    expect(bounds).toEqual({ x: -300, y: -200, width: 70, height: 70 })
    expect(orbDetails(layout, true)).toMatchObject({ side: null, detailWidth: 0 })
  })

  it('docks flush to either edge, clamps vertically and snaps by the tab after a drag', () => {
    const right = placeDock('right', .5, area)
    expect(right.bounds).toEqual({ x: 982, y: 314, width: 298, height: 172 })
    expect(dockShape(right.layout)).toEqual([{ x: 256, y: 28, width: 42, height: 116 }])
    expect(dockShape({ ...right.layout, dock: { side: 'right', expanded: true } })).toEqual([{ x: 0, y: 0, width: 298, height: 172 }])
    const left = placeDock('left', 0, area)
    expect(left.bounds).toMatchObject({ x: 0, y: 0 })
    expect(dockShape(left.layout)[0]).toMatchObject({ x: 0, width: 42 })
    expect(placeDock('left', 1, area).bounds.y).toBe(628)
    expect(snapDock({ x: 700, y: 100, width: 298, height: 172 }, 'right', area)).toEqual({ side: 'right', dockY: 186 / 800 })
    expect(snapDock({ x: 300, y: 100, width: 298, height: 172 }, 'right', area).side).toBe('left')
    expect(snapDock({ x: 700, y: 100, width: 298, height: 172 }, 'left', area).side).toBe('right')
    expect(snapDock({ x: 0, y: 900, width: 298, height: 172 }, 'left', area).dockY).toBe(714 / 800)
    expect(compactVisual(right.bounds, right.layout)).toEqual({ x: 1248, y: 352, width: 32, height: 96 })
  })

  it('opens the work panel beside the compact surface', () => {
    expect(placePanel({ x: 1192, y: 712, width: 64, height: 64 }, area)).toEqual({ x: 884, y: 284, width: 384, height: 504 })
    expect(placePanel({ x: 1248, y: 352, width: 32, height: 96 }, area, 'right')).toEqual({ x: 896, y: 148, width: 384, height: 504 })
    expect(placePanel({ x: 0, y: 700, width: 32, height: 96 }, area, 'left')).toEqual({ x: 0, y: 296, width: 384, height: 504 })
  })

  it('hit-tests the native shape rows', () => {
    const rows = stadiumRows(0, 0, 84, 84)
    expect(hitTest(rows, 42, 42)).toBe(true)
    expect(hitTest(rows, 1, 1)).toBe(false)
    expect(hitTest([], 0, 0)).toBe(false)
  })

  it('opens tray menus against the taskbar edge and surface menus at the pointer', () => {
    const bounds = { x: 0, y: 0, width: 1280, height: 800 }
    const place = (workArea: typeof bounds, cursor = { x: 1200, y: 780 }) => placeMenu('tray', cursor, { bounds, workArea })
    expect(place({ x: 0, y: 0, width: 1280, height: 752 })).toEqual({ x: 1280 - MENU.width, y: 752 - MENU.height, ...MENU })
    expect(place({ x: 0, y: 48, width: 1280, height: 752 }, { x: 600, y: 20 })).toMatchObject({ x: 600 - MENU.width / 2, y: 48 })
    expect(place({ x: 48, y: 0, width: 1232, height: 800 }, { x: 20, y: 400 })).toMatchObject({ x: 48, y: 400 - MENU.height / 2 })
    expect(place({ x: 0, y: 0, width: 1232, height: 800 }, { x: 1250, y: 790 })).toMatchObject({ x: 1232 - MENU.width, y: 800 - MENU.height })
    expect(place(bounds, { x: 640, y: 799 })).toMatchObject({ y: 800 - MENU.height })
    const work = { x: 0, y: 0, width: 1280, height: 752 }
    expect(placeMenu('pointer', { x: 100, y: 100 }, { bounds, workArea: work })).toMatchObject({ x: 90, y: 90 })
    expect(placeMenu('pointer', { x: 1250, y: 700 }, { bounds, workArea: work })).toMatchObject({ x: 1250 - MENU.width + 10, y: 700 - MENU.height + 10 })
  })
})
