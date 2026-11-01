package com.aspirelending.jobcards.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DriveHttpException(val code: Int, msg: String) : IOException("HTTP $code: $msg")
data class DriveFile(val id: String, val localId: String?)

/** Minimal Drive v3 REST client (no secrets in the app; token comes from Google's authorization API). */
class DriveApi(private val token: String) {
    private val base = "https://www.googleapis.com"

    private fun call(method: String, url: String, contentType: String? = null, body: ByteArray? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 20000; c.readTimeout = 60000
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", contentType); c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw DriveHttpException(code, text.take(200))
        return text
    }

    fun folderName(id: String): String =
        JSONObject(call("GET", "$base/drive/v3/files/$id?fields=name&supportsAllDrives=true")).getString("name")

    fun find(folderId: String, name: String): List<DriveFile> {
        val q = URLEncoder.encode("'$folderId' in parents and name='${name.replace("'", "\\'")}' and trashed=false", "UTF-8")
        val r = call("GET", "$base/drive/v3/files?q=$q&fields=files(id,appProperties)&supportsAllDrives=true&includeItemsFromAllDrives=true")
        val a: JSONArray = JSONObject(r).getJSONArray("files")
        return (0 until a.length()).map { val o = a.getJSONObject(it); DriveFile(o.getString("id"), o.optJSONObject("appProperties")?.optString("localId")) }
    }

    fun create(folderId: String, name: String, file: File, localId: String): String {
        val b = "aspire-${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folderId))
            .put("appProperties", JSONObject().put("localId", localId)).toString()
        val out = java.io.ByteArrayOutputStream()
        out.write("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
        out.write(file.readBytes()); out.write("\r\n--$b--".toByteArray())
        val r = call("POST", "$base/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=id", "multipart/related; boundary=$b", out.toByteArray())
        return JSONObject(r).getString("id")
    }

    fun rename(fileId: String, name: String) {
        call("PATCH", "$base/drive/v3/files/$fileId?supportsAllDrives=true&fields=id", "application/json", JSONObject().put("name", name).toString().toByteArray())
    }

    fun replace(fileId: String, file: File): String {
        call("PATCH", "$base/upload/drive/v3/files/$fileId?uploadType=media&supportsAllDrives=true&fields=id", "image/jpeg", file.readBytes())
        return fileId
    }
}
