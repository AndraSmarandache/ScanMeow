import os
import time
from typing import Set

import requests
from PyQt5.QtCore import QThread, pyqtSignal

from .supabase_client import SupabaseClient, jwt_sub


class SupabaseUserSyncThread(QThread):
    """
    Poll Supabase for new rows in `documents`, download PDFs to inbox_dir.
    """

    status = pyqtSignal(str)
    pdf_received = pyqtSignal(str, str, str, str)  # path, cloud_doc_id, storage_path, display_name
    names_updated = pyqtSignal()

    def __init__(
        self,
        *,
        supabase_url: str,
        anon_key: str,
        access_token: str,
        user_id: str,
        inbox_dir: str,
        poll_seconds: int = 12,
        document_manager=None,
        parent=None,
    ):
        super().__init__(parent)
        self._client = SupabaseClient(url=supabase_url, anon_key=anon_key)
        self._access_token = access_token
        self._user_id = user_id
        self._inbox_dir = inbox_dir
        self._poll_seconds = poll_seconds
        self._document_manager = document_manager
        self._received_doc_ids: Set[str] = set()

    def _is_token_expired(self) -> bool:
        try:
            import base64, json as _json
            parts = self._access_token.split(".")
            if len(parts) < 2:
                return True
            pad = parts[1] + "=" * ((4 - len(parts[1]) % 4) % 4)
            payload = _json.loads(base64.urlsafe_b64decode(pad.encode()))
            exp = payload.get("exp", 0)
            return time.time() > exp - 30
        except Exception:
            return False

    def run(self) -> None:
        os.makedirs(self._inbox_dir, exist_ok=True)
        self.status.emit(f"Supabase: connected as {self._user_id}")

        while True:
            if self._is_token_expired():
                self.status.emit("Supabase: session expired — please sign in again.")
                time.sleep(60)
                continue
            try:
                docs = self._client.list_user_documents(access_token=self._access_token, limit=50)
                if self._document_manager is not None:
                    pairs = [(d.doc_id, d.file_name) for d in docs]
                    if self._document_manager.sync_cloud_file_names_from_pairs(pairs):
                        self.names_updated.emit()

                new_docs = [d for d in docs if d.doc_id not in self._received_doc_ids]

                for d in new_docs:
                    if self._document_manager is not None and self._document_manager.has_cloud_doc(d.doc_id):
                        self._received_doc_ids.add(d.doc_id)
                        continue
                    self.status.emit(f"Supabase: downloading {d.file_name}…")
                    pdf_bytes = self._client.download_pdf(
                        access_token=self._access_token,
                        storage_path=d.storage_path,
                    )

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
                    self.status.emit(f"Supabase: received {safe_name}")
                    self.pdf_received.emit(out_path, d.doc_id, d.storage_path, d.file_name)

                time.sleep(self._poll_seconds)
            except requests.RequestException as e:
                self.status.emit(f"Supabase sync error: {e}")
                time.sleep(max(2, self._poll_seconds))
            except Exception as e:
                self.status.emit(f"Supabase sync error: {e}")
                time.sleep(max(2, self._poll_seconds))
