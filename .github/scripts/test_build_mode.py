import tempfile
from pathlib import Path
import unittest

from build_mode import base_version, select_mode, valid_version


class BuildModeTests(unittest.TestCase):
    def env(self, **changes):
        return dict(GITHUB_EVENT_NAME='push', GITHUB_REPOSITORY='owner/app',
                    DISTRIBUTION_REPOSITORY='owner/app', GITHUB_REF_TYPE='branch',
                    GITHUB_REF_NAME='main', DEFAULT_BRANCH='main') | changes

    def test_default_and_dev_commits_package_without_full_jvm_gate(self):
        for branch in ('main', 'dev'):
            self.assertEqual(('quick', '0.1.0'), select_mode(self.env(GITHUB_REF_NAME=branch), '0.1.0'))

    def test_pr_fork_unconfigured_and_other_branches_remain_secretless(self):
        for changes in ({'GITHUB_EVENT_NAME': 'pull_request'}, {'GITHUB_REPOSITORY': 'fork/app'},
                        {'DISTRIBUTION_REPOSITORY': ''}, {'GITHUB_REF_NAME': 'feature/a'}):
            self.assertEqual(('checks', '0.1.0'), select_mode(self.env(**changes), '0.1.0'))

    def test_tag_overrides_base_version_and_manual_tag_retry_keeps_full_checks(self):
        for event in ('push', 'workflow_dispatch'):
            self.assertEqual(('release', '1.2.3'), select_mode(self.env(GITHUB_EVENT_NAME=event,
                GITHUB_REF_TYPE='tag', GITHUB_REF_NAME='v1.2.3'), '0.1.0'))

    def test_pr_and_fork_never_publish_even_if_given_tag_metadata(self):
        for changes in ({'GITHUB_EVENT_NAME': 'pull_request'}, {'GITHUB_REPOSITORY': 'fork/app'}):
            self.assertEqual(('checks', '0.1.0'), select_mode(self.env(GITHUB_REF_TYPE='tag',
                GITHUB_REF_NAME='v1.2.3', **changes), '0.1.0'))

    def test_bad_tags_fail_before_native_build_or_secret_injection(self):
        for name in ('1.2.3', 'v1.2', 'v1.2.3-beta', 'v01.2.3', 'v256.1.0', 'v1.256.0', 'v1.2.65536', 'v1.2.3\nmode=quick'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                select_mode(self.env(GITHUB_REF_TYPE='tag', GITHUB_REF_NAME=name), '0.1.0')

    def test_msi_boundaries_and_single_version_source(self):
        self.assertEqual('255.255.65535', valid_version('255.255.65535'))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'gradle.properties'
            path.write_text('nnggramVersion=0.2.0\n', encoding='utf-8')
            self.assertEqual('0.2.0', base_version(root))
            path.write_text('nnggramVersion=0.2.0\nnnggramVersion=0.3.0\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                base_version(root)
