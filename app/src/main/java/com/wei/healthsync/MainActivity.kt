package com.wei.healthsync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
  private lateinit var status: TextView
  private val permLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
    Sync.record(this, "已授予 ${granted.size} 项健康数据权限"); refresh()
  }

  private val driveAuthLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { res ->
    try {
      val r = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
      if (r.accessToken != null) { Sync.prefs(this).edit().putBoolean("driveEnabled", true).apply(); Sync.record(this, "Google 云端硬盘已授权成功") }
    } catch (e: Exception) { Sync.record(this, "登录云端硬盘失败:${e.message}") }
    refresh()
  }

  private fun driveSignIn() {
    com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).authorize(Drive.request)
      .addOnSuccessListener { r ->
        if (r.hasResolution()) driveAuthLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
        else { Sync.prefs(this).edit().putBoolean("driveEnabled", true).apply(); Sync.record(this, "Google 云端硬盘已授权成功"); refresh() }
      }
      .addOnFailureListener { e -> Sync.record(this, "登录云端硬盘失败:${e.message}(请检查 Google Cloud 中的软件包名称和 SHA-1)"); refresh() }
  }

  private lateinit var badgeLast: TextView
  private lateinit var badgeHc: TextView
  private lateinit var badgeDrive: TextView
  private lateinit var badgeSh: TextView

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    setContentView(R.layout.activity_main)
    val p = Sync.prefs(this)
    status = findViewById(R.id.txtLog)
    badgeLast = findViewById(R.id.badgeLast); badgeHc = findViewById(R.id.badgeHc); badgeDrive = findViewById(R.id.badgeDrive); badgeSh = findViewById(R.id.badgeSh)
    val url = findViewById<EditText>(R.id.edUrl).apply { setText(p.getString("url", "")) }
    val token = findViewById<EditText>(R.id.edToken).apply { setText(p.getString("token", "")) }
    val days = findViewById<EditText>(R.id.edDays).apply { setText(p.getInt("days", 7).toString()) }
    fun save() = p.edit().putString("url", url.text.toString()).putString("token", token.text.toString())
      .putInt("days", days.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 7).apply()
    val body = findViewById<android.view.View>(R.id.advBody); val arrow = findViewById<android.view.View>(R.id.advArrow)
    findViewById<android.view.View>(R.id.advHeader).setOnClickListener {
      val open = body.visibility != android.view.View.VISIBLE
      body.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
      arrow.animate().rotation(if (open) 180f else 0f).setDuration(150).start()
    }
    findViewById<Button>(R.id.btnGrant).setOnClickListener { grant() }
    findViewById<Button>(R.id.btnSamsung).setOnClickListener {
      lifecycleScope.launch {
        val r = SamsungSource.requestPermissions(this@MainActivity); Sync.record(this@MainActivity, r); refresh()
        if (SamsungSource.bundled && (r.contains("失败") || SamsungSource.grantedCount(this@MainActivity) <= 0)) showDevModeHelp()
      }
    }
    findViewById<Button>(R.id.btnDrive).setOnClickListener { driveSignIn() }
    findViewById<Button>(R.id.btnShare).setOnClickListener { share() }
    findViewById<Button>(R.id.btnPrivacy).setOnClickListener { startActivity(Intent(this, PrivacyActivity::class.java)) }
    findViewById<Button>(R.id.btnDriveOff).setOnClickListener { p.edit().putBoolean("driveEnabled", false).apply(); Sync.record(this, "已关闭云端硬盘上传"); refresh() }
    findViewById<Button>(R.id.btnSave).setOnClickListener { save(); Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show() }
    val syncBtn = findViewById<Button>(R.id.btnSync); val prog = findViewById<android.view.View>(R.id.progress)
    syncBtn.setOnClickListener {
      save(); syncBtn.isEnabled = false; syncBtn.text = "正在同步…"; prog.visibility = android.view.View.VISIBLE
      lifecycleScope.launch {
        try { Sync.record(this@MainActivity, "同步成功 ✓ " + Sync.run(this@MainActivity)) }
        catch (e: Exception) { Sync.record(this@MainActivity, "同步失败:${e.message}") }
        syncBtn.isEnabled = true; syncBtn.text = getString(R.string.sync_now); prog.visibility = android.view.View.GONE
        refresh()
      }
    }
    Sync.schedule(this)
  }

  override fun onResume() { super.onResume(); refresh() }

  private enum class Lvl { OK, WARN, ERR }
  private fun badge(v: TextView, text: String, l: Lvl) {
    val (fg, bg) = when (l) { Lvl.OK -> R.color.ok to R.color.ok_bg; Lvl.WARN -> R.color.warn to R.color.warn_bg; Lvl.ERR -> R.color.err to R.color.err_bg }
    v.text = text; v.setTextColor(getColor(fg)); v.background.mutate().setTint(getColor(bg))
  }

  private fun grant() {
    when (HealthConnectClient.getSdkStatus(this)) {
      HealthConnectClient.SDK_AVAILABLE -> {
        val hc = HealthConnectClient.getOrCreate(this)
        val perms = Sync.PERMISSIONS.toMutableSet()
        if (hc.features.getFeatureStatus(androidx.health.connect.client.HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
          androidx.health.connect.client.HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) perms += Sync.BG_PERMISSION
        permLauncher.launch(perms)
      }
      HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> startActivity(Intent(Intent.ACTION_VIEW,
        android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")))
      else -> Toast.makeText(this, "此手机上没有可用的 Health Connect,请先安装或更新", Toast.LENGTH_LONG).show()
    }
  }

  private fun share() {
    val f = File(File(getExternalFilesDir(null), "exports"), "latest.json")
    if (!f.exists()) { Toast.makeText(this, "还没有数据文件,请先点「立即同步」", Toast.LENGTH_SHORT).show(); return }
    val uri = FileProvider.getUriForFile(this, "com.wei.healthsync.files", f)
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
      type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "分享健康数据文件"))
  }

  private fun refresh() { lifecycleScope.launch {
    val p = Sync.prefs(this@MainActivity)
    val total = Sync.PERMISSIONS.size
    val n = try { HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions().count { it in Sync.PERMISSIONS } } catch (e: Exception) { -1 }
    when { n < 0 -> badge(badgeHc, "不可用(备用)", Lvl.WARN); n == 0 -> badge(badgeHc, "未授权(备用)", Lvl.WARN)
      n < total -> badge(badgeHc, "部分 $n/$total", Lvl.WARN); else -> badge(badgeHc, "已授权 $n/$total", Lvl.OK) }
    val sh = SamsungSource.grantedCount(this@MainActivity); val shT = SamsungSource.total
    when { !SamsungSource.bundled -> badge(badgeSh, "未包含", Lvl.ERR); sh < 0 -> badge(badgeSh, "不可用", Lvl.ERR); sh == 0 -> badge(badgeSh, "未授权", Lvl.WARN)
      sh < shT -> badge(badgeSh, "部分 $sh/$shT", Lvl.WARN); else -> badge(badgeSh, "已授权 $sh/$shT", Lvl.OK) }
    if (Drive.enabled(this@MainActivity)) badge(badgeDrive, "已连接", Lvl.OK) else badge(badgeDrive, "未登录", Lvl.WARN)
    val last = p.getLong("lastSync", 0L)
    val msg = p.getString("status", null)
    val failed = msg?.contains("失败") == true
    if (last == 0L) badge(badgeLast, "尚未同步", Lvl.WARN) else {
      val t = java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault())
      val today = java.time.LocalDate.now()
      val txt = when (t.toLocalDate()) { today -> "今天 "; today.minusDays(1) -> "昨天 "; else -> t.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 ")) } +
        t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
      badge(badgeLast, txt, if (failed) Lvl.ERR else Lvl.OK)
    }
    status.text = msg ?: "还没有消息。请先点「授予三星健康权限」和「登录 Google 云端硬盘」,然后点「立即同步」。"
    status.setTextColor(if (failed) getColor(R.color.err) else status.textColors.defaultColor)
  } }
  private fun showDevModeHelp() {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
      .setTitle("无法获取三星健康权限?")
      .setMessage("请先在三星健康里打开「开发者模式」:\n\n1. 打开三星健康 → 右上角菜单 → 设置\n2. 点「关于三星健康」\n3. 连续点击版本号多次,直到出现开发者模式\n4. 打开「Developer mode (Samsung Health Data SDK)」\n\n然后回到本应用,再点「授予三星健康权限」。\n(需要 Android 10+、三星健康 6.30.2 或更高版本)")
      .setPositiveButton("知道了", null).show()
  }
}

class PrivacyActivity : AppCompatActivity() {
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    val pad = (24 * resources.displayMetrics.density).toInt()
    setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(TextView(this@PrivacyActivity).apply { setPadding(pad, pad, pad, pad); textSize = 16f; setLineSpacing(0f, 1.3f); text =
      "健康同步 隐私政策\n\n本应用在您授权后,在手机上读取三星健康(或 Health Connect)中的步数、睡眠、心率、血氧、血压、体成分、运动等健康数据," +
      "仅上传到您本人的 Google 云端硬盘 HealthSync 文件夹(drive.file 权限,只能访问本应用创建的文件),或您自己设置的服务器,并保存一份可分享的本地文件。" +
      "开发者没有服务器,不收集、不查看您的数据,没有统计分析,也不提供给任何第三方。\n\n删除数据:在云端硬盘删除 HealthSync 文件夹;卸载应用即删除本地数据。可随时在三星健康 / Health Connect 中撤销权限。\n\n联系:weicao503@gmail.com" }) })
  }

}
