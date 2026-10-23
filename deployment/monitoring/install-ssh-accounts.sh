#!/usr/bin/env bash
set -euo pipefail
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 2 ]; then echo 'Usage: sudo install-ssh-accounts.sh windows.pub github.pub' >&2; exit 1; fi
for key in "$1" "$2"; do
  test "$(wc -l < "$key")" -le 1
  grep -Eq '^ssh-ed25519 [A-Za-z0-9+/=]+( .*)?$' "$key"
done
for name in windows github; do
  account="yumpoo-monitor-$name"
  if id "$account" >/dev/null 2>&1; then
    test "$(getent passwd "$account" | cut -d: -f5)" = 'Yumpoo monitor forwarding'
  else
    useradd --create-home --shell /usr/sbin/nologin --comment 'Yumpoo monitor forwarding' "$account"
  fi
  home="$(getent passwd "$account" | cut -d: -f6)"
  test "$home" = "/home/$account"
  install -d -o "$account" -g "$account" -m 700 "$home/.ssh"
  file="$1"; options='restrict,port-forwarding,permitopen="127.0.0.1:8788",permitlisten="127.0.0.1:19100"'
  if [ "$name" = github ]; then file="$2"; options='restrict,port-forwarding,permitopen="127.0.0.1:8788"'; fi
  printf '%s %s\n' "$options" "$(cat "$file")" > "$home/.ssh/authorized_keys"
  chown "$account:$account" "$home/.ssh/authorized_keys"
  chmod 600 "$home/.ssh/authorized_keys"
done
target=/etc/ssh/sshd_config.d/70-yumpoo-monitor.conf
test ! -e "$target" || cp "$target" "$target.previous"
cp "$(dirname "$0")/ssh-forwarding.conf" "$target"
if ! /usr/sbin/sshd -t; then
  if [ -e "$target.previous" ]; then mv "$target.previous" "$target"; else rm "$target"; fi
  exit 1
fi
systemctl reload ssh
echo 'Restricted forwarding accounts installed; shell sessions and administrator ports are denied.'
