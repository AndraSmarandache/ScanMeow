import os
import time
import uuid
from typing import Optional

import firebase_admin
from firebase_admin import auth as fb_auth
from firebase_admin import credentials as fb_credentials
from firebase_admin import firestore
from firebase_admin import storage


class FirebaseAdminUploader:
    def __init__(self, *, service_account_json: str, storage_bucket: str):
        self._service_account_json = service_account_json
        self._storage_bucket = storage_bucket
        self._app = None

    def _ensure_init(self) -> None:
        if self._app is not None:
            return

        cred = fb_credentials.Certificate(self._service_account_json)
        # Initialize once per process.
        self._app = firebase_admin.initialize_app(
            cred,
            {
                "storageBucket": self._storage_bucket,
            },
        )

    def is_configured(self) -> bool:
        return bool(self._service_account_json and self._storage_bucket)

    def upload_pdf_from_id_token(
        self,
        *,
        id_token: str,
        pdf_bytes: bytes,
        original_file_name: str,
    ) -> str:
        """
        Verifies Firebase ID token => uid, uploads PDF to:
          users/{uid}/documents/{doc_id}.pdf
        and writes Firestore meta:
          users/{uid}/documents/{doc_id}:
            { fileName, storagePath, createdAtMillis }

        Returns doc_id.
        """
        self._ensure_init()

        decoded = fb_auth.verify_id_token(id_token)
        uid = decoded["uid"]

        doc_id = str(uuid.uuid4())
        storage_path = f"users/{uid}/documents/{doc_id}.pdf"

        bucket = storage.bucket()
        blob = bucket.blob(storage_path)
        blob.upload_from_string(pdf_bytes, content_type="application/pdf")

        db = firestore.client()
        created_at_millis = int(time.time() * 1000)

        meta = {
            "fileName": original_file_name,
            "storagePath": storage_path,
            "createdAtMillis": created_at_millis,
        }
        db.collection("users").document(uid).collection("documents").document(doc_id).set(meta)
        return doc_id

