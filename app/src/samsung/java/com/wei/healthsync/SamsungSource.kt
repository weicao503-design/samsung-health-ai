package com.wei.healthsync

import android.app.Activity
import android.content.Context
import com.google.gson.*
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

/** Reads Samsung Health via Samsung Health Data SDK (read-only; works with Samsung Health developer mode). */
object SamsungSource {
  const val bundled = true

  private val readable: List<Pair<String, DataType.Readable<HealthDataPoint, ReadDataRequest.DualTimeBuilder<HealthDataPoint>>>> = listOf(
    "exerciseSessions" to DataTypes.EXERCISE,
    "sleepSessions" to DataTypes.SLEEP,
    "heartRate" to DataTypes.HEART_RATE,
    "oxygenSaturation" to DataTypes.BLOOD_OXYGEN,
    "bloodPressure" to DataTypes.BLOOD_PRESSURE,
    "bodyComposition" to DataTypes.BODY_COMPOSITION,
    "bodyTemperature" to DataTypes.BODY_TEMPERATURE,
    "skinTemperature" to DataTypes.SKIN_TEMPERATURE,
    "floorsClimbed" to DataTypes.FLOORS_CLIMBED,
    "hydration" to DataTypes.WATER_INTAKE,
    "nutrition" to DataTypes.NUTRITION,
    "bloodGlucose" to DataTypes.BLOOD_GLUCOSE,
  )

  private val permissions: Set<Permission> =
    (readable.map { it.second as DataType } + DataTypes.STEPS).map { Permission.of(it, AccessType.READ) }.toSet()

  val total get() = permissions.size

  suspend fun grantedCount(c: Context): Int = try {
    HealthDataService.getStore(c).getGrantedPermissions(permissions).size
  } catch (e: Exception) { -1 }

  suspend fun requestPermissions(a: Activity): String = try {
    val store = HealthDataService.getStore(a.applicationContext)
    val granted = store.getGrantedPermissions(permissions)
    val got = if (granted.containsAll(permissions)) granted else store.requestPermissions(permissions, a)
    "已获得三星健康权限 ${got.size}/${permissions.size}"
  } catch (e: Exception) {
    if (e is ResolvablePlatformException && e.hasResolution) { e.resolve(a); "请按提示处理三星健康(安装/更新/同意条款)后再试" }
    else "三星健康授权失败:${e.message}"
  }

  private val gson: Gson = GsonBuilder()
    .registerTypeHierarchyAdapter(Instant::class.java, JsonSerializer<Instant> { v, _, _ -> JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(v)) })
    .registerTypeHierarchyAdapter(LocalDateTime::class.java, JsonSerializer<LocalDateTime> { v, _, _ -> JsonPrimitive(v.toString()) })
    .registerTypeHierarchyAdapter(LocalDate::class.java, JsonSerializer<LocalDate> { v, _, _ -> JsonPrimitive(v.toString()) })
    .registerTypeHierarchyAdapter(ZoneOffset::class.java, JsonSerializer<ZoneOffset> { v, _, _ -> JsonPrimitive(v.id) })
    .registerTypeHierarchyAdapter(Duration::class.java, JsonSerializer<Duration> { v, _, _ -> JsonPrimitive(v.seconds) })
    .serializeSpecialFloatingPointValues().create()

  private fun toJson(v: Any?): Any? = when (v) {
    null -> JSONObject.NULL
    is Number, is Boolean, is String -> v
    is Enum<*> -> v.name
    else -> try { gson.toJsonTree(v).let { t -> if (t.isJsonArray) JSONArray(t.toString()) else if (t.isJsonObject) JSONObject(t.toString()) else t.asString } }
            catch (e: Exception) { v.toString() }
  }

  /** Fills root with Samsung data. Returns number of records read. */
  suspend fun read(c: Context, start: Instant, end: Instant, root: JSONObject, skipped: MutableList<String>): Int {
    val store = HealthDataService.getStore(c)
    val granted = store.getGrantedPermissions(permissions)
    val zone = ZoneId.systemDefault()
    val filter = LocalTimeFilter.of(LocalDateTime.ofInstant(start, zone), LocalDateTime.ofInstant(end, zone))
    var n = 0
    for ((key, type) in readable) {
      if (Permission.of(type as DataType, AccessType.READ) !in granted) { skipped += key; continue }
      try {
        val pts = store.readData(type.readDataRequestBuilder.setLocalTimeFilter(filter).setOrdering(Ordering.ASC).build()).dataList
        val arr = JSONArray()
        for (p in pts) {
          val o = JSONObject().put("id", p.uid).put("start", DateTimeFormatter.ISO_INSTANT.format(p.startTime))
            .put("end", p.endTime?.let { DateTimeFormatter.ISO_INSTANT.format(it) }).put("zoneOffset", p.zoneOffset?.id)
            .put("source", p.dataSource?.appId).put("deviceId", p.dataSource?.deviceId)
          val fields = JSONObject()
          for (f in (type as DataType).allFields) {
            @Suppress("UNCHECKED_CAST")
            val v = try { p.getValue(f as Field<Any>) } catch (e: Exception) { null }
            if (v != null) fields.put(f.name, toJson(v))
          }
          arr.put(o.put("fields", fields))
        }
        root.put(key, arr); n += arr.length()
      } catch (e: Exception) { skipped += "$key(${e.javaClass.simpleName})" }
    }
    if (Permission.of(DataTypes.STEPS, AccessType.READ) in granted) try {
      val req = DataType.StepsType.TOTAL.requestBuilder
        .setLocalTimeFilterWithGroup(filter, LocalTimeGroup.of(LocalTimeGroupUnit.HOURLY, 1)).setOrdering(Ordering.ASC).build()
      val arr = JSONArray()
      store.aggregateData(req).dataList.forEach { a ->
        val cnt = (a.value as? Number)?.toLong() ?: 0L
        if (cnt > 0) arr.put(JSONObject().put("start", DateTimeFormatter.ISO_INSTANT.format(a.startTime))
          .put("end", a.endTime?.let { DateTimeFormatter.ISO_INSTANT.format(it) }).put("count", cnt).put("unit", "steps"))
      }
      root.put("steps", arr); n += arr.length()
    } catch (e: Exception) { skipped += "steps(${e.javaClass.simpleName})" } else skipped += "steps"
    return n
  }
}
