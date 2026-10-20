; Yumpoo Desktop NSIS hooks. electron-builder does not register URL protocols on Windows,
; so the installer owns the per-user yumpoo: key and removes it on a real uninstall.
; Saved as UTF-8 with BOM: without it makensis decodes the Chinese page text with the build machine's ANSI code page.

; Per-user only: skips the "for all users" page, so neither installing nor updating ever asks for administrator rights.
!macro customInstallMode
  StrCpy $isForceCurrentInstall "1"
!macroend

!ifndef BUILD_UNINSTALLER
  ; Consumed by the next MUI page, the license page, which shows the install notice rather than a licence.
  !define MUI_PAGE_HEADER_TEXT "安装须知与隐私说明"
  !define MUI_PAGE_HEADER_SUBTEXT "安装 Yumpoo Desktop 之前，请阅读以下内容。"
  !define MUI_LICENSEPAGE_TEXT_BOTTOM "阅读并接受以上内容后，单击「我同意(I)」继续安装。"

  ; Stays empty unless the options page ran, so silent installs and updates keep the desktop shortcut.
  Var desktopShortcutChoice
  Var desktopShortcutCheckbox
  Var installDirWritable
!endif

!macro customPageAfterChangeDir
  Page custom installOptionsPagePre installOptionsPageLeave

  Function installOptionsPagePre
    ${if} ${isUpdated}
      Abort
    ${endIf}
    ; instFilesPre appends the application folder only later; apply it now so the probe tests the real target.
    ${StrContains} $0 "${APP_FILENAME}" $INSTDIR
    ${if} $0 == ""
      StrCpy $INSTDIR "$INSTDIR\${APP_FILENAME}"
    ${endIf}

    StrCpy $installDirWritable "0"
    StrCpy $1 "0"
    ${if} ${FileExists} "$INSTDIR\*.*"
      StrCpy $1 "1"
    ${endIf}
    ClearErrors
    CreateDirectory "$INSTDIR"
    FileOpen $2 "$INSTDIR\.yumpoo-write-probe" w
    ${ifNot} ${Errors}
      FileClose $2
      Delete "$INSTDIR\.yumpoo-write-probe"
      StrCpy $installDirWritable "1"
    ${endIf}
    ${if} $1 == "0"
      RMDir "$INSTDIR"
    ${endIf}

    !insertmacro MUI_HEADER_TEXT "附加选项" "确认安装位置并选择附加任务。"
    nsDialogs::Create 1018
    Pop $0
    ${if} $installDirWritable == "1"
      ${NSD_CreateLabel} 0u 0u 300u 24u "将安装到：$\r$\n$INSTDIR"
      Pop $0
      ${NSD_CreateCheckbox} 0u 34u 300u 12u "创建桌面快捷方式(&D)"
      Pop $desktopShortcutCheckbox
      ${if} $desktopShortcutChoice != "0"
        ${NSD_Check} $desktopShortcutCheckbox
      ${endIf}
      ${NSD_CreateLabel} 0u 54u 300u 36u "开始菜单中的「Yumpoo Desktop」与「Yumpoo Desktop (Safe Mode)」快捷方式始终创建，用于启动、系统通知和故障自救。"
      Pop $0
    ${else}
      ${NSD_CreateLabel} 0u 0u 300u 80u "当前用户没有所选目录的写入权限：$\r$\n$INSTDIR$\r$\n$\r$\nYumpoo Desktop 只为当前用户安装，不使用管理员权限。请点击「上一步」，改选当前用户可写入的目录，例如默认目录：$\r$\n$LOCALAPPDATA\Programs\${APP_FILENAME}"
      Pop $0
      GetDlgItem $0 $HWNDPARENT 1
      EnableWindow $0 0
    ${endIf}
    nsDialogs::Show
  FunctionEnd

  Function installOptionsPageLeave
    ${if} $installDirWritable != "1"
      Abort
    ${endIf}
    ${NSD_GetState} $desktopShortcutCheckbox $0
    ${if} $0 == ${BST_CHECKED}
      StrCpy $desktopShortcutChoice "1"
    ${else}
      StrCpy $desktopShortcutChoice "0"
    ${endIf}
  FunctionEnd
!macroend

!macro customInstall
  WriteRegStr HKCU "Software\Classes\yumpoo" "" "URL:yumpoo"
  WriteRegStr HKCU "Software\Classes\yumpoo" "URL Protocol" ""
  WriteRegStr HKCU "Software\Classes\yumpoo\shell\open\command" "" '"$INSTDIR\${APP_EXECUTABLE_FILENAME}" "%1"'
  ; Kept ASCII so the shortcut name stays stable however NSIS decodes this include.
  CreateShortCut "$SMPROGRAMS\Yumpoo Desktop (Safe Mode).lnk" "$INSTDIR\${APP_EXECUTABLE_FILENAME}" "--safe-mode" "$INSTDIR\${APP_EXECUTABLE_FILENAME}" 0
  ; Unticked on the options page: remove the desktop shortcut electron-builder just created. Updates keep
  ; shortcuts the person deleted deleted, so it stays gone; the Start menu shortcut carries the AUMID and stays.
  ${if} $desktopShortcutChoice == "0"
    Delete "$DESKTOP\${SHORTCUT_NAME}.lnk"
    System::Call 'Shell32::SHChangeNotify(i 0x8000000, i 0, i 0, i 0)'
  ${endIf}
!macroend

; Updates run the old uninstaller with --updated and keep everything. A real uninstall signs the
; user out (auth\ holds the only persisted session) and clears Chromium runtime data, keeping only
; the preference files.
!macro customUnInstall
  ${ifNot} ${isUpdated}
    DeleteRegKey HKCU "Software\Classes\yumpoo"
    ; "Start at sign-in" registers this value (named after the AppUserModelID); updates keep it, uninstall removes it.
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "com.yumpoo.desktop"
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run" "com.yumpoo.desktop"
    ClearErrors
    Delete "$SMPROGRAMS\Yumpoo Desktop (Safe Mode).lnk"
    StrCpy $R7 "deleted"
    ${if} ${Errors}
      StrCpy $R7 "delete failed"
    ${endif}
    ReadEnvStr $R9 "YUMPOO_INSTALLER_TRACE"
    ${if} $R9 != ""
      FileOpen $R8 "$R9" a
      FileWrite $R8 "$CMDLINE | $SMPROGRAMS | $R7$\r$\n"
      FileClose $R8
    ${endif}
    ; The installer keeps a copy of itself here as the base for differential updates (about 100 MB).
    RMDir /r "$LOCALAPPDATA\yumpoo-desktop-updater"
    StrCpy $R9 "$APPDATA\Yumpoo Desktop"
    ${if} ${FileExists} "$R9\*.*"
      FindFirst $R0 $R1 "$R9\*"
      ${DoUntil} $R1 == ""
        ${if} $R1 != "."
        ${andIf} $R1 != ".."
        ${andIf} $R1 != "timer-preferences.json"
        ${andIf} $R1 != "inbox-preferences.json"
        ${andIf} $R1 != "desktop-preferences.json"
          ${if} ${FileExists} "$R9\$R1\*.*"
            RMDir /r "$R9\$R1"
          ${else}
            Delete "$R9\$R1"
          ${endIf}
        ${endIf}
        FindNext $R0 $R1
      ${Loop}
      FindClose $R0
    ${endIf}
  ${endIf}
!macroend
