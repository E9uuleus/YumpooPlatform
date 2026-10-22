declare module '*.md' { const page: { html: string }; export default page }
declare module '*.md?meta' { const meta: import('./docsNav').DocMeta; export default meta }
declare module '*.md?search' { const index: import('./docsNav').DocSearchEntry; export default index }
