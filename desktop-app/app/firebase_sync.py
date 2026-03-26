import os
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

import requests
from PyQt5.QtCore import QThread, pyqtSignal


@dataclass(frozen=True)
class FirebaseDocumentMeta:
    doc_id: str
    file_name: str
    storage_path: str
    created_at_millis: int


class FirebaseClient:
    """
    Minimal Firebase client using REST (no Admin SDK).
    Desktop flow:
      - signInWithPassword (Firebase Auth REST) => idToken + localId(uid)
      - runQuery (Firestore REST) => metadata docs under users/{uid}/documents
      - download from Firebase Storage using the same idToken
    """

    def __init__(self, *, api_key: str, project_id: str, storage_bucket: str):
        self._api_key = api_key
        self._project_id = project_id
        self._storage_bucket = storage_bucket

        self._base_firestore = (
            f"https://firestore.googleapis.com/v1/projects/{self._project_id}"
            "/databases/(default)/documents"
        )
        self._base_auth = "https://identitytoolkit.googleapis.com/v1"
        self._base_storage = f"https://firebasestorage.googleapis.com/v0/b/{self._storage_bucket}/o"

    def sign_in_with_password(self, *, email: str, password: str) -> Tuple[str, str]:
        url = f"{self._base_auth}/accounts:signInWithPassword?key={self._api_key}"
        payload = {
            "email": email,
            "password": password,
            "returnSecureToken": True,
        }
        r = requests.post(url, json=payload, timeout=30)
        r.raise_for_status()
        data = r.json()
        # idToken = Bearer token; localId = uid
        return data["idToken"], data["localId"]

    def list_user_documents(self, *, id_token: str, uid: str, limit: int = 20) -> List[FirebaseDocumentMeta]:
        """
        Returns recent docs by local ordering of createdAtMillis.
        Firestore structure we expect:
          users/{uid}/documents/{docId} with fields:
            - fileName: string
            - storagePath: string (e.g. users/{uid}/documents/{docId}.pdf)
            - createdAtMillis: integer
        """
        # Query parent: users/{uid}/documents
        parent = f"{self._base_firestore}/users/{uid}"
        url = f"{parent}:runQuery"
        headers = {"Authorization": f"Bearer {id_token}"}

        payload: Dict[str, Any] = {
            "structuredQuery": {
                "from": [{"collectionId": "documents"}],
                "limit": limit,
            }
        }

        r = requests.post(url, headers=headers, json=payload, timeout=30)
        r.raise_for_status()
        rows = r.json()

        out: List[FirebaseDocumentMeta] = []
        for row in rows:
            doc = row.get("document")
            if not doc:
                continue

            name = doc.get("name", "")  # projects/.../documents/users/{uid}/documents/{docId}
            doc_id = name.split("/")[-1] if "/" in name else name
            fields = doc.get("fields", {}) or {}

            def _get(field: str, default: Any) -> Any:
                v = fields.get(field, {}) or {}
                # Firestore REST types are nested like {"stringValue": "..."}
                for k in ("stringValue", "integerValue", "doubleValue"):
                    if k in v:
                        if k == "integerValue":
                            return int(v[k])
                        if k == "doubleValue":
                            return float(v[k])
                        return v[k]
                return default

            file_name = _get("fileName", "scan.pdf")
            storage_path = _get("storagePath", "")
            created_at_millis = _get("createdAtMillis", 0)

            if not storage_path:
                continue
            out.append(
                FirebaseDocumentMeta(
                    doc_id=doc_id,
                    file_name=file_name,
                    storage_path=storage_path,
                    created_at_millis=int(created_at_millis),
                )
            )

        # Client-side ordering (we don't enforce server order in this MVP).
        out.sort(key=lambda d: d.created_at_millis, reverse=True)
        return out

    def download_storage_file(self, *, id_token: str, storage_path: str) -> bytes:
        """
        Downloads bytes from Firebase Storage using the authenticated user token.
        """
        from urllib.parse import quote

        # Encode path in URL.
        encoded_path = quote(storage_path, safe="")
        url = f"{self._base_storage}/{encoded_path}?alt=media"
        headers = {"Authorization": f"Bearer {id_token}"}

        r = requests.get(url, headers=headers, timeout=60)
        r.raise_for_status()
        return r.content


class FirebaseSyncThread(QThread):
    """
    Background sync for the PyQt desktop app.
    """

    status = pyqtSignal(str)

    def __init__(
        self,
        *,
        api_key: str,
        project_id: str,
        storage_bucket: str,
        email: str,
        password: str,
        inbox_dir: str,
        poll_seconds: int = 10,
        parent=None,
    ):
        super().__init__(parent)
        self._client = FirebaseClient(api_key=api_key, project_id=project_id, storage_bucket=storage_bucket)
        self._email = email
        self._password = password
        self._inbox_dir = inbox_dir
        self._poll_seconds = poll_seconds
        self._received_doc_ids: set = set()

    def run(self) -> None:
        os.makedirs(self._inbox_dir, exist_ok=True)

        self.status.emit("Firebase: signing in…")
        try:
            id_token, uid = self._client.sign_in_with_password(
                email=self._email,
                password=self._password,
            )
        except Exception as e:
            self.status.emit(
                f"Firebase REST sync disabled: sign-in failed ({e}). "
                "Check FIREBASE_API_KEY (Web API key from Firebase Console, not placeholder) "
                "and email/password, or unset FIREBASE_* to use only TCP + service account upload.",
            )
            return

        self.status.emit(f"Firebase: connected as {uid}")

        while True:
            try:
                docs = self._client.list_user_documents(id_token=id_token, uid=uid, limit=20)
                new_docs = [d for d in docs if d.doc_id not in self._received_doc_ids]

                for d in new_docs:
                    self.status.emit(f"Firebase: downloading {d.file_name}…")
                    pdf_bytes = self._client.download_storage_file(id_token=id_token, storage_path=d.storage_path)

                    safe_name = d.file_name or f"{d.doc_id}.pdf"
                    if not safe_name.lower().endswith(".pdf"):
                        safe_name += ".pdf"
                    out_path = os.path.join(self._inbox_dir, safe_name)
                    # Avoid collisions.
                    if os.path.exists(out_path):
                        base, ext = os.path.splitext(safe_name)
                        out_path = os.path.join(self._inbox_dir, f"{base}_{int(time.time())}{ext}")

                    with open(out_path, "wb") as f:
                        f.write(pdf_bytes)

                    self._received_doc_ids.add(d.doc_id)
                    self.status.emit(f"Firebase: received {safe_name}")

                time.sleep(self._poll_seconds)
            except Exception as e:
                self.status.emit(f"Firebase sync error: {e}")
                time.sleep(max(2, self._poll_seconds))


class FirebaseUserSyncThread(QThread):
    """
    Background sync when caller already has Firebase idToken + uid
    (e.g. obtained via Google Sign-In on desktop).
    """

    status = pyqtSignal(str)
    pdf_received = pyqtSignal(str)

    def __init__(
        self,
        *,
        api_key: str,
        project_id: str,
        storage_bucket: str,
        id_token: str,
        uid: str,
        inbox_dir: str,
        poll_seconds: int = 10,
        parent=None,
    ):
        super().__init__(parent)
        self._client = FirebaseClient(api_key=api_key, project_id=project_id, storage_bucket=storage_bucket)
        self._id_token = id_token
        self._uid = uid
        self._inbox_dir = inbox_dir
        self._poll_seconds = poll_seconds
        self._received_doc_ids: set = set()

    def run(self) -> None:
        os.makedirs(self._inbox_dir, exist_ok=True)
        self.status.emit(f"Firebase: connected as {self._uid}")

        while True:
            try:
                docs = self._client.list_user_documents(id_token=self._id_token, uid=self._uid, limit=20)
                new_docs = [d for d in docs if d.doc_id not in self._received_doc_ids]

                for d in new_docs:
                    self.status.emit(f"Firebase: downloading {d.file_name}…")
                    pdf_bytes = self._client.download_storage_file(id_token=self._id_token, storage_path=d.storage_path)

                    safe_name = d.file_name or f"{d.doc_id}.pdf"
                    if not safe_name.lower().endswith(".pdf"):
                        safe_name += ".pdf"
                    out_path = os.path.join(self._inbox_dir, safe_name)
                    if os.path.exists(out_path):
                        base, ext = os.path.splitext(safe_name)
                        out_path = os.path.join(self._inbox_dir, f"{base}_{int(time.time())}{ext}")

                    with open(out_path, "wb") as f:
                        f.write(pdf_bytes)

                    self._received_doc_ids.add(d.doc_id)
                    self.status.emit(f"Firebase: received {safe_name}")
                    self.pdf_received.emit(out_path)

                time.sleep(self._poll_seconds)
            except Exception as e:
                self.status.emit(f"Firebase sync error: {e}")
                time.sleep(max(2, self._poll_seconds))

