const path = require('node:path')
const {
  cp,
  mkdir,
  mkdtemp,
  readFile,
  rename,
  rm,
  stat,
} = require('node:fs/promises')
const { pathToFileURL } = require('node:url')

const desktopOutputRoot = path.resolve(__dirname, 'out')
const outputRoot = process.env.YUMPOO_M015_OUTPUT_ROOT
  ? path.resolve(process.env.YUMPOO_M015_OUTPUT_ROOT)
  : desktopOutputRoot
const packageDirectory = path.resolve(outputRoot, 'Yumpoo Desktop-win32-x64')
const stagingPrefix = path.join(outputRoot, '.m0-15-app-')

void packageApplication().catch((error) => {
  console.error('M0-15 Windows x64 打包失败')
  console.error(error instanceof Error ? error.message : String(error))
  process.exitCode = 1
})

async function packageApplication() {
  assertSafeOutputPath()
  const desktopPackage = JSON.parse(
    await readFile(path.join(__dirname, 'package.json'), 'utf8'),
  )
  const electronPackage = require('electron/package.json')
  const expectedElectronVersion = desktopPackage?.devDependencies?.electron
  if (
    typeof expectedElectronVersion !== 'string' ||
    expectedElectronVersion !== electronPackage.version
  ) {
    throw new Error('Electron 安装版本与 desktop package.json 精确锁定值不一致')
  }

  const electronExecutable = require('electron')
  if (
    typeof electronExecutable !== 'string' ||
    path.basename(electronExecutable).toLowerCase() !== 'electron.exe' ||
    !(await stat(electronExecutable)).isFile()
  ) {
    throw new Error('未找到锁定 Electron 的 Windows x64 运行时')
  }

  const electronDist = path.dirname(electronExecutable)
  const defaultApp = path.join(electronDist, 'resources', 'default_app.asar')
  if (!(await stat(defaultApp)).isFile()) {
    throw new Error('Electron Windows 运行时缺少 default_app.asar')
  }

  await mkdir(outputRoot, { recursive: true })
  await rm(packageDirectory, { recursive: true, force: true })
  let stagingDirectory
  try {
    await cp(electronDist, packageDirectory, {
      recursive: true,
      dereference: false,
      errorOnExist: true,
      force: false,
    })
    await rename(
      path.join(packageDirectory, 'electron.exe'),
      path.join(packageDirectory, 'YumpooDesktop.exe'),
    )
    await rm(path.join(packageDirectory, 'resources', 'default_app.asar'))

    stagingDirectory = await mkdtemp(stagingPrefix)
    await mkdir(path.join(stagingDirectory, 'dist'), { recursive: true })
    await cp(
      path.join(__dirname, 'dist', 'main'),
      path.join(stagingDirectory, 'dist', 'main'),
      { recursive: true, errorOnExist: true, force: false },
    )
    await cp(
      path.join(__dirname, 'dist', 'preload'),
      path.join(stagingDirectory, 'dist', 'preload'),
      { recursive: true, errorOnExist: true, force: false },
    )
    await cp(
      path.join(__dirname, 'package.json'),
      path.join(stagingDirectory, 'package.json'),
      { errorOnExist: true, force: false },
    )

    const asarModuleUrl = pathToFileURL(
      path.join(__dirname, 'node_modules', '@electron', 'asar', 'lib', 'asar.js'),
    ).href
    const { createPackage } = await import(asarModuleUrl)
    await createPackage(
      stagingDirectory,
      path.join(packageDirectory, 'resources', 'app.asar'),
    )
  } catch (error) {
    await rm(packageDirectory, { recursive: true, force: true })
    throw error
  } finally {
    if (stagingDirectory) {
      await rm(stagingDirectory, { recursive: true, force: true })
    }
  }
}

function assertSafeOutputPath() {
  if (
    !(outputRoot === desktopOutputRoot || outputRoot.startsWith(`${desktopOutputRoot}${path.sep}`)) ||
    packageDirectory === outputRoot ||
    path.dirname(packageDirectory).toLowerCase() !== outputRoot.toLowerCase()
  ) {
    throw new Error('M0-15 打包输出路径越出 desktop out 目录')
  }
}
