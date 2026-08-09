import tsPlugin from '@typescript-eslint/eslint-plugin'
import tsParser from '@typescript-eslint/parser'
import vuePlugin from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'
import workspacePlugin from './tools/architecture/workspace-boundaries.mjs'

const typescriptFiles = [
  'frontend/**/*.ts',
  'desktop/**/*.ts',
  'packages/**/*.ts',
]

const workspaceFiles = [
  'frontend/**/*.{ts,vue}',
  'desktop/**/*.ts',
  'packages/**/*.ts',
]

export default [
  {
    ignores: [
      '**/node_modules/**',
      '**/dist/**',
      '**/coverage/**',
      'backend/**',
      'docs/**',
    ],
  },
  ...vuePlugin.configs['flat/recommended'],
  {
    files: typescriptFiles,
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
  {
    files: ['frontend/**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: tsParser,
        ecmaVersion: 'latest',
        sourceType: 'module',
        extraFileExtensions: ['.vue'],
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      'vue/multi-word-component-names': 'off',
    },
  },
  {
    files: workspaceFiles,
    plugins: {
      yumpoo: workspacePlugin,
    },
    rules: {
      'yumpoo/workspace-boundaries': 'error',
    },
  },
  {
    files: ['frontend/**/*.{ts,vue}'],
    rules: {
      'no-restricted-globals': [
        'error',
        'process',
        'require',
        'module',
        '__dirname',
        '__filename',
      ],
    },
  },
]
