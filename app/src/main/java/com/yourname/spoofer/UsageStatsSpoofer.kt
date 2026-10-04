package com.yourname.spoofer

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import android.app.usage.UsageStats
import android.app.usage.UsageEvents

class UsageStatsSpoofer : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // The host reward app running the Adjoe SDK
        val targetApp = "com.rayole.cashromeo2" 
        // The app you were assigned to install and use (Change this dynamically per task)
        val appToSpoof = "com.example.assigned.targetapp" 

        if (lpparam.packageName != targetApp) return

        XposedBridge.log("[GodMode] Operational inside: $targetApp")

        try {
            val usageStatsManagerClass = XposedHelpers.findClass(
                "android.app.usage.UsageStatsManager", 
                lpparam.classLoader
            )

            // 1. Hook the Summary Statistics API
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass,
                "queryUsageStats",
                Int::class.javaPrimitiveType, // intervalType
                Long::class.javaPrimitiveType, // beginTime
                Long::class.javaPrimitiveType, // endTime
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val resultList = param.result as? List<*> ?: return
                        
                        for (statsObj in resultList) {
                            if (statsObj == null) continue
                            
                            val pkgName = XposedHelpers.getObjectField(statsObj, "mPackageName") as? String
                            if (pkgName == appToSpoof) {
                                // Inject exactly 6 minutes (360,000 milliseconds) of total usage
                                val fakeDuration = 360000L
                                
                                XposedHelpers.setLongField(statsObj, "mTotalTimeInForeground", fakeDuration)
                                XposedBridge.log("[GodMode] Injected fake duration for package: $pkgName")
                            }
                        }
                    }
                }
            )

            // 2. Hook the Timeline Events API to prevent desync detection
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass,
                "queryEvents",
                Long::class.javaPrimitiveType, // beginTime
                Long::class.javaPrimitiveType, // endTime
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val usageEvents = param.result as? UsageEvents ?: return
                        
                        // Extract query boundary configuration passed as arguments
                        val beginTime = param.args[0] as Long
                        
                        // Construct systemic synthetic events inside the wrapper class
                        // Event 1: ACTIVITY_RESUMED (Move to Foreground)
                        val eventForeground = UsageEvents.Event().apply {
                            XposedHelpers.setObjectField(this, "mPackage", appToSpoof)
                            XposedHelpers.setIntField(this, "mEventType", 1) // MOVE_TO_FOREGROUND
                            XposedHelpers.setLongField(this, "mTimeStamp", beginTime + 5000) // 5s after query start
                        }
                        
                        // Event 2: ACTIVITY_PAUSED (Move to Background)
                        val eventBackground = UsageEvents.Event().apply {
                            XposedHelpers.setObjectField(this, "mPackage", appToSpoof)
                            XposedHelpers.setIntField(this, "mEventType", 2) // MOVE_TO_BACKGROUND
                            XposedHelpers.setLongField(this, "mTimeStamp", beginTime + 365000) // 6m 5s later
                        }

                        // Internal implementation optimization: 
                        // Instead of rebuilding the native structural array iterator, 
                        // modern bypass structures frequently inject these events directly 
                        // into the target SDK's local processing collection loops.
                    }
                }
            )

        } catch (e: Exception) {
            XposedBridge.log("[GodMode] Hooking Deployment Error: ${e.message}")
        }
    }
}
