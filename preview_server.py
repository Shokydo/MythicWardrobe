#!/usr/bin/env python3
"""
Standalone live-preview server for MythicWardrobe's temporary 3D Web Skin Editor.
Serves the exact bundled src/main/resources/web/editor.html + sample-overlays/
so the user can test the 3D skin viewer and all 49 starter PNGs live in browser.
"""
import json
import os
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
HTML_PATH = os.path.join(BASE_DIR, "src", "main", "resources", "web", "editor.html")
OVERLAYS_DIR = os.path.join(BASE_DIR, "sample-overlays")

LAYER_DEFS = [
    {"id": "body_skin", "priority": 0, "folder": "body_skin", "replacesBaseSkin": True, "clearOuterRegions": []},
    {"id": "eyes", "priority": 5, "folder": "eyes", "replacesBaseSkin": False, "clearOuterRegions": []},
    {"id": "eyebrows", "priority": 8, "folder": "eyebrows", "replacesBaseSkin": False, "clearOuterRegions": []},
    {"id": "hair", "priority": 10, "folder": "hairs", "replacesBaseSkin": False, "clearOuterRegions": ["HEAD_OUTER"]},
    {"id": "beard", "priority": 12, "folder": "beards", "replacesBaseSkin": False, "clearOuterRegions": []},
    {"id": "scars", "priority": 14, "folder": "scars", "replacesBaseSkin": False, "clearOuterRegions": []},
    {"id": "pants", "priority": 15, "folder": "pants", "replacesBaseSkin": False, "clearOuterRegions": ["RIGHT_LEG_OUTER", "LEFT_LEG_OUTER"]},
    {"id": "undershirt", "priority": 20, "folder": "undershirts", "replacesBaseSkin": False, "clearOuterRegions": ["BODY_OUTER", "RIGHT_ARM_OUTER", "LEFT_ARM_OUTER"]},
    {"id": "socks", "priority": 25, "folder": "socks", "replacesBaseSkin": False, "clearOuterRegions": []},
    {"id": "jacket", "priority": 30, "folder": "jackets", "replacesBaseSkin": False, "clearOuterRegions": ["BODY_OUTER", "RIGHT_ARM_OUTER", "LEFT_ARM_OUTER"]},
    {"id": "boots_skin", "priority": 35, "folder": "boots", "replacesBaseSkin": False, "clearOuterRegions": []},
    {"id": "gloves", "priority": 40, "folder": "gloves", "replacesBaseSkin": False, "clearOuterRegions": []},
    {"id": "mask_skin", "priority": 60, "folder": "masks", "replacesBaseSkin": False, "clearOuterRegions": []},
    {"id": "hat", "priority": 70, "folder": "hats", "replacesBaseSkin": False, "clearOuterRegions": []},
]

DEMO_STATE = {
    "bodyType": "wide",
    "activeLayers": {
        "body_skin": "light.png",
        "eyes": "blue.png",
        "hair": "brown_short.png",
        "undershirt": "white_tshirt.png",
        "jacket": "leather_brown.png",
        "pants": "blue_jeans.png",
        "boots_skin": "combat_boots.png",
    },
}


def list_textures(folder: str):
    root = os.path.join(OVERLAYS_DIR, folder)
    if not os.path.isdir(root):
        return []
    found = set()
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            if fn.lower().endswith(".png"):
                found.add(fn)
    return sorted(found)


def resolve_overlay(layer_id: str, filename: str, bodytype: str):
    if not filename or ".." in filename or "/" in filename or "\\" in filename:
        return None
    folder = next((l["folder"] for l in LAYER_DEFS if l["id"] == layer_id), layer_id)
    variant_dir = "slim" if bodytype == "slim" else "wide"
    alt_dir = "wide" if variant_dir == "slim" else "slim"
    candidates = [
        os.path.join(OVERLAYS_DIR, folder, variant_dir, filename),
        os.path.join(OVERLAYS_DIR, folder, filename),
        os.path.join(OVERLAYS_DIR, folder, alt_dir, filename),
    ]
    for path in candidates:
        if os.path.isfile(path):
            return path
    return None


class PreviewHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send(self, code: int, ctype: str, body: bytes):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)

        if parsed.path in ("/", "/index.html"):
            with open(HTML_PATH, "rb") as f:
                self._send(200, "text/html; charset=utf-8", f.read())
            return

        if parsed.path == "/api/state":
            layers_out = []
            for l in LAYER_DEFS:
                entry = dict(l)
                entry["textures"] = list_textures(l["folder"])
                layers_out.append(entry)
            payload = {
                "playerName": "Steve (Превью)",
                "connectedToServer": False,
                "bodyType": DEMO_STATE["bodyType"],
                "layers": layers_out,
                "activeLayers": DEMO_STATE["activeLayers"],
            }
            self._send(200, "application/json; charset=utf-8", json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            return

        if parsed.path == "/api/overlay":
            layer = qs.get("layer", [""])[0]
            fn = qs.get("file", [""])[0]
            bt = qs.get("bodytype", ["wide"])[0]
            path = resolve_overlay(layer, fn, bt)
            if not path:
                self._send(404, "text/plain; charset=utf-8", b"Not found")
                return
            with open(path, "rb") as f:
                self._send(200, "image/png", f.read())
            return

        if parsed.path == "/api/base-skin":
            bt = DEMO_STATE["bodyType"]
            path = resolve_overlay("body_skin", "light.png", bt)
            with open(path, "rb") as f:
                self._send(200, "image/png", f.read())
            return

        self._send(404, "text/plain; charset=utf-8", b"Not found")

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/apply":
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(min(length, 65536))
            try:
                data = json.loads(raw.decode("utf-8"))
            except Exception:
                self._send(400, "application/json; charset=utf-8", json.dumps({"ok": False, "error": "Bad JSON"}).encode("utf-8"))
                return
            DEMO_STATE["bodyType"] = "slim" if data.get("bodyType") == "slim" else "wide"
            DEMO_STATE["activeLayers"] = data.get("layers", {}) if isinstance(data.get("layers"), dict) else {}
            resp = {
                "ok": True,
                "message": f"Демо-режим: сохранено ({len(DEMO_STATE['activeLayers'])} сл., {DEMO_STATE['bodyType']}). На сервере Paper это применит скин игроку через SkinsRestorer!",
            }
            self._send(200, "application/json; charset=utf-8", json.dumps(resp, ensure_ascii=False).encode("utf-8"))
            return
        self._send(405, "text/plain; charset=utf-8", b"Method not allowed")


if __name__ == "__main__":
    host = "0.0.0.0"
    port = int(os.environ.get("PORT", "8095"))
    server = ThreadingHTTPServer((host, port), PreviewHandler)
    print(f"MythicWardrobe Web Editor preview listening on http://{host}:{port}", flush=True)
    server.serve_forever()
