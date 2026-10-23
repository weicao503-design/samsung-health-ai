package com.wei.healthsync

import android.app.Activity
import android.content.Context
import org.json.JSONObject
import java.time.Instant

/** Stub used when libs/samsung-health-data-api.aar is not present at build time. */
object SamsungSource {
  const val bundled = false
  val total get() = 0
  suspend fun grantedCount(c: Context): Int = -1
  suspend fun requestPermissions(a: Activity): String = "此版本未包含三星健康 SDK,请联系开发者更新应用"
  suspend fun read(c: Context, start: Instant, end: Instant, root: JSONObject, skipped: MutableList<String>): Int = 0
}
