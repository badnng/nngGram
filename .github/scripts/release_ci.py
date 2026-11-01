"""Validate and publish stable native artifacts through an owned GitHub draft; no implicit retries."""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile


@dataclass(frozen=True)
class Asset:
    path: Path
    size: int
    sha256: str


def sha256_file(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def stable_version(tag):
    match = re.fullmatch(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', tag)
    if not match or any(len(part) > 5 for part in match.groups()):
        raise ValueError('Release requires a stable vMAJOR.MINOR.PATCH tag')
    parts = tuple(map(int, match.groups()))
    if parts[0] > 255 or parts[1] > 255 or parts[2] > 65535:
        raise ValueError('Tag version exceeds Windows MSI version ranges')
    return tag[1:]


def regular(path):
    entry = path.lstat()
    if not stat.S_ISREG(entry.st_mode) or getattr(entry, 'st_file_attributes', 0) & 0x400:
        raise ValueError('Release inputs must be regular files without links')
    return path


def safe_relative(name):
    if not isinstance(name, str) or not name or '\\' in name or ':' in name or any(ord(char) < 32 for char in name):
        return False
    path = PurePosixPath(name)
    return not path.is_absolute() and str(path) == name and all(part not in ('.', '..') for part in path.parts)


def verify_payload(archive_path, manifest_path):
    payload = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
    if not isinstance(payload, list) or not 0 < len(payload) <= 5000:
        raise ValueError('Invalid package payload inventory')
    expected = {}
    folded = set()
    total = 0
    for item in payload:
        if (not isinstance(item, dict) or set(item) != {'Relative', 'Length', 'Sha256'}
                or not safe_relative(item['Relative']) or type(item['Length']) is not int
                or not 0 <= item['Length'] <= 1_000_000_000
                or not re.fullmatch('[a-f0-9]{64}', str(item['Sha256']))):
            raise ValueError('Invalid package payload entry')
        name = 'nngGram/' + item['Relative']
        if name.casefold() in folded:
            raise ValueError('Duplicate package payload path')
        folded.add(name.casefold())
        expected[name] = item
        total += item['Length']
    if total > 4_000_000_000 or 'nngGram/nngGram.exe' not in expected:
        raise ValueError('Invalid or oversized native package')
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != set(expected):
            raise ValueError('ZIP and payload inventory do not match')
        for entry in archive.infolist():
            item = expected[entry.filename]
            if entry.file_size != item['Length']:
                raise ValueError('ZIP payload size mismatch')
            with archive.open(entry) as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            if digest != item['Sha256']:
                raise ValueError('ZIP payload hash mismatch')


def validate(directory, tag, commit):
    version = stable_version(tag)
    if not re.fullmatch('[a-f0-9]{40}', commit):
        raise ValueError('Invalid release commit')
    names = {f'nngGram-{version}-windows-x64.zip', f'nngGram-{version}-windows-x64.msi',
             'SHA256SUMS.txt', 'build-info.json', 'payload-sha256.json'}
    children = list(directory.iterdir())
    if {path.name for path in children} != names:
        raise ValueError('Release directory must contain exactly the five public distribution files')
    files = {path.name: regular(path) for path in children}
    if any(not 0 < path.stat().st_size <= 2_000_000_000 for path in files.values()):
        raise ValueError('Release input size is invalid')
    info = json.loads(files['build-info.json'].read_text(encoding='utf-8-sig'))
    if not isinstance(info, dict) or info.get('versionName') != version or info.get('commit') != commit or info.get('projectName') != 'nngGram':
        raise ValueError('Build metadata does not match the release tag and commit')
    assets = {name: Asset(path, path.stat().st_size, sha256_file(path)) for name, path in files.items()}
    if not isinstance(info.get('artifacts'), dict) or set(info['artifacts']) != {'zip', 'installer'}:
        raise ValueError('Build metadata must describe exactly the ZIP and MSI')
    packages = {}
    for kind, extension in (('zip', 'zip'), ('installer', 'msi')):
        item = info.get('artifacts', {}).get(kind, {})
        name = f'nngGram-{version}-windows-x64.{extension}'
        actual = assets[name]
        if (not isinstance(item, dict) or item.get('file') != name or item.get('name') != name or type(item.get('bytes')) is not int
                or item['bytes'] != actual.size or item.get('sha256') != actual.sha256):
            raise ValueError('Package metadata size/hash/path mismatch')
        packages[name] = actual.sha256
    checksums = {}
    for line in files['SHA256SUMS.txt'].read_text(encoding='utf-8-sig').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9_.-]+)', line)
        if not match or match[2] in checksums:
            raise ValueError('Invalid or duplicate release checksum')
        checksums[match[2]] = match[1]
    if checksums != packages:
        raise ValueError('Both ZIP and MSI checksums must match')
    with files[f'nngGram-{version}-windows-x64.msi'].open('rb') as stream:
        if stream.read(8) != bytes.fromhex('d0cf11e0a1b11ae1'):
            raise ValueError('Installer is not a Windows MSI compound file')
    verify_payload(files[f'nngGram-{version}-windows-x64.zip'], files['payload-sha256.json'])
    return assets


class APIError(RuntimeError):
    def __init__(self, status):
        self.status = status
        super().__init__(f'GitHub release API failed (HTTP {status}); draft remains unpublished')


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) or any(part in ('.', '..') for part in repository.split('/')):
            raise ValueError('Invalid GitHub repository')
        if not token.strip():
            raise ValueError('GH_TOKEN is required')
        self.base = f'https://api.github.com/repos/{repository}'
        self.upload_base = f'https://uploads.github.com/repos/{repository}'
        self.token = token

    def request(self, method, path, payload=None, asset=None):
        url = self.upload_base + path if asset else self.base + path
        headers = {'Authorization': f'Bearer {self.token}', 'Accept': 'application/vnd.github+json',
                   'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'nngGram-release-ci'}
        body = None
        if payload is not None:
            body = json.dumps(payload).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        stream = None
        if asset:
            stream = regular(asset.path).open('rb')
            body = stream
            headers['Content-Type'] = 'application/octet-stream'
            headers['Content-Length'] = str(asset.size)
        try:
            request = urllib.request.Request(url, data=body, method=method, headers=headers)
            # GitHub JSON/upload endpoints must not redirect an authenticated request to an arbitrary host.
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *args, **kwargs):
                    return None
            opener = urllib.request.build_opener(NoRedirect())
            with opener.open(request, timeout=1200 if asset else 60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise APIError(error.code) from None
        except (OSError, ValueError):
            raise RuntimeError('GitHub release request failed; inspect the draft before retrying') from None
        finally:
            if stream:
                stream.close()

    def pages(self, path):
        result = []
        for page in range(1, 101):
            values = self.request('GET', f'{path}?per_page=100&page={page}')
            if not isinstance(values, list):
                raise ValueError('Invalid GitHub paginated response')
            result.extend(values)
            if len(values) < 100:
                return result
        raise ValueError('GitHub pagination exceeded the bounded release limit')


def body_for(tag, commit, assets):
    fingerprint = hashlib.sha256(json.dumps({name: [asset.size, asset.sha256] for name, asset in sorted(assets.items())}, sort_keys=True).encode()).hexdigest()
    return f'nngGram Windows x64 {tag}\n\n<!-- nnggram-native-release:v1;tag={tag};commit={commit};assets={fingerprint} -->'


def verify_draft(release, tag, commit, body):
    if (type(release.get('id')) is not int or release['id'] <= 0 or release.get('draft') is not True
            or release.get('tag_name') != tag or release.get('target_commitish') != commit
            or release.get('body') != body or release.get('prerelease') is not False or release.get('name') != tag):
        raise ValueError('Existing release is published or is not this exact owned draft; refusing to overwrite')


def check_tag(api, tag, commit):
    if api.request('GET', '/commits/' + urllib.parse.quote(tag, safe='')).get('sha') != commit:
        raise ValueError('Remote tag moved or does not resolve to the tested commit')


def checked_assets(api, release_id, expected, complete=False):
    actual = api.pages(f'/releases/{release_id}/assets')
    found = {}
    for item in actual:
        name = item.get('name')
        if name not in expected or name in found:
            raise ValueError('Draft contains duplicate or unexpected assets')
        local = expected[name]
        if (item.get('state') != 'uploaded' or item.get('size') != local.size
                or item.get('digest') != 'sha256:' + local.sha256):
            raise ValueError('Remote release asset size/state/SHA256 mismatch; refusing to replace it')
        found[name] = item
    if complete and set(found) != set(expected):
        raise ValueError('Not all release assets were uploaded')
    return found


def publish(directory, api, tag, commit):
    assets = validate(directory, tag, commit)
    body = body_for(tag, commit, assets)
    check_tag(api, tag, commit)
    matches = [release for release in api.pages('/releases') if release.get('tag_name') == tag]
    if len(matches) > 1:
        raise ValueError('Multiple releases exist for this tag')
    if matches:
        release = matches[0]
    else:
        release = api.request('POST', '/releases', {'tag_name': tag, 'target_commitish': commit,
                              'name': tag, 'body': body, 'draft': True, 'prerelease': False, 'make_latest': 'false'})
    verify_draft(release, tag, commit, body)
    release_id = release['id']
    existing = checked_assets(api, release_id, assets)
    for name, asset in sorted(assets.items()):
        if name not in existing:
            if asset.path.stat().st_size != asset.size or sha256_file(asset.path) != asset.sha256:
                raise ValueError('Local release input changed during upload')
            api.request('POST', f'/releases/{release_id}/assets?name=' + urllib.parse.quote(name, safe=''), asset=asset)
    checked_assets(api, release_id, assets, complete=True)
    # A later rerun must not complete a draft after the tag or another actor has changed ownership/content.
    verify_draft(api.request('GET', f'/releases/{release_id}'), tag, commit, body)
    if assets != validate(directory, tag, commit):
        raise ValueError('Local release files changed before publication')
    check_tag(api, tag, commit)
    published = api.request('PATCH', f'/releases/{release_id}', {'draft': False, 'make_latest': 'false'})
    if published.get('draft') is not False or published.get('tag_name') != tag:
        raise ValueError('GitHub did not confirm publication; inspect the release manually')
    print(f'Published verified stable release {tag}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    env = os.environ
    if env.get('GITHUB_REF_TYPE') != 'tag':
        raise ValueError('Only an explicit stable tag run may publish a release')
    publish(args.directory, GitHub(env.get('GITHUB_REPOSITORY', ''), env.get('GH_TOKEN', '')),
            env.get('GITHUB_REF_NAME', ''), env.get('GITHUB_SHA', ''))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError, zipfile.BadZipFile) as error:
        print(f'Release failed: {error}', file=sys.stderr)
        sys.exit(1)
