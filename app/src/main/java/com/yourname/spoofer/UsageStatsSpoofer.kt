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
        XposedBridge.log("[GodMode] 🟢 Successfully injected into: ${lpparam.packageName}")

        val prefs = XSharedPreferences("com.yourname.spoofer", "SpooferConfig")
        prefs.makeWorldReadable()

        try {
            // =========================================================
            // TRAP A: queryUsageStats (The Lazy Time Buckets)
            // =========================================================
            XposedHelpers.findAndHookMethod(
                "android.app.usage.UsageStatsManager", lpparam.classLoader, "queryUsageStats",
                Int::class.java, Long::class.java, Long::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        prefs.reload()
                        val interval = param.args[0] as Int
                        val begin = param.args[1] as Long
                        val end = param.args[2] as Long
                        
                        XposedBridge.log(
                            "[GodMode] 🕵️ ${lpparam.packageName} -> queryUsageStats (Trap A)\n" +
                            "   ├─ Interval: $interval\n" +
                            "   ├─ Range: ${formatTime(begin)} TO ${formatTime(end)}"
                        )

                        val statsList = param.result as? List<*> ?: return

                        for (stat in statsList) {
                            if (stat == null) continue
                            val pkgName = XposedHelpers.getObjectField(stat, "mPackageName") as? String ?: continue
                            
                            if (prefs.getBoolean("${pkgName}_active", false)) {
                                val mode = prefs.getInt("${pkgName}_mode", 0)
                                val targetTimeMs = prefs.getLong("${pkgName}_time", 0L)
                                val realTimeMs = XposedHelpers.getLongField(stat, "mTotalTimeInForeground") as Long

                                when (mode) {
                                    0 -> { 
                                        XposedHelpers.setLongField(stat, "mTotalTimeInForeground", targetTimeMs)
                                        XposedBridge.log("[GodMode] ⚙️ SET $pkgName to ${targetTimeMs / 60000} mins")
                                    }
                                    1 -> { 
                                        XposedHelpers.setLongField(stat, "mTotalTimeInForeground", realTimeMs + targetTimeMs)
                                        XposedBridge.log("[GodMode] ➕ ADDED ${targetTimeMs / 60000} mins to $pkgName")
                                    }
                                    2 -> { 
                                        XposedHelpers.setLongField(stat, "mTotalTimeInForeground", 0L)
                                        XposedBridge.log("[GodMode] 👻 HIDDEN (Ghosted) $pkgName in totals")
                                    }
                                }
                            }
                        }
                    }
                }
            )

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
                        
                        XposedBridge.log(
                            "[GodMode] 🕵️ ${lpparam.packageName} -> queryEvents (Trap B)\n" +
                            "   ├─ Range: ${formatTime(begin)} TO ${formatTime(end)}"
                        )

                        val usageEvents = param.result ?: return
                        val eventsArray = XposedHelpers.getObjectField(usageEvents, "mEventsToWrite") as? Array<*> ?: return
                        
                        val filteredEvents = mutableListOf<Any>()
                        
                        for (event in eventsArray) {
                            if (event == null) continue
                            val pkgName = XposedHelpers.getObjectField(event, "mPackage") as? String ?: ""
                            if (prefs.getBoolean("${pkgName}_active", false) && prefs.getInt("${pkgName}_mode", 0) == 2) {
                                continue 
                            }
                            filteredEvents.add(event)
                        }

                        var currentInjectionAnchor = begin + 43200000L 
                        val allPackagesActive = prefs.all.keys.filter { it.endsWith("_active") }
                        
                        for (key in allPackagesActive) {
                            if (prefs.getBoolean(key, false)) {
                                val pkgName = key.replace("_active", "")
                                val mode = prefs.getInt("${pkgName}_mode", 0)
                                val addedTimeMs = prefs.getLong("${pkgName}_time", 0L)
                                
                                if ((mode == 1 || mode == 0) && addedTimeMs > 0) {
                                    val fakeStartTime = currentInjectionAnchor
                                    val fakeEndTime = fakeStartTime + addedTimeMs
                                    
                                    val iterator = filteredEvents.iterator()
                                    while (iterator.hasNext()) {
                                        val realEvent = iterator.next()
                                        val realTimestamp = XposedHelpers.getLongField(realEvent, "mTimeStamp")
                                        if (realTimestamp in fakeStartTime..fakeEndTime) {
                                            iterator.remove() 
                                        }
                                    }

                                    try {
                                        val eventClass = Class.forName("android.app.usage.UsageEvents\$Event")
                                        
                                        val resumeEvent = eventClass.newInstance()
                                        XposedHelpers.setObjectField(resumeEvent, "mPackage", pkgName)
                                        XposedHelpers.setIntField(resumeEvent, "mEventType", 1) 
                                        XposedHelpers.setLongField(resumeEvent, "mTimeStamp", fakeStartTime)

                                        val pauseEvent = eventClass.newInstance()
                                        XposedHelpers.setObjectField(pauseEvent, "mPackage", pkgName)
                                        XposedHelpers.setIntField(pauseEvent, "mEventType", 2) 
                                        XposedHelpers.setLongField(pauseEvent, "mTimeStamp", fakeEndTime)

                                        filteredEvents.add(resumeEvent)
                                        filteredEvents.add(pauseEvent)
                                        XposedBridge.log("[GodMode] 💉 INJECTED $pkgName from ${formatTime(fakeStartTime)} to ${formatTime(fakeEndTime)}")
                                    } catch (e: Exception) {
                                        XposedBridge.log("[GodMode] ❌ Injection failed: ${e.message}")
                                    }
                                    currentInjectionAnchor = fakeEndTime + 1000L 
                                }
                            }
                        }

                        filteredEvents.sortBy { XposedHelpers.getLongField(it, "mTimeStamp") }

                        val newArrayType = eventsArray::class.java.componentType
                        if (newArrayType != null) {
                            val newEventsArray = java.lang.reflect.Array.newInstance(newArrayType, filteredEvents.size)
                            for (i in 0 until filteredEvents.size) {
                                java.lang.reflect.Array.set(newEventsArray, i, filteredEvents[i])ilteredEvents.indices) {
                            java.lang.reflect.Array.set(newEventsArray, i, filteredEvents[i])
                        }

                        XposedHelpers.setObjectField(usageEvents, "mEventsToWrite", newEventsArray)
                        XposedHelpers.setIntField(usageEvents, "mEventCount", filteredEvents.size)
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.lce(newArrayType, filteredEvents.size)
                        for (i in filteredEvents.indices) {
                            java.lang.reflect.Array.set(newEventsArray, i, filteredEvents[i])
                        }

                        XposedHelpers.setObjectField(usageEvents, "mEventsToWrite", newEventsArray)
                        XposedHelpers.setIntField(usageEvents, "mEventCount", filteredEvents.size)
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("[GodMode] CRITICAL ERROR: ${e.message}")
        }
    }
}
