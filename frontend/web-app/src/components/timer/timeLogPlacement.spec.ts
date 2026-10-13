import { describe, expect, it } from 'vitest'
import { timeLogPlacement } from './timeLogPlacement'

describe('timeLogPlacement', () => {
  it('opens below when the panel fits', () => {
    expect(timeLogPlacement({ top: 80, bottom: 116 }, 900, 400)).toEqual({ placement: 'bottom', maxHeight: 758 })
  })

  it('opens above when only that side fits', () => {
    expect(timeLogPlacement({ top: 700, bottom: 736 }, 900, 400)).toEqual({ placement: 'top', maxHeight: 674 })
  })

  it('uses the larger side and clamps height with a minimum of 240', () => {
    expect(timeLogPlacement({ top: 340.5, bottom: 376.5 }, 700, 400)).toEqual({ placement: 'top', maxHeight: 314 })
    expect(timeLogPlacement({ top: 80, bottom: 116 }, 300, 400)).toEqual({ placement: 'bottom', maxHeight: 240 })
  })
})
