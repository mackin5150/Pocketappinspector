package com.pocketappinspector

import android.app.Activity
import android.app.AppOpsManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class AppRow(
    val label: String,
    val pkg: String,
    val version: String,
    val versionCode: Long,
    val system: Boolean,
    val updatedSystem: Boolean,
    val enabled: Boolean,
    val hasLauncher: Boolean,
    val installer: String?,
    val firstInstalled: Long,
    val permissions: List<String>,
    val special: List<String>,
    var score: Int = 0,
    var reasons: List<String> = emptyList()
) {
    val systemType: String get() = when {
        updatedSystem -> "System app · updated"
        system -> "System app"
        else -> "User app"
    }
}

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private var apps: List<AppRow> = emptyList()
    private var showAll = true
    private val prefs by lazy { getSharedPreferences("audit", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 0); setBackgroundColor(0xFFF3F5F8.toInt()) }
        val title = TextView(this).apply { text = "Pocket App Inspector"; textSize = 24f; setTextColor(0xFF17243A.toInt()); setPadding(0, 0, 0, 6) }
        root.addView(title)
        root.addView(TextView(this).apply { text = "On-device app audit · nothing is uploaded"; textSize = 14f; setTextColor(0xFF596579.toInt()); setPadding(0, 0, 0, 12) })
        search = EditText(this).apply { hint = "Search app name or package"; setSingleLine(true) }
        root.addView(search)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("Rescan") { refresh() }, weight())
        actions.addView(button("Flagged only") { showAll = !showAll; render() }, weight())
        actions.addView(button("Save baseline") { saveBaseline() }, weight())
        root.addView(actions)
        search.addTextChangedListener(SimpleTextWatcher { render() })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 24) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; textSize = 12f; setOnClickListener { action() } }

    private fun refresh() {
        apps = try {
            installedRows().also { rows ->
                findDuplicates(rows)
                scoreRows(rows)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read app inventory: ${e.message}", Toast.LENGTH_LONG).show(); emptyList()
        }
        render()
    }

    @Suppress("DEPRECATION")
    private fun installedRows(): List<AppRow> {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS
        val installed = if (Build.VERSION.SDK_INT >= 33) packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        else packageManager.getInstalledApplications(flags)
        return installed.mapNotNull { ai ->
            try {
                val pi = if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageInfo(ai.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                else packageManager.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS)
                val label = runCatching { packageManager.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
                val sys = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val updated = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                val launcher = packageManager.getLaunchIntentForPackage(ai.packageName) != null
                val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { packageManager.getInstallSourceInfo(ai.packageName).installingPackageName }.getOrNull() else null
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
                AppRow(label, ai.packageName, pi.versionName ?: "unknown", versionCode, sys, updated,
                    ai.enabled, launcher, source, pi.firstInstallTime,
                    pi.requestedPermissions?.toList().orEmpty(), specialAccess(ai.packageName, ai.uid))
            } catch (_: Exception) { null }
        }.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun specialAccess(pkg: String, uid: Int): List<String> {
        val found = mutableListOf<String>()
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        fun op(label: String, name: String) {
            val mode = runCatching { appOps.checkOpNoThrow(name, uid, pkg) }.getOrNull()
            if (mode == AppOpsManager.MODE_ALLOWED) found += label
        }
        if (Build.VERSION.SDK_INT >= 23) {
            op("Usage access (app activity)", AppOpsManager.OPSTR_GET_USAGE_STATS)
            op("Display over other apps", AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)
            op("Install unknown apps", "android:request_install_packages")
        }
        val services = (getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (services.any { it.resolveInfo.serviceInfo.packageName == pkg }) found += "Enabled accessibility service"
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        if (listeners.split(':').any { it.substringBefore('/') == pkg }) found += "Notification access"
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.activeAdmins.orEmpty().any { it.packageName == pkg }) found += "Device administrator"
        val vpnPkg = runCatching { Settings.Secure.getString(contentResolver, "always_on_vpn_app") }.getOrNull()
        if (vpnPkg == pkg) found += "Always-on VPN"
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeVpn = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) } == true }.getOrDefault(false)
        if (activeVpn && pkg == vpnPkg && "Always-on VPN" !in found) found += "VPN"
        return found.distinct()
    }

    private fun findDuplicates(rows: List<AppRow>) {
        val groups = rows.groupBy { normalize(it.label) }.filter { it.key.length > 2 && it.value.size > 1 }
        groups.values.flatten().forEach { row -> row.reasons = row.reasons + "Duplicate display name: another installed app uses this name"; row.score += 12 }
        val known = listOf("Settings", "Google Play services", "Google Play Store", "System UI", "Phone", "Android System", "Device Health Services")
        rows.forEach { row ->
            val near = known.firstOrNull { target -> normalize(target) != normalize(row.label) && similarity(normalize(target), normalize(row.label)) >= .78 }
            if (near != null && !row.system) { row.reasons = row.reasons + "Name resembles '$near'"; row.score += 18 }
        }
    }

    private fun scoreRows(rows: List<AppRow>) {
        val baseline = readBaseline()
        rows.forEach { row ->
            val reasons = row.reasons.toMutableList()
            var score = row.score
            if (!row.enabled) { score += 24; reasons += "App is disabled" }
            if (row.system && row.updatedSystem) { score += 7; reasons += "Preinstalled system app has an updated version" }
            if (!row.hasLauncher && !row.system) { score += 8; reasons += "No launcher icon (can be normal for background apps)" }
            if (!row.system && row.installer == null) { score += 8; reasons += "Installer source unavailable or not recorded" }
            else if (!row.system && row.installer != "com.android.vending" && row.installer != "com.google.android.packageinstaller" && row.installer != "com.android.packageinstaller") {
                score += 12; reasons += "Installed by ${row.installer} rather than Google Play (not proof of malware)"
            }
            val riskyPerms = row.permissions.map { it.substringAfterLast('.') }.filter { it in setOf(
                "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CALL_LOG", "RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION",
                "ACCESS_BACKGROUND_LOCATION", "READ_CONTACTS", "READ_HISTORY_BOOKMARKS", "WRITE_SETTINGS", "PACKAGE_USAGE_STATS",
                "BIND_ACCESSIBILITY_SERVICE", "BIND_NOTIFICATION_LISTENER_SERVICE", "BIND_VPN_SERVICE", "REQUEST_INSTALL_PACKAGES") }.distinct()
            if (riskyPerms.isNotEmpty()) { score += (riskyPerms.size * 5).coerceAtMost(25); reasons += "Sensitive declared permissions: ${riskyPerms.joinToString()}" }
            if (row.special.isNotEmpty()) { score += (row.special.size * 15).coerceAtMost(45); reasons += "Special access active: ${row.special.joinToString()}" }
            val old = baseline?.optJSONObject(row.pkg)
            if (baseline != null && old == null) { score += 20; reasons += "Not present in your saved baseline" }
            else if (old != null && old.optLong("versionCode", -1) != row.versionCode) { score += 6; reasons += "Version changed since saved baseline" }
            row.score = score.coerceAtMost(100)
            row.reasons = reasons.distinct()
        }
    }

    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val query = search.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val shown = apps.filter { row ->
            (showAll || row.score >= 20) && (query.isEmpty() || row.label.lowercase().contains(query) || row.pkg.lowercase().contains(query))
        }.sortedWith(compareByDescending<AppRow> { it.score }.thenBy { it.label.lowercase() })
        val high = apps.count { it.score >= 60 }; val flagged = apps.count { it.score >= 20 }
        list.addView(TextView(this).apply {
            text = "${apps.size} packages · $flagged flagged for review · $high high review score\nFlags are clues, not a malware verdict. A permission alone does not prove it was used."
            textSize = 14f; setTextColor(0xFF344256.toInt()); setPadding(4, 12, 4, 12)
        })
        if (readBaseline() == null) list.addView(TextView(this).apply { text = "No baseline saved. Save one only when you believe the phone is in a clean, trusted state."; setTextColor(0xFF8A4B08.toInt()); setPadding(4, 4, 4, 12) })
        shown.forEach { row -> list.addView(card(row)) }
        if (shown.isEmpty()) list.addView(TextView(this).apply { text = "No matching packages."; setPadding(8, 18, 8, 18) })
    }

    private fun card(row: AppRow): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12); setBackgroundColor(0xFFFFFFFF.toInt()) }
        val risk = when { row.score >= 60 -> "HIGH REVIEW"; row.score >= 20 -> "REVIEW"; else -> "LOW SIGNAL" }
        card.addView(TextView(this).apply { text = "${row.label}  ·  $risk (${row.score})"; textSize = 17f; setTextColor(if (row.score >= 60) 0xFF9B2525.toInt() else 0xFF17243A.toInt()) })
        card.addView(TextView(this).apply { text = "${row.systemType}  ·  ${row.pkg}\nVersion ${row.version} (${row.versionCode})"; textSize = 12f; setTextColor(0xFF58667A.toInt()); setPadding(0, 4, 0, 6) })
        if (row.reasons.isNotEmpty()) card.addView(TextView(this).apply { text = row.reasons.joinToString("\n") { "• $it" }; textSize = 13f; setTextColor(0xFF344256.toInt()) })
        val detail = TextView(this).apply {
            text = "Details  ·  ${if (row.enabled) "enabled" else "disabled"}  ·  ${if (row.hasLauncher) "launcher icon" else "no launcher icon"}  ·  installer: ${row.installer ?: "unknown"}\nFirst installed: ${DateFormat.getDateTimeInstance().format(Date(row.firstInstalled))}"
            textSize = 12f; setTextColor(0xFF58667A.toInt()); setPadding(0, 6, 0, 6)
        }
        card.addView(detail)
        card.setOnClickListener { showDetails(row) }
        card.setOnLongClickListener { showActions(row); true }
        card.setPadding(14, 12, 14, 12)
        val lp = LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 4, 0, 8); card.layoutParams = lp
        return card
    }

    private fun showDetails(row: AppRow) {
        val message = buildString {
            appendLine("${row.systemType}\nPackage: ${row.pkg}\nVersion: ${row.version} (${row.versionCode})")
            appendLine("Installer: ${row.installer ?: "not reported"}")
            appendLine("First install: ${DateFormat.getDateTimeInstance().format(Date(row.firstInstalled))}")
            appendLine("Launcher icon: ${row.hasLauncher}; enabled: ${row.enabled}")
            appendLine("\nReview signals:\n${row.reasons.ifEmpty { listOf("No strong signals detected by this audit.") }.joinToString("\n• ", prefix = "• ")}")
            appendLine("\nRequested permissions:\n${row.permissions.ifEmpty { listOf("None reported") }.joinToString("\n")}")
            appendLine("\nSpecial access currently detected:\n${row.special.ifEmpty { listOf("None detected") }.joinToString("\n")}")
            append("\nA high score is a prioritization aid, not proof of spying or malware.")
        }
        AlertDialog.Builder(this).setTitle(row.label).setMessage(message).setPositiveButton("App settings") { _, _ -> openAppSettings(row.pkg) }
            .setNeutralButton(if (row.system) "System app info" else "Uninstall…") { _, _ -> if (row.system) openAppSettings(row.pkg) else uninstall(row.pkg) }
            .setNegativeButton("Close", null).show()
    }

    private fun showActions(row: AppRow) = showDetails(row)
    private fun openAppSettings(pkg: String) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) }
    private fun uninstall(pkg: String) {
        try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
        catch (_: Exception) { openAppSettings(pkg); Toast.makeText(this, "Use Android Settings to disable or uninstall this app.", Toast.LENGTH_LONG).show() }
    }

    private fun saveBaseline() {
        AlertDialog.Builder(this).setTitle("Save this device baseline?")
            .setMessage("Only do this if you believe the phone is currently in a clean, trusted state. Future scans will flag packages not present now and versions that change. It cannot prove the phone was clean.")
            .setNegativeButton("Cancel", null).setPositiveButton("Save baseline") { _, _ ->
                val obj = JSONObject()
                apps.forEach { row -> obj.put(row.pkg, JSONObject().put("versionCode", row.versionCode).put("system", row.system).put("label", row.label)) }
                prefs.edit().putString("baseline", obj.toString()).putLong("baseline_time", System.currentTimeMillis()).apply()
                Toast.makeText(this, "Baseline saved for ${apps.size} packages", Toast.LENGTH_LONG).show(); refresh()
            }.show()
    }

    private fun readBaseline(): JSONObject? = runCatching { prefs.getString("baseline", null)?.let(::JSONObject) }.getOrNull()
    private fun normalize(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }; val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            for (j in prev.indices) prev[j] = curr[j]
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}

private class SimpleTextWatcher(private val callback: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = callback()
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
