package com.yourname.spoofer

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import android.app.AndroidAppHelper
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageStatsSpoofer : IXposedHookLoadPackage {

    private val hostApp = "com.rayole.cashromeo2"
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun formatTimestamp(epochMs: Long): String {
        return "${dateFormat.format(Date(epochMs))} ($epochMs)"
    }

    private fun getContext() = AndroidAppHelper.currentApplication()

    private fun log(tag: String, message: String) {
        XposedBridge.log("[Telemetry] $message")
        getContext()?.let { LogWriter.log(it, tag, message) }
    }

    /**
     * Dynamically scans UsageEvents fields to find the event container.
     * Returns the first field that is a List or Array, or null if none found.
     */
    private fun findEventContainer(usageEvents: UsageEvents): ArrayList<Any>? {
        try {
            val fields = usageEvents.javaClass.declaredFields
            for (field in fields) {
                field.isAccessible = true
                val value = field.get(usageEvents) ?: continue
                if (value is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    return value as? ArrayList<Any>
                }
                if (value.javaClass.isArray) {
                    val list = ArrayList<Any>()
                    val length = java.lang.reflect.Array.getLength(value)
                    for (i in 0 until length) {
                        java.lang.reflect.Array.get(value, i)?.let { list.add(it) }
                    }
                    return list
                }
            }
        } catch (e: Exception) {
            log("ERROR", "findEventContainer failed: ${e.message}")
        }
        return null
    }

    private fun decodeEventType(type: Int): String {
        return when (type) {
            1 -> "RESUME"
            2 -> "PAUSE"
            5 -> "CONFIG_CHANGE"
            7 -> "INTERACTION"
            10 -> "NOTIFICATION_PANEL"
            13 -> "SCREEN_INTERACTIVE"
            14 -> "SCREEN_NON_INTERACTIVE"
            23 -> "STOPPED"
            26 -> "DEVICE_SHUTDOWN"
            else -> "UNKNOWN($type)"
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != hostApp) return

        log("INIT", "Telemetry Capture Trap active inside: $hostApp")

        try {
            val usageStatsManagerClass = XposedHelpers.findClass(
                "android.app.usage.UsageStatsManager",
                lpparam.classLoader
            )

            // =================================================================
            // 1. SUMMARY CAPTURE DECODER (queryUsageStats)
            // =================================================================
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass,
                "queryUsageStats",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val queryBeginTime = param.args[1] as? Long ?: return
                            val queryEndTime = param.args[2] as? Long ?: return
                            val resultList = param.result as? List<*> ?: return

                            val capture = buildString {
                                appendLine("╔══════════════════════════════════════════════════════════════╗")
                                appendLine("║           SUMMARY CAPTURE — queryUsageStats                  ║")
                                appendLine("╠══════════════════════════════════════════════════════════════╣")
                                appendLine("║ Capture Time: ${formatTimestamp(System.currentTimeMillis())}")
                                appendLine("║ Query Window: ${formatTimestamp(queryBeginTime)}")
                                appendLine("║ Query End:   ${formatTimestamp(queryEndTime)}")
                                appendLine("║ Packages:    ${resultList.size}")
                                appendLine("╚════════════════════════════════════════════════════════════════╝")
                            }
                            log("SUMMARY", capture)

                            for (statsObj in resultList) {
                                if (statsObj == null) continue

                                try {
                                    val pkgName = XposedHelpers.getObjectField(statsObj, "mPackageName") as? String ?: "?"
                                    val beginTimeStamp = XposedHelpers.getLongField(statsObj, "mBeginTimeStamp")
                                    val endTimeStamp = XposedHelpers.getLongField(statsObj, "mEndTimeStamp")
                                    val totalTimeInForeground = XposedHelpers.getLongField(statsObj, "mTotalTimeInForeground")
                                    val totalTimeVisible = XposedHelpers.getLongField(statsObj, "mTotalTimeVisible")
                                    val lastTimeUsed = XposedHelpers.getLongField(statsObj, "mLastTimeUsed")
                                    val lastTimeVisible = XposedHelpers.getLongField(statsObj, "mLastTimeVisible")
                                    val appLaunchCount = XposedHelpers.getIntField(statsObj, "mAppLaunchCount")

                                    val entry = buildString {
                                        appendLine("┌─ Package: $pkgName")
                                        appendLine("│  mBeginTimeStamp:        ${formatTimestamp(beginTimeStamp)}")
                                        appendLine("│  mEndTimeStamp:          ${formatTimestamp(endTimeStamp)}")
                                        appendLine("│  mTotalTimeInForeground: $totalTimeInForeground ms")
                                        appendLine("│  mTotalTimeVisible:      $totalTimeVisible ms")
                                        appendLine("│  mLastTimeUsed:          ${formatTimestamp(lastTimeUsed)}")
                                        appendLine("│  mLastTimeVisible:       ${formatTimestamp(lastTimeVisible)}")
                                        appendLine("│  mAppLaunchCount:        $appLaunchCount")
                                        appendLine("└────────────────────────────────────────────────────────────")
                                    }
                                    log("SUMMARY", entry)
                                } catch (e: Exception) {
                                    log("ERROR", "Failed to decode UsageStats entry: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            log("ERROR", "queryUsageStats capture failed: ${e.message}")
                        }
                    }
                }
            )

            // =================================================================
            // 2. TIMELINE EVENT DECODER (queryEvents)
            // =================================================================
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass,
                "queryEvents",
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val queryBeginTime = param.args[0] as? Long ?: return
                            val queryEndTime = param.args[1] as? Long ?: return
                            val usageEvents = param.result as? UsageEvents ?: return

                            val eventContainer = findEventContainer(usageEvents)
                            if (eventContainer == null) {
                                log("ERROR", "Could not find event container in UsageEvents")
                                return
                            }

                            val capture = buildString {
                                appendLine("╔══════════════════════════════════════════════════════════════╗")
                                appendLine("║          TIMELINE CAPTURE — queryEvents                      ║")
                                appendLine("╠══════════════════════════════════════════════════════════════╣")
                                appendLine("║ Capture Time: ${formatTimestamp(System.currentTimeMillis())}")
                                appendLine("║ Query Window: ${formatTimestamp(queryBeginTime)}")
                                appendLine("║ Query End:   ${formatTimestamp(queryEndTime)}")
                                appendLine("║ Total Events: ${eventContainer.size}")
                                appendLine("╚════════════════════════════════════════════════════════════════╝")
                            }
                            log("TIMELINE", capture)

                            for ((index, event) in eventContainer.withIndex()) {
                                try {
                                    val pkg = XposedHelpers.getObjectField(event, "mPackage") as? String ?: "?"
                                    val cls = XposedHelpers.getObjectField(event, "mClass") as? String ?: "?"
                                    val type = XposedHelpers.getIntField(event, "mEventType")
                                    val timestamp = XposedHelpers.getLongField(event, "mTimeStamp")

                                    val entry = buildString {
                                        appendLine("┌─ Event[$index]")
                                        appendLine("│  mPackage:    $pkg")
                                        appendLine("│  mClass:      $cls")
                                        appendLine("│  mEventType:  $type (${decodeEventType(type)})")
                                        appendLine("│  mTimeStamp:  ${formatTimestamp(timestamp)}")
                                        appendLine("└────────────────────────────────────────────────────────────")
                                    }
                                    log("TIMELINE", entry)
                                } catch (e: Exception) {
                                    log("ERROR", "Failed to decode event[$index]: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            log("ERROR", "queryEvents capture failed: ${e.message}")
                        }
                    }
                }
            )

        } catch (e: Exception) {
            log("ERROR", "Critical initialization failure: ${e.message}")
        }
    }
}
