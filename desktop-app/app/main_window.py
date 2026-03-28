import os
import tempfile
import time
from typing import List, Optional, Tuple

from PyQt5.QtCore import Qt, QEvent, QRect, QUrl, QThread, QTimer, pyqtSignal
from PyQt5.QtGui import QDesktopServices
from PyQt5.QtGui import QColor, QPixmap, QFont, QIcon, QKeySequence, QCloseEvent, QPainter, QPainterPath, QPen
from PyQt5.QtWidgets import (
    QAction,
    QCheckBox,
    QFileDialog,
    QInputDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMenu,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QShortcut,
    QSizePolicy,
    QStackedWidget,
    QStyle,
    QToolButton,
    QVBoxLayout,
    QWidgetAction,
    QWidget,
)
import requests

from .document_manager import Document, DocumentManager
from .google_oauth_desktop import GoogleSupabaseSignInThread, OAuthConfig
from .supabase_client import SupabaseClient
from .supabase_sync import SupabaseUserSyncThread

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

_CHK_TICK_PNG_PATH: Optional[str] = None


def _checkbox_white_tick_image_url() -> str:
    """Return a file://-friendly path to a small PNG used as the QSS image for the checked state"""
    global _CHK_TICK_PNG_PATH
    if _CHK_TICK_PNG_PATH is not None and os.path.isfile(_CHK_TICK_PNG_PATH):
        return _CHK_TICK_PNG_PATH.replace("\\", "/")
    pm = QPixmap(16, 16)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing, True)
    pen = QPen(QColor("#ffffff"))
    pen.setWidthF(2.25)
    pen.setCapStyle(Qt.RoundCap)
    pen.setJoinStyle(Qt.RoundJoin)
    p.setPen(pen)
    p.drawLine(3, 8, 7, 12)
    p.drawLine(7, 12, 13, 4)
    p.end()
    fd, path = tempfile.mkstemp(suffix=".png", prefix="smw_chk_")
    os.close(fd)
    pm.save(path, "PNG")
    _CHK_TICK_PNG_PATH = path
    return path.replace("\\", "/")


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
        self.setFixedHeight(46)
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
                    padding-left: 16px;
                    font-size: 15px;
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
                    padding-left: 16px;
                    font-size: 15px;
                }}
                QPushButton:hover {{
                    background-color: #dde1e7;
                }}
            """)

    def set_active(self, active: bool) -> None:
        self._set_style(active)


class Sidebar(QWidget):
    page_changed = pyqtSignal(int)  # 0 = recent, 1 = all
    sign_in_requested = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedWidth(220)
        self.setStyleSheet(
            f"background-color: {_SIDEBAR_BG}; border-right: 1px solid #d0d0d0;"
        )

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self._auth_signed_in = False
        self._profile_name = ""
        self._profile_picture_url = ""

        auth_w = QWidget()
        auth_l = QHBoxLayout(auth_w)
        auth_l.setContentsMargins(0, 30, 0, 0)
        auth_l.setSpacing(6)
        auth_l.addStretch()

        self._sign_in_btn = QPushButton("Sign in")
        self._sign_in_btn.setFixedHeight(32)
        self._sign_in_btn.setStyleSheet("""
            QPushButton {
                background-color: #111111;
                color: #ffffff;
                border: none;
                border-radius: 4px;
                font-size: 12px;
                font-weight: 600;
                padding: 0 14px;
            }
            QPushButton:hover   { background-color: #222222; }
            QPushButton:pressed { background-color: #000000; }
        """)
        self._sign_in_btn.setCursor(Qt.PointingHandCursor)
        self._sign_in_btn.clicked.connect(self.sign_in_requested)
        auth_l.addWidget(self._sign_in_btn)

        self._profile_btn = QToolButton()
        self._profile_btn.setText("👤")
        self._profile_btn.setFixedSize(34, 34)
        self._profile_btn.setStyleSheet("""
            QToolButton {
                background-color: #111111;
                color: #ffffff;
                border: none;
                border-radius: 17px;
                font-size: 16px;
                font-weight: 600;
                padding: 0;
            }
            QToolButton:hover   { background-color: #222222; }
            QToolButton:pressed { background-color: #000000; }
        """)
        self._profile_btn.setCursor(Qt.PointingHandCursor)
        self._profile_btn.clicked.connect(self._open_profile_menu)
        self._profile_btn.setVisible(False)
        auth_l.addWidget(self._profile_btn)
        auth_l.addStretch()
        auth_w.setVisible(False)
        layout.addWidget(auth_w)

        # ── logo ───────────────────────────────────────────────────────────
        logo_lbl = QLabel()
        logo_pix = QPixmap(os.path.join(_ASSETS_DIR, "logo.png"))
        if not logo_pix.isNull():
            # trim transparent margins from the source image
            img = logo_pix.toImage()
            img = img.convertToFormat(img.Format_ARGB32)
            x1, y1, x2, y2 = img.width(), img.height(), 0, 0
            for y in range(img.height()):
                for x in range(img.width()):
                    if (img.pixel(x, y) >> 24) & 0xFF > 0:
                        x1 = min(x1, x)
                        y1 = min(y1, y)
                        x2 = max(x2, x)
                        y2 = max(y2, y)
            if x2 > x1 and y2 > y1:
                logo_pix = QPixmap.fromImage(img.copy(QRect(x1, y1, x2 - x1 + 1, y2 - y1 + 1)))
            logo_pix = logo_pix.scaledToWidth(210, Qt.SmoothTransformation)
            logo_lbl.setPixmap(logo_pix)
        logo_lbl.setAlignment(Qt.AlignCenter)
        logo_lbl.setStyleSheet("background-color: transparent; margin-top: -8px; padding: 0px;")
        logo_lbl.setCursor(Qt.PointingHandCursor)
        logo_lbl.mousePressEvent = lambda e: QDesktopServices.openUrl(
            QUrl("https://github.com/AndraSmarandache/ScanMeow")
        )
        layout.addWidget(logo_lbl)

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
        bt_w.setStyleSheet(f"background-color: {_SIDEBAR_BG}; border: none;")
        btl = QHBoxLayout(bt_w)
        btl.setContentsMargins(14, 0, 14, 0)
        bt_dot = QLabel("●")
        bt_dot.setStyleSheet("color: #34a853; font-size: 16px; border: none;")
        bt_dot.setFixedWidth(22)
        bt_txt = QLabel(
            'Bluetooth: <span style="color:#34a853;font-weight:700;">Connected</span>'
        )
        bt_txt.setTextFormat(Qt.RichText)
        bt_txt.setStyleSheet(f"font-size: 13px; color: {_TEXT_SECONDARY}; border: none;")
        bt_txt.setTextInteractionFlags(Qt.NoTextInteraction)
        btl.addWidget(bt_dot)
        btl.addWidget(bt_txt)
        btl.addStretch()
        layout.addWidget(_separator())
        layout.addWidget(bt_w)

        self._activate(0)

    def set_auth_state(self, signed_in: bool, label: str = "", picture_url: str = "") -> None:
        self._auth_signed_in = signed_in
        self._profile_name = label or ""
        self._profile_picture_url = picture_url or ""
        self._sign_in_btn.setVisible(not signed_in)
        self._profile_btn.setVisible(signed_in)
        self._profile_btn.setText("👤")
        self._profile_btn.setIcon(QIcon())
        if signed_in:
            self._apply_profile_icon()

    def _apply_profile_icon(self) -> None:
        if not self._profile_picture_url:
            return
        try:
            resp = requests.get(self._profile_picture_url, timeout=12)
            resp.raise_for_status()
            pix = QPixmap()
            if pix.loadFromData(resp.content):
                size = min(self._profile_btn.width(), self._profile_btn.height()) - 4
                rounded = QPixmap(size, size)
                rounded.fill(Qt.transparent)
                painter = QPainter(rounded)
                painter.setRenderHint(QPainter.Antialiasing, True)
                path = QPainterPath()
                path.addEllipse(0, 0, size, size)
                painter.setClipPath(path)
                src = pix.scaled(size, size, Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
                painter.drawPixmap(0, 0, src)
                painter.end()

                self._profile_btn.setIcon(QIcon(rounded))
                self._profile_btn.setIconSize(rounded.size())
                self._profile_btn.setText("")
        except Exception:
            pass

    def _open_profile_menu(self) -> None:
        menu = QMenu(self)
        menu.setStyleSheet("""
            QMenu {
                background-color: #ffffff;
                border: 1px solid #d0d0d0;
                padding: 4px;
            }
            QMenu::item {
                padding: 4px 10px;
                color: #202124;
            }
            QMenu::item:selected {
                background-color: #f2f4f7;
                color: #202124;
            }
        """)
        if self._profile_name:
            info = menu.addAction(self._profile_name)
            info.setEnabled(False)
        if self._auth_signed_in:
            sign_out_btn = QPushButton("Sign out")
            sign_out_btn.setFlat(True)
            sign_out_btn.setCursor(Qt.PointingHandCursor)
            sign_out_btn.setStyleSheet("""
                QPushButton {
                    color: #c62828;
                    background-color: transparent;
                    border: none;
                    text-align: left;
                    padding: 4px 10px;
                    min-width: 120px;
                }
                QPushButton:hover {
                    background-color: #c62828;
                    color: #ffffff;
                }
            """)
            sign_out_btn.clicked.connect(lambda: (menu.close(), self.sign_in_requested.emit()))
            sign_out_action = QWidgetAction(menu)
            sign_out_action.setDefaultWidget(sign_out_btn)
            menu.addAction(sign_out_action)
        else:
            auth_action = menu.addAction("Sign in")
            chosen = menu.exec_(self._profile_btn.mapToGlobal(self._profile_btn.rect().bottomLeft()))
            if chosen == auth_action:
                self.sign_in_requested.emit()
            return
        menu.exec_(self._profile_btn.mapToGlobal(self._profile_btn.rect().bottomLeft()))

    def _activate(self, idx: int) -> None:
        self._btn_recent.set_active(idx == 0)
        self._btn_all.set_active(idx == 1)
        self.page_changed.emit(idx)

    def activate(self, idx: int) -> None:
        """Switch the active nav highlight without emitting page_changed again"""
        self._btn_recent.set_active(idx == 0)
        self._btn_all.set_active(idx == 1)


# ── document row ───────────────────────────────────────────────────────────────

class _DocumentRow(QWidget):
    open_requested = pyqtSignal(object)
    selection_toggled = pyqtSignal(int, bool)

    def __init__(self, doc: Document, list_view: "DocumentListView", parent=None):
        super().__init__(parent)
        self.doc = doc
        self._list_view = list_view
        self.setAttribute(Qt.WA_StyledBackground, True)
        self.setStyleSheet("background-color: #ffffff;")
        self.setFixedHeight(62)
        self.setMinimumWidth(460)

        self._lp_timer = QTimer(self)
        self._lp_timer.setSingleShot(True)
        self._lp_timer.timeout.connect(self._on_long_press)

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        row = QHBoxLayout()
        row.setContentsMargins(16, 0, 16, 0)
        row.setSpacing(12)

        self._chk = QCheckBox()
        self._chk.setCursor(Qt.PointingHandCursor)
        self._chk.setVisible(False)
        _tick_url = _checkbox_white_tick_image_url()
        self._chk.setStyleSheet(
            f"""
            QCheckBox::indicator {{
                width: 20px;
                height: 20px;
                border-radius: 10px;
                border: 2px solid {_BTN_BLUE};
                background: #ffffff;
            }}
            QCheckBox::indicator:checked {{
                background-color: {_BTN_BLUE};
                border: 2px solid {_BTN_BLUE};
                image: url("{_tick_url}");
            }}
            """
        )
        self._chk.stateChanged.connect(self._chk_changed)
        row.addWidget(self._chk)

        # pdf icon
        icon = QLabel("📄")
        icon.setStyleSheet("font-size: 36px;")
        icon.setFixedWidth(44)
        row.addWidget(icon)

        # name + date
        meta = QVBoxLayout()
        meta.setSpacing(1)
        name_lbl = QLabel(doc.name)
        name_lbl.setStyleSheet(
            f"font-size: 14px; font-weight: 600; color: {_TEXT_PRIMARY};"
        )
        name_lbl.setMinimumWidth(0)
        name_lbl.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        date_lbl = QLabel(doc.received_display)
        date_lbl.setStyleSheet(f"font-size: 12px; color: {_COL_HEADER};")
        date_lbl.setMinimumWidth(0)
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

        inner.installEventFilter(self)
        for ch in inner.findChildren(QWidget):
            if not isinstance(ch, (QPushButton, QCheckBox)):
                ch.installEventFilter(self)

    def eventFilter(self, obj, event):
        if isinstance(obj, (QPushButton, QCheckBox)):
            return False
        et = event.type()
        if et == QEvent.MouseButtonPress and event.button() == Qt.LeftButton:
            self._long_press_fired = False
            self._lp_timer.start(550)
        elif et == QEvent.MouseButtonRelease and event.button() == Qt.LeftButton:
            self._lp_timer.stop()
            self._long_press_fired = False
        return False

    def _chk_changed(self, st: int) -> None:
        self.selection_toggled.emit(self.doc.id, st == Qt.Checked)

    def _on_long_press(self) -> None:
        self._long_press_fired = True
        self._list_view.on_row_long_select(self.doc)

    def set_checkbox_visible(self, visible: bool) -> None:
        self._chk.setVisible(visible)

    def set_checked(self, checked: bool) -> None:
        self._chk.blockSignals(True)
        self._chk.setChecked(checked)
        self._chk.blockSignals(False)


# ── document list view ─────────────────────────────────────────────────────────

_SORT_MENU_ITEMS: Tuple[Tuple[str, str], ...] = (
    ("Name (A–Z)", "name_asc"),
    ("Name (Z–A)", "name_desc"),
    ("Date (newest first)", "date_desc"),
    ("Date (oldest first)", "date_asc"),
    ("Size (largest first)", "size_desc"),
    ("Size (smallest first)", "size_asc"),
)

class DocumentListView(QWidget):
    open_requested = pyqtSignal(object)
    receive_requested = pyqtSignal()
    sign_in_requested = pyqtSignal()
    delete_selected_requested = pyqtSignal(list)
    refresh_cloud_requested = pyqtSignal()

    def __init__(self, title: str, parent=None):
        super().__init__(parent)
        self._title = title
        self.setStyleSheet("background-color: #ffffff;")
        self._rows: list = []
        self._selected_ids: set = set()
        self._selection_mode = False
        self._documents_source: List[Document] = []
        self._sort_mode: str = (
            "date_desc" if title.lower().startswith("recent") else "name_asc"
        )

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
            f"font-size: 22px; font-weight: 700; color: {_TEXT_PRIMARY};"
        )
        tbl.addWidget(title_lbl)
        tbl.addStretch()

        self._auth_signed_in = False
        self._auth_label = "Not signed in"
        self._profile_name = ""
        self._profile_picture_url = ""

        self._sign_in_btn = QPushButton("Sign in")
        self._sign_in_btn.setFixedHeight(34)
        self._sign_in_btn.setStyleSheet(f"""
            QPushButton {{
                background-color: #111111;
                color: #ffffff;
                border: none;
                border-radius: 4px;
                font-size: 12px;
                font-weight: 600;
                padding: 0 16px;
            }}
            QPushButton:hover   {{ background-color: #222222; }}
            QPushButton:pressed {{ background-color: #000000; }}
        """)
        self._sign_in_btn.setCursor(Qt.PointingHandCursor)
        self._sign_in_btn.clicked.connect(self.sign_in_requested)
        tbl.addWidget(self._sign_in_btn)
        tbl.addSpacing(10)

        self._profile_btn = QToolButton()
        self._profile_btn.setText("👤")
        self._profile_btn.setFixedSize(34, 34)
        self._profile_btn.setStyleSheet(f"""
            QToolButton {{
                background-color: #111111;
                color: #ffffff;
                border: none;
                border-radius: 17px;
                font-size: 16px;
                font-weight: 600;
                padding: 0;
            }}
            QToolButton:hover   {{ background-color: #222222; }}
            QToolButton:pressed {{ background-color: #000000; }}
        """)
        self._profile_btn.setCursor(Qt.PointingHandCursor)
        self._profile_btn.clicked.connect(self._open_profile_menu)
        tbl.addWidget(self._profile_btn)
        tbl.addSpacing(6)

        self._refresh_cloud_btn = QToolButton()
        self._refresh_cloud_btn.setIcon(self.style().standardIcon(QStyle.SP_BrowserReload))
        self._refresh_cloud_btn.setFixedSize(34, 34)
        self._refresh_cloud_btn.setToolButtonStyle(Qt.ToolButtonIconOnly)
        self._refresh_cloud_btn.setCursor(Qt.PointingHandCursor)
        self._refresh_cloud_btn.setToolTip("Refresh cloud list")
        self._refresh_cloud_btn.setEnabled(False)
        self._refresh_cloud_btn.setStyleSheet("""
            QToolButton {
                background-color: transparent;
                border: none;
                border-radius: 4px;
            }
            QToolButton:hover:enabled { background-color: #e8eaed; }
            QToolButton:pressed { background-color: #dadce0; }
            QToolButton:disabled { background-color: transparent; }
        """)
        self._refresh_cloud_btn.clicked.connect(self.refresh_cloud_requested.emit)
        tbl.addWidget(self._refresh_cloud_btn)
        tbl.addSpacing(10)

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
        tbl.addSpacing(8)

        self._done_sel_btn = QPushButton("Done")
        self._done_sel_btn.setFixedHeight(34)
        self._done_sel_btn.setVisible(False)
        self._done_sel_btn.setCursor(Qt.PointingHandCursor)
        self._done_sel_btn.setStyleSheet(f"""
            QPushButton {{
                background-color: #ffffff;
                color: {_TEXT_PRIMARY};
                border: 1px solid #dadce0;
                border-radius: 4px;
                font-size: 12px;
                font-weight: 500;
                padding: 0 14px;
            }}
            QPushButton:hover:enabled {{ background-color: #f8f9fa; }}
            QPushButton:pressed {{ background-color: #e8eaed; }}
            QPushButton:disabled {{ background-color: #f5f5f5; color: #9aa0a6; }}
        """)
        self._done_sel_btn.clicked.connect(self._on_done_selection)
        tbl.addWidget(self._done_sel_btn)
        tbl.addSpacing(8)

        self._delete_sel_btn = QPushButton("Delete selected")
        self._delete_sel_btn.setFixedHeight(34)
        self._delete_sel_btn.setEnabled(False)
        self._delete_sel_btn.setStyleSheet("""
            QPushButton {
                background-color: #c62828;
                color: #ffffff;
                border: none;
                border-radius: 4px;
                font-size: 12px;
                font-weight: 500;
                padding: 0 14px;
            }
            QPushButton:hover:enabled { background-color: #a51f1f; }
            QPushButton:disabled { background-color: #cccccc; color: #666666; }
        """)
        self._delete_sel_btn.setCursor(Qt.PointingHandCursor)
        self._delete_sel_btn.clicked.connect(self._emit_delete_selected)
        tbl.addWidget(self._delete_sel_btn)

        layout.addWidget(topbar)
        layout.addWidget(_separator())

        # ── column headers ─────────────────────────────────────────────────
        hdr = QWidget()
        hdr.setFixedHeight(30)
        hdr.setStyleSheet("background-color: #f8f9fa;")
        hdrl = QHBoxLayout(hdr)
        hdrl.setContentsMargins(48, 0, 16, 0)

        name_hdr_wrap = QWidget()
        nhl = QHBoxLayout(name_hdr_wrap)
        nhl.setContentsMargins(0, 0, 0, 0)
        nhl.setSpacing(2)
        self._name_column_lbl = QLabel("Name")
        self._name_column_lbl.setStyleSheet(
            f"font-size: 11px; font-weight: 600; color: {_COL_HEADER};"
        )
        self._name_sort_btn = QToolButton()
        self._name_sort_btn.setIcon(self.style().standardIcon(QStyle.SP_ArrowDown))
        self._name_sort_btn.setToolButtonStyle(Qt.ToolButtonIconOnly)
        self._name_sort_btn.setAutoRaise(True)
        self._name_sort_btn.setToolTip("Sort documents")
        self._name_sort_btn.setStyleSheet(
            "QToolButton { border: none; padding: 0 2px; background: transparent; }"
            "QToolButton:hover { background: #e8eaed; border-radius: 2px; }"
        )
        self._sort_menu = QMenu(self)
        for label, mode in _SORT_MENU_ITEMS:
            act = QAction(label, self)
            m = mode
            act.triggered.connect(lambda _checked=False, sm=m: self._set_sort_mode(sm))
            self._sort_menu.addAction(act)
        self._name_sort_btn.clicked.connect(self._show_sort_menu_at_button)
        nhl.addWidget(self._name_column_lbl, 0)
        nhl.addWidget(self._name_sort_btn, 0)
        nhl.addStretch(1)

        size_hdr = QLabel("Size")
        size_hdr.setStyleSheet(
            f"font-size: 11px; font-weight: 600; color: {_COL_HEADER};"
        )
        size_hdr.setFixedWidth(56)
        size_hdr.setAlignment(Qt.AlignRight | Qt.AlignVCenter)

        hdrl.addWidget(name_hdr_wrap, 0)
        hdrl.addStretch()
        hdrl.addWidget(size_hdr)
        hdrl.addSpacing(144)  # room for "Open document" button
        layout.addWidget(hdr)
        layout.addWidget(_separator())

        # ── scrollable rows ────────────────────────────────────────────────
        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        self._scroll.setFrameShape(QFrame.NoFrame)
        self._scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        self._scroll.setStyleSheet("background-color: #ffffff;")

        self._list_widget = QWidget()
        self._list_widget.setStyleSheet("background-color: #ffffff;")
        self._list_layout = QVBoxLayout(self._list_widget)
        self._list_layout.setContentsMargins(0, 0, 0, 0)
        self._list_layout.setSpacing(0)
        self._list_layout.addStretch()

        self._scroll.setWidget(self._list_widget)
        layout.addWidget(self._scroll)

        self._shortcut_select_all = QShortcut(QKeySequence.SelectAll, self)
        self._shortcut_select_all.setContext(Qt.WidgetWithChildrenShortcut)
        self._shortcut_select_all.activated.connect(self._select_all_visible)

    def _refresh_selection_chrome(self) -> None:
        self._done_sel_btn.setVisible(self._selection_mode)
        self._delete_sel_btn.setEnabled(self._selection_mode and len(self._selected_ids) > 0)

    def _on_done_selection(self) -> None:
        self._selection_mode = False
        self._selected_ids.clear()
        for row in self._rows:
            row.set_checkbox_visible(False)
            row.set_checked(False)
        self._refresh_selection_chrome()

    def _select_all_visible(self) -> None:
        if not self._rows:
            return
        self._selection_mode = True
        for row in self._rows:
            row.set_checkbox_visible(True)
            self._selected_ids.add(row.doc.id)
            row.set_checked(True)
        self._refresh_selection_chrome()

    def on_row_long_select(self, doc: Document) -> None:
        self._selection_mode = True
        for row in self._rows:
            row.set_checkbox_visible(True)
        if doc.id in self._selected_ids:
            self._selected_ids.discard(doc.id)
            for row in self._rows:
                if row.doc.id == doc.id:
                    row.set_checked(False)
                    break
        else:
            self._selected_ids.add(doc.id)
            for row in self._rows:
                if row.doc.id == doc.id:
                    row.set_checked(True)
                    break
        n = len(self._selected_ids)
        if n == 0:
            self._selection_mode = False
            for row in self._rows:
                row.set_checkbox_visible(False)
        self._refresh_selection_chrome()

    def _sorted_documents(self) -> List[Document]:
        docs = list(self._documents_source)
        key_name = lambda d: d.name.lower()
        key_date = lambda d: d.received_at
        key_size = lambda d: d.size_bytes
        mode = self._sort_mode
        if mode == "name_asc":
            docs.sort(key=key_name)
        elif mode == "name_desc":
            docs.sort(key=key_name, reverse=True)
        elif mode == "date_desc":
            docs.sort(key=key_date, reverse=True)
        elif mode == "date_asc":
            docs.sort(key=key_date)
        elif mode == "size_desc":
            docs.sort(key=key_size, reverse=True)
        elif mode == "size_asc":
            docs.sort(key=key_size)
        else:
            docs.sort(key=key_name)
        return docs

    def _show_sort_menu_at_button(self) -> None:
        btn = self._name_sort_btn
        self._sort_menu.exec_(btn.mapToGlobal(btn.rect().bottomLeft()))

    def _set_sort_mode(self, mode: str) -> None:
        if self._sort_mode == mode:
            return
        self._sort_mode = mode
        if self._documents_source:
            self._rebuild_document_rows(preserve_selection=True)

    def _rebuild_document_rows(self, *, preserve_selection: bool) -> None:
        preserved_ids = set(self._selected_ids) if preserve_selection else set()
        preserved_mode = self._selection_mode if preserve_selection else False

        while self._list_layout.count() > 1:
            item = self._list_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        self._rows = []
        if not preserve_selection:
            self._selected_ids.clear()
            self._selection_mode = False
            self._delete_sel_btn.setEnabled(False)

        sorted_docs = self._sorted_documents()
        if not sorted_docs:
            empty = QLabel("No cloud documents yet.\nSign in to sync, or send a scan from the mobile app.")
            empty.setAlignment(Qt.AlignCenter)
            empty.setStyleSheet(f"font-size: 13px; color: {_COL_HEADER}; padding: 40px;")
            self._list_layout.insertWidget(0, empty)
            self._refresh_selection_chrome()
            return

        for doc in sorted_docs:
            row = _DocumentRow(doc, self)
            row.open_requested.connect(self.open_requested)
            row.selection_toggled.connect(self._on_row_selection)
            self._rows.append(row)
            self._list_layout.insertWidget(self._list_layout.count() - 1, row)

        if preserve_selection and preserved_mode and preserved_ids:
            self._selection_mode = True
            self._selected_ids = {i for i in preserved_ids if any(r.doc.id == i for r in self._rows)}
            for row in self._rows:
                row.set_checkbox_visible(True)
                row.set_checked(row.doc.id in self._selected_ids)
        self._refresh_selection_chrome()

    def _on_row_selection(self, doc_sql_id: int, checked: bool) -> None:
        if checked:
            self._selected_ids.add(doc_sql_id)
        else:
            self._selected_ids.discard(doc_sql_id)
        n = len(self._selected_ids)
        if n == 0:
            self._selection_mode = False
            for row in self._rows:
                row.set_checkbox_visible(False)
        self._refresh_selection_chrome()

    def _emit_delete_selected(self) -> None:
        docs = [r.doc for r in self._rows if r.doc.id in self._selected_ids]
        if docs:
            self.delete_selected_requested.emit(docs)

    def set_auth_state(self, signed_in: bool, label: str = "", picture_url: str = "") -> None:
        self._auth_signed_in = signed_in
        self._auth_label = label if (signed_in and label) else ("Signed in" if signed_in else "Not signed in")
        self._profile_name = label or ""
        self._profile_picture_url = picture_url or ""
        self._sign_in_btn.setVisible(not signed_in)
        self._profile_btn.setVisible(signed_in)
        self._profile_btn.setText("👤")
        self._profile_btn.setIcon(QIcon())
        self._refresh_cloud_btn.setEnabled(signed_in)
        self._refresh_cloud_btn.setToolTip(
            "Refresh cloud list (titles and new documents)"
            if signed_in
            else "Sign in to refresh the cloud list"
        )
        if signed_in:
            self._apply_profile_icon()

    def _apply_profile_icon(self) -> None:
        if not self._profile_picture_url:
            return
        try:
            resp = requests.get(self._profile_picture_url, timeout=12)
            resp.raise_for_status()
            pix = QPixmap()
            if pix.loadFromData(resp.content):
                size = min(self._profile_btn.width(), self._profile_btn.height()) - 4
                rounded = QPixmap(size, size)
                rounded.fill(Qt.transparent)
                painter = QPainter(rounded)
                painter.setRenderHint(QPainter.Antialiasing, True)
                path = QPainterPath()
                path.addEllipse(0, 0, size, size)
                painter.setClipPath(path)
                src = pix.scaled(size, size, Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
                painter.drawPixmap(0, 0, src)
                painter.end()

                self._profile_btn.setIcon(QIcon(rounded))
                self._profile_btn.setIconSize(rounded.size())
                self._profile_btn.setText("")
        except Exception:
            pass

    def _open_profile_menu(self) -> None:
        menu = QMenu(self)
        menu.setStyleSheet("""
            QMenu {
                background-color: #ffffff;
                border: 1px solid #d0d0d0;
                padding: 4px;
            }
            QMenu::item {
                padding: 4px 10px;
                color: #202124;
            }
            QMenu::item:selected {
                background-color: #f2f4f7;
                color: #202124;
            }
        """)
        info = menu.addAction(self._profile_name or self._auth_label)
        info.setEnabled(False)
        if self._auth_signed_in:
            sign_out_btn = QPushButton("Sign out")
            sign_out_btn.setFlat(True)
            sign_out_btn.setCursor(Qt.PointingHandCursor)
            sign_out_btn.setStyleSheet("""
                QPushButton {
                    color: #c62828;
                    background-color: transparent;
                    border: none;
                    text-align: left;
                    padding: 4px 10px;
                    min-width: 120px;
                }
                QPushButton:hover {
                    background-color: #c62828;
                    color: #ffffff;
                }
            """)
            sign_out_btn.clicked.connect(lambda: (menu.close(), self.sign_in_requested.emit()))
            sign_out_action = QWidgetAction(menu)
            sign_out_action.setDefaultWidget(sign_out_btn)
            menu.addAction(sign_out_action)
        else:
            auth_action = menu.addAction("Sign in")
            chosen = menu.exec_(self._profile_btn.mapToGlobal(self._profile_btn.rect().bottomLeft()))
            if chosen == auth_action:
                self.sign_in_requested.emit()
            return

        menu.exec_(self._profile_btn.mapToGlobal(self._profile_btn.rect().bottomLeft()))

    def set_documents(self, docs: list) -> None:
        self._documents_source = list(docs) if docs else []
        self._rebuild_document_rows(preserve_selection=False)


# ── document viewer ────────────────────────────────────────────────────────────


class _LongPressTitleLabel(QLabel):
    """Label that emits a signal after a ~550ms press (cloud title rename)"""

    long_pressed = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self._hold = QTimer(self)
        self._hold.setSingleShot(True)
        self._hold.timeout.connect(self.long_pressed.emit)

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self._hold.start(550)
        super().mousePressEvent(event)

    def mouseReleaseEvent(self, event):
        self._hold.stop()
        super().mouseReleaseEvent(event)

    def leaveEvent(self, event):
        self._hold.stop()
        super().leaveEvent(event)


class DocumentViewer(QWidget):
    back_requested = pyqtSignal()
    rename_title_requested = pyqtSignal()

    _ZOOM_STEPS = [0.25, 0.5, 0.67, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setStyleSheet("background-color: #ffffff;")

        self._pdf = None
        self._doc: Optional[Document] = None
        self._zoom = 1.0
        self._rotation = 0
        self._two_page = False
        self._page_labels = []

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

        self._title_lbl = _LongPressTitleLabel()
        self._title_lbl.setStyleSheet(
            f"font-size: 18px; font-weight: 700; color: {_TEXT_PRIMARY};"
        )
        self._title_lbl.setToolTip("Long press to rename title (cloud documents only)")
        self._title_lbl.long_pressed.connect(self.rename_title_requested)

        tbl.addWidget(back_btn)
        tbl.addSpacing(16)
        tbl.addWidget(self._title_lbl)
        tbl.addStretch()

        layout.addWidget(topbar)
        layout.addWidget(_separator())

        # ── toolbar ────────────────────────────────────────────────────────
        toolbar = QWidget()
        toolbar.setFixedHeight(44)
        toolbar.setStyleSheet("background-color: #f8f9fa;")
        tbar = QHBoxLayout(toolbar)
        tbar.setContentsMargins(16, 0, 16, 0)
        tbar.setSpacing(2)

        def _tb(text, tooltip):
            b = QPushButton(text)
            b.setFixedSize(34, 32)
            b.setCursor(Qt.PointingHandCursor)
            b.setToolTip(tooltip)
            b.setStyleSheet("""
                QPushButton {
                    background: transparent; border: none;
                    border-radius: 4px; font-size: 15px; color: #3c4043;
                }
                QPushButton:hover   { background: #e8eaed; }
                QPushButton:pressed { background: #d2d3d5; }
            """)
            return b

        def _divider():
            d = QFrame()
            d.setFrameShape(QFrame.VLine)
            d.setStyleSheet("color: #dadce0;")
            d.setFixedHeight(24)
            return d

        self._prev_btn = _tb("‹", "Previous page")
        self._page_lbl = QLabel("Page 1 of 1")
        self._page_lbl.setStyleSheet(f"font-size: 12px; color: {_TEXT_SECONDARY}; min-width: 80px;")
        self._page_lbl.setAlignment(Qt.AlignCenter)
        self._next_btn = _tb("›", "Next page")
        self._prev_btn.clicked.connect(self._prev_page)
        self._next_btn.clicked.connect(self._next_page)

        self._zoom_out_btn = _tb("−", "Zoom out")
        self._zoom_lbl = QLabel("100%")
        self._zoom_lbl.setStyleSheet(f"font-size: 12px; color: {_TEXT_SECONDARY}; min-width: 44px;")
        self._zoom_lbl.setAlignment(Qt.AlignCenter)
        self._zoom_in_btn = _tb("+", "Zoom in")
        self._zoom_out_btn.clicked.connect(self._zoom_out)
        self._zoom_in_btn.clicked.connect(self._zoom_in)

        fit_btn       = _tb("↔", "Fit to width")
        rot_left_btn  = _tb("↺", "Rotate left")
        rot_right_btn = _tb("↻", "Rotate right")
        self._layout_btn = _tb("⊟", "Toggle single/two page view")
        fit_btn.clicked.connect(self._fit_to_width)
        rot_left_btn.clicked.connect(self._rotate_left)
        rot_right_btn.clicked.connect(self._rotate_right)
        self._layout_btn.clicked.connect(self._toggle_layout)

        tbar.addWidget(self._prev_btn)
        tbar.addWidget(self._page_lbl)
        tbar.addWidget(self._next_btn)
        tbar.addSpacing(4)
        tbar.addWidget(_divider())
        tbar.addSpacing(4)
        tbar.addWidget(self._zoom_out_btn)
        tbar.addWidget(self._zoom_lbl)
        tbar.addWidget(self._zoom_in_btn)
        tbar.addSpacing(4)
        tbar.addWidget(_divider())
        tbar.addSpacing(4)
        tbar.addWidget(fit_btn)
        tbar.addSpacing(4)
        tbar.addWidget(_divider())
        tbar.addSpacing(4)
        tbar.addWidget(rot_left_btn)
        tbar.addWidget(rot_right_btn)
        tbar.addSpacing(4)
        tbar.addWidget(_divider())
        tbar.addSpacing(4)
        tbar.addWidget(self._layout_btn)
        tbar.addStretch()

        layout.addWidget(toolbar)
        layout.addWidget(_separator())

        # ── pdf scroll area ────────────────────────────────────────────────
        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        self._scroll.setFrameShape(QFrame.NoFrame)
        self._scroll.setStyleSheet("background-color: #e8eaed;")
        self._scroll.verticalScrollBar().valueChanged.connect(self._on_scroll)

        self._pages_widget = QWidget()
        self._pages_widget.setStyleSheet("background-color: #e8eaed;")
        self._pages_layout = QVBoxLayout(self._pages_widget)
        self._pages_layout.setContentsMargins(40, 30, 40, 30)
        self._pages_layout.setSpacing(20)
        self._pages_layout.addStretch()

        self._scroll.setWidget(self._pages_widget)
        layout.addWidget(self._scroll)

    # ── public ─────────────────────────────────────────────────────────────

    def current_document(self) -> Optional[Document]:
        return self._doc

    def set_viewer_title(self, text: str) -> None:
        self._title_lbl.setText(text)

    def load_document(self, doc: Document) -> None:
        self._doc = doc
        self._title_lbl.setText(doc.name)
        if self._pdf:
            self._pdf.close()
            self._pdf = None

        if not os.path.exists(doc.path):
            self._show_error("File not found:\n" + doc.path)
            return
        if not HAS_FITZ:
            self._show_error("PyMuPDF is not installed.\nRun:  pip install PyMuPDF")
            return

        try:
            self._pdf = fitz.open(doc.path)
            self._zoom = 1.0
            self._rotation = 0
            self._two_page = False
            self._layout_btn.setText("⊟")
            self._render()
        except Exception as exc:
            self._show_error(f"Could not open PDF:\n{exc}")

    # ── rendering ──────────────────────────────────────────────────────────

    def _render(self) -> None:
        self._clear_pages()
        self._page_labels = []
        if not self._pdf:
            return

        n = len(self._pdf)
        self._page_lbl.setText(f"Page 1 of {n}")
        self._zoom_lbl.setText(f"{round(self._zoom * 100)}%")

        mat = fitz.Matrix(self._zoom * 1.6, self._zoom * 1.6).prerotate(self._rotation)
        pixmaps = []
        for page in self._pdf:
            pix = page.get_pixmap(matrix=mat)
            qpix = QPixmap()
            qpix.loadFromData(pix.tobytes("ppm"))
            pixmaps.append(qpix)

        if self._two_page:
            i = 0
            while i < len(pixmaps):
                row_w = QWidget()
                row_w.setStyleSheet("background-color: transparent;")
                row_h = QHBoxLayout(row_w)
                row_h.setContentsMargins(0, 0, 0, 0)
                row_h.setSpacing(12)
                for qpix in pixmaps[i:i + 2]:
                    lbl = self._make_page_label(qpix)
                    self._page_labels.append(lbl)
                    row_h.addWidget(lbl)
                row_h.addStretch()
                self._pages_layout.insertWidget(
                    self._pages_layout.count() - 1, row_w, 0, Qt.AlignHCenter
                )
                i += 2
        else:
            for qpix in pixmaps:
                lbl = self._make_page_label(qpix)
                self._page_labels.append(lbl)
                self._pages_layout.insertWidget(
                    self._pages_layout.count() - 1, lbl, 0, Qt.AlignHCenter
                )

    def _make_page_label(self, qpix: QPixmap) -> QLabel:
        lbl = QLabel()
        lbl.setAlignment(Qt.AlignCenter)
        lbl.setPixmap(qpix)
        lbl.setStyleSheet("background-color: #ffffff; border: 1px solid #d0d0d0; padding: 0;")
        lbl.setFixedSize(qpix.width(), qpix.height())
        return lbl

    def _clear_pages(self) -> None:
        while self._pages_layout.count() > 1:
            item = self._pages_layout.takeAt(0)
            w = item.widget()
            if w:
                w.deleteLater()

    # ── toolbar slots ──────────────────────────────────────────────────────

    def _zoom_in(self) -> None:
        for z in self._ZOOM_STEPS:
            if z > self._zoom + 0.001:
                self._zoom = z
                break
        self._render()

    def _zoom_out(self) -> None:
        for z in reversed(self._ZOOM_STEPS):
            if z < self._zoom - 0.001:
                self._zoom = z
                break
        self._render()

    def _fit_to_width(self) -> None:
        if not self._pdf:
            return
        available = self._scroll.viewport().width() - 80
        page_w = self._pdf[0].rect.width
        self._zoom = max(0.25, available / (page_w * 1.6))
        self._render()

    def _rotate_left(self) -> None:
        self._rotation = (self._rotation - 90) % 360
        self._render()

    def _rotate_right(self) -> None:
        self._rotation = (self._rotation + 90) % 360
        self._render()

    def _toggle_layout(self) -> None:
        self._two_page = not self._two_page
        self._layout_btn.setText("⊞" if self._two_page else "⊟")
        self._render()

    def _prev_page(self) -> None:
        if not self._page_labels:
            return
        cur = self._current_visible_page()
        if cur > 0:
            self._scroll.ensureWidgetVisible(self._page_labels[cur - 1])

    def _next_page(self) -> None:
        if not self._page_labels:
            return
        cur = self._current_visible_page()
        if cur < len(self._page_labels) - 1:
            self._scroll.ensureWidgetVisible(self._page_labels[cur + 1])

    def _on_scroll(self) -> None:
        if not self._page_labels or not self._pdf:
            return
        idx = self._current_visible_page()
        self._page_lbl.setText(f"Page {idx + 1} of {len(self._pdf)}")

    def _current_visible_page(self) -> int:
        vp     = self._scroll.viewport()
        vp_mid = self._scroll.verticalScrollBar().value() + vp.height() // 2
        best, best_dist = 0, float("inf")
        for i, lbl in enumerate(self._page_labels):
            lbl_mid = lbl.mapTo(self._pages_widget, lbl.rect().topLeft()).y() + lbl.height() // 2
            dist = abs(lbl_mid - vp_mid)
            if dist < best_dist:
                best_dist, best = dist, i
        return best

    # ── error ──────────────────────────────────────────────────────────────

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
        self.setMinimumSize(960, 580)
        self.resize(1040, 660)

        self._manager = DocumentManager()
        self._current_page = 0  # 0=recent, 1=all
        self._sb_url = ""
        self._sb_anon = ""
        self._sb_access_token = None
        self._signed_in_uid = None  # set after Google/Supabase sign-in before the first list refresh

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
        self._view_recent.sign_in_requested.connect(self._sign_in_google)
        self._view_all.sign_in_requested.connect(self._sign_in_google)
        self._view_recent.delete_selected_requested.connect(self._on_delete_selected_docs)
        self._view_all.delete_selected_requested.connect(self._on_delete_selected_docs)
        self._view_recent.refresh_cloud_requested.connect(self._refresh_cloud_manual)
        self._view_all.refresh_cloud_requested.connect(self._refresh_cloud_manual)
        self._viewer.back_requested.connect(self._back_to_list)
        self._viewer.rename_title_requested.connect(self._on_viewer_rename_title)

        self._stack.addWidget(self._view_recent)  # index 0
        self._stack.addWidget(self._view_all)      # index 1
        self._stack.addWidget(self._viewer)         # index 2

        self._refresh_lists()
        self._stack.setCurrentIndex(0)

        # ── desktop receiver (TCP test channel for mobile Share) ─────────
        # Mobile sends: "SMK1" + uint16 nameLen + filename UTF-8 + uint64 fileSize + file bytes
        self._start_pdf_receiver()

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
        if self._signed_in_uid:
            QMessageBox.information(
                self,
                "Cloud only",
                "Lists show only cloud documents. Use the mobile app or TCP transfer while signed in.",
            )
            return
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
                f'"{doc.name}" was added (local only — sign in to use cloud lists).',
            )
        except Exception as exc:
            QMessageBox.critical(self, "Error", str(exc))

    def _on_viewer_rename_title(self) -> None:
        doc = self._viewer.current_document()
        if not doc or not doc.cloud_doc_id:
            QMessageBox.information(
                self,
                "Rename",
                "Renaming is only available for cloud-synced documents.",
            )
            return
        if not (self._sb_access_token and self._sb_url and self._sb_anon):
            return
        new_name, ok = QInputDialog.getText(
            self,
            "Document title",
            "Display name:",
            text=doc.name,
        )
        if not ok:
            return
        new_name = (new_name or "").strip()
        if not new_name:
            return
        try:
            client = SupabaseClient(url=self._sb_url, anon_key=self._sb_anon)
            client.patch_document_file_name(
                access_token=self._sb_access_token,
                doc_id=doc.cloud_doc_id,
                file_name=new_name,
            )
        except Exception as e:
            QMessageBox.critical(self, "Rename failed", str(e))
            return
        self._manager.update_document_name(doc.id, new_name)
        self._viewer.set_viewer_title(new_name)
        self._refresh_lists()

    # ── helpers ────────────────────────────────────────────────────────────

    def _refresh_lists(self) -> None:
        if self._signed_in_uid and self._sb_access_token:
            recent = self._manager.get_recent_cloud()
            all_docs = self._manager.get_all_cloud()
        else:
            recent, all_docs = [], []
        self._view_recent.set_documents(recent)
        self._view_all.set_documents(all_docs)

    def _start_pdf_receiver(self) -> None:
        base_dir = os.path.join(os.path.expanduser("~"), "ScanMeow")
        inbox_dir = os.path.join(base_dir, "inbox")
        os.makedirs(inbox_dir, exist_ok=True)

        self._pdf_receiver = PdfReceiver(
            port=5566,
            inbox_dir=inbox_dir,
            parent=self,
        )
        self._pdf_receiver.pdf_received.connect(self._on_pdf_received)
        self._pdf_receiver.start()

        self._cloud_user_sync = None
        self._google_sign_in = None
        self._signed_in_uid = None
        self._set_auth_state(False, "")

    def _set_auth_state(self, signed_in: bool, label: str, picture_url: str = "") -> None:
        self._view_recent.set_auth_state(signed_in, label, picture_url)
        self._view_all.set_auth_state(signed_in, label, picture_url)

    def _on_firebase_status(self, msg: str) -> None:
        try:
            self.statusBar().showMessage(msg, 10000)
        except Exception:
            # Ignore if the status bar is not available
            pass

    def _sign_in_google(self) -> None:
        if self._signed_in_uid:
            if self._cloud_user_sync is not None:
                try:
                    self._cloud_user_sync.terminate()
                except Exception:
                    pass
                self._cloud_user_sync = None
            self._signed_in_uid = None
            self._sb_access_token = None
            self._sb_url = ""
            self._sb_anon = ""
            self._set_auth_state(False, "")
            self._on_firebase_status("Signed out.")
            self._refresh_lists()
            return

        sb_url = os.environ.get("SUPABASE_URL", "").strip()
        sb_anon = os.environ.get("SUPABASE_ANON_KEY", "").strip()
        client_id = os.environ.get("GOOGLE_OAUTH_CLIENT_ID", "").strip()
        client_secret = os.environ.get("GOOGLE_OAUTH_CLIENT_SECRET", "").strip()

        if not (sb_url and sb_anon and client_id and client_secret):
            QMessageBox.information(
                self,
                "Missing config",
                "Set env vars before signing in:\n"
                "- SUPABASE_URL\n"
                "- SUPABASE_ANON_KEY\n"
                "- GOOGLE_OAUTH_CLIENT_ID\n"
                "- GOOGLE_OAUTH_CLIENT_SECRET\n",
            )
            return

        cfg = OAuthConfig(
            supabase_url=sb_url,
            supabase_anon_key=sb_anon,
            google_client_id=client_id,
            google_client_secret=client_secret,
            redirect_port=8769,
        )
        self._google_sign_in = GoogleSupabaseSignInThread(config=cfg, parent=self)
        self._google_sign_in.status.connect(self._on_firebase_status)
        self._google_sign_in.success.connect(
            lambda token, uid, display_name, picture_url: self._start_supabase_user_sync(
                supabase_url=sb_url,
                anon_key=sb_anon,
                access_token=token,
                uid=uid,
                display_name=display_name,
                picture_url=picture_url,
            )
        )
        self._google_sign_in.failure.connect(lambda err: QMessageBox.critical(self, "Sign-in failed", err))
        self._google_sign_in.start()

    def _start_supabase_user_sync(
        self,
        *,
        supabase_url: str,
        anon_key: str,
        access_token: str,
        uid: str,
        display_name: str,
        picture_url: str,
    ) -> None:
        base_dir = os.path.join(os.path.expanduser("~"), "ScanMeow")
        inbox_dir = os.path.join(base_dir, "supabase_inbox")
        os.makedirs(inbox_dir, exist_ok=True)

        if self._cloud_user_sync is not None:
            try:
                self._cloud_user_sync.terminate()
            except Exception:
                pass

        self._sb_url = supabase_url
        self._sb_anon = anon_key
        self._sb_access_token = access_token

        self._cloud_user_sync = SupabaseUserSyncThread(
            supabase_url=supabase_url,
            anon_key=anon_key,
            access_token=access_token,
            user_id=uid,
            inbox_dir=inbox_dir,
            poll_seconds=12,
            document_manager=self._manager,
            parent=self,
        )
        self._cloud_user_sync.status.connect(self._on_firebase_status)
        self._cloud_user_sync.pdf_received.connect(self._on_pdf_received)
        self._cloud_user_sync.names_updated.connect(self._refresh_lists)
        self._signed_in_uid = uid
        self._set_auth_state(True, display_name or uid, picture_url)
        # Import cloud rows before starting the poller to avoid duplicate rows for the same cloud_doc_id
        self._bootstrap_supabase_to_local()
        self._cloud_user_sync.start()
        QMessageBox.information(self, "Signed in", "Desktop is now synced to your cloud documents.")

    def _bootstrap_supabase_to_local(self, prefetched_docs=None) -> None:
        if not self._signed_in_uid or not self._sb_access_token:
            return
        if not (self._sb_url and self._sb_anon):
            return
        client = SupabaseClient(url=self._sb_url, anon_key=self._sb_anon)
        if prefetched_docs is None:
            try:
                docs = client.list_user_documents(
                    access_token=self._sb_access_token, limit=100
                )
            except Exception as e:
                self._on_firebase_status(f"Cloud list: {e}")
                return
        else:
            docs = prefetched_docs
        for d in docs:
            if self._manager.has_cloud_doc(d.doc_id):
                continue
            try:
                pdf_bytes = client.download_pdf(
                    access_token=self._sb_access_token,
                    storage_path=d.storage_path,
                )
                tmp = os.path.join(tempfile.gettempdir(), f"sm_bootstrap_{d.doc_id}.pdf")
                with open(tmp, "wb") as f:
                    f.write(pdf_bytes)
                self._manager.add_from_file(
                    tmp,
                    d.doc_id,
                    d.storage_path,
                    display_name=d.file_name,
                )
                try:
                    os.remove(tmp)
                except OSError:
                    pass
            except Exception as ex:
                self._on_firebase_status(f"Bootstrap skip {d.file_name}: {ex}")
        self._refresh_lists()

    def _refresh_cloud_manual(self) -> None:
        """Fetch the latest document list and names from Supabase; download any PDFs missing locally"""
        if not self._signed_in_uid or not self._sb_access_token:
            self._on_firebase_status("Sign in to refresh the cloud list.")
            return
        if not (self._sb_url and self._sb_anon):
            return
        self._on_firebase_status("Refreshing cloud…")
        client = SupabaseClient(url=self._sb_url, anon_key=self._sb_anon)
        try:
            docs = client.list_user_documents(
                access_token=self._sb_access_token, limit=100
            )
        except Exception as e:
            self._on_firebase_status(f"Cloud refresh failed: {e}")
            return
        pairs = [(d.doc_id, d.file_name) for d in docs]
        self._manager.sync_cloud_file_names_from_pairs(pairs)
        self._bootstrap_supabase_to_local(prefetched_docs=docs)
        self._on_firebase_status("Cloud list updated.")

    def _on_delete_selected_docs(self, docs) -> None:
        if not docs:
            return
        r = QMessageBox.question(
            self,
            "Delete documents",
            f"Delete {len(docs)} document(s)? This cannot be undone.",
            QMessageBox.Yes | QMessageBox.No,
            QMessageBox.No,
        )
        if r != QMessageBox.Yes:
            return
        client = None
        if self._sb_access_token and self._sb_url and self._sb_anon:
            client = SupabaseClient(url=self._sb_url, anon_key=self._sb_anon)
        for d in docs:
            if client and d.cloud_doc_id and d.cloud_storage_path:
                try:
                    client.delete_document(
                        access_token=self._sb_access_token,
                        doc_id=d.cloud_doc_id,
                        storage_path=d.cloud_storage_path,
                    )
                except Exception as e:
                    self._on_firebase_status(f"Cloud delete failed ({d.name}): {e}")
            try:
                self._manager.delete(d.id)
            except Exception:
                pass
        self._refresh_lists()

    def closeEvent(self, event: QCloseEvent) -> None:
        # Stop background threads to avoid "QThread: Destroyed while thread is still running"
        for th_attr in ("_cloud_user_sync", "_google_sign_in", "_pdf_receiver"):
            th = getattr(self, th_attr, None)
            if th is not None and th.isRunning():
                try:
                    th.terminate()
                    th.wait(1200)
                except Exception:
                    pass
        super().closeEvent(event)

    def _on_pdf_received(
        self,
        pdf_path: str,
        cloud_doc_id: str = "",
        cloud_storage_path: str = "",
        display_name: str = "",
    ) -> None:
        try:
            if cloud_doc_id and self._manager.has_cloud_doc(cloud_doc_id):
                try:
                    os.remove(pdf_path)
                except OSError:
                    pass
                self._refresh_lists()
                return
            doc = self._manager.add_from_file(
                pdf_path,
                cloud_doc_id or None,
                cloud_storage_path or None,
                display_name=display_name or None,
            )
            try:
                os.remove(pdf_path)
            except OSError:
                pass
            self._refresh_lists()
            self._on_firebase_status(f'Updated library: "{doc.name}"')
        except Exception as exc:
            QMessageBox.critical(self, "Error", str(exc))


class PdfReceiver(QThread):
    pdf_received = pyqtSignal(str, str, str, str)

    def __init__(self, *, port: int, inbox_dir: str, parent=None):
        super().__init__(parent)
        self._port = port
        self._inbox_dir = inbox_dir
        self._supabase: Optional[SupabaseClient] = None
        sb_url = os.environ.get("SUPABASE_URL", "").strip()
        sb_anon = os.environ.get("SUPABASE_ANON_KEY", "").strip()
        if sb_url and sb_anon:
            self._supabase = SupabaseClient(url=sb_url, anon_key=sb_anon)

    def run(self) -> None:
        import socket

        def recv_exact(conn: socket.socket, n: int) -> bytes:
            data = b""
            while len(data) < n:
                chunk = conn.recv(n - len(data))
                if not chunk:
                    raise ConnectionError("unexpected EOF")
                data += chunk
            return data

        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", self._port))
        srv.listen(5)

        while True:
            conn, _addr = srv.accept()
            with conn:
                magic = recv_exact(conn, 4)
                if magic not in (b"SMK1", b"SMK2"):
                    continue

                name_len = int.from_bytes(recv_exact(conn, 2), "big", signed=False)
                file_name = recv_exact(conn, name_len).decode("utf-8", errors="ignore")

                access_token = None
                if magic == b"SMK2":
                    token_len = int.from_bytes(recv_exact(conn, 2), "big", signed=False)
                    access_token = recv_exact(conn, token_len).decode("utf-8", errors="ignore")

                file_size = int.from_bytes(recv_exact(conn, 8), "big", signed=False)

                # Basic sanity guard (avoid OOM / disk fill on malformed clients)
                max_size = 80 * 1024 * 1024  # 80MB
                if file_size <= 0 or file_size > max_size:
                    continue

                if not file_name.lower().endswith(".pdf"):
                    file_name = "scanmeow_received.pdf"

                out_path = os.path.join(self._inbox_dir, file_name)
                # Avoid overwriting
                if os.path.exists(out_path):
                    base, ext = os.path.splitext(file_name)
                    out_path = os.path.join(
                        self._inbox_dir, f"{base}_{int(os.times().elapsed)}{ext}"
                    )

                remaining = file_size
                with open(out_path, "wb") as f:
                    while remaining > 0:
                        chunk = conn.recv(min(32 * 1024, remaining))
                        if not chunk:
                            raise ConnectionError("unexpected EOF during file payload")
                        f.write(chunk)
                        remaining -= len(chunk)

                cloud_doc_id = ""
                cloud_storage_path = ""
                if access_token and self._supabase is not None:
                    try:
                        with open(out_path, "rb") as f:
                            pdf_bytes = f.read()
                        # Skip a second cloud insert if the mobile app already uploaded this PDF
                        min_ms = int(time.time() * 1000) - 25 * 60 * 1000
                        existing = self._supabase.find_recent_document_by_file_name(
                            access_token=access_token,
                            file_name=file_name,
                            min_created_at_millis=min_ms,
                        )
                        if existing is not None:
                            cloud_doc_id, cloud_storage_path = (
                                existing.doc_id,
                                existing.storage_path,
                            )
                        else:
                            cloud_doc_id, cloud_storage_path = self._supabase.upload_pdf_and_row(
                                access_token=access_token,
                                pdf_bytes=pdf_bytes,
                                original_file_name=file_name,
                            )
                    except Exception as ex:
                        import traceback

                        traceback.print_exc()
                        # Token invalid, RLS, or SUPABASE_* missing in env → row id stays empty
                        print(f"ScanMeow TCP upload/metadata failed: {ex}", flush=True)

                self.pdf_received.emit(
                    out_path,
                    cloud_doc_id or "",
                    cloud_storage_path or "",
                    file_name,
                )
