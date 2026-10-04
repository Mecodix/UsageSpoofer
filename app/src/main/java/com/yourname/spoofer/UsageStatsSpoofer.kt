package com.yourname.spoofer

import android.app.usage.UsageStats
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class UsageStatsSpoofer : IXposedHookLoadPackage {

    // The app you want to hide/modify (e.g., YouTube)
    private val TARGET_APP_TO_SPOOF = "com.google.android.youtube"
    
    // The fake time you want to show (in milliseconds). E.g., 5 minutes = 300000ms. 
    // Set to 0 to completely hide usage.
    private val FAKE_TIME_MS = 300000L 

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Step 1: Only inject if the app is a tracker (saves battery & memory)
        val targetTrackers = listOf(
            "com.google.android.apps.wellbeing",
            "com.samsung.android.forest"
        )
        if (!targetTrackers.contains(lpparam.packageName)) return

        XposedBridge.log("UsageSpoofer: Loaded into tracker -> ${lpparam.packageName}")

        try {
            // =========================================================
            // HOOK 1: Spoof the "Lazy" Time Bucket (queryUsageStats)
            // =========================================================
            XposedHelpers.findAndHookMethod(
                "android.app.usage.UsageStatsManager",
                lpparam.classLoader,
                "queryUsageStats",
                Int::class.java,  // intervalType
                Long::class.java, // beginTime
                Long::class.java, // endTime
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // The method returns a List of UsageStats
                        val statsList = param.result as? List<*> ?: return

                        for (stat in statsList) {
                            if (stat == null) continue
                            
                            // Access the hidden variable 'mPackageName'
                            val pkgName = XposedHelpers.getObjectField(stat, "mPackageName") as? String
                            
                            if (pkgName == TARGET_APP_TO_SPOOF) {
                                // Overwrite the actual time with our fake time!
                                XposedHelpers.setLongField(stat, "mTotalTimeInForeground", FAKE_TIME_MS)
                                XposedBridge.log("UsageSpoofer: Successfully faked queryUsageStats for $pkgName")
                            }
                        }
                    }
                }
            )

            // =========================================================
            // HOOK 2: Spoof the "Precise" Raw Event Logs (queryEvents)
            // =========================================================
            XposedHelpers.findAndHookMethod(
                "android.app.usage.UsageStatsManager",
                lpparam.classLoader,
                "queryEvents",
                Long::class.java, // beginTime
                Long::class.java, // endTime
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // The method returns a 'UsageEvents' object
                        val usageEvents = param.result ?: return

                        // 'UsageEvents' contains a private array called 'mEventsToWrite'
                        val eventsArray = XposedHelpers.getObjectField(usageEvents, "mEventsToWrite") as? Array<*>
                        if (eventsArray != null) {
                            val filteredEvents = mutableListOf<Any>()
                            
                            // Loop through the raw events
                            for (event in eventsArray) {
                                if (event == null) continue
                                
                                val pkgName = XposedHelpers.getObjectField(event, "mPackage") as? String
                                
                                // If the event belongs to the app we are hiding, we simply DELETE it from the list.
                                // This causes the tracker to think the app was never opened today.
                                if (pkgName != TARGET_APP_TO_SPOOF) {
                                    filteredEvents.add(event)
                                }
                            }

                            // Reconstruct the array without our target app's events
                            val newArrayType = eventsArray::class.java.componentType
                            val newEventsArray = java.lang.reflect.Array.newInstance(newArrayType, filteredEvents.size)
                            for (i in filteredEvents.indices) {
                                java.lang.reflect.Array.set(newEventsArray, i, filteredEvents[i])
                            }

                            // Inject the filtered array back into the UsageEvents object
                            XposedHelpers.setObjectField(usageEvents, "mEventsToWrite", newEventsArray)
                            XposedHelpers.setIntField(usageEvents, "mEventCount", filteredEvents.size)
                            
                            XposedBridge.log("UsageSpoofer: Successfully wiped raw event history for $TARGET_APP_TO_SPOOF")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("UsageSpoofer Error: ${e.message}")
        }
    }
}
