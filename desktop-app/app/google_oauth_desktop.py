import http.server
import json
import secrets
import threading
import time
import urllib.parse
import webbrowser
from dataclasses import dataclass
from typing import Tuple

import requests
from PyQt5.QtCore import QThread, pyqtSignal


@dataclass(frozen=True)
class OAuthConfig:
    supabase_url: str
    supabase_anon_key: str
    google_client_id: str
    google_client_secret: str
    redirect_port: int = 8769


class GoogleSupabaseSignInThread(QThread):
    """
    Desktop OAuth (browser) -> Google ID token -> Supabase auth (id_token grant).
    """

    status = pyqtSignal(str)
    success = pyqtSignal(str, str, str, str)  # access_token, user_uuid, display_name, picture_url
    failure = pyqtSignal(str)

    def __init__(self, *, config: OAuthConfig, parent=None):
        super().__init__(parent)
        self._cfg = config

    def run(self) -> None:
        try:
            access_token, uid, display_name, picture_url = self._sign_in()
            self.success.emit(access_token, uid, display_name, picture_url)
        except Exception as e:
            self.failure.emit(str(e))

    def _sign_in(self) -> Tuple[str, str, str, str]:
        redirect_uri = f"http://127.0.0.1:{self._cfg.redirect_port}/callback"
        state = secrets.token_urlsafe(24)

        code_box = {"code": None, "state": None, "error": None}
        done = threading.Event()

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                parsed = urllib.parse.urlparse(self.path)
                if parsed.path != "/callback":
                    self.send_response(204)
                    self.end_headers()
                    return
                params = urllib.parse.parse_qs(parsed.query)
                code_box["code"] = (params.get("code") or [None])[0]
                code_box["state"] = (params.get("state") or [None])[0]
                code_box["error"] = (params.get("error") or [None])[0]
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                if code_box["error"]:
                    self.wfile.write(b"<h2>Sign-in failed.</h2>You can close this tab.")
                else:
                    self.wfile.write(b"<h2>Signed in.</h2>You can close this tab and return to the app.")
                done.set()

            def log_message(self, *_args, **_kwargs):
                return

        self.status.emit("Desktop: opening Google sign-in…")
        server = http.server.HTTPServer(("127.0.0.1", self._cfg.redirect_port), Handler)
        server.socket.settimeout(1.0)

        def _serve():
            while not done.is_set():
                try:
                    server.handle_request()
                except Exception:
                    pass

        th = threading.Thread(target=_serve, daemon=True)
        th.start()

        auth_params = {
            "client_id": self._cfg.google_client_id,
            "redirect_uri": redirect_uri,
            "response_type": "code",
            "scope": "openid email profile",
            "state": state,
            "access_type": "offline",
            "prompt": "select_account",
        }
        auth_url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode(auth_params)
        webbrowser.open(auth_url)

        if not done.wait(timeout=180):
            raise TimeoutError("Google sign-in timed out (no redirect received).")

        try:
            server.server_close()
        except Exception:
            pass

        if code_box["state"] != state:
            raise RuntimeError("OAuth state mismatch.")
        if code_box["error"]:
            raise RuntimeError(f"OAuth error: {code_box['error']}")
        if not code_box["code"]:
            raise RuntimeError("Missing OAuth code.")

        self.status.emit("Desktop: exchanging code…")
        token_url = "https://oauth2.googleapis.com/token"
        token_payload = {
            "code": code_box["code"],
            "client_id": self._cfg.google_client_id,
            "client_secret": self._cfg.google_client_secret,
            "redirect_uri": redirect_uri,
            "grant_type": "authorization_code",
        }
        r = requests.post(token_url, data=token_payload, timeout=30)
        if not r.ok:
            raise RuntimeError(
                f"Google token exchange HTTP {r.status_code}: {r.text or r.reason}. "
                "Verifică Web Client ID/Secret, redirect http://127.0.0.1:8769/callback în Google Cloud."
            )
        tokens = r.json()
        google_id_token = tokens.get("id_token")
        if not google_id_token:
            raise RuntimeError("Google token exchange did not return id_token.")
        display_name, picture_url = _extract_profile_from_jwt(google_id_token)

        self.status.emit("Desktop: signing into Supabase…")
        sb_url = f"{self._cfg.supabase_url.rstrip('/')}/auth/v1/token?grant_type=id_token"
        r2 = requests.post(
            sb_url,
            headers={
                "apikey": self._cfg.supabase_anon_key,
                "Content-Type": "application/json",
            },
            json={"provider": "google", "id_token": google_id_token},
            timeout=30,
        )
        r2.raise_for_status()
        data = r2.json()
        access_token = data.get("access_token")
        user = data.get("user") or {}
        uid = user.get("id")
        if not access_token or not uid:
            raise RuntimeError("Supabase sign-in did not return access_token/user id.")
        return str(access_token), str(uid), display_name, picture_url


def _extract_profile_from_jwt(jwt_token: str) -> Tuple[str, str]:
    parts = jwt_token.split(".")
    if len(parts) < 2:
        return "", ""
    payload_b64 = parts[1]
    payload_b64 += "=" * ((4 - len(payload_b64) % 4) % 4)
    try:
        import base64

        payload = json.loads(base64.urlsafe_b64decode(payload_b64.encode("utf-8")).decode("utf-8"))
        name = payload.get("name", "") or ""
        picture = payload.get("picture", "") or ""
        return name, picture
    except Exception:
        return "", ""
