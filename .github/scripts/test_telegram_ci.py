"""Offline regression checks for publishing boundaries; never contacts Telegram/GitHub."""

from contextlib import contextmanager
from email import policy
from email.parser import BytesParser
import hashlib
import io
import json
import os
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import urllib.error

import telegram_ci

class TelegramTests(unittest.TestCase):
    def setUp(self):
        self.info = {
            "projectName": "澎湃记", "runNumber": "42", "versionName": "1.0", "branch": "master",
            "commit": "a" * 40, "commitMessage": "<b>用户 & 提交</b> " + "😀" * 700,
            "runUrl": "https://github.com/test/repo/actions/runs/42",
        }

    def test_caption_escapes_untrusted_commit_text(self):
        text = telegram_ci.caption(self.info)
        self.assertIn("&lt;b&gt;用户 &amp; 提交&lt;/b&gt;", text)
        self.assertLess(len(text.encode("utf-16-le")) // 2, 1024)

    def test_message_template_keeps_layout_and_accepts_other_project_names(self):
        with patch.dict(os.environ, {"TELEGRAM_CHAT_ID": "@test"}):
            info = {**self.info, "projectName": "模组 <CI> & 测试", "commitMessage": "修复采样"}
            fields = telegram_ci.notification_fields(info, "https://github.com/artifact")
        self.assertEqual(fields["caption"], (
            "<b>模组 &lt;CI&gt; &amp; 测试 | CI Build #42</b>\n"
            "Version: 1.0\nBranch: master\nCommit: <code>aaaaaaa</code>\n\n"
            "Commit message:\n<pre>修复采样</pre>"
        ))
        self.assertEqual(json.loads(fields["reply_markup"])["inline_keyboard"], [[
            {"text": "下载 Actions 产物", "url": "https://github.com/artifact"},
            {"text": "构建详情", "url": self.info["runUrl"]},
        ]])

    def test_chat_id_accepts_ids_and_normalizes_public_channel_links(self):
        for value, expected in (
            ("@channel_name", "@channel_name"), (" -1001234567890\n", "-1001234567890"),
            ("123456789", "123456789"), ("https://t.me/channel_name", "@channel_name"),
            ("t.me/channel_name/", "@channel_name"), ("https://telegram.me/channel_name", "@channel_name"),
        ):
            with self.subTest(value=value), patch.dict(os.environ, {
                "TELEGRAM_BOT_TOKEN": "test-token", "TELEGRAM_CHAT_ID": value,
                "TELEGRAM_API_ID": "", "TELEGRAM_API_HASH": "",
            }):
                self.assertEqual(telegram_ci.validate_config(), "cloud")
                fields = telegram_ci.notification_fields(self.info, "https://github.com/artifact")
                self.assertEqual(fields["chat_id"], expected)

    def test_chat_id_rejects_display_names_and_invite_links_without_echoing_them(self):
        for value in ("我的构建频道", "https://t.me/+PRIVATE_INVITE", "https://t.me/joinchat/PRIVATE_INVITE",
                      "https://t.me/c/1234567890/42", "https://example.com/channel_name", "channel_name"):
            with self.subTest(value=value), patch.dict(os.environ, {
                "TELEGRAM_BOT_TOKEN": "test-token", "TELEGRAM_CHAT_ID": value,
            }), patch.object(telegram_ci, "api_call") as api:
                with self.assertRaisesRegex(ValueError, "TELEGRAM_CHAT_ID") as captured:
                    telegram_ci.validate_config()
                self.assertNotIn(value, str(captured.exception))
                api.assert_not_called()

    def test_http_400_reports_the_known_cause_without_exposing_the_response(self):
        for description, hint in (
            ("Bad Request: chat not found", "destination chat not found"),
            ("Bad Request: not enough rights to send text messages to the chat", "bot cannot post"),
            ("Bad Request: can't parse entities", "HTML caption"),
            ("Bad Request: caption is too long", "message length"),
            ("Bad Request: BUTTON_URL_INVALID", "rejected a URL"),
        ):
            token = "DO_NOT_LOG_THIS_TOKEN"
            raw_description = description + " " + token
            body = json.dumps({"ok": False, "error_code": 400, "description": raw_description}).encode()
            error = urllib.error.HTTPError(
                f"https://api.telegram.org/bot{token}/sendDocument", 400, raw_description, {}, io.BytesIO(body)
            )
            opener = MagicMock()
            opener.open.side_effect = error
            with self.subTest(description=description), \
                    patch.object(telegram_ci.urllib.request, "build_opener", return_value=opener):
                with self.assertRaises(telegram_ci.TelegramAPIError) as captured:
                    telegram_ci.api_call(token, "sendDocument", endpoint=telegram_ci.CLOUD_API)
            self.assertIn(hint, str(captured.exception))
            self.assertNotIn(token, str(captured.exception))
            self.assertNotIn(raw_description, str(captured.exception))
            opener.open.assert_called_once()

    def test_json_api_error_reports_the_known_cause_and_keeps_unknown_details_private(self):
        for description, hint in (("Bad Request: chat not found", "destination chat not found"),
                                  ("UNKNOWN_PRIVATE_RESPONSE", "check credentials")):
            opener = MagicMock()
            opener.open.return_value.__enter__.return_value = io.BytesIO(json.dumps({
                "ok": False, "error_code": 400, "description": description, "parameters": None,
            }).encode())
            with self.subTest(description=description), \
                    patch.object(telegram_ci.urllib.request, "build_opener", return_value=opener):
                with self.assertRaises(telegram_ci.TelegramAPIError) as captured:
                    telegram_ci.api_call("test-token", "sendDocument", endpoint=telegram_ci.CLOUD_API)
            self.assertIn(hint, str(captured.exception))
            self.assertNotIn(description, str(captured.exception))

    def test_large_archive_uses_local_file_without_loading_it_into_memory(self):
        with patch.dict(os.environ, {"TELEGRAM_CHAT_ID": "@test"}):
            archive = MagicMock()
            archive.resolve.return_value.as_uri.return_value = "file:///tmp/ci%20build.archive"
            for size in (1, 50_000_000, 50_000_001, 2_000_000_000):
                archive.stat.return_value.st_size = size
                fields = telegram_ci.request_payload(self.info, archive, "https://github.com/artifact")
                self.assertEqual(fields["document"], "file:///tmp/ci%20build.archive")
                self.assertEqual(fields["parse_mode"], "HTML")
                self.assertIn("https://github.com/artifact", fields["reply_markup"])
                self.assertLess(len(json.dumps(fields)), 10000)
            archive.read_bytes.assert_not_called()
            for size in (0, 2_000_000_001):
                archive.stat.return_value.st_size = size
                with self.assertRaisesRegex(ValueError, "2000 MB"):
                    telegram_ci.request_payload(self.info, archive, "https://github.com/artifact")

    def test_http_error_does_not_expose_token(self):
        token = "DO_NOT_LOG_THIS_TOKEN"
        error = urllib.error.HTTPError(f"https://api.telegram.org/bot{token}/sendDocument", 403, token, {}, io.BytesIO(b'{}'))
        opener = MagicMock()
        opener.open.side_effect = error
        with patch.object(telegram_ci.urllib.request, "build_opener", return_value=opener):
            with self.assertRaises(RuntimeError) as captured:
                telegram_ci.api_call(token, "sendDocument", {"document": "file:///tmp/build.archive"})
        self.assertNotIn(token, str(captured.exception))
        self.assertIn("403", str(captured.exception))

    def test_api_posts_only_json_to_loopback(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value = io.BytesIO(b'{"ok":true,"result":{"message_id":1}}')
        with patch.object(telegram_ci.urllib.request, "build_opener", return_value=opener) as build_opener:
            result = telegram_ci.api_call("test-token", "sendDocument", {"document": "file:///tmp/a.archive"})
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "http://127.0.0.1:8081/bottest-token/sendDocument")
        self.assertEqual(json.loads(request.data)["document"], "file:///tmp/a.archive")
        self.assertEqual(build_opener.call_args.args[0].proxies, {})
        self.assertEqual(result["message_id"], 1)

    def test_hosted_api_uploads_actual_multipart_document(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value = io.BytesIO(b'{"ok":true,"result":{"message_id":2}}')
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {"TELEGRAM_CHAT_ID": "@test"}), \
                patch.object(telegram_ci.urllib.request, "build_opener", return_value=opener):
            archive = Path(temporary) / "test build.archive"
            archive.write_bytes(b"ZIP\x00\xff\r\nbytes")
            telegram_ci.publish_cloud("test-token", self.info, archive, "https://github.com/artifact")
            opener.open.assert_called_once()
            request = opener.open.call_args.args[0]
            self.assertEqual(request.full_url, "https://api.telegram.org/bottest-token/sendDocument")
            message = BytesParser(policy=policy.default).parsebytes(
                f"Content-Type: {request.get_header('Content-type')}\r\n\r\n".encode() + request.data
            )
            parts = {part.get_param("name", header="content-disposition"): part for part in message.iter_parts()}
            self.assertEqual(parts["document"].get_filename(), archive.name)
            self.assertEqual(parts["document"].get_payload(decode=True), archive.read_bytes())
            self.assertEqual(parts["parse_mode"].get_payload(decode=True), b"HTML")
            self.assertEqual(parts["caption"].get_payload(decode=True).decode(), telegram_ci.caption(self.info))
            self.assertIn(b"https://github.com/artifact", parts["reply_markup"].get_payload(decode=True))

    def test_hosted_api_upload_limit_and_oversize_link_fallback(self):
        with patch.dict(os.environ, {"TELEGRAM_CHAT_ID": "@test"}):
            for size in (1, 50_000_000, 50_000_001, 2_000_000_001):
                with self.subTest(size=size), patch.object(telegram_ci, "api_call") as api:
                    archive = MagicMock()
                    archive.stat.return_value.st_size = size
                    telegram_ci.publish_cloud("test-token", self.info, archive, "https://github.com/artifact")
                    api.assert_called_once()
                    args, kwargs = api.call_args
                    self.assertEqual(kwargs["endpoint"], telegram_ci.CLOUD_API)
                    if size <= 50_000_000:
                        self.assertEqual(args[1], "sendDocument")
                        self.assertIs(kwargs["document"], archive)
                    else:
                        self.assertEqual(args[1], "sendMessage")
                        self.assertIn("50 MB", args[2]["text"])
                        self.assertNotIn("caption", args[2])
                        self.assertNotIn("document", kwargs)
                    self.assertIn("https://github.com/artifact", args[2]["reply_markup"])
                    archive.read_bytes.assert_not_called()
            with patch.object(telegram_ci, "api_call") as api:
                archive.stat.return_value.st_size = 0
                with self.assertRaisesRegex(ValueError, "nonempty"):
                    telegram_ci.publish_cloud("test-token", self.info, archive, "https://github.com/artifact")
                api.assert_not_called()

    def test_cloud_main_needs_no_application_credentials_binary_or_runner(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {
            "TELEGRAM_BOT_TOKEN": "test-token", "TELEGRAM_CHAT_ID": "@test",
            "ARTIFACT_URL": "https://github.com/artifact",
        }, clear=True), patch.object(telegram_ci, "api_call") as api, \
                patch.object(telegram_ci, "local_server") as server:
            directory = Path(temporary)
            (directory / "app.archive").write_bytes(b"fake ZIP")
            (directory / "build-info.json").write_text(json.dumps({**self.info, "artifacts": {"zip": {"file": "app.archive"}}}), encoding="utf-8")
            telegram_ci.main(directory)
            server.assert_not_called()
            api.assert_called_once()
            self.assertEqual(api.call_args.args[1], "sendDocument")
            self.assertEqual(api.call_args.kwargs["endpoint"], telegram_ci.CLOUD_API)

    def test_cloud_failure_is_not_retried_or_replaced_with_another_message(self):
        for size in (10, 50_000_001):
            with self.subTest(size=size), patch.dict(os.environ, {"TELEGRAM_CHAT_ID": "@test"}), \
                    patch.object(telegram_ci, "api_call", side_effect=telegram_ci.TelegramAPIError("sendDocument", 0)) as api:
                archive = MagicMock()
                archive.stat.return_value.st_size = size
                with self.assertRaises(telegram_ci.TelegramAPIError):
                    telegram_ci.publish_cloud("test-token", self.info, archive, "https://github.com/artifact")
                api.assert_called_once()

    def test_cloud_logout_tolerates_only_already_logged_out_or_cooldown(self):
        for code in (401, 429, 403, 500, 0):
            with self.subTest(code=code), patch.object(telegram_ci, "api_call", side_effect=telegram_ci.TelegramAPIError("logOut", code)):
                if code in (401, 429):
                    telegram_ci.logout_cloud("test-token")
                else:
                    with self.assertRaises(telegram_ci.TelegramAPIError):
                        telegram_ci.logout_cloud("test-token")

    def test_local_server_logs_out_even_if_upload_fails_without_reposting(self):
        process = MagicMock()
        process.poll.return_value = None
        for failed in (True, False):
            with self.subTest(failed=failed):
                def call(token, method, *args, **kwargs):
                    if failed and method == "sendDocument":
                        raise telegram_ci.TelegramAPIError(method, 0)
                    return True

                with patch.object(telegram_ci, "local_server") as server, patch.object(telegram_ci, "logout_cloud"), \
                        patch.object(telegram_ci, "wait_ready"), patch.object(telegram_ci, "api_call", side_effect=call) as api:
                    server.return_value.__enter__.return_value = process
                    if failed:
                        with self.assertRaises(telegram_ci.TelegramAPIError):
                            telegram_ci.publish(Path("server"), "test-token", {"document": "file:///tmp/a.archive"})
                    else:
                        telegram_ci.publish(Path("server"), "test-token", {"document": "file:///tmp/a.archive"})
                    self.assertEqual([item.args[1] for item in api.call_args_list], ["sendDocument", "logOut"])

    def test_native_process_is_stopped_and_session_directory_removed_on_failure(self):
        process = MagicMock()
        process.poll.return_value = None
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {
            "RUNNER_TEMP": temporary, "TELEGRAM_BOT_TOKEN": "secret-token", "TELEGRAM_API_HASH": "b" * 32,
        }), patch.object(telegram_ci.subprocess, "Popen", return_value=process) as start:
            with self.assertRaisesRegex(RuntimeError, "upload failed"):
                with telegram_ci.local_server(Path("server")):
                    self.assertEqual(len(list(Path(temporary).iterdir())), 1)
                    raise RuntimeError("upload failed")
            self.assertEqual(list(Path(temporary).iterdir()), [])
        process.terminate.assert_called_once()
        process.wait.assert_called_once()
        self.assertIn("--local", start.call_args.args[0])
        self.assertIn("--http-ip-address=127.0.0.1", start.call_args.args[0])
        self.assertNotIn("TELEGRAM_BOT_TOKEN", start.call_args.kwargs["env"])

    def test_readiness_checks_authentication_and_detects_process_exit(self):
        process = MagicMock()
        process.poll.return_value = None
        with patch.object(telegram_ci, "api_call", side_effect=[telegram_ci.TelegramAPIError("getMe", 0), {"id": 1}]) as api, \
                patch.object(telegram_ci.time, "sleep"):
            telegram_ci.wait_ready("test-token", process)
            self.assertEqual(api.call_count, 2)
        process.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError, "exited"):
            telegram_ci.wait_ready("test-token", process)

    def test_readiness_does_not_retry_before_telegram_cooldown(self):
        process = MagicMock()
        process.poll.return_value = None
        with patch.object(telegram_ci, "api_call", side_effect=telegram_ci.TelegramAPIError("getMe", 429, 600)) as api, \
                patch.object(telegram_ci.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "timed out"):
                telegram_ci.wait_ready("test-token", process, timeout=10)
            api.assert_called_once()
            sleep.assert_not_called()

    def test_stalled_server_is_killed_after_graceful_shutdown_timeout(self):
        process = MagicMock()
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired("server", 30), 0]
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {"RUNNER_TEMP": temporary}), \
                patch.object(telegram_ci.subprocess, "Popen", return_value=process):
            with telegram_ci.local_server(Path("server")):
                pass
            self.assertEqual(list(Path(temporary).iterdir()), [])
        process.terminate.assert_called_once()
        process.kill.assert_called_once()

    def test_configuration_falls_back_when_application_credentials_are_incomplete(self):
        base = {"TELEGRAM_BOT_TOKEN": "secret-token", "TELEGRAM_CHAT_ID": "@test"}
        for app_config in ({}, {"TELEGRAM_API_ID": "12345"}, {"TELEGRAM_API_HASH": "b" * 32},
                           {"TELEGRAM_API_ID": "", "TELEGRAM_API_HASH": ""},
                           {"TELEGRAM_API_ID": " \t", "TELEGRAM_API_HASH": "b" * 32},
                           {"TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": "\n"}):
            with self.subTest(app_config=app_config), patch.dict(os.environ, {**base, **app_config}, clear=True):
                self.assertEqual(telegram_ci.validate_config(), "cloud")

    def test_missing_bot_credentials_still_fail_for_both_modes(self):
        for app_config in ({}, {"TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": "b" * 32}):
            for missing in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"):
                with self.subTest(app_config=app_config, missing=missing), patch.dict(os.environ, {
                    "TELEGRAM_BOT_TOKEN": "secret-token", "TELEGRAM_CHAT_ID": "@test", **app_config, missing: " ",
                }, clear=True):
                    with self.assertRaisesRegex(ValueError, missing):
                        telegram_ci.validate_config()

    def test_config_check_exports_mode_without_credentials(self):
        for mode in ("cloud", "local"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                output = Path(temporary) / "outputs"
                output.write_text("previous=value\n", encoding="utf-8")
                config = {"TELEGRAM_BOT_TOKEN": "secret-token", "TELEGRAM_CHAT_ID": "@test",
                          "GITHUB_OUTPUT": str(output)}
                if mode == "local":
                    config.update(TELEGRAM_API_ID="12345", TELEGRAM_API_HASH="b" * 32)
                with patch.dict(os.environ, config, clear=True), patch("sys.stdout", new_callable=io.StringIO) as stdout:
                    self.assertEqual(telegram_ci.check_config(), mode)
                self.assertEqual(output.read_text(encoding="utf-8"), f"previous=value\napi_mode={mode}\n")
                for secret in ("secret-token", "12345", "b" * 32):
                    self.assertNotIn(secret, stdout.getvalue())

    def test_complete_application_credentials_are_validated_without_echoing_values(self):
        config = {"TELEGRAM_BOT_TOKEN": "secret-token", "TELEGRAM_CHAT_ID": "@test",
                  "TELEGRAM_API_ID": "12345", "TELEGRAM_API_HASH": "b" * 32}
        with patch.dict(os.environ, config, clear=True):
            self.assertEqual(telegram_ci.validate_config(), "local")
            for name in ("TELEGRAM_API_ID", "TELEGRAM_API_HASH"):
                with patch.dict(os.environ, {name: "secret-invalid-value"}):
                    with self.assertRaises(ValueError) as captured:
                        telegram_ci.validate_config()
                    self.assertIn(name, str(captured.exception))
                    self.assertNotIn("secret-invalid-value", str(captured.exception))


if __name__ == "__main__":
    unittest.main()
