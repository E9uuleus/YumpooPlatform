import hashlib, json, pathlib, subprocess, sys

directory = pathlib.Path(sys.argv[1]).resolve()
expected_commit = sys.argv[2]
lock = json.loads((directory / 'images.lock.json').read_text())
if lock['schemaVersion'] != 1 or lock['commit'] != expected_commit:
    raise ValueError('Offline bundle source commit mismatch')
archive = directory / 'images.tar.gz'
with archive.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
if digest != lock['archiveSha256']:
    raise ValueError('Offline image archive checksum mismatch')
compose_source = (pathlib.Path(__file__).parent / 'compose.yml').read_text()
for name, image in lock['images'].items():
    if name != 'hub' and image['source'] not in compose_source:
        raise ValueError('Offline image is not pinned by this source checkout')
    if name == 'hub' and image['source'] != 'yumpoo-monitor-hub:' + expected_commit:
        raise ValueError('Hub source mismatch')
subprocess.run(['docker', 'load', '-i', str(archive)], check=True)
for image in lock['images'].values():
    actual = subprocess.check_output(['docker', 'image', 'inspect', image['tag'], '--format', '{{.Id}}'], text=True).strip()
    if actual != image['id']:
        raise ValueError('Loaded image identity mismatch')
print('Offline archive and every loaded image identity verified.')
