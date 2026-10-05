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
                        val queryBeginTime = param.args[1] as Long
                        val queryEndTime = param.args[2] as Long
                        val resultList = param.result as? List<*> ?: return

                        for (statsObj in resultList) {
                            if (statsObj == null) continue
                            
                            val pkgName = XposedHelpers.getObjectField(statsObj, "mPackageName") as? String ?: continue
                            
                            // Check if this specific package has a configuration active in your UI app
                            val isActive = getRemoteConfig(pkgName, "active")
                            if (isActive != "true") continue

                            // Safely retrieve the dynamic duration configurations set via your UI spinner/input
                            val savedTimeMsStr = getRemoteConfig(pkgName, "time")
                            val modeStr = getRemoteConfig(pkgName, "mode")
                            
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
                        val queryBeginTime = param.args[0] as Long
                        val queryEndTime = param.args[1] as Long
                        val usageEvents = param.result as? UsageEvents ?: return

                        // Since queryEvents returns an aggregate collection instance, we can trace inside the sequence loop
                        // to assert structural milestones for configured packages matching your overnight window shifts.
                        XposedBridge.log("[GodMode] Parsing dynamic query execution path across event loops.")
                    }
                }
            )

        } catch (e: Exception) {
            XposedBridge.log("[GodMode] Critical Initialization Defect: ${e.message}")
        }
    }
}
