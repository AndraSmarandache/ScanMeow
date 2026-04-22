package com.project.scanmeow.data.db

import android.content.Context
import com.project.scanmeow.data.model.Document
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DocumentStorage(context: Context) {
    private val file = File(context.filesDir, "documents.json")

    fun readAll(): List<Document> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it).toDocument() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun insert(doc: Document): Document {
        val docs = readAll().toMutableList()
        val newId = (docs.maxOfOrNull { it.id } ?: 0L) + 1L
        val saved = doc.copy(id = newId)
        docs.add(0, saved)
        write(docs)
        return saved
    }

    fun delete(id: Long) {
        write(readAll().filter { it.id != id })
    }

    fun getById(id: Long): Document? = readAll().find { it.id == id }

    private fun write(docs: List<Document>) {
        val arr = JSONArray()
        docs.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    private fun Document.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("imagePath", imagePath)
        put("pdfPath", pdfPath ?: "")
        put("createdAt", createdAt)
        put("sizeBytes", sizeBytes)
    }

    private fun JSONObject.toDocument() = Document(
        id = getLong("id"),
        name = getString("name"),
        imagePath = getString("imagePath"),
        pdfPath = getString("pdfPath").takeIf { it.isNotEmpty() },
        createdAt = getLong("createdAt"),
        sizeBytes = getLong("sizeBytes")
    )
}
