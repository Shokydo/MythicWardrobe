#!/usr/bin/env python3
"""
MythicWardrobe — Self-Hosted Private Skin-Signer API (MineSkin v2 `/v2/generate` compatible!)
=============================================================================================
Зачем нужен этот сервер:
  • Клиент Minecraft (в online-mode) принимает только скины с RSA-подписью Mojang
    (поля `value` и `signature`).
  • Публичный MineSkin без API-ключа держит лимит ~10 скинов/мин (задержка 6с) и может
    быть перегружен тысячами других серверов.
  • Этот локальный микросервис принимает POST /v2/generate прямо от MythicWardrobe,
    кэширует все подписанные PNG по SHA-256 на диск навсегда и подписывает новые скины
    через ВАШИ собственные лицензионные аккаунты Minecraft Java (пул 1..N аккаунтов)!

Подключение в plugins/MythicWardrobe/config.yml:
  mineskin:
    api-key: "YOUR_OPTIONAL_LOCAL_SECRET"
    base-url: "http://127.0.0.1:8088"

Настройка аккаунтов (accounts.json рядом со скриптом):
  [
    {
      "name": "SignerBot1",
      "uuid": "32-значный-uuid-без-дефисов",
      "access_token": "Bearer-токен Minecraft Services (api.minecraftservices.com)"
    }
  ]

Запуск:
  python3 selfhosted_skin_signer.py --host 127.0.0.1 --port 8088 --accounts accounts.json
"""
import argparse
import hashlib
import json
import os
import threading
import time
import urllib.error
import urllib.request
from email.parser import BytesParser
from email.policy import default as email_default_policy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


CACHE_FILE = "signer_cache.json"
MOJANG_SKIN_UPLOAD_URL = "https://api.minecraftservices.com/minecraft/profile/skins"
MOJANG_PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/{uuid}?unsigned=false"
MIN_ACCOUNT_COOLDOWN_SEC = 12.0  # Safe per-account interval for Mojang skin API


class AccountPool:
    def __init__(self, accounts, cache_path=CACHE_FILE):
        self.accounts = accounts
        self.cooldowns = [0.0] * len(accounts)
        self.lock = threading.Lock()
        self.cache_path = cache_path
        self.cache = self._load_cache()

    def _load_cache(self):
        if os.path.isfile(self.cache_path):
            try:
                with open(self.cache_path, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                return {}
        return {}

    def _save_cache(self):
        tmp = self.cache_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.cache, f, indent=2)
        os.replace(tmp, self.cache_path)

    def get_cached(self, key):
        with self.lock:
            return self.cache.get(key)

    def put_cached(self, key, signed):
        with self.lock:
            self.cache[key] = signed
            self._save_cache()

    def acquire_account(self):
        if not self.accounts:
            raise RuntimeError("Нет настроенных аккаунтов в accounts.json")
        while True:
            with self.lock:
                now = time.time()
                best_idx = min(range(len(self.accounts)), key=lambda i: self.cooldowns[i])
                wait = self.cooldowns[best_idx] - now
                if wait <= 0:
                    self.cooldowns[best_idx] = now + MIN_ACCOUNT_COOLDOWN_SEC
                    return self.accounts[best_idx]
            time.sleep(min(wait, 0.5))


def sign_with_mojang(account, png_bytes, variant="classic"):
    mojang_variant = "slim" if str(variant).lower() == "slim" else "classic"
    boundary = f"----MWSigner{int(time.time() * 1000)}"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="variant"\r\n\r\n{mojang_variant}\r\n'
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="skin.png"\r\n'
        f"Content-Type: image/png\r\n\r\n"
    ).encode("utf-8") + png_bytes + f"\r\n--{boundary}--\r\n".encode("utf-8")

    req = urllib.request.Request(
        MOJANG_SKIN_UPLOAD_URL,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {account['access_token']}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "User-Agent": "MythicWardrobe-SelfHostedSigner/1.5",
        },
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        if resp.status not in (200, 204):
            raise RuntimeError(f"Mojang upload HTTP {resp.status}")

    # Small pause so sessionserver reflects the new skin texture
    time.sleep(0.35)

    clean_uuid = account["uuid"].replace("-", "")
    prof_req = urllib.request.Request(
        MOJANG_PROFILE_URL.format(uuid=clean_uuid),
        headers={"User-Agent": "MythicWardrobe-SelfHostedSigner/1.5"},
    )
    with urllib.request.urlopen(prof_req, timeout=15) as resp:
        profile = json.loads(resp.read().decode("utf-8"))

    for prop in profile.get("properties", []):
        if prop.get("name") == "textures" and prop.get("value") and prop.get("signature"):
            return {
                "value": prop["value"],
                "signature": prop["signature"],
                "variant": mojang_variant,
            }
    raise RuntimeError("Mojang profile did not return signed textures property")


def make_handler(pool: AccountPool, secret_key: str):
    class SignerHandler(BaseHTTPRequestHandler):
        def _send_json(self, status, payload):
            raw = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            if self.path == "/health":
                self._send_json(200, {"ok": True, "accounts": len(pool.accounts), "cached": len(pool.cache)})
                return
            self._send_json(404, {"error": "Not found"})

        def do_POST(self):
            if self.path not in ("/v2/generate", "/v2/queue"):
                self._send_json(404, {"error": "Unknown endpoint"})
                return

            if secret_key:
                auth = self.headers.get("Authorization", "")
                if auth != f"Bearer {secret_key}":
                    self._send_json(401, {"errors": [{"code": "invalid_api_key", "message": "Unauthorized"}]})
                    return

            try:
                content_type = self.headers.get("Content-Type", "")
                if "multipart/form-data" not in content_type.lower():
                    self._send_json(415, {"errors": [{"code": "invalid_content_type", "message": "Expected multipart/form-data"}]})
                    return
                length = int(self.headers.get("Content-Length", "0"))
                raw_body = self.rfile.read(length)
                header_block = f"Content-Type: {content_type}\r\nMIME-Version: 1.0\r\n\r\n".encode("utf-8")
                msg = BytesParser(policy=email_default_policy).parsebytes(header_block + raw_body)

                png_bytes = None
                variant = "classic"
                if msg.is_multipart():
                    for part in msg.iter_parts():
                        cd = part.get("Content-Disposition", "")
                        if 'name="file"' in cd:
                            png_bytes = part.get_payload(decode=True)
                        elif 'name="variant"' in cd:
                            variant = (part.get_payload(decode=True) or b"classic").decode("utf-8", errors="ignore").strip()

                if not png_bytes:
                    self._send_json(400, {"errors": [{"code": "missing_file", "message": "Missing file part"}]})
                    return

                cache_key = hashlib.sha256(png_bytes).hexdigest() + ":" + variant.lower()
                signed = pool.get_cached(cache_key)
                if not signed:
                    account = pool.acquire_account()
                    signed = sign_with_mojang(account, png_bytes, variant)
                    pool.put_cached(cache_key, signed)

                # MineSkin v2 compatible JSON response
                self._send_json(200, {
                    "success": True,
                    "skin": {
                        "variant": signed["variant"],
                        "texture": {
                            "data": {
                                "value": signed["value"],
                                "signature": signed["signature"],
                            }
                        },
                    },
                })
            except urllib.error.HTTPError as e:
                detail = e.read().decode("utf-8", errors="ignore")[:300]
                self._send_json(502, {"errors": [{"code": "skin_change_failed", "message": f"Mojang HTTP {e.code}: {detail}"}]})
            except Exception as e:
                self._send_json(500, {"errors": [{"code": "internal_error", "message": str(e)}]})

    return SignerHandler


def main():
    parser = argparse.ArgumentParser(description="MythicWardrobe Self-Hosted Skin Signer API (MineSkin v2 compatible)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8088)
    parser.add_argument("--accounts", default="accounts.json")
    parser.add_argument("--secret", default="", help="Optional Bearer secret key matching config.yml mineskin.api-key")
    args = parser.parse_args()

    if not os.path.isfile(args.accounts):
        sample = [{"name": "YourBotAccount", "uuid": "00000000000000000000000000000000", "access_token": "PASTE_MINECRAFT_ACCESS_TOKEN_HERE"}]
        with open(args.accounts, "w", encoding="utf-8") as f:
            json.dump(sample, f, indent=2)
        print(f"Created template {args.accounts}. Fill in your licensed Minecraft account UUID + access_token and restart!")
        return

    with open(args.accounts, "r", encoding="utf-8") as f:
        accounts = json.load(f)

    pool = AccountPool(accounts)
    srv = ThreadingHTTPServer((args.host, args.port), make_handler(pool, args.secret))
    print(f"[MythicWardrobe Signer] Listening on http://{args.host}:{args.port} ({len(accounts)} account(s), {len(pool.cache)} cached)")
    srv.serve_forever()


if __name__ == "__main__":
    main()
