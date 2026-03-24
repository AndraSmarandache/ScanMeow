import os

from PyQt5.QtCore import Qt, pyqtSignal
from PyQt5.QtGui import QPixmap, QFont
from PyQt5.QtWidgets import (
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from .document_manager import Document, DocumentManager

try:
    import fitz  # PyMuPDF

    HAS_FITZ = True
except ImportError:
    HAS_FITZ = False

# ── palette ────────────────────────────────────────────────────────────────────
_NAV_ACTIVE_BG = "#1a73e8"
_NAV_ACTIVE_FG = "#ffffff"
_NAV_FG = "#5f6368"
_SIDEBAR_BG = "#f0f0f0"
_BTN_BLUE = "#1a73e8"
_BTN_BLUE_HOVER = "#1558c0"
_BTN_BLUE_PRESS = "#0d47a1"
_COL_HEADER = "#80868b"
_ROW_BORDER = "#e8eaed"
_TEXT_PRIMARY = "#202124"
_TEXT_SECONDARY = "#5f6368"

_ASSETS_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "assets")


# ── helpers ────────────────────────────────────────────────────────────────────

def _separator() -> QFrame:
    line = QFrame()
    line.setFrameShape(QFrame.HLine)
    line.setStyleSheet(f"color: {_ROW_BORDER}; margin: 0;")
    return line


# ── sidebar ────────────────────────────────────────────────────────────────────

class _NavButton(QPushButton):
    def __init__(self, icon_char: str, label: str, parent=None):
        super().__init__(f"  {icon_char}   {label}", parent)
        self.setFixedHeight(44)
        self.setCursor(Qt.PointingHandCursor)
        self._set_style(False)

    def _set_style(self, active: bool) -> None:
        if active:
            self.setStyleSheet(f"""
                QPushButton {{
                    background-color: {_NAV_ACTIVE_BG};
                    color: {_NAV_ACTIVE_FG};
                    border: none;
                    text-align: left;
                    padding-left: 18px;
                    font-size: 13px;
                    font-weight: 600;
                }}
            """)
        else:
            self.setStyleSheet(f"""
                QPushButton {{
                    background-color: transparent;
                    color: {_NAV_FG};
                    border: none;
                    text-align: left;
                    padding-left: 18px;
                    font-size: 13px;
                }}
                QPushButton:hover {{
                    background-color: #dde1e7;
                }}
            """)

    def set_active(self, active: bool) -> None:
        self._set_style(active)


class Sidebar(QWidget):
    page_changed = pyqtSignal(int)  # 0 = recent, 1 = all

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedWidth(178)
        self.setStyleSheet(
            f"background-color: {_SIDEBAR_BG}; border-right: 1px solid #d0d0d0;"
        )

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # ── logo area ──────────────────────────────────────────────────────
        logo_area = QWidget()
        logo_area.setFixedHeight(68)
        logo_area.setStyleSheet("background-color: #ffffff;")
        la = QHBoxLayout(logo_area)
        la.setContentsMargins(10, 10, 10, 10)

        icon_lbl = QLabel()
        pix = QPixmap(os.path.join(_ASSETS_DIR, "logo.jpeg"))
        if not pix.isNull():
            crop_w = int(pix.width() * 0.42)
            icon_pix = pix.copy(0, 0, crop_w, pix.height()).scaled(
                44, 44, Qt.KeepAspectRatio, Qt.SmoothTransformation
            )
            icon_lbl.setPixmap(icon_pix)
        icon_lbl.setFixedSize(44, 44)

        name_lbl = QLabel(
            '<span style="color:#1a73e8;font-size:15px;font-weight:700;">Scan</span>'
            '<span style="color:#f5a623;font-size:15px;font-weight:700;">Meow</span>'
        )
        name_lbl.setTextFormat(Qt.RichText)

        la.addWidget(icon_lbl)
        la.addSpacing(4)
        la.addWidget(name_lbl)
        la.addStretch()
        layout.addWidget(logo_area)
        layout.addWidget(_separator())
        layout.addSpacing(6)

        # ── nav buttons ────────────────────────────────────────────────────
        self._btn_recent = _NavButton("📄", "Recent documents")
        self._btn_all = _NavButton("📋", "All documents")
        self._btn_recent.clicked.connect(lambda: self._activate(0))
        self._btn_all.clicked.connect(lambda: self._activate(1))
        layout.addWidget(self._btn_recent)
        layout.addWidget(self._btn_all)
        layout.addStretch()

        # ── bluetooth status ───────────────────────────────────────────────
        bt_w = QWidget()
        bt_w.setFixedHeight(44)
        btl = QHBoxLayout(bt_w)
        btl.setContentsMargins(14, 0, 14, 0)
        bt_dot = QLabel("●")
        bt_dot.setStyleSheet("color: #1a73e8; font-size: 18px;")
        bt_dot.setFixedWidth(22)
        bt_txt = QLabel(
            'Bluetooth: <span style="color:#34a853;font-weight:700;">Connected</span>'
        )
        bt_txt.setTextFormat(Qt.RichText)
        bt_txt.setStyleSheet(f"font-size: 12px; color: {_TEXT_SECONDARY};")
        btl.addWidget(bt_dot)
        btl.addWidget(bt_txt)
        btl.addStretch()
        layout.addWidget(_separator())
        layout.addWidget(bt_w)

        self._activate(0)

    def _activate(self, idx: int) -> None:
        self._btn_recent.set_active(idx == 0)
        self._btn_all.set_active(idx == 1)
        self.page_changed.emit(idx)

    def activate(self, idx: int) -> None:
        """External call to switch active nav without re-emitting."""
        self._btn_recent.set_active(idx == 0)
        self._btn_all.set_active(idx == 1)


# ── document row ───────────────────────────────────────────────────────────────

class _DocumentRow(QWidget):
    open_requested = pyqtSignal(object)

    def __init__(self, doc: Document, parent=None):
        super().__init__(parent)
        self.doc = doc
        self.setAttribute(Qt.WA_StyledBackground, True)
        self.setStyleSheet("background-color: #ffffff;")
        self.setFixedHeight(62)

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        row = QHBoxLayout()
        row.setContentsMargins(16, 0, 16, 0)
        row.setSpacing(12)

        # pdf icon
        icon = QLabel("📄")
        icon.setStyleSheet("font-size: 24px;")
        icon.setFixedWidth(30)
        row.addWidget(icon)

        # name + date
        meta = QVBoxLayout()
        meta.setSpacing(1)
        name_lbl = QLabel(doc.name)
        name_lbl.setStyleSheet(
            f"font-size: 13px; font-weight: 600; color: {_TEXT_PRIMARY};"
        )
        date_lbl = QLabel(doc.received_display)
        date_lbl.setStyleSheet(f"font-size: 11px; color: {_COL_HEADER};")
        meta.addWidget(name_lbl)
        meta.addWidget(date_lbl)
        row.addLayout(meta)
        row.addStretch()

        # size
        size_lbl = QLabel(doc.size_display)
        size_lbl.setStyleSheet(
            f"font-size: 12px; color: {_TEXT_SECONDARY}; min-width: 56px;"
        )
        size_lbl.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        row.addWidget(size_lbl)
        row.addSpacing(16)

        # open button
        btn = QPushButton("Open document")
        btn.setFixedSize(128, 32)
        btn.setStyleSheet(f"""
            QPushButton {{
                background-color: {_BTN_BLUE};
                color: #ffffff;
                border: none;
                border-radius: 4px;
                font-size: 12px;
                font-weight: 500;
            }}
            QPushButton:hover   {{ background-color: {_BTN_BLUE_HOVER}; }}
            QPushButton:pressed {{ background-color: {_BTN_BLUE_PRESS}; }}
        """)
        btn.setCursor(Qt.PointingHandCursor)
        btn.clicked.connect(lambda: self.open_requested.emit(self.doc))
        row.addWidget(btn)

        inner = QWidget()
        inner.setLayout(row)
        inner.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)

        root.addWidget(inner)
        root.addWidget(_separator())


# ── document list view ─────────────────────────────────────────────────────────

class DocumentListView(QWidget):
    open_requested = pyqtSignal(object)
    receive_requested = pyqtSignal()

    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self._title = title
        self.setStyleSheet("background-color: #ffffff;")

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # ── top bar ────────────────────────────────────────────────────────
        topbar = QWidget()
        topbar.setFixedHeight(58)
        topbar.setStyleSheet("background-color: #ffffff;")
        tbl = QHBoxLayout(topbar)
        tbl.setContentsMargins(20, 12, 20, 8)

        title_lbl = QLabel(title)
        title_lbl.setStyleSheet(
            f"font-size: 20px; font-weight: 700; color: {_TEXT_PRIMARY};"
        )
        tbl.addWidget(title_lbl)
        tbl.addStretch()

        sim_btn = QPushButton("+ Simulate Receive")
        sim_btn.setFixedHeight(34)
        sim_btn.setStyleSheet(f"""
            QPushButton {{
                background-color: {_BTN_BLUE};
                color: #ffffff;
                border: none;
                border-radius: 4px;
                font-size: 12px;
                font-weight: 500;
                padding: 0 16px;
            }}
            QPushButton:hover   {{ background-color: {_BTN_BLUE_HOVER}; }}
            QPushButton:pressed {{ background-color: {_BTN_BLUE_PRESS}; }}
        """)
        sim_btn.setCursor(Qt.PointingHandCursor)
        sim_btn.clicked.connect(self.receive_requested)
        tbl.addWidget(sim_btn)

        layout.addWidget(topbar)
        layout.addWidget(_separator())

        # ── column headers ─────────────────────────────────────────────────
        hdr = QWidget()
        hdr.setFixedHeight(30)
        hdr.setStyleSheet("background-color: #f8f9fa;")
        hdrl = QHBoxLayout(hdr)
        hdrl.setContentsMargins(62, 0, 16, 0)  # align with row content

        name_hdr = QLabel("Name  ↑")
        name_hdr.setStyleSheet(
            f"font-size: 11px; font-weight: 600; color: {_COL_HEADER};"
        )
        size_hdr = QLabel("Size")
        size_hdr.setStyleSheet(
            f"font-size: 11px; font-weight: 600; color: {_COL_HEADER};"
        )
        size_hdr.setFixedWidth(56)
        size_hdr.setAlignment(Qt.AlignRight | Qt.AlignVCenter)

        hdrl.addWidget(name_hdr)
        hdrl.addStretch()
        hdrl.addWidget(size_hdr)
        hdrl.addSpacing(144)  # room for "Open document" button
        layout.addWidget(hdr)
        layout.addWidget(_separator())

        # ── scrollable rows ────────────────────────────────────────────────
        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        self._scroll.setFrameShape(QFrame.NoFrame)
        self._scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self._scroll.setStyleSheet("background-color: #ffffff;")

        self._list_widget = QWidget()
        self._list_widget.setStyleSheet("background-color: #ffffff;")
        self._list_layout = QVBoxLayout(self._list_widget)
        self._list_layout.setContentsMargins(0, 0, 0, 0)
        self._list_layout.setSpacing(0)
        self._list_layout.addStretch()

        self._scroll.setWidget(self._list_widget)
        layout.addWidget(self._scroll)

    def set_documents(self, docs: list) -> None:
        # clear existing rows (keep the trailing stretch)
        while self._list_layout.count() > 1:
            item = self._list_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        if not docs:
            empty = QLabel("No documents yet.\nUse '+ Simulate Receive' to add one.")
            empty.setAlignment(Qt.AlignCenter)
            empty.setStyleSheet(f"font-size: 13px; color: {_COL_HEADER}; padding: 40px;")
            self._list_layout.insertWidget(0, empty)
            return

        for doc in docs:
            row = _DocumentRow(doc)
            row.open_requested.connect(self.open_requested)
            self._list_layout.insertWidget(self._list_layout.count() - 1, row)


# ── document viewer ────────────────────────────────────────────────────────────

class DocumentViewer(QWidget):
    back_requested = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setStyleSheet("background-color: #ffffff;")

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # ── top bar ────────────────────────────────────────────────────────
        topbar = QWidget()
        topbar.setFixedHeight(58)
        topbar.setStyleSheet("background-color: #ffffff;")
        tbl = QHBoxLayout(topbar)
        tbl.setContentsMargins(20, 12, 20, 8)

        back_btn = QPushButton("← Back")
        back_btn.setFixedHeight(32)
        back_btn.setStyleSheet(f"""
            QPushButton {{
                background-color: transparent;
                color: {_BTN_BLUE};
                border: 1px solid {_BTN_BLUE};
                border-radius: 4px;
                font-size: 12px;
                padding: 0 14px;
            }}
            QPushButton:hover {{ background-color: #e8f0fe; }}
        """)
        back_btn.setCursor(Qt.PointingHandCursor)
        back_btn.clicked.connect(self.back_requested)

        self._title_lbl = QLabel()
        self._title_lbl.setStyleSheet(
            f"font-size: 18px; font-weight: 700; color: {_TEXT_PRIMARY};"
        )

        tbl.addWidget(back_btn)
        tbl.addSpacing(16)
        tbl.addWidget(self._title_lbl)
        tbl.addStretch()

        layout.addWidget(topbar)
        layout.addWidget(_separator())

        # ── pdf scroll area ────────────────────────────────────────────────
        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        self._scroll.setFrameShape(QFrame.NoFrame)
        self._scroll.setStyleSheet("background-color: #e8eaed;")

        self._pages_widget = QWidget()
        self._pages_widget.setStyleSheet("background-color: #e8eaed;")
        self._pages_layout = QVBoxLayout(self._pages_widget)
        self._pages_layout.setContentsMargins(40, 30, 40, 30)
        self._pages_layout.setSpacing(20)
        self._pages_layout.addStretch()

        self._scroll.setWidget(self._pages_widget)
        layout.addWidget(self._scroll)

    def load_document(self, doc: Document) -> None:
        self._title_lbl.setText(doc.name)

        # clear previous pages
        while self._pages_layout.count() > 1:
            item = self._pages_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        if not os.path.exists(doc.path):
            self._show_error("File not found:\n" + doc.path)
            return

        if not HAS_FITZ:
            self._show_error(
                "PyMuPDF is not installed.\n"
                "Run:  pip install PyMuPDF\n\n"
                "File path:\n" + doc.path
            )
            return

        try:
            pdf = fitz.open(doc.path)
            for page in pdf:
                mat = fitz.Matrix(1.6, 1.6)
                pix = page.get_pixmap(matrix=mat)
                img_bytes = pix.tobytes("ppm")

                page_lbl = QLabel()
                page_lbl.setAlignment(Qt.AlignCenter)
                qpix = QPixmap()
                qpix.loadFromData(img_bytes)

                # white card shadow effect
                page_lbl.setPixmap(qpix)
                page_lbl.setStyleSheet(
                    "background-color: #ffffff;"
                    "border: 1px solid #d0d0d0;"
                    "padding: 0;"
                )
                page_lbl.setFixedSize(qpix.width(), qpix.height())

                self._pages_layout.insertWidget(
                    self._pages_layout.count() - 1, page_lbl, 0, Qt.AlignHCenter
                )
            pdf.close()
        except Exception as exc:
            self._show_error(f"Could not render PDF:\n{exc}")

    def _show_error(self, msg: str) -> None:
        lbl = QLabel(msg)
        lbl.setAlignment(Qt.AlignCenter)
        lbl.setStyleSheet(f"font-size: 13px; color: {_COL_HEADER}; padding: 40px;")
        self._pages_layout.insertWidget(0, lbl)


# ── main window ────────────────────────────────────────────────────────────────

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("ScanMeow")
        self.setMinimumSize(880, 580)
        self.resize(960, 640)

        self._manager = DocumentManager()
        self._current_page = 0  # 0=recent, 1=all

        # ── central widget ─────────────────────────────────────────────────
        central = QWidget()
        self.setCentralWidget(central)
        root = QHBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        # sidebar
        self._sidebar = Sidebar()
        self._sidebar.page_changed.connect(self._on_nav)
        root.addWidget(self._sidebar)

        # stacked content
        self._stack = QStackedWidget()
        self._stack.setStyleSheet("background-color: #ffffff;")
        root.addWidget(self._stack)

        # pages: 0=recent list, 1=all list, 2=viewer
        self._view_recent = DocumentListView("Recent documents")
        self._view_all = DocumentListView("All documents")
        self._viewer = DocumentViewer()

        self._view_recent.open_requested.connect(self._open_doc)
        self._view_all.open_requested.connect(self._open_doc)
        self._view_recent.receive_requested.connect(self._simulate_receive)
        self._view_all.receive_requested.connect(self._simulate_receive)
        self._viewer.back_requested.connect(self._back_to_list)

        self._stack.addWidget(self._view_recent)  # index 0
        self._stack.addWidget(self._view_all)      # index 1
        self._stack.addWidget(self._viewer)         # index 2

        self._refresh_lists()
        self._stack.setCurrentIndex(0)

    # ── slots ──────────────────────────────────────────────────────────────

    def _on_nav(self, idx: int) -> None:
        self._current_page = idx
        self._stack.setCurrentIndex(idx)

    def _open_doc(self, doc: Document) -> None:
        self._viewer.load_document(doc)
        self._stack.setCurrentIndex(2)

    def _back_to_list(self) -> None:
        self._stack.setCurrentIndex(self._current_page)
        self._sidebar.activate(self._current_page)

    def _simulate_receive(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Simulate Receive — select a PDF",
            os.path.expanduser("~"),
            "PDF files (*.pdf)",
        )
        if not path:
            return
        try:
            doc = self._manager.add_from_file(path)
            self._refresh_lists()
            QMessageBox.information(
                self,
                "Document received",
                f'"{doc.name}" was added successfully.',
            )
        except Exception as exc:
            QMessageBox.critical(self, "Error", str(exc))

    # ── helpers ────────────────────────────────────────────────────────────

    def _refresh_lists(self) -> None:
        self._view_recent.set_documents(self._manager.get_recent())
        self._view_all.set_documents(self._manager.get_all())
