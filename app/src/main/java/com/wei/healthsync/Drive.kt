package com.wei.healthsync

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Google Drive upload using the Identity Authorization API (drive.file scope).
 *  No client ID in the app: Google matches the Android OAuth client by package name + signing SHA-1. */
object Drive {
  const val SCOPE = "https://www.googleapis.com/auth/drive.file"
  val request: AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

  fun enabled(c: Context) = Sync.prefs(c).getBoolean("driveEnabled", false)

  /** Non-interactive token fetch (works in background once user has consented). */
  suspend fun token(c: Context): String {
    val r: AuthorizationResult = Identity.getAuthorizationClient(c).authorize(request).await()
    if (r.hasResolution()) error("Google 云端硬盘需要重新授权:请打开本应用,点「登录 Google 云端硬盘」")
    return r.accessToken ?: error("未获得云端硬盘访问授权")
  }

  private fun http(method: String, url: String, tok: String, ct: String? = null, body: ByteArray? = null): Pair<Int, String> {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = method; connectTimeout = 15000; readTimeout = 60000
      setRequestProperty("Authorization", "Bearer $tok")
      if (ct != null) setRequestProperty("Content-Type", ct)
      if (body != null) { doOutput = true; outputStream.use { it.write(body) } }
    }
    val code = c.responseCode
    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
    c.disconnect(); return code to txt
  }

  private fun folderId(c: Context, tok: String): String {
    val p = Sync.prefs(c)
    p.getString("driveFolderId", null)?.let { id ->
      val (code, txt) = http("GET", "https://www.googleapis.com/drive/v3/files/$id?fields=id,trashed", tok)
      if (code == 200 && !JSONObject(txt).optBoolean("trashed")) return id
    }
    val q = URLEncoder.encode("name='HealthSync' and mimeType='application/vnd.google-apps.folder' and trashed=false and 'root' in parents", "UTF-8")
    val (code, txt) = http("GET", "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)", tok)
    if (code != 200) error("云端硬盘查询文件夹失败 HTTP $code: ${txt.take(200)}")
    val files = JSONObject(txt).getJSONArray("files")
    val id = if (files.length() > 0) files.getJSONObject(0).getString("id") else {
      val meta = JSONObject().put("name", "HealthSync").put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray()
      val (cc, ct) = http("POST", "https://www.googleapis.com/drive/v3/files?fields=id", tok, "application/json; charset=UTF-8", meta)
      if (cc !in 200..299) error("云端硬盘创建文件夹失败 HTTP $cc: ${ct.take(200)}")
      JSONObject(ct).getString("id")
    }
    p.edit().putString("driveFolderId", id).apply(); return id
  }

  /** Uploads json, returns file name. */
  suspend fun upload(c: Context, json: String): String {
    val tok = token(c)
    val name = "health-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss")) + ".json"
    val folder = folderId(c, tok)
    val b = "hsb" + System.nanoTime()
    val meta = JSONObject().put("name", name).put("mimeType", "application/json").put("parents", org.json.JSONArray().put(folder))
    val body = ("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$b\r\nContent-Type: application/json\r\n\r\n$json\r\n--$b--\r\n").toByteArray()
    val (code, txt) = http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", tok, "multipart/related; boundary=$b", body)
    if (code == 401) { Identity.getAuthorizationClient(c); error("云端硬盘拒绝访问(401):请在应用中重新登录 Google 云端硬盘") }
    if (code !in 200..299) error("云端硬盘上传失败 HTTP $code: ${txt.take(200)}")
    return name
  }
}
