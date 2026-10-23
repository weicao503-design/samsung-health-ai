package com.wei.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class Spec(val key: String, val type: KClass<out Record>, val ser: (JSONObject, Record) -> JSONObject)

private fun iso(i: Instant) = DateTimeFormatter.ISO_INSTANT.format(i)
private fun JSONObject.iv(s: Instant, e: Instant, z: ZoneOffset?) = put("start", iso(s)).put("end", iso(e)).put("zoneOffset", z?.id)
private fun JSONObject.inst(t: Instant, z: ZoneOffset?) = put("time", iso(t)).put("zoneOffset", z?.id)
private inline fun <reified T : Record> spec(key: String, crossinline f: JSONObject.(T) -> JSONObject) =
  Spec(key, T::class) { o, r -> o.f(r as T) }
private fun kg(o: JSONObject, k: String, m: androidx.health.connect.client.units.Mass) = o.put(k, m.inKilograms).put("unit", "kg")

val SPECS = listOf(
  spec<ExerciseSessionRecord>("exerciseSessions") { iv(it.startTime, it.endTime, it.startZoneOffset).put("exerciseType", it.exerciseType).put("title", it.title).put("notes", it.notes)
    .put("segments", JSONArray(it.segments.map { s -> JSONObject().put("start", iso(s.startTime)).put("end", iso(s.endTime)).put("segmentType", s.segmentType).put("reps", s.repetitions) }))
    .put("laps", JSONArray(it.laps.map { l -> JSONObject().put("start", iso(l.startTime)).put("end", iso(l.endTime)).put("meters", l.length?.inMeters) })) },
  spec<StepsRecord>("steps") { iv(it.startTime, it.endTime, it.startZoneOffset).put("count", it.count).put("unit", "steps") },
  spec<DistanceRecord>("distance") { iv(it.startTime, it.endTime, it.startZoneOffset).put("meters", it.distance.inMeters).put("unit", "m") },
  spec<ActiveCaloriesBurnedRecord>("activeCalories") { iv(it.startTime, it.endTime, it.startZoneOffset).put("kcal", it.energy.inKilocalories).put("unit", "kcal") },
  spec<TotalCaloriesBurnedRecord>("totalCalories") { iv(it.startTime, it.endTime, it.startZoneOffset).put("kcal", it.energy.inKilocalories).put("unit", "kcal") },
  spec<FloorsClimbedRecord>("floorsClimbed") { iv(it.startTime, it.endTime, it.startZoneOffset).put("floors", it.floors).put("unit", "floors") },
  spec<ElevationGainedRecord>("elevationGained") { iv(it.startTime, it.endTime, it.startZoneOffset).put("meters", it.elevation.inMeters).put("unit", "m") },
  spec<SpeedRecord>("speed") { iv(it.startTime, it.endTime, it.startZoneOffset).put("unit", "m/s").put("samples", JSONArray(it.samples.map { x -> JSONObject().put("time", iso(x.time)).put("mps", x.speed.inMetersPerSecond) })) },
  spec<PowerRecord>("power") { iv(it.startTime, it.endTime, it.startZoneOffset).put("unit", "W").put("samples", JSONArray(it.samples.map { x -> JSONObject().put("time", iso(x.time)).put("watts", x.power.inWatts) })) },
  spec<Vo2MaxRecord>("vo2Max") { inst(it.time, it.zoneOffset).put("value", it.vo2MillilitersPerMinuteKilogram).put("unit", "mL/kg/min").put("method", it.measurementMethod) },
  spec<SleepSessionRecord>("sleepSessions") { iv(it.startTime, it.endTime, it.startZoneOffset).put("title", it.title).put("notes", it.notes)
    .put("stages", JSONArray(it.stages.map { x -> JSONObject().put("start", iso(x.startTime)).put("end", iso(x.endTime)).put("stage", x.stage)
      .put("stageName", SleepSessionRecord.STAGE_TYPE_INT_TO_STRING_MAP[x.stage]) })) },
  spec<HeartRateRecord>("heartRate") { iv(it.startTime, it.endTime, it.startZoneOffset).put("unit", "bpm").put("samples", JSONArray(it.samples.map { x -> JSONObject().put("time", iso(x.time)).put("bpm", x.beatsPerMinute) })) },
  spec<RestingHeartRateRecord>("restingHeartRate") { inst(it.time, it.zoneOffset).put("bpm", it.beatsPerMinute).put("unit", "bpm") },
  spec<HeartRateVariabilityRmssdRecord>("hrvRmssd") { inst(it.time, it.zoneOffset).put("ms", it.heartRateVariabilityMillis).put("unit", "ms") },
  spec<OxygenSaturationRecord>("oxygenSaturation") { inst(it.time, it.zoneOffset).put("percent", it.percentage.value).put("unit", "%") },
  spec<BloodPressureRecord>("bloodPressure") { inst(it.time, it.zoneOffset).put("systolic", it.systolic.inMillimetersOfMercury).put("diastolic", it.diastolic.inMillimetersOfMercury).put("unit", "mmHg").put("bodyPosition", it.bodyPosition).put("location", it.measurementLocation) },
  spec<BloodGlucoseRecord>("bloodGlucose") { inst(it.time, it.zoneOffset).put("mmolPerL", it.level.inMillimolesPerLiter).put("unit", "mmol/L").put("specimenSource", it.specimenSource).put("mealType", it.mealType).put("relationToMeal", it.relationToMeal) },
  spec<BodyTemperatureRecord>("bodyTemperature") { inst(it.time, it.zoneOffset).put("celsius", it.temperature.inCelsius).put("unit", "C").put("location", it.measurementLocation) },
  spec<RespiratoryRateRecord>("respiratoryRate") { inst(it.time, it.zoneOffset).put("rate", it.rate).put("unit", "breaths/min") },
  spec<WeightRecord>("weight") { kg(inst(it.time, it.zoneOffset), "kg", it.weight) },
  spec<HeightRecord>("height") { inst(it.time, it.zoneOffset).put("meters", it.height.inMeters).put("unit", "m") },
  spec<BodyFatRecord>("bodyFat") { inst(it.time, it.zoneOffset).put("percent", it.percentage.value).put("unit", "%") },
  spec<LeanBodyMassRecord>("leanBodyMass") { kg(inst(it.time, it.zoneOffset), "kg", it.mass) },
  spec<BasalMetabolicRateRecord>("basalMetabolicRate") { inst(it.time, it.zoneOffset).put("kcalPerDay", it.basalMetabolicRate.inKilocaloriesPerDay).put("unit", "kcal/day") },
  spec<BodyWaterMassRecord>("bodyWaterMass") { kg(inst(it.time, it.zoneOffset), "kg", it.mass) },
  spec<BoneMassRecord>("boneMass") { kg(inst(it.time, it.zoneOffset), "kg", it.mass) },
  spec<HydrationRecord>("hydration") { iv(it.startTime, it.endTime, it.startZoneOffset).put("liters", it.volume.inLiters).put("unit", "L") },
  spec<NutritionRecord>("nutrition") { iv(it.startTime, it.endTime, it.startZoneOffset).put("name", it.name).put("mealType", it.mealType).put("kcal", it.energy?.inKilocalories)
    .put("proteinG", it.protein?.inGrams).put("fatG", it.totalFat?.inGrams).put("carbsG", it.totalCarbohydrate?.inGrams)
    .put("sugarG", it.sugar?.inGrams).put("fiberG", it.dietaryFiber?.inGrams).put("sodiumG", it.sodium?.inGrams) },
)

object Sync {
  val PERMISSIONS: Set<String> get() = SPECS.map { HealthPermission.getReadPermission(it.type) }.toSet()
  const val BG_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

  fun prefs(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)

  fun schedule(c: Context) {
    val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
    WorkManager.getInstance(c).enqueueUniquePeriodicWork("health-sync", ExistingPeriodicWorkPolicy.KEEP, req)
  }


  private suspend fun readAll(hc: HealthConnectClient, type: KClass<out Record>, f: TimeRangeFilter): List<Record> {
    val out = mutableListOf<Record>(); var token: String? = null
    do {
      val r = hc.readRecords(ReadRecordsRequest(@Suppress("UNCHECKED_CAST") (type as KClass<Record>), f, pageToken = token))
      out += r.records; token = r.pageToken
    } while (!token.isNullOrEmpty())
    return out
  }

  private fun meta(o: JSONObject, r: Record) = o.apply {
    put("id", r.metadata.id); put("source", r.metadata.dataOrigin.packageName)
    r.metadata.device?.let { put("device", "${it.manufacturer ?: ""} ${it.model ?: ""}".trim()) }
  }

  /** Returns status string; throws on hard failures. */
  suspend fun run(c: Context): String = withContext(Dispatchers.IO) {
    val p = prefs(c)
    val first = p.getLong("lastSync", 0L) == 0L
    val days = if (first) 30L else p.getInt("days", 7).toLong()
    val end = Instant.now(); val start = end.minus(days, ChronoUnit.DAYS)
    val root = JSONObject().put("generatedAt", iso(end)).put("rangeStart", iso(start)).put("rangeEnd", iso(end)).put("days", days)
    val skipped = mutableListOf<String>()
    var count = 0
    // 1) Preferred: Samsung Health Data SDK
    if (SamsungSource.bundled && SamsungSource.grantedCount(c) > 0) {
      try {
        count = SamsungSource.read(c, start, end, root, skipped)
        root.put("dataSource", "samsung_health")
      } catch (e: Exception) { skipped += "samsung(${e.message})" }
    }
    // 2) Fallback: Health Connect
    if (count == 0 && HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE) {
      val hc = HealthConnectClient.getOrCreate(c)
      val granted = hc.permissionController.getGrantedPermissions()
      if (granted.isNotEmpty()) {
        val f = TimeRangeFilter.between(start, end)
        root.put("dataSource", "health_connect")
        for (spec in SPECS) {
          if (HealthPermission.getReadPermission(spec.type) !in granted) { skipped += spec.key; continue }
          val arr = JSONArray()
          try { readAll(hc, spec.type, f).forEach { r -> arr.put(spec.ser(meta(JSONObject(), r), r)) } }
          catch (e: Exception) { skipped += "${spec.key}(${e.javaClass.simpleName})"; continue }
          root.put(spec.key, arr); count += arr.length()
        }
      }
    }
    if (!root.has("dataSource")) error("没有任何数据权限:请先点「授予三星健康权限」")
    root.put("recordCount", count)
    root.put("skippedTypes", JSONArray(skipped))
    val json = root.toString(2)
    val dir = File(c.getExternalFilesDir(null), "exports").apply { mkdirs() }
    File(dir, "latest.json").writeText(json)

    val parts = mutableListOf("读取 ${root.optInt("recordCount")} 条记录(${if (root.optString("dataSource") == "samsung_health") "三星健康" else "Health Connect"}),${json.length / 1024} KB")
    val errors = mutableListOf<String>()
    if (Drive.enabled(c)) {
      try { parts += "已上传到云端硬盘:${Drive.upload(c, json)}" } catch (e: Exception) { errors += "云端硬盘上传失败:${e.message}" }
    }
    val url = p.getString("url", "").orEmpty().trim()
    if (url.isNotEmpty()) try {
      val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 30000
        setRequestProperty("Content-Type", "application/json")
        p.getString("token", "").orEmpty().trim().takeIf { it.isNotEmpty() }?.let { setRequestProperty("Authorization", "Bearer $it") }
      }
      conn.outputStream.use { it.write(json.toByteArray()) }
      val code = conn.responseCode; conn.disconnect()
      if (code in 200..299) parts += "服务器上传成功(HTTP $code)" else errors += "服务器上传失败:HTTP $code"
    } catch (e: Exception) { errors += "服务器上传失败:${e.message}" }
    val status = (parts + errors).joinToString("; ")
    if (errors.isNotEmpty()) error(status)
    p.edit().putLong("lastSync", end.toEpochMilli()).apply()
    status
  }

  fun record(c: Context, s: String) = prefs(c).edit().putString("status", "${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}  $s").apply()
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
  override suspend fun doWork(): Result = try {
    Sync.record(applicationContext, "[自动] 同步成功 ✓ " + Sync.run(applicationContext)); Result.success()
  } catch (e: Exception) {
    Sync.record(applicationContext, "[自动] 同步失败:${e.message}"); Result.retry()
  }
}
