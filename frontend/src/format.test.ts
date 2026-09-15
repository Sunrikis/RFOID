import { describe, expect, it } from 'vitest'
import { validRegion } from './format'
describe('manual track calibration', () => {
  it('accepts a normalized convex quadrilateral in either direction', () => {
    expect(
      validRegion([
        [0.1, 0.1],
        [0.9, 0.1],
        [0.9, 0.9],
        [0.1, 0.9],
      ]),
    ).toBe(true)
    expect(
      validRegion([
        [0.1, 0.9],
        [0.9, 0.9],
        [0.9, 0.1],
        [0.1, 0.1],
      ]),
    ).toBe(true)
  })
  it('rejects incomplete, crossed, tiny and nonfinite regions', () => {
    expect(validRegion([[0.1, 0.1]])).toBe(false)
    expect(
      validRegion([
        [0.1, 0.1],
        [0.9, 0.9],
        [0.1, 0.9],
        [0.9, 0.1],
      ]),
    ).toBe(false)
    expect(
      validRegion([
        [0.1, 0.1],
        [0.101, 0.1],
        [0.101, 0.101],
        [0.1, 0.101],
      ]),
    ).toBe(false)
    expect(
      validRegion([
        [NaN, 0.1],
        [0.9, 0.1],
        [0.9, 0.9],
        [0.1, 0.9],
      ]),
    ).toBe(false)
  })
})
