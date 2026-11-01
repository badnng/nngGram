"""Enrich only the public distribution manifest; never reads application credentials."""
import json
import os
from pathlib import Path
import subprocess
import sys


def enrich(directory):
    path = directory / 'build-info.json'
    info = json.loads(path.read_text(encoding='utf-8-sig'))
    env = os.environ
    commit = env['GITHUB_SHA']
    info.update(projectName='nngGram', runNumber=env['GITHUB_RUN_NUMBER'],
                branch=env['GITHUB_REF_NAME'], commit=commit,
                commitMessage=subprocess.check_output(['git', 'show', '-s', '--format=%s', commit], text=True).strip(),
                runUrl=f"{env['GITHUB_SERVER_URL']}/{env['GITHUB_REPOSITORY']}/actions/runs/{env['GITHUB_RUN_ID']}")
    info.setdefault('versionName', info.get('version', '0.1.0'))
    path.write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    enrich(Path(sys.argv[1]))
