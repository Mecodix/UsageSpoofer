package com.yourname.spoofer

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import android.app.AndroidAppHelper
import android.net.Uri
import android.app.usage.UsageStats
import android.app.usage.UsageEvents

class UsageStatsSpoofer : IXposedHookLoadPackage {

    // Helper method to safely query your UI app configuration across sandboxed processes
    private fun getRemoteConfig(packageNameKey: String, field: String): String {
        try {
            val context = AndroidAppHelper.currentApplication() ?: return ""
            // Accesses the custom ContentProvider database exposed by your UI application
            val uri = Uri.parse("content://com.yourname.spoofer.configprovider/$packageNameKey")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            
            cursor?.use {
                while (it.moveToNext()) {
                    val currentKey = it.getString(0) // Column: key
                    if (currentKey == field) {
                        return it.getString(1) // Column: value
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[GodMode] Config Provider Retrieval Failure: ${e.message}")
        }
        return ""
    }

    /**
     * Dynamically scans UsageEvents fields to find the event container.
     * Field names vary across OEM ROMs and Android versions (mEventList, mEvents, etc.).
     * Returns the first field that is a List or Array, or null if none found.
     */
    private fun findEventContainer(usageEvents: UsageEvents): ArrayList<Any>? {
        try {
            val fields = usageEvents.javaClass.declaredFields
            for (field in fields) {
                field.isAccessible = true
                val value = field.get(usageEvents) ?: continue
                // Check if it's a List or Array
                if (value is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    return value as? ArrayList<Any>
                }
                if (value.javaClass.isArray) {
                    // Convert array to ArrayList
                    val list = ArrayList<Any>()
                    val length = java.lang.reflect.Array.getLength(value)
                    for (i in 0 until length) {
                        java.lang.reflect.Array.get(value, i)?.let { list.add(it) }
                    }
                    return list
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[GodMode] findEventContainer error: ${e.message}")
        }
        return null
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // The host tracker application processing the telemetry SDK
        val hostApp = "com.rayole.cashromeo2" 
        
        if (lpparam.packageName != hostApp) return

        XposedBridge.log("[GodMode] Adaptive Spoofer fully active inside: $hostApp")

        try {
            val usageStatsManagerClass = XposedHelpers.findClass(
                "android.app.usage.UsageStatsManager", 
                lpparam.classLoader
            )

            // =================================================================
            // 1. HOOK: Summary Statistics (queryUsageStats)
            // =================================================================
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass,
                "queryUsageStats",
                Int::class.javaPrimitiveType,  // intervalType
                Long::class.javaPrimitiveType, // beginTime
                Long::class.javaPrimitiveType, // endTime
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Check if spoofer is enabled
                        val isEnabled = getRemoteConfig("", "spoofer_enabled")
                        if (isEnabled == "false") return

                        val queryBeginTime = param.args[1] as Long
                        val queryEndTime = param.args[2] as Long
                        val resultList = param.result as? List<*> ?: return

                        for (statsObj in resultList) {
                            if (statsObj == null) continue
                            
                            val pkgName = XposedHelpers.getObjectField(statsObj, "mPackageName") as? String ?: continue
                            
                            // Check if this package matches our target directly
                            val appToSpoof = getRemoteConfig("", "target_package_to_spoof")
                            if (pkgName != appToSpoof) continue

                            // Safely retrieve the dynamic duration configurations set via your UI spinner/input
                            val savedTimeMsStr = getRemoteConfig("", "time")
                            val modeStr = getRemoteConfig("", "mode")
                            
                            val inputDurationMs = savedTimeMsStr.toLongOrNull() ?: 360000L // Defaults to 6 mins
                            val mode = modeStr.toIntOrNull() ?: 0 // 0 = SET, 1 = ADD, 2 = HIDE

                            val maxAvailableWindow = queryEndTime - queryBeginTime
                            var finalDurationMs = inputDurationMs

                            // Calculate target variables based on your UI's Spinner Selection
                            when (mode) {
                                0 -> { // SET mode: Forces target value directly
                                    if (finalDurationMs > maxAvailableWindow) {
                                        finalDurationMs = maxAvailableWindow - 5000L 
                                    }
                                }
                                1 -> { // ADD mode: Extends existing foreground duration
                                    val currentRealTime = XposedHelpers.getLongField(statsObj, "mTotalTimeInForeground")
                                    finalDurationMs = currentRealTime + inputDurationMs
                                }
                                2 -> { // HIDE mode: Zeroes out telemetry tracks
                                    finalDurationMs = 0L
                                }
                            }

                            // Modify the summary analytics payload fields inside memory
                            XposedHelpers.setLongField(statsObj, "mTotalTimeInForeground", finalDurationMs)
                            XposedHelpers.setLongField(statsObj, "mBeginTimeStamp", queryBeginTime)
                            XposedHelpers.setLongField(statsObj, "mEndTimeStamp", queryEndTime)

                            // Fix "Last Seen" disconnect — update last used/visible timestamps
                            // so the tracker doesn't see a contradiction between total time and last seen
                            XposedHelpers.setLongField(statsObj, "mLastTimeUsed", queryEndTime)
                            XposedHelpers.setLongField(statsObj, "mLastTimeVisible", queryEndTime)

                            // Log all hidden fields for audit (compare real vs spoof sessions)
                            XposedBridge.log("[GodMode] === FIELD AUDIT for $pkgName ===")
                            XposedBridge.log("[GodMode] mPackageName: $pkgName")
                            XposedBridge.log("[GodMode] mTotalTimeInForeground: ${XposedHelpers.getLongField(statsObj, "mTotalTimeInForeground")}")
                            XposedBridge.log("[GodMode] mLastTimeUsed: ${XposedHelpers.getLongField(statsObj, "mLastTimeUsed")}")
                            XposedBridge.log("[GodMode] mLastTimeVisible: ${XposedHelpers.getLongField(statsObj, "mLastTimeVisible")}")
                            XposedBridge.log("[GodMode] mAppLaunchCount: ${XposedHelpers.getIntField(statsObj, "mAppLaunchCount")}")
                            XposedBridge.log("[GodMode] mBeginTimeStamp: ${XposedHelpers.getLongField(statsObj, "mBeginTimeStamp")}")
                            XposedBridge.log("[GodMode] mEndTimeStamp: ${XposedHelpers.getLongField(statsObj, "mEndTimeStamp")}")
                            XposedBridge.log("[GodMode] Successfully applied mode $mode to summary statistics for: $pkgName")
                        }
                    }
                }
            )

            // =================================================================
            // 2. HOOK: Timeline Events (queryEvents)
            // =================================================================
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass,
                "queryEvents",
                Long::class.javaPrimitiveType, // beginTime
                Long::class.javaPrimitiveType, // endTime
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // Check if spoofer is enabled
                            val isEnabled = getRemoteConfig("", "spoofer_enabled")
                            if (isEnabled == "false") return

                            val queryBeginTime = param.args[0] as Long
                            val queryEndTime = param.args[1] as Long
                            val usageEvents = param.result as? UsageEvents ?: return

                            // Get config for target package
                            val targetPkg = getRemoteConfig("", "target_package_to_spoof")
                            if (targetPkg.isEmpty()) return

                            val savedTimeMsStr = getRemoteConfig("", "time")
                            val modeStr = getRemoteConfig("", "mode")

                            val inputDurationMs = savedTimeMsStr.toLongOrNull() ?: 360000L
                            val mode = modeStr.toIntOrNull() ?: 0

                            // Dynamically scan UsageEvents fields to find the event container
                            // (field names vary across OEM ROMs and Android versions)
                            val eventContainer = findEventContainer(usageEvents)
                            if (eventContainer == null) {
                                XposedBridge.log("[GodMode] Could not find event container in UsageEvents")
                                return
                            }

                            when (mode) {
                                2 -> { // HIDE mode: remove all events for target package
                                    eventContainer.removeAll { event ->
                                        val pkg = XposedHelpers.getObjectField(event, "mPackage") as? String
                                        pkg == targetPkg
                                    }
                                    XposedBridge.log("[GodMode] HIDE mode: cleared events for $targetPkg")
                                }
                                else -> { // SET or ADD mode: inject fake events
                                    val eventClass = XposedHelpers.findClass(
                                        "android.app.usage.UsageEvents\$Event",
                                        usageEvents.javaClass.classLoader
                                    )

                                    val maxAvailableWindow = queryEndTime - queryBeginTime
                                    var durationMs = inputDurationMs
                                    if (mode == 0 && durationMs > maxAvailableWindow) {
                                        durationMs = maxAvailableWindow - 5000L
                                    }

                                    val foregroundTime = queryBeginTime
                                    val backgroundTime = (queryBeginTime + durationMs).coerceAtMost(queryEndTime)

                                    // Create foreground event (ACTIVITY_RESUMED)
                                    val eventForeground = eventClass.newInstance()
                                    XposedHelpers.setObjectField(eventForeground, "mPackage", targetPkg)
                                    XposedHelpers.setLongField(eventForeground, "mTimeStamp", foregroundTime)
                                    XposedHelpers.setIntField(eventForeground, "mEventType", 1)

                                    // Create touch event (USER_INTERACTION) - proves human usage
                                    val eventTouch = eventClass.newInstance()
                                    XposedHelpers.setObjectField(eventTouch, "mPackage", targetPkg)
                                    XposedHelpers.setLongField(eventTouch, "mTimeStamp", foregroundTime + 2500L)
                                    XposedHelpers.setIntField(eventTouch, "mEventType", 7)

                                    // Create background event (ACTIVITY_PAUSED)
                                    val eventBackground = eventClass.newInstance()
                                    XposedHelpers.setObjectField(eventBackground, "mPackage", targetPkg)
                                    XposedHelpers.setLongField(eventBackground, "mTimeStamp", backgroundTime)
                                    XposedHelpers.setIntField(eventBackground, "mEventType", 2)

                                    // Inject all three into the system's internal event list
                                    eventContainer.add(eventForeground)
                                    eventContainer.add(eventTouch)
                                    eventContainer.add(eventBackground)

                                    // Timeline audit log — dump all injected events for comparison
                                    XposedBridge.log("[GodMode] === TIMELINE AUDIT for $targetPkg ===")
                                    for ((index, event) in eventContainer.withIndex()) {
                                        val pkg = XposedHelpers.getObjectField(event, "mPackage") as? String ?: "?"
                                        val ts = XposedHelpers.getLongField(event, "mTimeStamp")
                                        val type = XposedHelpers.getIntField(event, "mEventType")
                                        XposedBridge.log("[GodMode] Event[$index]: pkg=$pkg, type=$type, timestamp=$ts")
                                    }
                                    XposedBridge.log("[GodMode] Injected foreground+touch+background events for $targetPkg (${durationMs}ms)")
                                }
                            }
                        } catch (e: Exception) {
                            XposedBridge.log("[GodMode] queryEvents hook error: ${e.message}")
                        }
                    }
                }
            )

        } catch (e: Exception) {
            XposedBridge.log("[GodMode] Critical Initialization Defect: ${e.message}")
        }
    }
}
