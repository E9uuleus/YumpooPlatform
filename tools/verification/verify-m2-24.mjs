process.env.YUMPOO_CI_BASE_REF ||= process.env.YUMPOO_M224_BASE_REF || 'origin/dev'
await import('../ci/run.mjs')
