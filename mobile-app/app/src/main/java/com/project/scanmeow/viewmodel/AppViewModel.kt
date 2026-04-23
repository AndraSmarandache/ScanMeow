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
            // Save immediately with JPEG so the user can navigate away instantly
            val sizeBytes = File(imagePath).length()
            val doc = Document(name = name, imagePath = imagePath, pdfPath = null, sizeBytes = sizeBytes)
            val saved = storage.insert(doc)
            refreshDocuments()
            withContext(Dispatchers.Main) { onSaved(saved.id) }

            // Generate PDF in background after navigation
            val pdfPath = generatePdf(imagePath, name)
            if (pdfPath != null) {
                storage.update(saved.copy(pdfPath = pdfPath, sizeBytes = File(pdfPath).length()))
                refreshDocuments()
            }
        }
    }

    private fun generatePdf(imagePath: String, name: String): String? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(imagePath, this)
                inSampleSize = calcSampleSize(outWidth, outHeight, 2480, 3508)
                inJustDecodeBounds = false
            }
            val bitmap = BitmapFactory.decodeFile(imagePath, opts) ?: return null
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

private fun calcSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    var sample = 1
    while (width / sample > reqWidth || height / sample > reqHeight) sample *= 2
    return sample
}
