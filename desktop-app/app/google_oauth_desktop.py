import http.server
import secrets
import threading
import time
import urllib.parse
import webbrowser
from dataclasses import dataclass
from typing import Optional, Tuple

import requests
from PyQt5.QtCore import QThread, pyqtSignal


@dataclass(frozen=True)
class OAuthConfig:
    firebase_api_key: str
    google_client_id: str
    google_client_secret: str
    redirect_port: int = 8769


class GoogleFirebaseSignInThread(QThread):
    """
    Desktop OAuth (browser) -> Google ID token -> Firebase signInWithIdp -> Firebase idToken + uid.
    """

    status = pyqtSignal(str)
    success = pyqtSignal(str, str)  # firebase_id_token, uid
    failure = pyqtSignal(str)

    def __init__(self, *, config: OAuthConfig, parent=None):
        super().__init__(parent)
        self._cfg = config

    def run(self) -> None:
        try:
            firebase_id_token, uid = self._sign_in()
            self.success.emit(firebase_id_token, uid)
        except Exception as e:
            self.failure.emit(str(e))

    def _sign_in(self) -> Tuple[str, str]:
        redirect_uri = f"http://127.0.0.1:{self._cfg.redirect_port}/callback"
        state = secrets.token_urlsafe(24)

        # Start localhost receiver for OAuth callback.
        code_box = {"code": None, "state": None, "error": None}
        done = threading.Event()

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                try:
                    parsed = urllib.parse.urlparse(self.path)
                    if parsed.path != "/callback":
                        self.send_response(404)
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
                finally:
                    done.set()

            def log_message(self, *_args, **_kwargs):  # silence
                return

        self.status.emit("Desktop: opening Google sign-in…")
        server = http.server.HTTPServer(("127.0.0.1", self._cfg.redirect_port), Handler)
        th = threading.Thread(target=server.handle_request, daemon=True)
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

        # Exchange code -> tokens (Google).
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
        r.raise_for_status()
        tokens = r.json()
        google_id_token = tokens.get("id_token")
        if not google_id_token:
            raise RuntimeError("Google token exchange did not return id_token.")

        # Exchange Google ID token -> Firebase session (idToken + localId).
        self.status.emit("Desktop: signing into Firebase…")
        fb_url = (
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp"
            f"?key={self._cfg.firebase_api_key}"
        )
        fb_payload = {
            "postBody": f"id_token={google_id_token}&providerId=google.com",
            "requestUri": redirect_uri,
            "returnIdpCredential": True,
            "returnSecureToken": True,
        }
        r2 = requests.post(fb_url, json=fb_payload, timeout=30)
        r2.raise_for_status()
        fb = r2.json()
        firebase_id_token = fb.get("idToken")
        uid = fb.get("localId")
        if not firebase_id_token or not uid:
            raise RuntimeError("Firebase sign-in did not return idToken/localId.")
        return firebase_id_token, uid

