const fs = require('node:fs')
const path = require('node:path')

// Packaging reuses the exact Electron runtime installed from the lockfile instead of downloading a second copy.
const electronDist = path.dirname(require('electron'))
const electronVersion = require('electron/package.json').version
const desktopPackage = require('./package.json')
if (desktopPackage.devDependencies.electron !== electronVersion) {
  throw new Error('Electron 安装版本与 desktop package.json 精确锁定值不一致')
}
// Packaging follows the distribution the build step validated and baked into the app.
const distribution = JSON.parse(fs.readFileSync(path.join(__dirname, 'dist', 'main', 'distribution.json'), 'utf8'))
// Only update end-to-end test builds (generated distribution file) may stamp another version; releases use package.json.
const versionOverride = process.env.YUMPOO_DESKTOP_TEST_VERSION
if (versionOverride && !process.env.YUMPOO_DISTRIBUTION_FILE) {
  throw new Error('YUMPOO_DESKTOP_TEST_VERSION 只能与测试分发配置一起使用')
}

/** @type {import('electron-builder').Configuration} */
module.exports = {
  // The AppUserModelID doubles as the NSIS install identity; changing it would orphan existing installations.
  appId: 'com.yumpoo.desktop',
  productName: 'Yumpoo Desktop',
  executableName: 'YumpooDesktop',
  copyright: 'Copyright © Yumpoo',
  electronDist,
  electronVersion,
  // NSIS derives the per-user install directory from the package name; the scoped workspace name is not a folder name.
  extraMetadata: { name: 'yumpoo-desktop', ...(versionOverride ? { version: versionOverride } : {}) },
  directories: {
    output: process.env.YUMPOO_M015_OUTPUT_ROOT
      ? path.resolve(process.env.YUMPOO_M015_OUTPUT_ROOT)
      : path.join(__dirname, 'out', 'builder'),
    buildResources: 'build',
  },
  files: [
    'dist/main/**/*',
    'dist/preload/**/*',
    'dist/diagnostics/**/*',
    'assets/application.png',
    'package.json',
    '!**/*.map',
  ],
  asar: true,
  // electronDist is copied verbatim; the stock default app never ships even though OnlyLoadAppFromAsar ignores it.
  afterPack: context => {
    fs.rmSync(path.join(context.appOutDir, 'resources', 'default_app.asar'), { force: true })
  },
  npmRebuild: false,
  nodeGypRebuild: false,
  buildDependenciesFromSource: false,
  electronFuses: {
    runAsNode: false,
    enableCookieEncryption: true,
    enableNodeOptionsEnvironmentVariable: false,
    enableNodeCliInspectArguments: false,
    enableEmbeddedAsarIntegrityValidation: true,
    onlyLoadAppFromAsar: true,
    grantFileProtocolExtraPrivileges: false,
  },
  win: {
    target: [{ target: 'nsis', arch: ['x64'] }],
    icon: 'assets/application.ico',
    // No Authenticode certificate yet; resource editing (icon, version info) still runs.
    signAndEditExecutable: true,
    verifyUpdateCodeSignature: false,
  },
  nsis: {
    oneClick: true,
    perMachine: false,
    packElevateHelper: false,
    createDesktopShortcut: true,
    createStartMenuShortcut: true,
    shortcutName: 'Yumpoo Desktop',
    uninstallDisplayName: 'Yumpoo Desktop',
    deleteAppDataOnUninstall: false,
    runAfterFinish: true,
    include: 'build/installer.nsh',
    artifactName: 'YumpooDesktop-Setup-${version}-x64.${ext}',
  },
  // A generic feed makes electron-builder emit latest.yml and resources/app-update.yml (updater cache directory,
  // differential download base); nothing is uploaded because packaging always runs with --publish never.
  publish: distribution.update ? [{ provider: 'generic', url: distribution.update.feed }] : null,
}
