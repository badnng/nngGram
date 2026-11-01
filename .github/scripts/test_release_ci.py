"""Offline release-state regressions. All GitHub operations are in-memory mocks."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import urllib.error
import urllib.parse
import zipfile

import release_ci as release

COMMIT = 'a' * 40
TAG = 'v1.2.3'


class FakeGitHub:
    def __init__(self):
        self.release = None
        self.assets = []
        self.calls = []
        self.tag_checks = 0
        self.move_tag_on_final_check = False
        self.corrupt_upload = False
        self.after_upload = None

    def pages(self, path):
        self.calls.append(('PAGES', path))
        return copy.deepcopy(([self.release] if self.release else []) if path == '/releases' else self.assets)

    def request(self, method, path, payload=None, asset=None):
        self.calls.append((method, path, copy.deepcopy(payload)))
        if path.startswith('/commits/'):
            self.tag_checks += 1
            return {'sha': 'b' * 40 if self.move_tag_on_final_check and self.tag_checks > 1 else COMMIT}
        if method == 'POST' and path == '/releases':
            self.release = {**payload, 'id': 123}
            return copy.deepcopy(self.release)
        if method == 'POST' and '/assets?name=' in path:
            name = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)['name'][0]
            self.assets.append({'name': name, 'size': asset.size, 'digest': 'sha256:' + ('0' * 64 if self.corrupt_upload else asset.sha256), 'state': 'uploaded'})
            if self.after_upload:
                self.after_upload()
            return copy.deepcopy(self.assets[-1])
        if method == 'GET' and path == '/releases/123':
            return copy.deepcopy(self.release)
        if method == 'PATCH' and path == '/releases/123':
            self.release.update(payload)
            return copy.deepcopy(self.release)
        raise AssertionError((method, path))


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.zip = self.directory / 'nngGram-1.2.3-windows-x64.zip'
        self.msi = self.directory / 'nngGram-1.2.3-windows-x64.msi'
        self.msi.write_bytes(bytes.fromhex('d0cf11e0a1b11ae1') + b'installer fixture')
        content = {'nngGram.exe': b'native executable fixture', 'native/tdjni.dll': b'native library fixture'}
        with zipfile.ZipFile(self.zip, 'w') as archive:
            for name, data in content.items():
                archive.writestr('nngGram/' + name, data)
        payload = [{'Relative': name, 'Length': len(data), 'Sha256': hashlib.sha256(data).hexdigest()} for name, data in content.items()]
        (self.directory / 'payload-sha256.json').write_text(json.dumps(payload), encoding='utf-8')
        self.info = {'projectName': 'nngGram', 'versionName': '1.2.3', 'commit': COMMIT, 'artifacts': {}}
        self.refresh_packages()
        self.api = FakeGitHub()

    def refresh_packages(self):
        for key, path in (('zip', self.zip), ('installer', self.msi)):
            self.info['artifacts'][key] = {'file': path.name, 'name': path.name, 'bytes': path.stat().st_size, 'sha256': release.sha256_file(path)}
        self.write_info()
        (self.directory / 'SHA256SUMS.txt').write_text(''.join(f'{release.sha256_file(path)}  {path.name}\n' for path in (self.zip, self.msi)), encoding='utf-8')

    def write_info(self):
        (self.directory / 'build-info.json').write_text(json.dumps(self.info), encoding='utf-8')

    def own_draft(self):
        assets = release.validate(self.directory, TAG, COMMIT)
        self.api.release = {'id': 123, 'tag_name': TAG, 'name': TAG, 'target_commitish': COMMIT,
                            'body': release.body_for(TAG, COMMIT, assets), 'draft': True, 'prerelease': False}
        return assets

    def assert_unpublished(self):
        self.assertFalse(any(call[0] == 'PATCH' for call in self.api.calls))

    def test_new_release_uploads_all_five_verified_files_before_publish(self):
        release.publish(self.directory, self.api, TAG, COMMIT)
        self.assertEqual(len(self.api.assets), 5)
        self.assertEqual(self.api.calls[-1], ('PATCH', '/releases/123', {'draft': False, 'make_latest': 'false'}))
        create = next(call for call in self.api.calls if call[:2] == ('POST', '/releases'))
        self.assertTrue(create[2]['draft'])
        self.assertEqual(create[2]['target_commitish'], COMMIT)
        self.assertEqual(create[2]['make_latest'], 'false')
        self.assertEqual(self.api.tag_checks, 2)

    def test_matching_partial_draft_reuses_identical_assets_without_overwriting(self):
        assets = self.own_draft()
        name, asset = next(iter(assets.items()))
        self.api.assets = [{'name': name, 'size': asset.size, 'digest': 'sha256:' + asset.sha256, 'state': 'uploaded'}]
        release.publish(self.directory, self.api, TAG, COMMIT)
        uploads = [call for call in self.api.calls if call[0] == 'POST' and 'assets?' in call[1]]
        self.assertEqual(len(uploads), 4)
        self.assertFalse(any(call[:2] == ('POST', '/releases') for call in self.api.calls))

    def test_published_release_is_never_overwritten(self):
        self.own_draft()
        self.api.release['draft'] = False
        with self.assertRaisesRegex(ValueError, 'published'):
            release.publish(self.directory, self.api, TAG, COMMIT)
        self.assert_unpublished()
        self.assertEqual(self.api.assets, [])

    def test_arbitrary_or_changed_draft_is_never_adopted(self):
        for field, value in (('body', 'user-owned draft'), ('target_commitish', 'b' * 40), ('name', 'manual'), ('prerelease', True)):
            with self.subTest(field=field):
                self.own_draft()
                self.api.release[field] = value
                with self.assertRaisesRegex(ValueError, 'owned draft'):
                    release.publish(self.directory, self.api, TAG, COMMIT)
                self.assert_unpublished()

    def test_moved_tag_fails_before_publication_even_after_upload(self):
        self.api.move_tag_on_final_check = True
        with self.assertRaisesRegex(ValueError, 'Remote tag moved'):
            release.publish(self.directory, self.api, TAG, COMMIT)
        self.assertEqual(len(self.api.assets), 5)
        self.assert_unpublished()
        self.assertTrue(self.api.release['draft'])

    def test_wrong_initial_tag_commit_does_not_create_a_draft(self):
        with patch.object(self.api, 'request', return_value={'sha': 'b' * 40}):
            with self.assertRaisesRegex(ValueError, 'Remote tag moved'):
                release.publish(self.directory, self.api, TAG, COMMIT)
        self.assertIsNone(self.api.release)

    def test_invalid_metadata_version_or_commit_prevents_any_api_mutation(self):
        for field, value in (('versionName', '1.2.4'), ('commit', 'b' * 40), ('projectName', 'other')):
            with self.subTest(field=field):
                original = self.info[field]
                self.info[field] = value
                self.write_info()
                with self.assertRaisesRegex(ValueError, 'metadata does not match'):
                    release.publish(self.directory, self.api, TAG, COMMIT)
                self.info[field] = original
                self.assertEqual(self.api.calls, [])

    def test_tampered_zip_or_msi_is_rejected_by_metadata(self):
        for path in (self.zip, self.msi):
            with self.subTest(path=path.name):
                original = path.read_bytes()
                path.write_bytes(original + b'tampered')
                with self.assertRaisesRegex(ValueError, 'size/hash/path'):
                    release.validate(self.directory, TAG, COMMIT)
                path.write_bytes(original)

    def test_checksums_require_exactly_both_packages(self):
        sums = self.directory / 'SHA256SUMS.txt'
        lines = sums.read_text().splitlines()
        for text in (lines[0] + '\n', '\n'.join(lines + [lines[0]]) + '\n', '0' * 64 + '  private.session\n'):
            with self.subTest(text=text):
                sums.write_text(text, encoding='utf-8')
                with self.assertRaises(ValueError):
                    release.validate(self.directory, TAG, COMMIT)

    def test_payload_hash_rejects_tampered_zip_even_with_updated_package_metadata(self):
        with zipfile.ZipFile(self.zip, 'w') as archive:
            archive.writestr('nngGram/nngGram.exe', b'altered executable')
            archive.writestr('nngGram/native/tdjni.dll', b'native library fixture')
        self.refresh_packages()
        with self.assertRaisesRegex(ValueError, 'payload (size|hash) mismatch'):
            release.validate(self.directory, TAG, COMMIT)

    def test_private_extra_file_and_missing_public_file_are_rejected(self):
        private = self.directory / 'telegram-api.local.properties'
        private.write_text('private fixture', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'exactly the five public'):
            release.validate(self.directory, TAG, COMMIT)
        private.unlink()
        self.msi.unlink()
        with self.assertRaisesRegex(ValueError, 'exactly the five public'):
            release.validate(self.directory, TAG, COMMIT)

    def test_metadata_cannot_redirect_upload_to_parent_or_absolute_paths(self):
        for name in ('../private.zip', '/private.zip', 'C:\\private.zip'):
            with self.subTest(name=name):
                self.info['artifacts']['zip']['file'] = name
                self.write_info()
                with self.assertRaisesRegex(ValueError, 'size/hash/path'):
                    release.validate(self.directory, TAG, COMMIT)

    def test_unknown_duplicate_or_unverified_remote_assets_are_not_replaced(self):
        for modifier in ('unknown', 'duplicate', 'bad-digest', 'missing-digest', 'bad-state', 'bad-size'):
            with self.subTest(modifier=modifier):
                assets = self.own_draft()
                name, local = next(iter(assets.items()))
                item = {'name': name, 'size': local.size, 'digest': 'sha256:' + local.sha256, 'state': 'uploaded'}
                if modifier == 'unknown': item['name'] = 'private.session'
                if modifier == 'bad-digest': item['digest'] = 'sha256:' + '0' * 64
                if modifier == 'missing-digest': item.pop('digest')
                if modifier == 'bad-state': item['state'] = 'starter'
                if modifier == 'bad-size': item['size'] += 1
                self.api.assets = [item, copy.deepcopy(item)] if modifier == 'duplicate' else [item]
                with self.assertRaises(ValueError):
                    release.publish(self.directory, self.api, TAG, COMMIT)
                self.assert_unpublished()

    def test_server_upload_digest_mismatch_leaves_draft_unpublished(self):
        self.api.corrupt_upload = True
        with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
            release.publish(self.directory, self.api, TAG, COMMIT)
        self.assert_unpublished()

    def test_local_file_changed_after_upload_is_not_published(self):
        def change():
            self.api.after_upload = None
            self.info['versionName'] = '9.9.9'
            self.write_info()
        self.api.after_upload = change
        with self.assertRaises(ValueError):
            release.publish(self.directory, self.api, TAG, COMMIT)
        self.assert_unpublished()

    def test_stable_tag_validation_obeys_windows_msi_ranges(self):
        self.assertEqual(release.stable_version('v255.255.65535'), '255.255.65535')
        for tag in ('1.2.3', 'v01.2.3', 'v1.2.3-rc1', 'v1.2.3+meta', 'v256.1.1', 'v1.256.1', 'v1.1.65536', 'v' + '9' * 10000 + '.1.1'):
            with self.subTest(tag=tag[:40]), self.assertRaises(ValueError):
                release.stable_version(tag)

    def test_cli_refuses_non_tag_context(self):
        with patch.dict(os.environ, {'GITHUB_REF_TYPE': 'branch'}, clear=True), patch('sys.argv', ['release_ci.py', str(self.directory)]), patch.object(release, 'GitHub') as client:
            with self.assertRaisesRegex(ValueError, 'Only an explicit stable tag'):
                release.main()
            client.assert_not_called()


class APIBoundaryTests(unittest.TestCase):
    def test_release_and_asset_pagination_consumes_all_pages(self):
        api = release.GitHub('owner/repo', 'fixture-token')
        for path in ('/releases', '/releases/123/assets'):
            with self.subTest(path=path), patch.object(api, 'request', side_effect=[[{'id': index} for index in range(100)], [{'id': 101}]]) as request:
                self.assertEqual(len(api.pages(path)), 101)
                self.assertEqual(request.call_args_list[-1].args, ('GET', path + '?per_page=100&page=2'))

    def test_http_403_and_404_are_not_swallowed_or_leaked(self):
        api = release.GitHub('owner/repo', 'DO_NOT_EXPOSE')
        for code in (403, 404, 422, 500):
            error = urllib.error.HTTPError('https://api.github.com/private/DO_NOT_EXPOSE', code, 'private response', {}, io.BytesIO(b'private body'))
            opener = MagicMock()
            opener.open.side_effect = error
            with self.subTest(code=code), patch.object(release.urllib.request, 'build_opener', return_value=opener):
                with self.assertRaises(release.APIError) as caught:
                    api.request('GET', '/releases')
                self.assertEqual(caught.exception.status, code)
                self.assertNotIn('DO_NOT_EXPOSE', str(caught.exception))
                self.assertNotIn('private', str(caught.exception))
                opener.open.assert_called_once()

    def test_asset_upload_streams_file_with_explicit_size_and_disables_redirects(self):
        api = release.GitHub('owner/repo', 'fixture-token')
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'asset.zip'
            path.write_bytes(b'fixture payload')
            asset = release.Asset(path, path.stat().st_size, release.sha256_file(path))
            opener = MagicMock()
            opener.open.return_value.__enter__.return_value = io.BytesIO(b'{"id":1}')
            with patch.object(release.urllib.request, 'build_opener', return_value=opener) as factory:
                api.request('POST', '/releases/123/assets?name=asset.zip', asset=asset)
            request = opener.open.call_args.args[0]
            self.assertEqual(request.get_header('Content-length'), str(asset.size))
            self.assertEqual(request.full_url, 'https://uploads.github.com/repos/owner/repo/releases/123/assets?name=asset.zip')
            self.assertTrue(hasattr(request.data, 'read'))
            self.assertTrue(request.data.closed)
            self.assertIsNone(factory.call_args.args[0].redirect_request(None, None, None, None, None, None))

    def test_invalid_repository_and_empty_token_rejected(self):
        for repository in ('owner/repo/extra', '../repo', 'https://evil.test/repo'):
            with self.subTest(repository=repository), self.assertRaises(ValueError):
                release.GitHub(repository, 'fixture')
        with self.assertRaisesRegex(ValueError, 'GH_TOKEN'):
            release.GitHub('owner/repo', ' ')
