; Yumpoo Desktop NSIS hooks. electron-builder does not register URL protocols on Windows,
; so the installer owns the per-user yumpoo: key and removes it on a real uninstall.

!macro customInstall
  WriteRegStr HKCU "Software\Classes\yumpoo" "" "URL:yumpoo"
  WriteRegStr HKCU "Software\Classes\yumpoo" "URL Protocol" ""
  WriteRegStr HKCU "Software\Classes\yumpoo\shell\open\command" "" '"$INSTDIR\${APP_EXECUTABLE_FILENAME}" "%1"'
!macroend

; Updates run the old uninstaller with --updated and keep everything. A real uninstall signs the
; user out: the encrypted credential store and Chromium's persistent cookie store both hold the session,
; so everything in userData goes except the two preference files.
!macro customUnInstall
  ${ifNot} ${isUpdated}
    DeleteRegKey HKCU "Software\Classes\yumpoo"
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
