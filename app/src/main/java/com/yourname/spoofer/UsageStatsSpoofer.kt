package com.yourname.spoofer

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class UsageStatsSpoofer : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val targetTrackers = listOf("com.google.android.apps.wellbeing", "com.samsung.android.forest")
        if (!targetTrackers.contains(lpparam.packageName)) return

        // Create the bridge to read the UI settings
        val prefs = XSharedPreferences("com.yourname.spoofer", "SpooferConfig")
        prefs.makeWorldReadable()

        try {
            // HOOK 1: The Lazy Time Buckets
            XposedHelpers.findAndHookMethod(
                "android.app.usage.UsageStatsManager", lpparam.classLoader, "queryUsageStats",
                Int::class.java, Long::class.java, Long::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        prefs.reload() // Reload config in case user changed it in the app
                        val statsList = param.result as? List<*> ?: return

                        for (stat in statsList) {
                            if (stat == null) continue
                            val pkgName = XposedHelpers.getObjectField(stat, "mPackageName") as? String ?: continue
                            
                            // Check if user enabled spoofing for this specific app
                            if (prefs.getBoolean("${pkgName}_active", false)) {
                                val mode = prefs.getInt("${pkgName}_mode", 0)
                                val targetTimeMs = prefs.getLong("${pkgName}_time", 0L)
                                val realTimeMs = XposedHelpers.getLongField(stat, "mTotalTimeInForeground") as Long

                                when (mode) {
                                    0 -> XposedHelpers.setLongField(stat, "mTotalTimeInForeground", targetTimeMs) // SET
                                    1 -> XposedHelpers.setLongField(stat, "mTotalTimeInForeground", realTimeMs + targetTimeMs) // ADD
                                    2 -> XposedHelpers.setLongField(stat, "mTotalTimeInForeground", 0L) // HIDE
                                }
                            }
                        }
                    }
                }
            )

            // HOOK 2: The Precise Event Logs (Ghost Mode Enforcer)
            XposedHelpers.findAndHookMethod(
                "android.app.usage.UsageStatsManager", lpparam.classLoader, "queryEvents",
                Long::class.java, Long::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        prefs.reload()
                        val usageEvents = param.result ?: return
                        val eventsArray = XposedHelpers.getObjectField(usageEvents, "mEventsToWrite") as? Array<*> ?: return
                        
                        val filteredEvents = mutableListOf<Any>()
                        
                        for (event in eventsArray) {
                            if (event == null) continue
                            val pkgName = XposedHelpers.getObjectField(event, "mPackage") as? String ?: ""
                            
                            // If Mode 2 (HIDE) is active for this app, we drop the events completely.
                            // If it's SET or ADD, we leave the events alone for stability, as Tracker Apps 
                            // usually prioritize the `queryUsageStats` total time over recalculating raw events.
                            if (prefs.getBoolean("${pkgName}_active", false) && prefs.getInt("${pkgName}_mode", 0) == 2) {
                                // Skip adding this event (Ghost Mode)
                                continue 
                            } else {
                                filteredEvents.add(event)
                            }
                        }

                        // Write array back
                        val newArrayType = eventsArray::class.java.componentType
                        val newEventsArray = java.lang.reflect.Array.newInstance(newArrayType, filteredEvents.size)
                        for (i in filteredEvents.indices) {
                            java.lang.reflect.Array.set(newEventsArray, i, filteredEvents[i])
                        }

                        XposedHelpers.setObjectField(usageEvents, "mEventsToWrite", newEventsArray)
                        XposedHelpers.setIntField(usageEvents, "mEventCount", filteredEvents.size)
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("GodModeSpoofer Error: ${e.message}")
        }
    }
}
