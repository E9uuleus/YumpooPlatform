const fs = require('node:fs')
const path = require('node:path')

// Packaging reuses the exact Electron runtime installed from the lockfile instead of downloading a second copy.
const electronDist = path.dirname(require('electron'))
const electronVersion = require('electron/package.json').version
const desktopPackage = require('./package.json')
if (desktopPackage.devDependencies.electron !== electronVersion) {
  throw new Error('Electron 安装版本与 desktop package.json 精确锁定值不一致')
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
  extraMetadata: { name: 'yumpoo-desktop' },
  directories: {
    output: process.env.YUMPOO_M015_OUTPUT_ROOT
      ? path.resolve(process.env.YUMPOO_M015_OUTPUT_ROOT)
      : path.join(__dirname, 'out', 'builder'),
    buildResources: 'build',
  },
  files: [
    'dist/main/**/*',
    'dist/preload/**/*',
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
  publish: null,
}
