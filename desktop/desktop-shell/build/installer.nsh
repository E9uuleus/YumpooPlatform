; Yumpoo Desktop NSIS hooks. electron-builder does not register URL protocols on Windows,
; so the installer owns the per-user yumpoo: key and removes it on a real uninstall.

!macro customInstall
  WriteRegStr HKCU "Software\Classes\yumpoo" "" "URL:yumpoo"
  WriteRegStr HKCU "Software\Classes\yumpoo" "URL Protocol" ""
  WriteRegStr HKCU "Software\Classes\yumpoo\shell\open\command" "" '"$INSTDIR\${APP_EXECUTABLE_FILENAME}" "%1"'
  ; Kept ASCII so the shortcut name does not depend on how NSIS decodes this include.
  CreateShortCut "$SMPROGRAMS\Yumpoo Desktop (Safe Mode).lnk" "$INSTDIR\${APP_EXECUTABLE_FILENAME}" "--safe-mode" "$INSTDIR\${APP_EXECUTABLE_FILENAME}" 0
!macroend

; Updates run the old uninstaller with --updated and keep everything. A real uninstall signs the
; user out (auth\ holds the only persisted session) and clears Chromium runtime data, keeping only
; the two preference files.
!macro customUnInstall
  ${ifNot} ${isUpdated}
    DeleteRegKey HKCU "Software\Classes\yumpoo"
    Delete "$SMPROGRAMS\Yumpoo Desktop (Safe Mode).lnk"
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
