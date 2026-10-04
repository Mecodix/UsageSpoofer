package com.yourname.spoofer

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageStatsSpoofer : IXposedHookLoadPackage {

    private fun formatTime(ms: Long): String {
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        XposedBridge.log("[GodMode] 🟢 Reality hijacked in: ${lpparam.packageName}")

        val prefs = XSharedPreferences("com.yourname.spoofer", "SpooferConfig")
        prefs.makeWorldReadable()

        try {
            // =========================================================
            // TRAP A: queryUsageStats (The Lazy Time Buckets)
            // (Your original code here is fine for lazy trackers)
            // =========================================================

            // =========================================================
            // TRAP B: queryEvents (The Precise Raw Timeline)
            // =========================================================
            XposedHelpers.findAndHookMethod(
                "android.app.usage.UsageStatsManager", lpparam.classLoader, "queryEvents",
                Long::class.java, Long::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        prefs.reload()
                        val begin = param.args[0] as Long
                        val end = param.args[1] as Long
                        
                        val usageEvents = param.result ?: return
                        val eventsArray = XposedHelpers.getObjectField(usageEvents, "mEventsToWrite") as? Array<*> ?: return
                        
                        val filteredEvents = mutableListOf<Any>()
                        val packageRealTimes = mutableMapOf<String, Long>()
                        val packageLastResume = mutableMapOf<String, Long>()
                        
                        // PASS 1: Analyze & Obliterate Target Events
                        for (event in eventsArray) {
                            if (event == null) continue
                            val pkgName = XposedHelpers.getObjectField(event, "mPackage") as? String ?: ""
                            val type = XposedHelpers.getIntField(event, "mEventType")
                            val timestamp = XposedHelpers.getLongField(event, "mTimeStamp")
                            
                            // Track real usage time in case we need it for Mode 1 (Add Time)
                            if (type == 1) { // ACTIVITY_RESUMED
                                packageLastResume[pkgName] = timestamp
                            } else if ((type == 2 || type == 23) && packageLastResume.containsKey(pkgName)) { // ACTIVITY_PAUSED / STOPPED
                                val start = packageLastResume.remove(pkgName)!!
                                packageRealTimes[pkgName] = (packageRealTimes[pkgName] ?: 0L) + (timestamp - start)
                            }

                            // If package is active in our spoofer, strip ALL its real events from the timeline.
                            // We will forge a better reality for it later.
                            if (prefs.getBoolean("${pkgName}_active", false)) {
                                continue 
                            }
                            
                            filteredEvents.add(event)
                        }

                        // PASS 2: Inject Flawless Reality Blocks
                        // We start injecting 1 minute after the query 'begin' to stay safely in bounds.
                        var anchorTime = begin + 60000L 
                        
                        val allPackagesActive = prefs.all.keys.filter { it.endsWith("_active") }
                        
                        for (key in allPackagesActive) {
                            if (prefs.getBoolean(key, false)) {
                                val pkgName = key.replace("_active", "")
                                val mode = prefs.getInt("${pkgName}_mode", 0)
                                val addedTimeMs = prefs.getLong("${pkgName}_time", 0L)
                                
                                var targetDuration = 0L
                                when (mode) {
                                    0 -> targetDuration = addedTimeMs // Mode 0: Exact Time
                                    1 -> {
                                        // Mode 1: Real Time + Added Time
                                        val realTime = packageRealTimes[pkgName] ?: 0L
                                        targetDuration = realTime + addedTimeMs
                                    }
                                    // Mode 2 (Ghost) skips this entirely, leaving 0 events. Perfect stealth.
                                }
                                
                                if (targetDuration > 0) {
                                    // Cap it so we don't accidentally project into the future and crash the tracker
                                    val maxAllowed = end - anchorTime - 60000L
                                    if (targetDuration > maxAllowed) targetDuration = maxAllowed
                                    
                                    if (targetDuration > 0) {
                                        val fakeStartTime = anchorTime
                                        val fakeEndTime = fakeStartTime + targetDuration
                                        
                                        try {
                                            val eventClass = Class.forName("android.app.usage.UsageEvents\$Event")
                                            
                                            // INJECT: RESUME
                                            val resumeEvent = eventClass.newInstance()
                                            XposedHelpers.setObjectField(resumeEvent, "mPackage", pkgName)
                                            XposedHelpers.setObjectField(resumeEvent, "mClass", "$pkgName.MainActivity") // Mandatory for smart trackers
                                            XposedHelpers.setIntField(resumeEvent, "mEventType", 1) 
                                            XposedHelpers.setLongField(resumeEvent, "mTimeStamp", fakeStartTime)

                                            // INJECT: PAUSE
                                            val pauseEvent = eventClass.newInstance()
                                            XposedHelpers.setObjectField(pauseEvent, "mPackage", pkgName)
                                            XposedHelpers.setObjectField(pauseEvent, "mClass", "$pkgName.MainActivity")
                                            XposedHelpers.setIntField(pauseEvent, "mEventType", 2) 
                                            XposedHelpers.setLongField(pauseEvent, "mTimeStamp", fakeEndTime)

                                            filteredEvents.add(resumeEvent)
                                            filteredEvents.add(pauseEvent)
                                            
                                            XposedBridge.log("[GodMode] 💉 INJECTED TIMELINE: $pkgName from ${formatTime(fakeStartTime)} to ${formatTime(fakeEndTime)} (${targetDuration / 60000} mins)")
                                            
                                            // Shift anchor forward so the next spoofed app doesn't overlap! 
                                            // Android only allows one foreground app at a time.
                                            anchorTime = fakeEndTime + 1000L 
                                        } catch (e: Exception) {
                                            XposedBridge.log("[GodMode] ❌ Injection failed: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }

                        // PASS 3: Reorder the Universe
                        // Trackers will instantly crash if events are out of chronological order.
                        filteredEvents.sortBy { XposedHelpers.getLongField(it, "mTimeStamp") }

                        // Write our forged reality back to the event array
                        val newArrayType = eventsArray::class.java.componentType
                        if (newArrayType != null) {
                            val newEventsArray = java.lang.reflect.Array.newInstance(newArrayType, filteredEvents.size)
                            for (i in filteredEvents.indices) {
                                java.lang.reflect.Array.set(newEventsArray, i, filteredEvents[i])
                            }
                            XposedHelpers.setObjectField(usageEvents, "mEventsToWrite", newEventsArray)
                            XposedHelpers.setIntField(usageEvents, "mEventCount", filteredEvents.size)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("[GodMode] CRITICAL ERROR: ${e.message}")
        }
    }
}
