import fs from 'node:fs'
import path from 'node:path'
import { builtinModules } from 'node:module'
import { fileURLToPath } from 'node:url'

const REPOSITORY_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)

const PACKAGE_ROOTS = {
  web: path.join(REPOSITORY_ROOT, 'frontend', 'web-app'),
  desktop: path.join(REPOSITORY_ROOT, 'desktop', 'desktop-shell'),
  contract: path.join(REPOSITORY_ROOT, 'packages', 'preload-contract'),
  apiClient: path.join(REPOSITORY_ROOT, 'packages', 'api-client'),
}

const DESKTOP_AREAS = {
  main: path.join(PACKAGE_ROOTS.desktop, 'src', 'main'),
  preload: path.join(PACKAGE_ROOTS.desktop, 'src', 'preload'),
}

const PRELOAD_ENTRY = path.join(DESKTOP_AREAS.preload, 'index.ts')
const PRELOAD_IPC_CHANNELS = new Map([
  ['invoke', new Set(['yumpoo:auth:is-enabled', 'yumpoo:auth:start'])],
  ['on', new Set(['yumpoo:auth:status'])],
  ['removeListener', new Set(['yumpoo:auth:status'])],
])

const NODE_MODULES = new Set(
  builtinModules.flatMap((moduleName) => [moduleName, `node:${moduleName}`]),
)

const WORKSPACE_PACKAGES = new Map([
  ['@yumpoo/web-app', 'web'],
  ['@yumpoo/desktop-shell', 'desktop'],
  ['@yumpoo/preload-contract', 'contract'],
  ['@yumpoo/api-client', 'apiClient'],
])

const DECLARED_PACKAGES = Object.fromEntries(
  Object.entries(PACKAGE_ROOTS).map(([owner, packageRoot]) => {
    const manifest = JSON.parse(
      fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8'),
    )
    return [
      owner,
      new Set([
        ...Object.keys(manifest.dependencies ?? {}),
        ...Object.keys(manifest.devDependencies ?? {}),
      ]),
    ]
  }),
)

function isInside(candidate, root) {
  const relative = path.relative(root, candidate)
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

function packageOwner(filename) {
  return Object.entries(PACKAGE_ROOTS).find(([, packageRoot]) =>
    isInside(filename, packageRoot),
  )?.[0]
}

function desktopArea(filename) {
  return Object.entries(DESKTOP_AREAS).find(([, areaRoot]) =>
    isInside(filename, areaRoot),
  )?.[0]
}

function workspaceTarget(specifier) {
  return [...WORKSPACE_PACKAGES.entries()].find(
    ([packageName]) =>
      specifier === packageName || specifier.startsWith(`${packageName}/`),
  )?.[1]
}

function isPathSpecifier(specifier) {
  return (
    specifier.startsWith('.') ||
    specifier.startsWith('/') ||
    specifier.startsWith('file:') ||
    path.isAbsolute(specifier)
  )
}

function resolvePathSpecifier(specifier, importer) {
  if (specifier.startsWith('file:')) {
    try {
      return fileURLToPath(specifier)
    } catch {
      return undefined
    }
  }
  return path.resolve(path.dirname(importer), specifier)
}

function externalPackageName(specifier) {
  const segments = specifier.split('/')
  return specifier.startsWith('@') ? segments.slice(0, 2).join('/') : segments[0]
}

function isTypeOnlyImport(node) {
  if (node.importKind === 'type' || node.exportKind === 'type') {
    return true
  }
  if (!Array.isArray(node.specifiers) || node.specifiers.length === 0) {
    return false
  }
  return node.specifiers.every(
    (specifier) =>
      specifier.importKind === 'type' || specifier.exportKind === 'type',
  )
}

function isAllowedPreloadElectronImport(node, filename) {
  return (
    filename === PRELOAD_ENTRY &&
    node.type === 'ImportDeclaration' &&
    node.specifiers.length > 0 &&
    node.specifiers.every(
      (specifier) =>
        specifier.type === 'ImportSpecifier' &&
        ['contextBridge', 'ipcRenderer'].includes(specifier.imported?.name) &&
        specifier.local?.name === specifier.imported?.name,
    )
  )
}

function createRule() {
  return {
    meta: {
      type: 'problem',
      docs: {
        description:
          'enforce Yumpoo Web, Electron, preload-contract and API client boundaries',
      },
      schema: [],
      messages: {
        forbidden: '{{reason}}（{{specifier}}）',
      },
    },
    create(context) {
      const filename = path.resolve(context.filename)
      const owner = packageOwner(filename)
      const area = desktopArea(filename)

      function report(node, specifier, reason) {
        context.report({
          node,
          messageId: 'forbidden',
          data: { reason, specifier },
        })
      }

      function rejectPathEscape(node, specifier) {
        if (!isPathSpecifier(specifier)) {
          return false
        }
        const resolvedTarget = resolvePathSpecifier(specifier, filename)
        const allowedRoot =
          owner === 'desktop' && area ? DESKTOP_AREAS[area] : PACKAGE_ROOTS[owner]
        if (!resolvedTarget || !allowedRoot || !isInside(resolvedTarget, allowedRoot)) {
          report(node, specifier, '相对、绝对或 file: 导入不得逃出当前代码边界')
          return true
        }
        return false
      }

      function rejectUndeclaredExternal(node, specifier) {
        if (
          isPathSpecifier(specifier) ||
          NODE_MODULES.has(specifier) ||
          specifier.startsWith('node:') ||
          workspaceTarget(specifier)
        ) {
          return false
        }
        const packageName = externalPackageName(specifier)
        if (!DECLARED_PACKAGES[owner]?.has(packageName)) {
          report(node, specifier, '不得通过未声明包或 alias 绕过工作区边界')
          return true
        }
        return false
      }

      function check(node, specifier) {
        if (typeof specifier !== 'string' || !owner) {
          return
        }
        if (rejectPathEscape(node, specifier)) {
          return
        }
        if (rejectUndeclaredExternal(node, specifier)) {
          return
        }

        const target = workspaceTarget(specifier)
        const nodeBuiltin = NODE_MODULES.has(specifier) || specifier.startsWith('node:')
        const electron = specifier === 'electron' || specifier.startsWith('electron/')
        const typeOnly = isTypeOnlyImport(node)

        if (owner === 'web') {
          if (nodeBuiltin || electron) {
            report(node, specifier, 'Web/renderer 不得依赖 Node 或 Electron')
          } else if (target === 'desktop') {
            report(node, specifier, 'Web/renderer 不得依赖 desktop-shell 实现')
          } else if (target === 'contract' && !typeOnly) {
            report(node, specifier, 'Web 只能以 type-only 方式依赖 preload-contract')
          }
          return
        }

        if (owner === 'contract') {
          if (!isPathSpecifier(specifier)) {
            report(node, specifier, 'preload-contract 只能依赖自身相对模块')
          }
          return
        }

        if (owner === 'apiClient') {
          if (nodeBuiltin || electron) {
            report(node, specifier, 'API client 不得依赖 Node 或 Electron')
          } else if (target && target !== 'apiClient') {
            report(node, specifier, 'API client 不得依赖其他工作区包')
          }
          return
        }

        if (owner === 'desktop' && area === 'preload') {
          if (nodeBuiltin) {
            report(node, specifier, 'preload 不得导入 Node built-in')
          } else if (
            target === 'web' ||
            target === 'desktop' ||
            target === 'apiClient'
          ) {
            report(
              node,
              specifier,
              'preload 不得依赖 Web、desktop 或 API client 运行时实现',
            )
          } else if (target === 'contract' && !typeOnly) {
            report(node, specifier, 'preload 只能以 type-only 方式依赖 preload-contract')
          } else if (electron && !isAllowedPreloadElectronImport(node, filename)) {
            report(
              node,
              specifier,
              'preload 仅允许入口命名导入 contextBridge 与受限 ipcRenderer',
            )
          }
          return
        }

        if (owner === 'desktop' && area === 'main') {
          if (target === 'web' || target === 'apiClient') {
            report(node, specifier, 'Electron main 不得导入 Web 或 API client 源码')
          } else if (target === 'contract' && !typeOnly) {
            report(node, specifier, 'Electron main 只能以 type-only 方式依赖 preload-contract')
          }
        }
      }

      function checkSource(node) {
        if (node.source) {
          check(node, node.source.value)
        }
      }

      function rejectDynamic(node, description) {
        report(node, '<dynamic>', `${description} 必须使用可静态分析的字符串字面量`)
      }

      function checkPreloadIpcCall(node) {
        if (owner !== 'desktop' || area !== 'preload') {
          return
        }
        const callee = node.callee
        if (
          callee?.type !== 'MemberExpression' ||
          callee.object?.type !== 'Identifier' ||
          callee.object.name !== 'ipcRenderer'
        ) {
          return
        }
        const method =
          !callee.computed && callee.property?.type === 'Identifier'
            ? callee.property.name
            : undefined
        const allowedChannels = method ? PRELOAD_IPC_CHANNELS.get(method) : undefined
        const channel = node.arguments?.[0]
        const channelName =
          channel?.type === 'Literal' && typeof channel.value === 'string'
            ? channel.value
            : undefined
        const expectedArguments = method === 'invoke' ? 1 : 2
        if (
          !allowedChannels?.has(channelName) ||
          node.arguments.length !== expectedArguments
        ) {
          report(
            node,
            channelName ?? '<dynamic>',
            'preload ipcRenderer 仅允许固定认证通道、固定方法与固定参数个数',
          )
        }
      }

      return {
        ImportDeclaration: checkSource,
        ExportNamedDeclaration: checkSource,
        ExportAllDeclaration: checkSource,
        ImportExpression(node) {
          if (node.source?.type === 'Literal' && typeof node.source.value === 'string') {
            check(node, node.source.value)
          } else {
            rejectDynamic(node, '动态 import')
          }
        },
        CallExpression(node) {
          checkPreloadIpcCall(node)
          if (node.callee?.type !== 'Identifier' || node.callee.name !== 'require') {
            return
          }
          const argument = node.arguments?.[0]
          if (argument?.type === 'Literal' && typeof argument.value === 'string') {
            check(node, argument.value)
          } else {
            rejectDynamic(node, '动态 require')
          }
        },
        TSImportEqualsDeclaration(node) {
          const expression = node.moduleReference?.expression
          if (expression?.type === 'Literal' && typeof expression.value === 'string') {
            check(node, expression.value)
          } else {
            rejectDynamic(node, 'TypeScript import equals')
          }
        },
        Identifier(node) {
          if (
            owner !== 'desktop' ||
            area !== 'preload' ||
            filename !== PRELOAD_ENTRY ||
            node.name !== 'ipcRenderer'
          ) {
            return
          }
          const parent = node.parent
          const importReference = parent?.type === 'ImportSpecifier'
          const calledMemberReference =
            parent?.type === 'MemberExpression' &&
            parent.object === node &&
            parent.parent?.type === 'CallExpression' &&
            parent.parent.callee === parent
          if (!importReference && !calledMemberReference) {
            report(
              node,
              'ipcRenderer',
              'preload 不得暴露、传递或保存原始 ipcRenderer',
            )
          }
        },
      }
    },
  }
}

export default {
  rules: {
    'workspace-boundaries': createRule(),
  },
}
