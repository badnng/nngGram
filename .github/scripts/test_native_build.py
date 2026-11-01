"""Exercise the native build driver with a recording Gradle process, without compiling."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which('pwsh')


@unittest.skipUnless(os.name == 'nt' and PWSH, 'Windows PowerShell 7 build driver')
class NativeBuildDriverTests(unittest.TestCase):
    def run_driver(self, *arguments, failure=''):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / 'scripts'
            scripts.mkdir()
            driver = scripts / 'build-desktop-native.ps1'
            shutil.copyfile(ROOT / 'scripts/build-desktop-native.ps1', driver)
            recorder = root / 'record_gradle.py'
            recorder.write_text(
                'import json, os, sys\n'
                'from pathlib import Path\n'
                'args = sys.argv[1:]\n'
                'with Path(__file__).with_suffix(".jsonl").open("a") as output:\n'
                '    output.write(json.dumps(args) + "\\n")\n'
                'phase = "native" if ":desktopApp:createGraalvmNativeDistributable" in args else "prepare"\n'
                'sys.exit(7 if os.environ.get("TEST_GRADLE_FAILURE") == phase else 0)\n',
                encoding='utf-8',
            )
            (root / 'gradlew.bat').write_text(
                f'@echo off\r\n@"{sys.executable}" "{recorder}" %*\r\n@exit /b %errorlevel%\r\n',
                encoding='utf-8',
            )
            env = dict(os.environ, TEST_GRADLE_FAILURE=failure)
            result = subprocess.run(
                [PWSH, '-NoProfile', '-NonInteractive', '-File', str(driver),
                 '-BuildName', 'contract', *arguments],
                cwd=root, env=env, capture_output=True, text=True, encoding='utf-8', timeout=60,
            )
            recording = recorder.with_suffix('.jsonl')
            calls = [json.loads(line) for line in recording.read_text().splitlines()] if recording.exists() else []
            return result, calls

    def test_full_validation_finishes_compiler_jvm_before_memory_bounded_native_build(self):
        result, calls = self.run_driver('-RunTests')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, len(calls))
        prepare, native = calls
        for task in (':desktopApp:assemble', ':desktopApp:verifyNativeUberJar', ':desktopApp:test', ':shared:jvmTest'):
            self.assertIn(task, prepare)
            self.assertNotIn(task, native)
        self.assertNotIn(':desktopApp:createGraalvmNativeDistributable', prepare)
        self.assertIn(':desktopApp:createGraalvmNativeDistributable', native)
        self.assertIn('-Dorg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8', prepare)
        self.assertIn('-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8', native)
        for call in calls:
            self.assertIn('--no-daemon', call)
            self.assertIn('--no-configuration-cache', call)
            self.assertIn('-Pkotlin.compiler.execution.strategy=in-process', call)
            self.assertIn('-PnativeImageHeap=8g', call)
            self.assertIn('-PnativeImageThreads=2', call)
            for smoke in ('nativeSmoke', 'nativeAcceptance', 'mediaSmoke'):
                self.assertIn('-P' + smoke, call)

    def test_quick_build_keeps_explicit_resource_and_identity_options_without_tests(self):
        result, calls = self.run_driver('-NativeImageHeap', '9g', '-NativeImageThreads', '3',
                                        '-AppVersion', '1.2.3', '-RequireTelegramApi')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, len(calls))
        for call in calls:
            self.assertNotIn(':desktopApp:test', call)
            self.assertNotIn(':shared:jvmTest', call)
            self.assertNotIn('-PnativeSmoke', call)
            self.assertIn('-PnativeImageHeap=9g', call)
            self.assertIn('-PnativeImageThreads=3', call)
            self.assertIn('-PnnggramVersion=1.2.3', call)
            self.assertIn('-PrequireTelegramApi=true', call)

    def test_preparation_failure_never_starts_native_compilation(self):
        result, calls = self.run_driver('-RunTests', failure='prepare')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(calls))
        self.assertIn('preparation failed (7)', result.stderr)

    def test_native_failure_is_returned_to_ci(self):
        result, calls = self.run_driver(failure='native')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, len(calls))
        self.assertIn('build failed (7)', result.stderr)
