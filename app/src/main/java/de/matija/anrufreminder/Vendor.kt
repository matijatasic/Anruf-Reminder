package de.matija.anrufreminder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * Herstellerspezifische Akku-/Autostart-Einstellungen. Viele Hersteller beenden Apps im Hintergrund
 * und löschen dabei deren Wecker, solange man die App dort nicht freigibt.
 * Komponentennamen nach dontkillmyapp.com und gängigen Open-Source-Bibliotheken; nicht jede Variante
 * existiert auf jedem Gerät, deshalb wird der Reihe nach probiert.
 */
data class Vendor(
    val name: String,
    val hint: String,
    val guideSlug: String,
    val components: List<Pair<String, String>>
) {
    val guideUrl get() = "https://dontkillmyapp.com/$guideSlug"

    /** Öffnet die erste vorhandene Hersteller-Einstellung, sonst die App-Details. */
    fun openSettings(ctx: Context) {
        for ((pkg, cls) in components) {
            try {
                ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        private val HUAWEI = listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
        )
        private val COLOROS = listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"
        )

        /** Bekannter Hersteller dieses Geräts oder null (z. B. Google Pixel, Motorola: Standard-Android reicht). */
        fun current(): Vendor? {
            val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)
            return when {
                "samsung" in m -> Vendor(
                    "Samsung",
                    "Einstellungen → Akku → Hintergrundnutzungsgrenzen → „Nie schlafende Apps“ → Anruf Reminder hinzufügen.",
                    "samsung",
                    listOf(
                        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
                        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                )
                "xiaomi" in m || "redmi" in m || "poco" in m -> Vendor(
                    "Xiaomi",
                    "Autostart für Anruf Reminder einschalten. Außerdem: App-Info → Akku-Sparmodus → „Keine Einschränkungen“.",
                    "xiaomi",
                    listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
                )
                "honor" in m -> Vendor(
                    "Honor",
                    "App-Start → Anruf Reminder auf „Manuell verwalten“ und alle Schalter einschalten.",
                    "huawei",
                    listOf("com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity") + HUAWEI
                )
                "huawei" in m -> Vendor(
                    "Huawei",
                    "App-Start → Anruf Reminder auf „Manuell verwalten“ und alle Schalter einschalten.",
                    "huawei",
                    HUAWEI
                )
                "oneplus" in m -> Vendor(
                    "OnePlus",
                    "Autostart erlauben. Außerdem: App-Info → Akku → „Hintergrundaktivität erlauben“.",
                    "oneplus",
                    listOf("com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity") + COLOROS
                )
                "oppo" in m || "realme" in m -> Vendor(
                    if ("realme" in m) "Realme" else "Oppo",
                    "Autostart erlauben. Außerdem: App-Info → Akku → „Hintergrundaktivität erlauben“.",
                    if ("realme" in m) "realme" else "oppo",
                    COLOROS
                )
                "vivo" in m || "iqoo" in m -> Vendor(
                    "Vivo",
                    "Hintergrund-/Autostart für Anruf Reminder erlauben und Akku auf „Hoher Hintergrundverbrauch erlauben“.",
                    "vivo",
                    listOf(
                        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                    )
                )
                "asus" in m -> Vendor(
                    "Asus",
                    "Autostart für Anruf Reminder erlauben.",
                    "asus",
                    listOf(
                        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
                        "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity"
                    )
                )
                "nokia" in m || "hmd" in m -> Vendor(
                    "Nokia",
                    "Anruf Reminder von der Akku-Optimierung bzw. vom Energiesparen ausnehmen.",
                    "nokia",
                    listOf("com.evenwell.powersaving.g3" to "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")
                )
                else -> null
            }
        }
    }
}
