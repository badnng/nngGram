"""Offline dependency retention tests: synthetic PE/JAR fixtures; no compiler, account or network."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile

import tdlib_bundle as bundle


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'native'
        self.source.mkdir()
        self.cache = self.root / 'cache'
        self.expected = {'format': 1, 'sourceCommit': 'a' * 40, 'architecture': 'x64',
                         'compiler': '15.2.0', 'cmake': 'cmake version 4.1.0',
                         'packages': ['gperf 3.1-1', 'mingw-w64-ucrt-x86_64-gcc 15.2.0-1'],
                         'key': 'tdlib-windows-x64-v1-' + 'b' * 64}
        for name in bundle.DLLS:
            data = bytearray(128)
            data[:2] = b'MZ'
            struct.pack_into('<I', data, 60, 64)
            data[64:68] = b'PE\0\0'
            struct.pack_into('<H', data, 68, 0x8664)
            struct.pack_into('<H', data, 86, 0x2000)
            (self.source / name).write_bytes(data)
        self.write_jar('a' * 40)
        (self.source / 'TDLib-LICENSE.txt').write_text('TDLib license', encoding='utf-8')
        (self.source / 'toolchain-versions.txt').write_text('gperf 3.1-1\n', encoding='utf-8')
        # The older combined GCC package remains supported; newer runners split it in two.
        for package in {item for group in bundle.LICENSE_GROUPS for item in group['alternatives'][-1]}:
            path = self.source / 'licenses' / package / 'LICENSE'
            path.parent.mkdir(parents=True)
            path.write_text('Dependency license', encoding='utf-8')
        self.write_manifest()

    def write_jar(self, api_commit):
        with zipfile.ZipFile(self.source / 'tdlib.jar', 'w') as jar:
            jar.writestr('META-INF/nnggram-tdlib.properties', f'source.commit={self.expected["sourceCommit"]}\njava.api.commit={api_commit}\nloader.patch=absolute-path-v1\n')
            jar.writestr('org/drinkless/tdlib/Client.class', b'fixture')
            jar.writestr('org/drinkless/tdlib/TdApi.class', b'fixture')

    def write_manifest(self):
        order = sorted(bundle.DLLS - {'tdjni.dll'}) + ['tdjni.dll']
        fields = {'format': '1', 'source.commit': self.expected['sourceCommit'], 'architecture': 'x64',
                  'binding': 'official-java-jni', 'binding.loader.patch': 'absolute-path-v1',
                  'compiler': self.expected['compiler'], 'cmake': self.expected['cmake'], 'load.order': ','.join(order)}
        fields.update({'sha256.' + name: bundle.digest(self.source / name) for name in bundle.DLLS | {'tdlib.jar'}})
        (self.source / 'manifest.properties').write_text(''.join(f'{key}={value}\n' for key, value in fields.items()), encoding='utf-8')

    def export(self):
        bundle.export_bundle(self.source, self.cache, self.expected)

    def split_gcc_licenses(self, complete=True):
        (self.source / 'licenses/gcc-libs/LICENSE').unlink()
        (self.source / 'licenses/gcc-libs').rmdir()
        for package in (('libgcc', 'libstdc++') if complete else ('libgcc',)):
            directory = self.source / 'licenses' / package
            directory.mkdir()
            for name in ('COPYING3', 'COPYING.RUNTIME'):
                (directory / name).write_text(f'{package} {name} fixture', encoding='utf-8')

    def test_modern_split_gcc_runtime_preserves_both_licenses_and_exceptions(self):
        self.split_gcc_licenses()
        self.export()
        files = bundle.verify_cache(self.cache, self.expected)
        for package in ('libgcc', 'libstdc++'):
            for name in ('COPYING3', 'COPYING.RUNTIME'):
                self.assertIn(f'licenses/{package}/{name}', files)
        self.assertFalse(any(name.startswith('licenses/gcc-libs/') for name in files))

    def test_one_half_of_split_gcc_runtime_is_not_a_complete_license_set(self):
        self.split_gcc_licenses(complete=False)
        with self.assertRaisesRegex(ValueError, r'Missing dependency license for GCC runtime.*libstdc\+\+'):
            self.export()

    def test_license_error_identifies_the_missing_group_and_available_directories(self):
        (self.source / 'licenses/crt/LICENSE').unlink()
        with self.assertRaisesRegex(ValueError, r'Missing dependency license for MinGW CRT: expected crt; found .*gcc-libs'):
            self.export()

    def collect_licenses(self, toolchain, output):
        pwsh = shutil.which('pwsh')
        if not pwsh:
            self.skipTest('PowerShell collector integration requires pwsh; exercised on Windows CI')
        return subprocess.run([pwsh, '-NoProfile', '-Command',
            "$ErrorActionPreference='Stop'; Import-Module ./scripts/TdlibLicenses.psm1 -Force; "
            'Copy-TdlibDependencyLicenses -ToolchainDirectory $env:TEST_TDLIB_TOOLCHAIN -BundleDirectory $env:TEST_TDLIB_BUNDLE'],
            cwd=Path(__file__).resolve().parents[2], env=dict(os.environ,
                TEST_TDLIB_TOOLCHAIN=str(toolchain), TEST_TDLIB_BUNDLE=str(output)),
            capture_output=True, text=True, encoding='utf-8', timeout=30)

    def test_actual_powershell_collector_handles_old_and_split_msys2_layouts(self):
        for modern in (False, True):
            if modern:
                self.split_gcc_licenses()
            toolchain = self.root / f'toolchain-{modern}'
            shutil.copytree(self.source / 'licenses', toolchain / 'share/licenses')
            (toolchain / 'share/licenses/openssl/private.properties').write_text('private fixture')
            output = self.root / f'collected-{modern}'
            result = self.collect_licenses(toolchain, output)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(bundle.regular_files(self.source / 'licenses').keys(), bundle.regular_files(output / 'licenses').keys())
            for name, path in bundle.regular_files(self.source / 'licenses').items():
                self.assertEqual(path.read_bytes(), (output / 'licenses' / name).read_bytes())

    def test_actual_collector_fails_before_copying_when_a_split_dependency_is_missing(self):
        self.split_gcc_licenses(complete=False)
        toolchain = self.root / 'incomplete-toolchain'
        shutil.copytree(self.source / 'licenses', toolchain / 'share/licenses')
        output = self.root / 'not-created'
        result = self.collect_licenses(toolchain, output)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Missing dependency license for GCC runtime', result.stderr)
        self.assertIn('libstdc++', result.stderr)
        self.assertFalse(output.exists())

    def test_complete_roundtrip_archive_has_only_public_dependencies(self):
        (self.source / 'telegram-api.local.properties').write_text('private', encoding='utf-8')
        (self.source / 'media').mkdir()
        (self.source / 'media/ffmpeg.exe').write_bytes(b'media')
        self.export()
        target = self.root / 'restored'
        bundle.restore(self.cache, target, self.expected)
        self.assertEqual(bundle.digest(target / 'tdlib.jar'), bundle.digest(self.source / 'tdlib.jar'))
        artifact = bundle.archive(self.cache, self.root / 'artifacts', self.expected)
        with zipfile.ZipFile(artifact) as archive:
            self.assertIn('bundle/tdjni.dll', archive.namelist())
            self.assertFalse(any('media' in name or 'local.properties' in name for name in archive.namelist()))
        self.assertIn(bundle.digest(artifact), (artifact.parent / 'SHA256SUMS.txt').read_text())

    def test_tampered_library_fails_before_restore_writes_anything(self):
        self.export()
        (self.cache / 'bundle/tdjni.dll').write_bytes(b'tampered')
        destination = self.root / 'restored'
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            bundle.restore(self.cache, destination, self.expected)
        self.assertFalse(destination.exists())

    def test_missing_java_or_native_member_is_not_a_complete_bundle(self):
        for member in ('tdlib.jar', 'tdjni.dll', 'libssl-3-x64.dll'):
            with self.subTest(member=member):
                path = self.source / member
                content = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(ValueError, 'incomplete'):
                    bundle.validate_bundle(self.source, self.expected)
                path.write_bytes(content)

    def test_java_api_pin_must_match_even_with_matching_jar_hash(self):
        self.write_jar('c' * 40)
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'source mismatch'):
            bundle.validate_bundle(self.source, self.expected)

    def test_x86_library_rejected_even_with_matching_manifest_hash(self):
        path = self.source / 'tdjni.dll'
        data = bytearray(path.read_bytes())
        struct.pack_into('<H', data, 68, 0x14c)
        path.write_bytes(data)
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'x64 DLL'):
            bundle.validate_bundle(self.source, self.expected)

    def test_different_source_compiler_or_cmake_cannot_reuse_bundle(self):
        self.export()
        for key, value in (('sourceCommit', 'c' * 40), ('compiler', '16.0.0'), ('cmake', 'cmake version 5.0.0')):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'identity mismatch'):
                bundle.verify_cache(self.cache, {**self.expected, key: value})

    def test_extra_private_cache_file_rejected_even_if_added_to_inventory(self):
        self.export()
        extra = self.cache / 'bundle/td.binlog'
        extra.write_text('not a dependency', encoding='utf-8')
        inventory_path = self.cache / 'bundle-inventory.json'
        info = json.loads(inventory_path.read_text())
        info['files']['td.binlog'] = bundle.digest(extra)
        inventory_path.write_text(json.dumps(info), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'unexpected'):
            bundle.verify_cache(self.cache, self.expected)

    def test_license_tampering_and_missing_license_are_rejected(self):
        self.export()
        path = self.cache / 'bundle/licenses/openssl/LICENSE'
        path.write_text('modified', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'inventory mismatch'):
            bundle.verify_cache(self.cache, self.expected)
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing dependency license'):
            bundle.verify_cache(self.cache, self.expected)

    def test_manifest_rejects_duplicate_or_wrong_hash_members(self):
        path = self.source / 'manifest.properties'
        with path.open('a', encoding='utf-8') as output:
            output.write('sha256.unexpected.dll=' + '0' * 64 + '\n')
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            bundle.validate_bundle(self.source, self.expected)
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            bundle.properties('source.commit=a\nsource.commit=b')

    def test_restore_never_overwrites_existing_directory(self):
        self.export()
        target = self.root / 'existing'
        target.mkdir()
        (target / 'tdlib.jar').write_bytes(b'existing')
        with self.assertRaisesRegex(ValueError, 'must be empty'):
            bundle.restore(self.cache, target, self.expected)
        self.assertEqual((target / 'tdlib.jar').read_bytes(), b'existing')

    def test_unexpected_files_outside_bundle_are_rejected(self):
        self.export()
        (self.cache / 'account.session').write_bytes(b'private')
        with self.assertRaisesRegex(ValueError, 'Unexpected cache content'):
            bundle.verify_cache(self.cache, self.expected)

    def test_toolchain_package_manifest_must_match_active_packages(self):
        (self.source / 'toolchain-versions.txt').write_text('gperf 0.0-1\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'package versions'):
            bundle.validate_bundle(self.source, self.expected)
