import os
import shutil
import sqlite3
from dataclasses import dataclass
from datetime import datetime
from typing import List, Optional, Tuple

_BASE_DIR = os.path.join(os.path.expanduser("~"), "ScanMeow")
DOCS_DIR = os.path.join(_BASE_DIR, "documents")
DB_PATH = os.path.join(_BASE_DIR, "scanmeow.db")

RECENT_LIMIT = 5


@dataclass
class Document:
    id: int
    name: str
    path: str
    size_bytes: int
    received_at: datetime
    cloud_doc_id: Optional[str] = None
    cloud_storage_path: Optional[str] = None

    @property
    def size_display(self) -> str:
        kb = self.size_bytes / 1024
        if kb >= 1024:
            return f"{kb / 1024:.1f} MB"
        return f"{kb:.0f} KB"

    @property
    def received_display(self) -> str:
        dt = self.received_at
        h = dt.hour % 12 or 12
        ap = "AM" if dt.hour < 12 else "PM"
        return f"{dt.month}/{dt.day}/{dt.year}, at {h}:{dt.minute:02d} {ap}"


class DocumentManager:
    def __init__(self):
        os.makedirs(DOCS_DIR, exist_ok=True)
        self._init_db()

    def _connect(self):
        return sqlite3.connect(DB_PATH)

    def _init_db(self) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS documents (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT    NOT NULL,
                    path        TEXT    NOT NULL,
                    size_bytes  INTEGER NOT NULL,
                    received_at TEXT    NOT NULL,
                    cloud_doc_id TEXT,
                    cloud_storage_path TEXT
                )
                """
            )
            cols = {row[1] for row in conn.execute("PRAGMA table_info(documents)").fetchall()}
            if "cloud_doc_id" not in cols:
                conn.execute("ALTER TABLE documents ADD COLUMN cloud_doc_id TEXT")
            if "cloud_storage_path" not in cols:
                conn.execute("ALTER TABLE documents ADD COLUMN cloud_storage_path TEXT")

    def add_from_file(
        self,
        src_path: str,
        cloud_doc_id: Optional[str] = None,
        cloud_storage_path: Optional[str] = None,
        display_name: Optional[str] = None,
    ) -> "Document":
        """Copy a file into managed storage and record it in the DB."""
        name = (display_name or "").strip() or os.path.basename(src_path)
        dst_path = self._unique_path(name)
        shutil.copy2(src_path, dst_path)
        size = os.path.getsize(dst_path)
        received_at = datetime.now()
        with self._connect() as conn:
            cur = conn.execute(
                """
                INSERT INTO documents (name, path, size_bytes, received_at, cloud_doc_id, cloud_storage_path)
                VALUES (?,?,?,?,?,?)
                """,
                (
                    name,
                    dst_path,
                    size,
                    received_at.isoformat(),
                    cloud_doc_id,
                    cloud_storage_path,
                ),
            )
            doc_id = cur.lastrowid
        return Document(
            doc_id,
            name,
            dst_path,
            size,
            received_at,
            cloud_doc_id=cloud_doc_id,
            cloud_storage_path=cloud_storage_path,
        )

    def has_cloud_doc(self, cloud_doc_id: str) -> bool:
        if not cloud_doc_id:
            return False
        with self._connect() as conn:
            row = conn.execute(
                "SELECT 1 FROM documents WHERE cloud_doc_id=? LIMIT 1",
                (cloud_doc_id,),
            ).fetchone()
        return row is not None

    def delete(self, doc_id: int) -> None:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT path FROM documents WHERE id=?",
                (doc_id,),
            ).fetchone()
            if row and os.path.exists(row[0]):
                os.remove(row[0])
            conn.execute("DELETE FROM documents WHERE id=?", (doc_id,))

    def get_recent(self) -> List[Document]:
        return self._query(
            "SELECT id,name,path,size_bytes,received_at,cloud_doc_id,cloud_storage_path FROM documents "
            "ORDER BY received_at DESC LIMIT ?",
            (RECENT_LIMIT,),
        )

    def get_all(self) -> List[Document]:
        return self._query(
            "SELECT id,name,path,size_bytes,received_at,cloud_doc_id,cloud_storage_path FROM documents "
            "ORDER BY received_at DESC"
        )

    def get_recent_cloud(self) -> List[Document]:
        return self._query(
            "SELECT id,name,path,size_bytes,received_at,cloud_doc_id,cloud_storage_path FROM documents "
            "WHERE cloud_doc_id IS NOT NULL AND cloud_storage_path IS NOT NULL "
            "ORDER BY received_at DESC LIMIT ?",
            (RECENT_LIMIT,),
        )

    def get_all_cloud(self) -> List[Document]:
        return self._query(
            "SELECT id,name,path,size_bytes,received_at,cloud_doc_id,cloud_storage_path FROM documents "
            "WHERE cloud_doc_id IS NOT NULL AND cloud_storage_path IS NOT NULL "
            "ORDER BY received_at DESC"
        )

    def update_document_name(self, doc_id: int, new_name: str) -> None:
        with self._connect() as conn:
            conn.execute("UPDATE documents SET name=? WHERE id=?", (new_name, doc_id))

    def sync_cloud_file_names_from_pairs(self, pairs: List[Tuple[str, str]]) -> bool:
        """Synchronize local display names with Supabase `file_name` (e.g. after mobile rename)."""
        changed = False
        with self._connect() as conn:
            for cloud_doc_id, file_name in pairs:
                row = conn.execute(
                    "SELECT id, name FROM documents WHERE cloud_doc_id=?",
                    (cloud_doc_id,),
                ).fetchone()
                if row and row[1] != file_name:
                    conn.execute(
                        "UPDATE documents SET name=? WHERE id=?",
                        (file_name, row[0]),
                    )
                    changed = True
        return changed

    def _query(self, sql: str, params=()) -> List[Document]:
        with self._connect() as conn:
            rows = conn.execute(sql, params).fetchall()
        return [
            Document(
                id=r[0],
                name=r[1],
                path=r[2],
                size_bytes=r[3],
                received_at=datetime.fromisoformat(r[4]),
                cloud_doc_id=r[5],
                cloud_storage_path=r[6],
            )
            for r in rows
        ]

    def _unique_path(self, name: str) -> str:
        base, ext = os.path.splitext(name)
        dst = os.path.join(DOCS_DIR, name)
        counter = 1
        while os.path.exists(dst):
            dst = os.path.join(DOCS_DIR, f"{base} ({counter}){ext}")
            counter += 1
        return dst
