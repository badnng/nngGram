"""Choose secretless checks, commit packaging, or a tested stable-tag release."""
import os
from pathlib import Path
import re


def valid_version(value):
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', value):
        raise ValueError('Use a stable numeric MAJOR.MINOR.PATCH version')
    major, minor, build = map(int, value.split('.'))
    if major > 255 or minor > 255 or build > 65535:
        raise ValueError('Version exceeds Windows MSI limits')
    return value


def base_version(root):
    matches = re.findall(r'^nnggramVersion=(.+)$', (root / 'gradle.properties').read_text(encoding='utf-8'), re.MULTILINE)
    if len(matches) != 1:
        raise ValueError('Define exactly one nnggramVersion in gradle.properties')
    return valid_version(matches[0].strip())


def select_mode(env, version):
    valid_version(version)
    event = env.get('GITHUB_EVENT_NAME')
    trusted = bool(env.get('DISTRIBUTION_REPOSITORY')) and env.get('GITHUB_REPOSITORY') == env['DISTRIBUTION_REPOSITORY']
    if event not in ('push', 'workflow_dispatch') or not trusted:
        return 'checks', version
    ref_type, name = env.get('GITHUB_REF_TYPE'), env.get('GITHUB_REF_NAME', '')
    if ref_type == 'tag':
        if not name.startswith('v'):
            raise ValueError('Release tags must use vMAJOR.MINOR.PATCH')
        return 'release', valid_version(name[1:])
    if ref_type == 'branch' and name in (env.get('DEFAULT_BRANCH'), 'dev'):
        return 'quick', version
    return 'checks', version


if __name__ == '__main__':
    mode, version = select_mode(os.environ, base_version(Path.cwd()))
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write(f'mode={mode}\nversion={version}\n')
    print(f'Windows build mode: {mode}; version: {version}')
