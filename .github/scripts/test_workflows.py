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

    def test_workflow_gates_distribution_and_scopes_secrets(self):
        text = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        checks = text.split('  native:')[0]
        self.assertNotIn('secrets.', checks)
        self.assertNotIn('pull_request_target', text)
        self.assertIn("github.repository == vars.DISTRIBUTION_REPOSITORY", text)
        native = text.split('  native:')[1].split('  release:')[0]
        release = text.split('  release:')[1].split('  telegram:')[0]
        self.assertIn('RequireTelegramApi = $true', native)
        self.assertNotIn('contents: write', checks + native)
        self.assertEqual(1, text.count('contents: write'))
        self.assertIn('contents: write', release)
        self.assertIn("github.event_name != 'pull_request'", native)
        self.assertIn('github.repository == vars.DISTRIBUTION_REPOSITORY', native)
        self.assertIn("github.ref_type == 'tag'", release)
        self.assertIn('github.repository == vars.DISTRIBUTION_REPOSITORY', release)
        self.assertIn('compression-level: 0', text)
        self.assertIn('queue: max', text)

    def test_quick_job_does_not_depend_on_full_jvm_job_and_tag_reuses_its_packages(self):
        text = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        native = text.split('  native:')[1].split('  release:')[0]
        release = text.split('  release:')[1].split('  telegram:')[0]
        self.assertIn("tags: ['v*']", text)
        self.assertIn('needs: plan', native)
        self.assertNotIn('needs: checks', native)
        self.assertIn("RunTests = ($env:BUILD_MODE -eq 'release')", native)
        self.assertIn('Verify built EXE using offline native diagnostics', native)
        self.assertIn('AppVersion = $env:APP_VERSION', native)
        self.assertIn('-Version $env:APP_VERSION', native)
        self.assertIn('needs: [plan, native]', release)
        self.assertIn('needs.native.outputs.artifact_name', release)
        self.assertNotIn('build-desktop-native.ps1', release)
        self.assertIn('release_ci.py', release)

    def test_tdlib_dependency_retention_precedes_application_tests(self):
        action = (ROOT / '.github/actions/prepare-native/action.yml').read_text(encoding='utf-8')
        workflow = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertNotIn('restore-keys:', action)
        self.assertNotIn('secrets.', action)
        self.assertIn('Reject any nonexact cache match', action)
        self.assertIn('retention-days: 90', action)
        self.assertLess(action.index('actions/cache/save@'), action.index('actions/upload-artifact@'))
        self.assertIn('GenerateTdlibNativeMetadata.java $env:GITHUB_WORKSPACE --check', action)
        checks = workflow.split('  native:')[0]
        self.assertLess(checks.index('uses: ./.github/actions/prepare-native'), checks.index('Assemble and run JVM tests'))

    def test_yaml_if_parser_available(self):
        try:
            import yaml
        except ImportError:
            self.skipTest('PyYAML optional; actionlint handles workflow validation separately')
        for path in (ROOT / '.github').rglob('*.yml'):
            self.assertIsInstance(yaml.safe_load(path.read_text(encoding='utf-8-sig')), dict)
