package com.project.scanmeow.data

import com.project.scanmeow.BuildConfig
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ListenerRegistration(private val cancel: () -> Unit) {
    fun remove() = cancel()
}

/**
 * Supabase REST (PostgREST + Storage) client using the logged-in user's JWTi
 */
class SupabaseDocumentsRepository(
    private val http: OkHttpClient,
) {
    var accessToken: String? = null

    private val baseUrl: String
        get() = BuildConfig.SUPABASE_URL.trimEnd('/')

    private val anon: String
        get() = BuildConfig.SUPABASE_ANON_KEY

    private fun requireBearer(): String =
        accessToken?.takeIf { it.isNotBlank() } ?: error("Not signed in")

    suspend fun fetchDocumentsFromServer(userId: String): List<UserCloudDocument> = withContext(Dispatchers.IO) {
        val url =
            "$baseUrl/rest/v1/documents?user_id=eq.$userId&select=id,file_name,storage_path,created_at_millis&order=created_at_millis.desc"
        val req = Request.Builder()
            .url(url)
            .header("apikey", anon)
            .header("Authorization", "Bearer ${requireBearer()}")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("list documents HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val arr = JSONArray(body)
            val out = ArrayList<UserCloudDocument>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    UserCloudDocument(
                        docId = o.getString("id"),
                        fileName = o.getString("file_name"),
                        storagePath = o.getString("storage_path"),
                        createdAtMillis = o.getLong("created_at_millis"),
                    ),
                )
            }
            out
        }
    }

    fun attachListener(
        scope: CoroutineScope,
        userId: String,
        onChange: (List<UserCloudDocument>) -> Unit,
    ): ListenerRegistration {
        val job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { onChange(fetchDocumentsFromServer(userId)) }
                delay(8_000L)
            }
        }
        return ListenerRegistration { job.cancel() }
    }

    suspend fun downloadPdfToFile(doc: UserCloudDocument, outFile: File) = withContext(Dispatchers.IO) {
        val pathEnc = doc.storagePath.split("/").joinToString("/") { part ->
            URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        val url = "$baseUrl/storage/v1/object/authenticated/scans/$pathEnc"
        val req = Request.Builder()
            .url(url)
            .header("apikey", anon)
            .header("Authorization", "Bearer ${requireBearer()}")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("download HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: error("empty body")
            outFile.writeBytes(bytes)
        }
    }

    suspend fun uploadPdfAndMeta(userId: String, pdfBytes: ByteArray, fileName: String) = withContext(Dispatchers.IO) {
        val safeBase = fileName.replace("/", "_").ifBlank { "scan.pdf" }
        val storagePath = "$userId/${System.currentTimeMillis()}_$safeBase"
        val pathEnc = storagePath.split("/").joinToString("/") { part ->
            URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        val uploadUrl = "$baseUrl/storage/v1/object/scans/$pathEnc"
        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .header("apikey", anon)
            .header("Authorization", "Bearer ${requireBearer()}")
            .post(pdfBytes.toRequestBody("application/pdf".toMediaType()))
            .build()
        http.newCall(uploadReq).execute().use { resp ->
            if (!resp.isSuccessful) error("upload HTTP ${resp.code}: ${resp.body?.string()}")
        }
        val createdAt = System.currentTimeMillis()
        val row = JSONObject()
            .put("user_id", userId)
            .put("file_name", fileName)
            .put("storage_path", storagePath)
            .put("created_at_millis", createdAt)
        val insertReq = Request.Builder()
            .url("$baseUrl/rest/v1/documents")
            .header("apikey", anon)
            .header("Authorization", "Bearer ${requireBearer()}")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .post(row.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(insertReq).execute().use { resp ->
            if (!resp.isSuccessful) error("insert row HTTP ${resp.code}: ${resp.body?.string()}")
        }
    }

    suspend fun updateFileName(doc: UserCloudDocument, newTitle: String) = withContext(Dispatchers.IO) {
        val clean = newTitle.replace("/", "_").trim().ifBlank { error("empty title") }
        val idEnc = URLEncoder.encode(doc.docId, StandardCharsets.UTF_8.name())
        val url = "$baseUrl/rest/v1/documents?id=eq.$idEnc"
        val body = JSONObject().put("file_name", clean).toString()
        val req = Request.Builder()
            .url(url)
            .header("apikey", anon)
            .header("Authorization", "Bearer ${requireBearer()}")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .patch(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("rename HTTP ${resp.code}: ${resp.body?.string()}")
        }
    }

    suspend fun deleteDocument(userId: String, doc: UserCloudDocument) = withContext(Dispatchers.IO) {
        val pathEnc = doc.storagePath.split("/").joinToString("/") { part ->
            URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        val delStorage = "$baseUrl/storage/v1/object/scans/$pathEnc"
        http.newCall(
            Request.Builder()
                .url(delStorage)
                .header("apikey", anon)
                .header("Authorization", "Bearer ${requireBearer()}")
                .delete()
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) error("delete storage HTTP ${resp.code}")
        }
        val idEnc = URLEncoder.encode(doc.docId, StandardCharsets.UTF_8.name())
        val delRow = "$baseUrl/rest/v1/documents?id=eq.$idEnc"
        http.newCall(
            Request.Builder()
                .url(delRow)
                .header("apikey", anon)
                .header("Authorization", "Bearer ${requireBearer()}")
                .delete()
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) error("delete row HTTP ${resp.code}")
        }
    }
}

suspend fun supabaseSignInWithGoogleIdToken(
    http: OkHttpClient,
    googleIdToken: String,
): Pair<String, String> = withContext(Dispatchers.IO) {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anon = BuildConfig.SUPABASE_ANON_KEY
    val body = JSONObject()
        .put("provider", "google")
        .put("id_token", googleIdToken)
        .toString()
    val req = Request.Builder()
        .url("$base/auth/v1/token?grant_type=id_token")
        .header("apikey", anon)
        .header("Content-Type", "application/json")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            error("Supabase auth HTTP ${resp.code}: ${resp.body?.string()}")
        }
        val json = JSONObject(resp.body?.string().orEmpty())
        val access = json.getString("access_token")
        val user = json.getJSONObject("user")
        val id = user.getString("id")
        access to id
    }
}
