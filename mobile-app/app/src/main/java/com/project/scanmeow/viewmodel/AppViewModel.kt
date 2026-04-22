package com.project.scanmeow.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.project.scanmeow.data.db.DocumentStorage
import com.project.scanmeow.data.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = DocumentStorage(application)

    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val recentDocuments: StateFlow<List<Document>> = _documents.asStateFlow()
    val allDocuments: StateFlow<List<Document>> = _documents.asStateFlow()

    private val _capturedImagePath = MutableStateFlow<String?>(null)
    val capturedImagePath: StateFlow<String?> = _capturedImagePath.asStateFlow()

    private val _sendingProgress = MutableStateFlow(0f)
    val sendingProgress: StateFlow<Float> = _sendingProgress.asStateFlow()

    init {
        refreshDocuments()
    }

    private fun refreshDocuments() {
        viewModelScope.launch(Dispatchers.IO) {
            _documents.value = storage.readAll()
        }
    }

    fun setCapturedImage(path: String) { _capturedImagePath.value = path }
    fun clearCapturedImage() { _capturedImagePath.value = null }

    fun isBluetoothEnabled(): Boolean {
        val bm = getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter?.isEnabled == true
    }

    fun saveDocument(imagePath: String, name: String, onSaved: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val pdfPath = generatePdf(imagePath, name)
            val sizeBytes = File(pdfPath ?: imagePath).length()
            val doc = Document(name = name, imagePath = imagePath, pdfPath = pdfPath, sizeBytes = sizeBytes)
            val saved = storage.insert(doc)
            refreshDocuments()
            withContext(Dispatchers.Main) { onSaved(saved.id) }
        }
    }

    private fun generatePdf(imagePath: String, name: String): String? {
        return try {
            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return null
            val pdfDoc = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            val page = pdfDoc.startPage(pageInfo)
            val canvas: Canvas = page.canvas
            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), null)
            pdfDoc.finishPage(page)
            val pdfFile = File(File(imagePath).parent, "${name.replace(" ", "_")}.pdf")
            FileOutputStream(pdfFile).use { pdfDoc.writeTo(it) }
            pdfDoc.close()
            bitmap.recycle()
            pdfFile.absolutePath
        } catch (e: Exception) { null }
    }

    fun simulateSending(onComplete: () -> Unit) {
        viewModelScope.launch {
            _sendingProgress.value = 0f
            for (i in 1..20) {
                delay(150)
                _sendingProgress.value = i / 20f
            }
            onComplete()
        }
    }

    fun deleteDocument(document: Document) {
        viewModelScope.launch(Dispatchers.IO) {
            storage.delete(document.id)
            refreshDocuments()
        }
    }

    suspend fun getDocumentById(id: Long): Document? =
        withContext(Dispatchers.IO) { storage.getById(id) }
}
