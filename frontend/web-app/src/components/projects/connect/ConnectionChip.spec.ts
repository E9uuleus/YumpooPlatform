import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import ConnectionChip from './ConnectionChip.vue'
import { targetCard } from './connectTestFixtures'

describe('连接 chip', () => {
  it('单目标仅标题、多目标带项目，归档灰显且点击不冒泡', async () => {
    const clicked = vi.fn()
    const parent = mount(defineComponent({ components: { ConnectionChip }, setup: () => ({ targetCard, clicked }), template: '<div @click="clicked"><connection-chip :card="targetCard" :show-project="false" /></div>' }))
    const chip = parent.findComponent(ConnectionChip)
    expect(chip.text()).toBe(targetCard.title)
    await chip.get('button').trigger('click'); expect(clicked).not.toHaveBeenCalled()
    expect(chip.emitted('click')).toHaveLength(1); parent.unmount()
    const archived = mount(ConnectionChip, { props: { card: { ...targetCard, archived: true }, showProject: true } })
    expect(archived.text()).toBe(`Yumpoo 门户 · ${targetCard.title}`)
    expect(archived.classes()).toContain('connection-chip--archived')
    expect(archived.get('.connection-chip__dot').attributes('style')).toContain('var(--yp-label-orange)')
    archived.unmount()
  })
})
