// happy-dom 的 Element.prototype.toString() 返回 outerHTML，浏览器返回 "[object HTMLDivElement]"。
// Popper 每次定位都对参考元素与滚动祖先调用 toString() 判断是否为 window，弹层多的组件因此反复序列化整棵子树。
Object.defineProperty(Element.prototype, 'toString', {
  configurable: true,
  writable: true,
  value: Node.prototype.toString,
})
