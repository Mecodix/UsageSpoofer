package com.yourname.spoofer

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import android.app.usage.UsageStats
import android.app.usage.UsageEvents

class UsageStatsSpoofer : IXposedHookLoadPackage {

    // Initialize cross-process preferences using your module's actual package name
    private val prefs = XSharedPreferences("com.yourname.spoofer", "spoofer_settings")

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // The host reward app executing the telemetry gathering SDK
        val hostApp = "com.rayole.cashromeo2" 
        
        if (lpparam.packageName != hostApp) return

        XposedBridge.log("[GodMode] Hook successfully active inside host: $hostApp")

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
                        // Force reload the preference file from disk to get the latest UI input
                        prefs.reload()
                        val appToSpoof = prefs.getString("target_package_to_spoof", "")
                        
                        if (appToSpoof.isNullOrBlank()) {
                            XposedBridge.log("[GodMode] Warning: No target package specified in UI settings.")
                            return
                        }

                        val resultList = param.result as? List<*> ?: return
                        
                        for (statsObj in resultList) {
                            if (statsObj == null) continue
                            
                            val pkgName = XposedHelpers.getObjectField(statsObj, "mPackageName") as? String
                            if (pkgName == appToSpoof) {
                                // Inject exactly 6 minutes (360,000 milliseconds) of total usage
                                val fakeDuration = 360000L
                                
                                XposedHelpers.setLongField(statsObj, "mTotalTimeInForeground", fakeDuration)
                                XposedBridge.log("[GodMode] Successfully spoofed stats duration for: $pkgName")
                            }
                        }
                    }
                }
            )

            // 2. Hook the Timeline Events API to match the spoofed duration
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass,
                "queryEvents",
                Long::class.javaPrimitiveType, // beginTime
                Long::class.javaPrimitiveType, // endTime
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        prefs.reload()
                        val appToSpoof = prefs.getString("target_package_to_spoof", "")
                        if (appToSpoof.isNullOrBlank()) return

                        val usageEvents = param.result as? UsageEvents ?: return
                        val beginTime = param.args[0] as Long
                        
                        // Construct systemic synthetic timeline milestones
                        val eventForeground = UsageEvents.Event().apply {
                            XposedHelpers.setObjectField(this, "mPackage", appToSpoof)
                            XposedHelpers.setIntField(this, "mEventType", 1) // MOVE_TO_FOREGROUND
                            XposedHelpers.setLongField(this, "mTimeStamp", beginTime + 5000)
                        }
                        
                        val eventBackground = UsageEvents.Event().apply {
                            XposedHelpers.setObjectField(this, "mPackage", appToSpoof)
                            XposedHelpers.setIntField(this, "mEventType", 2) // MOVE_TO_BACKGROUND
                            XposedHelpers.setLongField(this, "mTimeStamp", beginTime + 365000)
                        }
                        
                        // In an experimental structure, these event objects must be array-inserted 
                        // into the native iterable collection inside the returned UsageEvents instance.
                    }
                }
            )

        } catch (e: Exception) {
            XposedBridge.log("[GodMode] Failed to initialize hooks: ${e.message}")
        }
    }
}
