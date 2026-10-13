import { describe, expect, it } from 'vitest'
import { timeLogPlacement } from './timeLogPlacement'

describe('timeLogPlacement', () => {
  it('opens below when the panel fits', () => {
    expect(timeLogPlacement({ top: 80, bottom: 116 }, 900, 400)).toEqual({ placement: 'bottom', maxHeight: 758 })
  })

  it('opens above when only that side fits', () => {
    expect(timeLogPlacement({ top: 700, bottom: 736 }, 900, 400)).toEqual({ placement: 'top', maxHeight: 674 })
  })

  it('uses the larger side without exceeding the available room on short viewports', () => {
    expect(timeLogPlacement({ top: 340.5, bottom: 376.5 }, 700, 400)).toEqual({ placement: 'top', maxHeight: 314 })
    expect(timeLogPlacement({ top: 80, bottom: 116 }, 300, 400)).toEqual({ placement: 'bottom', maxHeight: 158 })
    expect(timeLogPlacement({ top: 80, bottom: 116 }, 200, 400)).toEqual({ placement: 'bottom', maxHeight: 58 })
    expect(timeLogPlacement({ top: 20, bottom: 56 }, 60, 400).maxHeight).toBe(0)
  })

  it('keeps a chosen side for form expansion but clamps its remaining space', () => {
    expect(timeLogPlacement({ top: 80, bottom: 116 }, 300, 580, 'top')).toEqual({ placement: 'top', maxHeight: 54 })
  })

  it('caps room at the viewport even if scrolling moves the anchor outside it', () => {
    expect(timeLogPlacement({ top: -100, bottom: -64 }, 300, 400)).toEqual({ placement: 'bottom', maxHeight: 274 })
    expect(timeLogPlacement({ top: 400, bottom: 436 }, 300, 400)).toEqual({ placement: 'top', maxHeight: 274 })
  })
})
