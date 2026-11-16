"""Static checks for workflow trust and artifact boundaries; requires PyYAML only when installed."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]

class WorkflowTests(unittest.TestCase):
    def test_external_actions_are_commit_pinned(self):
        for path in (ROOT / '.github').rglob('*.yml'):
            for action in re.findall(r'(?m)^\s*-?\s*uses:\s*(\S+)', path.read_text(encoding='utf-8-sig')):
                if not action.startswith('./'):
                    self.assertRegex(action, r'^[\w.-]+/[\w./-]+@[0-9a-f]{40}$')

    def test_ci_and_release_share_one_build_and_only_release_may_write(self):
        workflows = ROOT / '.github/workflows'
        build = (workflows / 'build.yml').read_text(encoding='utf-8')
        ci = (workflows / 'ci.yml').read_text(encoding='utf-8')
        release = (workflows / 'release.yml').read_text(encoding='utf-8')
        for text in (build, ci, release):
            self.assertNotIn('pull_request', text)
        self.assertIn('workflow_call:', build)
        self.assertNotIn('contents:', build)
        for caller in (ci, release):
            self.assertIn('uses: ./.github/workflows/build.yml', caller)
        self.assertIn('contents: read', ci)
        self.assertNotIn('contents: write', ci)
        self.assertNotIn("tags:", ci)
        self.assertNotIn('release: true', ci)
        self.assertIn("tags: ['v*']", release)
        self.assertNotIn('branches:', release)
        self.assertIn('release: true', release)
        self.assertIn('contents: write', release)

    def test_installers_use_repository_identity_and_upload_unarchived(self):
        build = (ROOT / '.github/workflows/build.yml').read_text(encoding='utf-8')
        self.assertIn('RequireTelegramApi = $true', build)
        self.assertIn('-PrequireTelegramApi=true', build)
        self.assertIn('build-macos.sh --release', build)
        self.assertEqual(build.count('actions/upload-artifact@'), build.count('archive: false'))
        self.assertEqual(3, build.count('if: inputs.release'))
        self.assertEqual(build.count('gh release upload'), build.count('if: inputs.release'))

    def test_tdlib_dependency_cache_is_exact_and_saved_before_upload(self):
        action = (ROOT / '.github/actions/prepare-native/action.yml').read_text(encoding='utf-8')
        self.assertNotIn('restore-keys:', action)
        self.assertNotIn('secrets.', action)
        self.assertIn('Reject any nonexact cache match', action)
        self.assertIn('retention-days: 90', action)
        self.assertLess(action.index('actions/cache/save@'), action.index('actions/upload-artifact@'))
        self.assertIn('GenerateTdlibNativeMetadata.java $env:GITHUB_WORKSPACE --check', action)

    def test_yaml_if_parser_available(self):
        try:
            import yaml
        except ImportError:
            self.skipTest('PyYAML optional; actionlint handles workflow validation separately')
        for path in (ROOT / '.github').rglob('*.yml'):
            self.assertIsInstance(yaml.safe_load(path.read_text(encoding='utf-8-sig')), dict)
