import base64
import json
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import quote

import requests


def jwt_sub(jwt: str) -> str:
    parts = jwt.split(".")
    if len(parts) < 2:
        raise ValueError("invalid jwt")
    payload_b64 = parts[1] + "=" * ((4 - len(parts[1]) % 4) % 4)
    payload = json.loads(base64.urlsafe_b64decode(payload_b64.encode("utf-8")))
    sub = payload.get("sub")
    if not sub:
        raise ValueError("jwt missing sub")
    return str(sub)


@dataclass(frozen=True)
class CloudDocumentMeta:
    doc_id: str
    file_name: str
    storage_path: str
    created_at_millis: int


def _encode_storage_path(storage_path: str) -> str:
    return "/".join(quote(part, safe="") for part in storage_path.split("/"))


class SupabaseClient:
    """REST client for ScanMeow table `documents` and storage bucket `scans`."""

    def __init__(self, *, url: str, anon_key: str):
        self._url = url.rstrip("/")
        self._anon = anon_key

    def _headers(self, access_token: str, *, json_body: bool = False) -> Dict[str, str]:
        h: Dict[str, str] = {"apikey": self._anon, "Authorization": f"Bearer {access_token}"}
        if json_body:
            h["Content-Type"] = "application/json"
        return h

    def list_user_documents(self, *, access_token: str, limit: int = 100) -> List[CloudDocumentMeta]:
        q = (
            f"/rest/v1/documents?select=id,file_name,storage_path,created_at_millis"
            f"&order=created_at_millis.desc&limit={limit}"
        )
        r = requests.get(self._url + q, headers=self._headers(access_token), timeout=30)
        r.raise_for_status()
        rows: Any = r.json()
        out: List[CloudDocumentMeta] = []
        for row in rows:
            out.append(
                CloudDocumentMeta(
                    doc_id=row["id"],
                    file_name=row["file_name"],
                    storage_path=row["storage_path"],
                    created_at_millis=int(row["created_at_millis"]),
                )
            )
        return out

    def find_recent_document_by_file_name(
        self,
        *,
        access_token: str,
        file_name: str,
        min_created_at_millis: int,
    ) -> Optional[CloudDocumentMeta]:
        """Dacă telefonul a salvat deja PDF-ul în cloud, evităm un al doilea INSERT la primirea TCP."""
        fn_enc = quote(file_name, safe="")
        q = (
            f"/rest/v1/documents?file_name=eq.{fn_enc}"
            f"&created_at_millis=gte.{min_created_at_millis}"
            f"&select=id,file_name,storage_path,created_at_millis"
            f"&order=created_at_millis.desc&limit=1"
        )
        r = requests.get(self._url + q, headers=self._headers(access_token), timeout=30)
        r.raise_for_status()
        rows: Any = r.json()
        if not isinstance(rows, list) or not rows:
            return None
        row = rows[0]
        return CloudDocumentMeta(
            doc_id=row["id"],
            file_name=row["file_name"],
            storage_path=row["storage_path"],
            created_at_millis=int(row["created_at_millis"]),
        )

    def download_pdf(self, *, access_token: str, storage_path: str) -> bytes:
        enc = _encode_storage_path(storage_path)
        url = f"{self._url}/storage/v1/object/authenticated/scans/{enc}"
        r = requests.get(url, headers=self._headers(access_token), timeout=120)
        r.raise_for_status()
        return r.content

    def delete_document(self, *, access_token: str, doc_id: str, storage_path: str) -> None:
        """Șterge rândul și obiectul din storage. PostgREST poate răspunde 200/204; 404 e OK dacă e deja șters."""
        # UUID în filtrul eq — păstrăm cratimele ne‑encodate (altfel unele gateway‑uri dau erori false).
        id_enc = quote(str(doc_id), safe="-")
        url_row = f"{self._url}/rest/v1/documents?id=eq.{id_enc}"
        r_row = requests.delete(
            url_row,
            headers={**self._headers(access_token), "Prefer": "return=minimal"},
            timeout=30,
        )
        if r_row.status_code not in (200, 204, 404):
            r_row.raise_for_status()

        enc = _encode_storage_path(storage_path)
        url_st = f"{self._url}/storage/v1/object/scans/{enc}"
        r_st = requests.delete(url_st, headers=self._headers(access_token), timeout=30)
        # După ștergerea rândului, storage poate fi deja curățat sau bucketul răspunde altfel — nu eșuăm în UI.
        if r_st.status_code not in (200, 204, 404):
            try:
                r_st.raise_for_status()
            except requests.HTTPError:
                pass

    def upload_pdf_and_row(
        self,
        *,
        access_token: str,
        pdf_bytes: bytes,
        original_file_name: str,
    ) -> Tuple[str, str]:
        """Returns (document_row_id, storage_path)."""
        uid = jwt_sub(access_token)
        safe = (original_file_name or "scan.pdf").replace("/", "_")
        storage_path = f"{uid}/{int(time.time() * 1000)}_{safe}"
        enc = _encode_storage_path(storage_path)
        up = f"{self._url}/storage/v1/object/scans/{enc}"
        r = requests.post(
            up,
            headers={**self._headers(access_token), "Content-Type": "application/pdf"},
            data=pdf_bytes,
            timeout=120,
        )
        r.raise_for_status()
        body = {
            "user_id": uid,
            "file_name": original_file_name,
            "storage_path": storage_path,
            "created_at_millis": int(time.time() * 1000),
        }
        r2 = requests.post(
            f"{self._url}/rest/v1/documents",
            headers={
                **self._headers(access_token, json_body=True),
                "Prefer": "return=representation",
            },
            json=body,
            timeout=30,
        )
        r2.raise_for_status()
        data = r2.json()
        row_id = ""
        if isinstance(data, list) and data:
            row_id = str(data[0].get("id", ""))
        elif isinstance(data, dict):
            row_id = str(data.get("id", ""))
        if not row_id:
            row_id = self.find_document_id_by_storage_path(
                access_token=access_token,
                storage_path=storage_path,
            )
        return row_id, storage_path

    def find_document_id_by_storage_path(self, *, access_token: str, storage_path: str) -> str:
        """Fallback când INSERT nu întoarce rândul (RLS / Prefer)."""
        sp_enc = quote(storage_path, safe="")
        r = requests.get(
            f"{self._url}/rest/v1/documents?storage_path=eq.{sp_enc}&select=id&limit=1",
            headers=self._headers(access_token),
            timeout=30,
        )
        r.raise_for_status()
        rows = r.json()
        if isinstance(rows, list) and len(rows) > 0:
            return str(rows[0].get("id", ""))
        return ""

    def patch_document_file_name(self, *, access_token: str, doc_id: str, file_name: str) -> None:
        id_enc = quote(str(doc_id), safe="-")
        r = requests.patch(
            f"{self._url}/rest/v1/documents?id=eq.{id_enc}",
            headers={**self._headers(access_token, json_body=True), "Prefer": "return=minimal"},
            json={"file_name": file_name},
            timeout=30,
        )
        r.raise_for_status()
