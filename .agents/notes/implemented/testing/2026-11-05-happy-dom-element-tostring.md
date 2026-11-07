# Agent Note: Web 单测恢复浏览器的 Element.toString 语义

Status: implemented

## Problem

happy-dom 的 `Element.prototype.toString()` 返回 `outerHTML`，浏览器返回 `[object HTMLDivElement]` 这类类型标签。Element Plus 的提示、弹层和下拉菜单基于 Popper，Popper 每次定位都对参考元素、弹层及其滚动祖先调用 `toString()` 判断对象是否为 `window`，于是每次定位都序列化这些元素的完整子树。带大量单元格弹层的工作项表格测试因此约四分之三的 CPU 花在 HTML 序列化上，单个 spec 决定了整个 Web 回归的墙钟时间，本机高并发运行时还会撞到单用例超时。

## Decision

[happyDomCompat.ts](../../../../frontend/web-app/src/testing/happyDomCompat.ts) 作为 Web 单测的 `setupFiles`，把 `Element.prototype.toString` 设为 happy-dom 自带的 `Node.prototype.toString`，返回与浏览器一致的 `[object <构造器名>]`。[happyDomCompat.spec.ts](../../../../frontend/web-app/src/testing/happyDomCompat.spec.ts) 锁定该语义。DOM 环境的其余行为不变，弹层组件照常真实渲染。

## Alternatives considered

- 拆分慢 spec 让 Vitest 并行：总 CPU 不变；CI 只有 3 个 worker，其余文件已占满另外两个 worker，墙钟收益有限。
- 在相关 spec 中 stub 提示、弹层或 Popper：需要逐文件配置，并失去弹层交互的覆盖。
- 改用 jsdom：该方法语义正确，但 180 个 spec 要整体迁移并处理两种环境的差异。
- 用 `pnpm patch` 修改 happy-dom 或 Popper：每次升级都要重做补丁，且补丁对评审不可见。

## Consequences

单测中 `String(element)` 不再得到 HTML；需要 HTML 时使用 `outerHTML` 或 `wrapper.html()`。升级 happy-dom 时，如果其 `Element.prototype` 不再自带返回 HTML 的 `toString`，删除兼容层、守护用例与 `setupFiles` 配置，并归档本 Note。
