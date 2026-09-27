import { ElOption as Option, ElTabs as Tabs } from 'element-plus'
import { createVNode, type FunctionalComponent } from 'vue'

// Element Plus 2.14.4 exposes prop descriptors under exactOptionalPropertyTypes.
// Keep the used public props checked without weakening the application's TypeScript settings.
type OptionProps = {
  value: string | number | boolean | Record<string, unknown>
  label?: string | number
  disabled?: boolean
}
export const ElOption: FunctionalComponent<OptionProps> = (props, { attrs, slots }) => createVNode(Option, { ...attrs, ...props }, slots)

type TabsProps = {
  modelValue: string | number
  'onUpdate:modelValue'?: (value: string | number) => void
}
export const ElTabs: FunctionalComponent<TabsProps> = (props, { attrs, slots }) => createVNode(Tabs, { ...attrs, ...props }, slots)
