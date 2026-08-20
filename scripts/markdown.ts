import { fromMarkdown } from 'mdast-util-from-markdown'

export interface MarkdownPosition {
  start: { line: number }
  end: { line: number }
}

export interface MarkdownNode {
  type: string
  value?: string
  alt?: string
  depth?: number
  url?: string
  children?: MarkdownNode[]
  position?: MarkdownPosition
}

export interface MarkdownHeading {
  depth: number
  line: number
  raw: string
  text: string
}

export function parseMarkdown(source: string): MarkdownNode {
  return fromMarkdown(source) as MarkdownNode
}

export function visitMarkdown(node: MarkdownNode, visitor: (node: MarkdownNode) => boolean | void): void {
  if (visitor(node) === false) return
  for (const child of node.children ?? []) visitMarkdown(child, visitor)
}

function renderedText(node: MarkdownNode): string {
  if (node.type === 'text' || node.type === 'inlineCode') return node.value ?? ''
  if (node.type === 'image' || node.type === 'imageReference') return node.alt ?? ''
  if (node.type === 'break') return ' '
  return (node.children ?? []).map(renderedText).join('')
}

export function markdownHeadings(source: string): MarkdownHeading[] {
  const lines = source.split('\n')
  const headings: MarkdownHeading[] = []
  visitMarkdown(parseMarkdown(source), (node) => {
    if (node.type !== 'heading' || node.depth === undefined || node.position === undefined) return
    headings.push({
      depth: node.depth,
      line: node.position.start.line,
      raw: (lines[node.position.start.line - 1] ?? '').trimEnd(),
      text: renderedText(node),
    })
  })
  return headings
}

export function markdownProseLines(source: string): Array<{ line: number; raw: string }> {
  const lines = source.split('\n')
  const excluded = new Set<number>()
  visitMarkdown(parseMarkdown(source), (node) => {
    if (node.position === undefined) return
    const isCode = node.type === 'code'
    const isComment = node.type === 'html' && (node.value ?? '').includes('<!--')
    if (!isCode && !isComment) return
    for (let line = node.position.start.line; line <= node.position.end.line; line += 1) excluded.add(line)
  })
  return lines.flatMap((raw, index) => excluded.has(index + 1) ? [] : [{ line: index + 1, raw }])
}
