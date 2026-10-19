import { afterEach, describe, expect, it } from 'vitest'
import { dismissBootSplash } from './boot-splash'

afterEach(() => { document.body.innerHTML = '' })

describe('启动页', () => {
  it('首屏就绪后移除，重复调用无害', () => {
    document.body.innerHTML = '<div id="boot-splash"></div><div id="app"></div>'
    dismissBootSplash()
    dismissBootSplash()
    expect(document.getElementById('boot-splash')).toBeNull()
    expect(document.getElementById('app')).not.toBeNull()
  })
})
