package com.yourname.spoofer

import android.app.AndroidAppHelper
import android.app.usage.UsageEvents
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Constructor

/**
 * Unified UsageStats Spoofer — creates a complete, internally-consistent
 * usage profile identical to what a real app would produce.
 *
 * The event timeline shows multiple realistic sessions building up over time.
 * The summary metrics (total time, launch count, last used) are derived from
 * the exact same session data, so all three query pathways always agree.
 */
class UsageStatsSpoofer : IXposedHookLoadPackage {

    private companion object {
        private const val MODULE_PACKAGE = "com.yourname.spoofer"
        private const val CONFIG_AUTHORITY = "com.yourname.spoofer.configprovider"

        private const val TAIL_ANCHOR_MS = 3000L
        private const val INTERACTION_OFFSET_MS = 5000L
        private const val CACHE_RESET_THRESHOLD_MS = 60_000L
        private const val CONFIG_CACHE_MS = 1500L

        private const val MODE_SET = 0
        private const val MODE_ADD = 1
        private const val MODE_HIDE = 2

        private const val EVENT_MOVE_TO_FOREGROUND = 1
        private const val EVENT_MOVE_TO_BACKGROUND = 2
        private const val EVENT_USER_INTERACTION = 7
        private const val EVENT_FOREGROUND_SERVICE_START = 16
        private const val EVENT_FOREGROUND_SERVICE_STOP = 17

        private const val FIELD_PACKAGE = "mPackage"
        private const val FIELD_EVENT_TYPE = "mEventType"
        private const val FIELD_TIMESTAMP = "mTimeStamp"
    }

    // ============================================================ state machine

    @Volatile private var isSessionLocked = false
    @Volatile private var cachedDurationMs = 0L
    @Volatile private var cachedBeginTime = 0L
    @Volatile private var cachedEndTime = 0L
    @Volatile private var cachedLastUsedTime = 0L
    @Volatile private var cachedSessionStartTime = 0L
    @Volatile private var cachedSessionEndTime = 0L
    @Volatile private var cachedTargetPackage: String? = null
    @Volatile private var cachedLaunchCount = 0

    private val cachedSyntheticEvents = mutableListOf<CachedEvent>()
    private val stateLock = Any()

    private data class CachedEvent(
        val type: Int,
        val packageName: String,
        val timestamp: Long
    )

    private data class SessionSnapshot(
        val targetPackage: String,
        val mode: Int,
        val queryBeginTime: Long,
        val queryEndTime: Long,
        val sessionStartTime: Long,
        val sessionEndTime: Long,
        val durationMs: Long,
        val lastUsedTime: Long,
        val launchCount: Int,
        val events: List<CachedEvent>
    )

    // ============================================================ config

    private class SessionConfig(
        val targetPackage: String,
        val mode: Int,
        val targetDurationMs: Long
    )

    private val configLock = Any()
    private var cachedConfig: SessionConfig? = null
    private var cachedConfigAt = 0L

    private fun getRemoteConfig(packageNameKey: String, field: String): String {
        return try {
            val context = getContext() ?: return ""
            val uri = Uri.parse("content://$CONFIG_AUTHORITY/${Uri.encode(packageNameKey)}")
            context.contentResolver
                .query(uri, null, null, null, null)
                ?.use { cursor ->
                    val keyIndex = cursor.getColumnIndex("key")
                    val valueIndex = cursor.getColumnIndex("value")
                    if (keyIndex < 0 || valueIndex < 0) return@use ""
                    if (!cursor.moveToFirst()) return@use ""
                    do {
                        if (cursor.getString(keyIndex) == field) {
                            return cursor.getString(valueIndex) ?: ""
                        }
                    } while (cursor.moveToNext())
                    ""
                }
                ?: ""
        } catch (t: Throwable) {
            logError("config read failed for $field: ${t.message}")
            ""
        }
    }

    private fun resolveConfig(): SessionConfig? {
        synchronized(configLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            val snapshot = cachedConfig
            if (snapshot != null && now - cachedConfigAt < CONFIG_CACHE_MS) return snapshot
            val resolved = buildConfig()
            cachedConfig = resolved
            cachedConfigAt = now
            return resolved
        }
    }

    private fun buildConfig(): SessionConfig? {
        val target = getRemoteConfig("", "target_package_to_spoof").trim()
        if (target.isEmpty()) {
            logError("no target package configured yet - save a config in the module UI")
            return null
        }
        val enabled = getRemoteConfig(target, "enabled")
        if (enabled == "false") {
            logError("config disabled for $target")
            return null
        }
        val mode = getRemoteConfig(target, "mode").trim().toIntOrNull() ?: MODE_SET
        val durationMs = getRemoteConfig(target, "time").trim().toLongOrNull() ?: 0L
        log("config resolved: target=$target mode=$mode durationMs=$durationMs enabled=$enabled")
        return SessionConfig(target, mode, durationMs.coerceAtLeast(0L))
    }

    // ============================================================ safe mutation

    private fun setLongField(target: Any, name: String, value: Long): Boolean =
        try {
            XposedHelpers.setLongField(target, name, value); true
        } catch (t: Throwable) {
            false
        }

    private fun setIntField(target: Any, name: String, value: Int): Boolean =
        try {
            XposedHelpers.setIntField(target, name, value); true
        } catch (t: Throwable) {
            false
        }

    private fun getLongField(target: Any, name: String): Long =
        try {
            XposedHelpers.getLongField(target, name)
        } catch (t: Throwable) {
            0L
        }

    private fun getIntField(target: Any, name: String): Int =
        try {
            XposedHelpers.getIntField(target, name)
        } catch (t: Throwable) {
            0
        }

    private fun zeroOutStats(statsObj: Any) {
        setLongField(statsObj, "mTotalTimeInForeground", 0L)
        setLongField(statsObj, "mTotalTimeVisible", 0L)
        setLongField(statsObj, "mBeginTimeStamp", 0L)
        setLongField(statsObj, "mEndTimeStamp", 0L)
        setLongField(statsObj, "mLastTimeUsed", 0L)
        setLongField(statsObj, "mLastTimeVisible", 0L)
        setLongField(statsObj, "mAppLaunchCount", 0)
    }

    // ============================================================ UsageEvents access

    private val eventConstructor: Constructor<*>? by lazy {
        val eventClass = try {
            Class.forName("android.app.usage.UsageEvents\$Event")
        } catch (t: Throwable) {
            logError("UsageEvents.Event class unavailable: ${t.message}")
            return@lazy null
        }

        val candidates = listOf(
            arrayOf<Class<*>>(
                Int::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType!!
            ),
            arrayOf<Class<*>>(
                Int::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            )
        )

        var lastError: Throwable? = null
        for (signature in candidates) {
            try {
                val ctor = eventClass.getDeclaredConstructor(*signature)
                ctor.isAccessible = true
                return@lazy ctor
            } catch (t: Throwable) {
                lastError = t
            }
        }

        logError("UsageEvents.Event constructor unavailable: ${lastError?.message}")
        null
    }

    private fun findEventContainer(usageEvents: UsageEvents): ArrayList<Any?>? {
        return try {
            for (field in usageEvents.javaClass.declaredFields) {
                if (!ArrayList::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val value = field.get(usageEvents) as? ArrayList<Any?>
                if (value != null) return value
            }
            null
        } catch (t: Throwable) {
            logError("findEventContainer failed: ${t.message}")
            null
        }
    }

    private fun buildEvent(
        eventType: Int,
        packageName: String,
        className: String,
        timestamp: Long
    ): Any? {
        val ctor = eventConstructor ?: return null
        return try {
            if (ctor.parameterTypes.size == 5) {
                ctor.newInstance(eventType, packageName, className, timestamp, 1)
            } else {
                ctor.newInstance(eventType, packageName, className, timestamp)
            }
        } catch (t: Throwable) {
            logError("event synthesis failed (type=$eventType): ${t.message}")
            null
        }
    }

    private fun eventTimestamp(event: Any?): Long {
        if (event == null) return Long.MAX_VALUE
        return try {
            XposedHelpers.getLongField(event, FIELD_TIMESTAMP)
        } catch (t: Throwable) {
            Long.MAX_VALUE
        }
    }

    private fun eventPackage(event: Any?): String? {
        if (event == null) return null
        return try {
            XposedHelpers.getObjectField(event, FIELD_PACKAGE) as? String
        } catch (t: Throwable) {
            null
        }
    }

    // ============================================================ logging

    private fun getContext(): Context? = try {
        appContext ?: AndroidAppHelper.currentApplication()
    } catch (t: Throwable) {
        null
    }

    private fun log(message: String) {
        XposedBridge.log(message)
        pushToModule("HOOK", message)
    }

    private fun logError(message: String) {
        XposedBridge.log("[State-Machine] ERROR: $message")
        pushToModule("ERROR", message)
    }

    private fun pushToModule(tag: String, message: String) {
        try {
            val context = getContext() ?: return
            val values = android.content.ContentValues().apply {
                put("tag", tag)
                put("message", message)
            }
            context.contentResolver.insert(
                Uri.parse("content://$CONFIG_AUTHORITY/log"),
                values
            )
        } catch (t: Throwable) {
            XposedBridge.log("[State-Machine] log sink unavailable: ${t.message}")
        }
    }

    @Volatile
    private var appContext: Context? = null

    private fun awaitApplication(classLoader: ClassLoader, onReady: (Context) -> Unit) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (appContext != null) return
                        val ctx = (param.args?.getOrNull(0) as? Context)
                            ?: AndroidAppHelper.currentApplication()
                        if (ctx == null) {
                            XposedBridge.log("[State-Machine] no context available at attach; skipping")
                            return
                        }
                        appContext = ctx
                        log("[State-Machine] host Application ready (pid=${android.os.Process.myPid()})")
                        try {
                            onReady(ctx)
                        } catch (t: Throwable) {
                            logError("hook installation failed: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logError("could not hook Application.attach: ${t.message}")
        }
    }

    // ============================================================ hidden API access

    @Volatile
    private var hiddenApiExemption: String? = null

    private fun enableHiddenApiAccess(): String {
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            activityThread
                .getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .apply { isAccessible = true }
                .invoke(current, arrayOf("L"))
            return "ActivityThread"
        } catch (t: Throwable) {
            // Fall through
        }

        try {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmRuntime
                .getDeclaredMethod("getRuntime")
                .apply { isAccessible = true }
                .invoke(null)
            vmRuntime
                .getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .apply { isAccessible = true }
                .invoke(runtime, arrayOf("L"))
            return "VMRuntime"
        } catch (t: Throwable) {
            // Fall through
        }

        return "none"
    }

    // ============================================================ hooks

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == MODULE_PACKAGE) return

        if (hiddenApiExemption == null) {
            hiddenApiExemption = enableHiddenApiAccess()
            log("[State-Machine] hidden API exemption route: $hiddenApiExemption")
        }

        awaitApplication(lpparam.classLoader) {
            val subject = getRemoteConfig("", "target_package_to_spoof").trim()
            if (subject.isEmpty()) {
                logError("no target package configured; open the module UI and save a config")
                return@awaitApplication
            }
            log("[State-Machine] attached to reader app ${lpparam.packageName}, spoofing subject=$subject")
            installUsageStatsHooks(lpparam.classLoader)
        }
    }

    private fun installUsageStatsHooks(classLoader: ClassLoader) {
        val usageStatsManagerClass = try {
            XposedHelpers.findClass("android.app.usage.UsageStatsManager", classLoader)
        } catch (t: Throwable) {
            logError("could not resolve UsageStatsManager: ${t.message}")
            return
        }

        val intClass = Int::class.javaPrimitiveType!!
        val longClass = Long::class.javaPrimitiveType!!
        val stringClass = String::class.java

        val summaryHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    applySummary(param.result, param.args, 1, "summary")
                } catch (t: Throwable) {
                    logError("summary hook failure: ${t.message}")
                }
            }
        }
        register(usageStatsManagerClass, summaryHook, "queryUsageStats", arrayOf(intClass, longClass, longClass), 1)

        val aggregateHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    applySummary(param.result, param.args, 0, "aggregate")
                } catch (t: Throwable) {
                    logError("aggregate hook failure: ${t.message}")
                }
            }
        }
        register(usageStatsManagerClass, aggregateHook, "queryAndAggregateUsageStats", arrayOf(longClass, longClass), 0)

        val eventsHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val begin = param.args.longAt(0)
                    val end = param.args.longAt(1)
                    if (begin == null || end == null) return
                    applyTimeline(param.result, begin, end)
                } catch (t: Throwable) {
                    logError("queryEvents hook failure: ${t.message}")
                }
            }
        }
        register(usageStatsManagerClass, eventsHook, "queryEvents", arrayOf(longClass, longClass), 0)

        val eventsForPackageHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val begin = param.args.longAt(1)
                    val end = param.args.longAt(2)
                    if (begin == null || end == null) return
                    applyTimeline(param.result, begin, end)
                } catch (t: Throwable) {
                    logError("queryEventsForPackage hook failure: ${t.message}")
                }
            }
        }
        register(usageStatsManagerClass, eventsForPackageHook, "queryEventsForPackage", arrayOf(stringClass, longClass, longClass), 1)

        // ---- fallback: queryEventsForUser -----------------------------------
        val eventsForUserHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val begin = param.args.longAt(1)
                    val end = param.args.longAt(2)
                    if (begin == null || end == null) return
                    applyTimeline(param.result, begin, end)
                } catch (t: Throwable) {
                    logError("queryEventsForUser hook failure: ${t.message}")
                }
            }
        }
        register(usageStatsManagerClass, eventsForUserHook, "queryEventsForUser", arrayOf(longClass, longClass, Int::class.javaPrimitiveType!!), 1)

        // ---- fallback: queryConfigurations -----------------------------------
        val configHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val begin = param.args.longAt(0)
                    val end = param.args.longAt(1)
                    if (begin == null || end == null) return
                    applyTimeline(param.result, begin, end)
                } catch (t: Throwable) {
                    logError("queryConfigurations hook failure: ${t.message}")
                }
            }
        }
        register(usageStatsManagerClass, configHook, "queryConfigurations", arrayOf(longClass, longClass), 0)

        reportCoverage(usageStatsManagerClass)
    }

    private fun register(
        usageStatsManagerClass: Class<*>,
        callback: XC_MethodHook,
        method: String,
        params: Array<Class<*>>,
        beginIndex: Int
    ) {
        try {
            XposedHelpers.findAndHookMethod(usageStatsManagerClass, method, *params, callback)
        } catch (t: Throwable) {
            XposedBridge.log("[State-Machine] hook $method failed: ${t.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(usageStatsManagerClass, "${method}AsUser", *params, Int::class.javaPrimitiveType!!, callback)
        } catch (t: Throwable) {
            XposedBridge.log("[State-Machine] ${method}AsUser absent, skipped")
        }
    }

    private fun reportCoverage(usageStatsManagerClass: Class<*>) {
        val watched = listOf(
            "queryUsageStats", "queryUsageStatsAsUser",
            "queryAndAggregateUsageStats", "queryAndAggregateUsageStatsAsUser",
            "queryEvents", "queryEventsAsUser",
            "queryEventsForPackage", "queryEventsForPackageForUser",
            "queryEventsForUser"
        )
        val present = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (name in watched) {
            val found = try {
                usageStatsManagerClass.declaredMethods.any { it.name == name }
            } catch (t: Throwable) {
                false
            }
            if (found) present.add(name) else missing.add(name)
        }
        log("[State-Machine] query surface present: ${present.joinToString(",")}")
        log("[State-Machine] query surface absent: ${missing.joinToString(",")}")
    }

    private fun Array<Any?>.longAt(index: Int): Long? =
        if (index < size) getOrNull(index) as? Long else null

    // ============================================================ state machine core

    private fun ensureLocked(
        queryBeginTime: Long,
        queryEndTime: Long,
        realForegroundMs: Long = 0L
    ): SessionSnapshot? {
        synchronized(stateLock) {
            if (isSessionLocked && queryEndTime < cachedEndTime - CACHE_RESET_THRESHOLD_MS) {
                isSessionLocked = false
                cachedSyntheticEvents.clear()
                log("[State-Machine] Cache reset: queryEndTime dropped behind cached snapshot")
            }

            if (isSessionLocked) {
                val pkg = cachedTargetPackage ?: return null
                log("[State-Machine] Serving idempotent cached snapshot.")
                return SessionSnapshot(
                    targetPackage = pkg,
                    mode = MODE_SET,
                    queryBeginTime = cachedBeginTime,
                    queryEndTime = cachedEndTime,
                    sessionStartTime = cachedSessionStartTime,
                    sessionEndTime = cachedSessionEndTime,
                    durationMs = cachedDurationMs,
                    lastUsedTime = cachedLastUsedTime,
                    launchCount = cachedLaunchCount,
                    events = cachedSyntheticEvents.toList()
                )
            }

            val config = resolveConfig() ?: return null

            if (config.mode == MODE_HIDE) {
                return SessionSnapshot(
                    targetPackage = config.targetPackage,
                    mode = MODE_HIDE,
                    queryBeginTime = queryBeginTime,
                    queryEndTime = queryEndTime,
                    sessionStartTime = 0L,
                    sessionEndTime = 0L,
                    durationMs = 0L,
                    lastUsedTime = 0L,
                    launchCount = 0,
                    events = emptyList()
                )
            }

            val fakeLastUsedTime = queryEndTime - TAIL_ANCHOR_MS
            val durationMs = if (config.mode == MODE_ADD) {
                realForegroundMs + config.targetDurationMs
            } else {
                config.targetDurationMs
            }
            val sessionEndTime = fakeLastUsedTime
            val sessionStartTime = (sessionEndTime - durationMs).coerceAtLeast(queryBeginTime)

            val events = buildRealisticSessions(config.targetPackage, sessionStartTime, sessionEndTime, durationMs)
            val launchCount = events.count { it.type == EVENT_MOVE_TO_FOREGROUND }

            cachedDurationMs = durationMs
            cachedBeginTime = queryBeginTime
            cachedEndTime = queryEndTime
            cachedLastUsedTime = fakeLastUsedTime
            cachedSessionStartTime = sessionStartTime
            cachedSessionEndTime = sessionEndTime
            cachedTargetPackage = config.targetPackage
            cachedLaunchCount = launchCount
            cachedSyntheticEvents.clear()
            cachedSyntheticEvents.addAll(events)
            isSessionLocked = true

            log("[State-Machine] Telemetry profile initialized and locked for package: ${config.targetPackage}")

            return SessionSnapshot(
                targetPackage = config.targetPackage,
                mode = config.mode,
                queryBeginTime = queryBeginTime,
                queryEndTime = queryEndTime,
                sessionStartTime = sessionStartTime,
                sessionEndTime = sessionEndTime,
                durationMs = durationMs,
                lastUsedTime = fakeLastUsedTime,
                launchCount = launchCount,
                events = events
            )
        }
    }

    /**
     * Builds a realistic multi-session timeline that looks like a real app.
     * Each session: MOVE_TO_FOREGROUND -> USER_INTERACTION(s) -> MOVE_TO_BACKGROUND
     * Sessions are distributed across the time window with natural gaps.
     */
    private fun buildRealisticSessions(
        packageName: String,
        sessionStartTime: Long,
        sessionEndTime: Long,
        totalDurationMs: Long
    ): List<CachedEvent> {
        val events = mutableListOf<CachedEvent>()
        val windowMs = sessionEndTime - sessionStartTime

        if (windowMs <= 0 || totalDurationMs <= 0) return events

        // Determine number of sessions — contiguous (no gaps) so event span == summary duration
        val sessionCount = when {
            totalDurationMs < 60_000 -> 2
            totalDurationMs < 300_000 -> 3
            else -> 4
        }

        val sessionDuration = totalDurationMs / sessionCount
        var currentTime = sessionStartTime

        for (i in 0 until sessionCount) {
            val sessionEnd = if (i == sessionCount - 1) {
                sessionEndTime // Last session ends exactly at sessionEndTime
            } else {
                currentTime + sessionDuration
            }

            // MOVE_TO_FOREGROUND
            events.add(CachedEvent(EVENT_MOVE_TO_FOREGROUND, packageName, currentTime))

            // USER_INTERACTION events during session
            if (sessionDuration > INTERACTION_OFFSET_MS * 2) {
                events.add(CachedEvent(EVENT_USER_INTERACTION, packageName, currentTime + INTERACTION_OFFSET_MS))
                events.add(CachedEvent(EVENT_USER_INTERACTION, packageName, sessionEnd - INTERACTION_OFFSET_MS))
            }

            // FOREGROUND_SERVICE events (real apps have these)
            if (sessionDuration > INTERACTION_OFFSET_MS * 4) {
                events.add(CachedEvent(EVENT_FOREGROUND_SERVICE_START, packageName, currentTime + INTERACTION_OFFSET_MS * 2))
                events.add(CachedEvent(EVENT_FOREGROUND_SERVICE_STOP, packageName, sessionEnd - INTERACTION_OFFSET_MS * 2))
            }

            // MOVE_TO_BACKGROUND
            events.add(CachedEvent(EVENT_MOVE_TO_BACKGROUND, packageName, sessionEnd))

            currentTime = sessionEnd
        }

        return events
    }

    // ============================================================ hook application

    private fun applySummary(
        result: Any?,
        args: Array<Any?>,
        beginIndex: Int,
        kind: String
    ) {
        try {
            val queryBeginTime = args.longAt(beginIndex) ?: return
            val queryEndTime = args.longAt(beginIndex + 1) ?: return

            val targetPackage: String? = if (isSessionLocked) {
                cachedTargetPackage
            } else {
                resolveConfig()?.targetPackage
            } ?: return

            // Find or create target entry
            val targetEntry: Any = when (result) {
                is List<*> -> {
                    val found = result.firstOrNull { statsPackageName(it) == targetPackage }
                    if (found != null) {
                        found
                    } else {
                        val created = createUsageStats(targetPackage)
                        if (created != null && result is MutableList<*>) {
                            result.add(created)
                            log("[State-Machine] $kind: created new row for $targetPackage")
                        }
                        created ?: return
                    }
                }
                is Map<*, *> -> {
                    val found = result[targetPackage]
                        ?: result.entries.firstOrNull {
                            statsPackageName(it.value) == targetPackage
                        }?.value
                    if (found != null) {
                        found
                    } else {
                        val created = createUsageStats(targetPackage)
                        if (created != null && result is MutableMap<*, *>) {
                            result[targetPackage] = created
                            log("[State-Machine] $kind: created new entry for $targetPackage")
                        }
                        created ?: return
                    }
                }
                else -> return
            }

            val realForegroundMs = if (!isSessionLocked) {
                getLongField(targetEntry, "mTotalTimeInForeground")
            } else {
                0L
            }

            val snapshot = ensureLocked(queryBeginTime, queryEndTime, realForegroundMs) ?: return

            if (snapshot.mode == MODE_HIDE) {
                var removed = false
                if (result is MutableList<*>) removed = result.remove(targetEntry)
                if (result is MutableMap<*, *>) {
                    result.remove(targetPackage)
                    removed = true
                }
                if (!removed) zeroOutStats(targetEntry)
                log("[State-Machine] $kind HIDDEN $targetPackage removed=$removed")
                return
            }

            val failed = mutableListOf<String>()
            fun writeLong(field: String, value: Long) {
                if (!setLongField(targetEntry, field, value)) failed.add(field)
            }

            writeLong("mTotalTimeInForeground", snapshot.durationMs)
            writeLong("mTotalTimeVisible", snapshot.durationMs)
            writeLong("mBeginTimeStamp", snapshot.queryBeginTime)
            writeLong("mEndTimeStamp", snapshot.queryEndTime)
            writeLong("mLastTimeUsed", snapshot.lastUsedTime)
            writeLong("mLastTimeVisible", snapshot.lastUsedTime + 500L)

            if (!setIntField(targetEntry, "mAppLaunchCount", snapshot.launchCount)) {
                failed.add("mAppLaunchCount")
            }

            if (failed.isNotEmpty()) {
                logError(
                    "$kind injection failed for $targetPackage; " +
                        "unwritable: ${failed.joinToString(",")} " +
                        "exemption=$hiddenApiExemption"
                )
                return
            }

            val verified = getLongField(targetEntry, "mTotalTimeInForeground")
            log(
                "[State-Machine] $kind injected $targetPackage " +
                    "wrote=${snapshot.durationMs}ms " +
                    "readBack=${verified}ms " +
                    "window=${snapshot.queryEndTime - snapshot.queryBeginTime}ms"
            )
        } catch (t: Throwable) {
            logError("summary hook failure: ${t.message}")
        }
    }

    private fun applyTimeline(result: Any?, queryBeginTime: Long, queryEndTime: Long) {
        try {
            val snapshot = ensureLocked(queryBeginTime, queryEndTime) ?: return
            val usageEvents = result as? UsageEvents ?: return

            val container = findEventContainer(usageEvents) ?: return

            if (snapshot.mode == MODE_HIDE) {
                val removed = container.removeAll {
                    eventPackage(it) == snapshot.targetPackage
                }
                log(
                    "[State-Machine] TIMELINE HIDDEN ${snapshot.targetPackage} " +
                        "($removed events removed)"
                )
                return
            }

            val removedCount =
                container.removeAll { eventPackage(it) == snapshot.targetPackage }

            val injected = mutableListOf<Any>()
            for (eventParam in snapshot.events) {
                buildEvent(
                    eventParam.type,
                    eventParam.packageName,
                    eventParam.packageName,
                    eventParam.timestamp
                )?.let(injected::add)
            }

            if (injected.isEmpty()) {
                logError("no synthetic events built for ${snapshot.targetPackage}")
                return
            }

            container.addAll(injected)
            container.sortBy { eventTimestamp(it) }

            log(
                "[State-Machine] TIMELINE injected ${snapshot.targetPackage} " +
                    "removed=$removedCount injected=${injected.size} " +
                    "total=${container.size} " +
                    "span=${snapshot.sessionEndTime - snapshot.sessionStartTime}ms"
            )
        } catch (t: Throwable) {
            logError("queryEvents hook failure: ${t.message}")
        }
    }

    /**
     * Creates a synthetic UsageStats object for the target package.
     */
    private fun createUsageStats(packageName: String): Any? {
        return try {
            val usageStatsClass = XposedHelpers.findClass(
                "android.app.usage.UsageStats", null
            )
            val constructor = usageStatsClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val instance = constructor.newInstance()

            XposedHelpers.setObjectField(instance, "mPackageName", packageName)
            zeroOutStats(instance)

            instance
        } catch (t: Throwable) {
            logError("createUsageStats failed: ${t.message}")
            null
        }
    }

    private fun statsPackageName(statsObj: Any?): String? {
        if (statsObj == null) return null
        return try {
            XposedHelpers.getObjectField(statsObj, "mPackageName") as? String
        } catch (t: Throwable) {
            null
        }
    }
}
