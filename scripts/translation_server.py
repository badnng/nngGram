#!/usr/bin/env python3
"""Local LibreTranslate-compatible adapter. Replace translate() to use your own engine."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


MAX_REQUEST_BYTES = 64 * 1024
MAX_RESPONSE_BYTES = 128 * 1024
LANGUAGE_CODE = re.compile(r"[a-zA-Z]{2,8}(?:-[a-zA-Z0-9]{1,8})*")
# nngGram and LibreTranslate use zh/zt for the two Chinese variants.
AZURE_LANGUAGE_CODES = {"zh": "zh-Hans", "zt": "zh-Hant"}
CLIENT_LANGUAGE_CODES = {code.lower(): language for language, code in AZURE_LANGUAGE_CODES.items()}


class TranslationError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def translate(text, source, target):
    """Call your own engine here and return a dict containing translatedText."""
    key = os.environ.get("AZURE_TRANSLATOR_KEY", "").strip()
    if not key:
        raise TranslationError(503, "Azure Translator is not configured")

    params = {"api-version": "3.0", "to": AZURE_LANGUAGE_CODES.get(target, target), "textType": "plain"}
    if source != "auto":
        params["from"] = AZURE_LANGUAGE_CODES.get(source, source)
    headers = {"Content-Type": "application/json; charset=utf-8", "Ocp-Apim-Subscription-Key": key}
    region = os.environ.get("AZURE_TRANSLATOR_REGION", "").strip()
    if region:
        headers["Ocp-Apim-Subscription-Region"] = region
    request = Request(
        "https://api.cognitive.microsofttranslator.com/translate?" + urlencode(params),
        data=json.dumps([{"Text": text}], ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with build_opener(NoRedirect()).open(request, timeout=12) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
        if len(raw) > MAX_RESPONSE_BYTES:
            raise ValueError("Provider response too large")
        item = json.loads(raw)[0]
        translated = item["translations"][0]["text"]
        if not isinstance(translated, str) or not translated.strip():
            raise ValueError("Provider returned an empty translation")
        result = {"translatedText": translated}
        detected = item.get("detectedLanguage")
        if isinstance(detected, dict) and isinstance(detected.get("language"), str):
            code = detected["language"]
            result["detectedLanguage"] = {"language": CLIENT_LANGUAGE_CODES.get(code.lower(), code)}
        return result
    except HTTPError as error:
        error.close()
        if error.code == 429:
            raise TranslationError(429, "Translation quota exceeded") from None
        if error.code in (400, 422):
            raise TranslationError(422, "Unsupported language or text") from None
        if error.code in (401, 403):
            raise TranslationError(403, "Translation provider denied the request") from None
        raise TranslationError(502, "Translation provider failed") from None
    except (URLError, OSError, ValueError, IndexError, KeyError, TypeError, AttributeError):
        raise TranslationError(502, "Translation provider failed") from None


def demo_translation(text, source, target):
    if text.strip() == "Good morning" and source in ("en", "auto") and target == "zh":
        return {"translatedText": "早上好", "detectedLanguage": {"language": "en"}}
    raise TranslationError(422, "Demo accepts only 'Good morning' from English to Simplified Chinese")


class TranslationHandler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(5)
        super().setup()

    def log_message(self, format, *args):
        pass

    def do_POST(self):
        if self.path != "/translate":
            self.send_json(404, {"error": "Use POST /translate"})
            return
        try:
            body = self.read_request()
            self.send_json(200, self.server.translate_text(*body))
        except TranslationError as error:
            self.send_json(error.status, {"error": str(error)})

    def read_request(self):
        if self.headers.get_content_type() != "application/json":
            raise TranslationError(415, "Use application/json")
        try:
            size = int(self.headers.get("Content-Length", ""))
            if not 0 < size <= MAX_REQUEST_BYTES:
                raise ValueError()
            body = json.loads(self.rfile.read(size).decode("utf-8"))
        except (ValueError, OSError):
            raise TranslationError(400, "Invalid JSON request") from None
        if not isinstance(body, dict):
            raise TranslationError(400, "Expected a JSON object")
        text, source, target = body.get("q"), body.get("source", "auto"), body.get("target")
        if not isinstance(text, str) or not text.strip() or len(text) > 4096:
            raise TranslationError(400, "q must be a nonempty string up to 4096 characters")
        if not isinstance(source, str) or not LANGUAGE_CODE.fullmatch(source):
            raise TranslationError(400, "Invalid source language")
        if not isinstance(target, str) or target.lower() == "auto" or not LANGUAGE_CODE.fullmatch(target):
            raise TranslationError(400, "Invalid target language")
        if body.get("format", "text") != "text":
            raise TranslationError(400, "Only plain text is supported")
        return text, "auto" if source.lower() == "auto" else source, target

    def send_json(self, status, payload):
        content = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=5000)
    parser.add_argument("--demo", action="store_true", help="return a fixed sample to test connectivity")
    args = parser.parse_args()
    with ThreadingHTTPServer(("127.0.0.1", args.port), TranslationHandler) as server:
        server.translate_text = demo_translation if args.demo else translate
        print(f"Translation adapter listening at http://127.0.0.1:{server.server_port}/translate", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
