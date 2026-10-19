/** Removes the static boot splash from index.html once the first route has rendered (or failed to). */
export function dismissBootSplash(root: Document = document): void {
  root.getElementById('boot-splash')?.remove()
}
