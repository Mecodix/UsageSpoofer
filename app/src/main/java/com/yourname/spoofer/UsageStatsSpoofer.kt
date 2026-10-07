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
 * Single-Shot Cache & Lock State Machine for UsageStats spoofing.
 *
 * Architecture:
 * - First query pass: compute anchors from config + query window, cache all values,
 *   build synthetic events, lock the session.
 * - Subsequent passes: serve frozen cached values directly. Zero recomputation.
 * - Cache reset: if queryEndTime drops significantly behind cachedEndTime, unlock.
 *
 * Data flow:
 *   MainActivity (UI) -> SharedPreferences -> ConfigProvider (ContentProvider)
 *     -> UsageStatsSpoofer (hook) -> UsageStatsManager (host process)
 */
class UsageStatsSpoofer : IXposedHookLoadPackage {

    private companion object {
        private const val MODULE_PACKAGE = "com.yourname.spoofer"
        private const val CONFIG_AUTHORITY = "com.yourname.spoofer.configprovider"

        /** Simulated session exits this far before the query window closes. */
        private const val TAIL_ANCHOR_MS = 3000L

        /** Interaction events are offset this far from each session boundary. */
        private const val INTERACTION_OFFSET_MS = 5000L

        /** If queryEndTime drops this far behind cachedEndTime, reset the lock. */
        private const val CACHE_RESET_THRESHOLD_MS = 60_000L

        /** Config is cached briefly to avoid excessive binder round-trips. */
        private const val CONFIG_CACHE_MS = 1500L

        private const val MODE_SET = 0
        private const val MODE_ADD = 1
        private const val MODE_HIDE = 2

        private const val EVENT_MOVE_TO_FOREGROUND = 1
        private const val EVENT_MOVE_TO_BACKGROUND = 2
        private const val EVENT_USER_INTERACTION = 7

        private const val FIELD_PACKAGE = "mPackage"
        private const val FIELD_EVENT_TYPE = "mEventType"
        private const val FIELD_TIMESTAMP = "mTimeStamp"
    }

    // ============================================================ state machine

    /** Primary lock flag -- true once the snapshot is frozen. */
    @Volatile
    private var isSessionLocked = false

    /** Frozen summary metrics. */
    @Volatile
    private var cachedDurationMs = 0L
    @Volatile
    private var cachedBeginTime = 0L
    @Volatile
    private var cachedEndTime = 0L
    @Volatile
    private var cachedLastUsedTime = 0L

    /** Frozen timeline anchors for event construction. */
    @Volatile
    private var cachedSessionStartTime = 0L
    @Volatile
    private var cachedSessionEndTime = 0L

    /** Frozen target package (needed when locked to skip provider lookups). */
    @Volatile
    private var cachedTargetPackage: String? = null

    /** Frozen timeline event parameters (rebuilt into objects on each serve). */
    private val cachedSyntheticEvents = mutableListOf<CachedEvent>()

    /** Guards all state-machine transitions. */
    private val stateLock = Any()

    /** A single synthetic event's parameters. */
    private data class CachedEvent(
        val type: Int,
        val packageName: String,
        val timestamp: Long
    )

    /** The complete frozen snapshot served on locked passes. */
    private data class SessionSnapshot(
        val targetPackage: String,
        val mode: Int,
        val queryBeginTime: Long,
        val queryEndTime: Long,
        val sessionStartTime: Long,
        val sessionEndTime: Long,
        val durationMs: Long,
        val lastUsedTime: Long,
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

    /**
     * Reads a single field from the configuration app over its ContentProvider
     * pipe. Returns "" on any failure so callers can treat absence as "unset".
     */
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

    /**
     * Lazily resolved hidden-API constructor for UsageEvents.Event.
     * Tries the four-arg form first, then the five-arg variant seen on newer builds.
     */
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

    /**
     * Scans for the internal ArrayList storage rather than hardcoding "mEvents",
     * because vendor ROMs rename or reshape that container.
     */
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

    /** Success path: one structural line, mirrored into the in-app viewer. */
    private fun log(message: String) {
        XposedBridge.log(message)
        pushToModule("HOOK", message)
    }

    /** Failure path: same sink, tagged so failures stand out in the viewer. */
    private fun logError(message: String) {
        XposedBridge.log("[State-Machine] ERROR: $message")
        pushToModule("ERROR", message)
    }

    /**
     * Forwards a line into the module's own log file via its ContentProvider.
     * Writing via LogWriter directly would land in the *host* app's
     * externalFilesDir, which the module's LogViewerActivity cannot read.
     */
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

    /**
     * Cached Application reference. handleLoadPackage runs before
     * currentApplication() is populated, so an early log line would otherwise
     * have nowhere to go.
     */
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
                            XposedBridge.log(
                                "[State-Machine] no context available at attach; skipping"
                            )
                            return
                        }
                        appContext = ctx
                        log(
                            "[State-Machine] host Application ready " +
                                "(pid=${android.os.Process.myPid()})"
                        )
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

    /**
     * Grants this process a hidden API exemption before any framework reflection
     * is attempted. Tries ActivityThread first, then VMRuntime.
     */
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
            // Fall through to VMRuntime.
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
            // Fall through to reporting failure.
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
                logError(
                    "no target package configured; open the module UI and save a config " +
                        "(process ${lpparam.packageName} instrumented but inactive)"
                )
                return@awaitApplication
            }

            log(
                "[State-Machine] attached to reader app ${lpparam.packageName} " +
                    "(pid=${android.os.Process.myPid()}), spoofing subject=$subject"
            )
            installUsageStatsHooks(lpparam.classLoader)
        }
    }

    /**
     * Registers hooks across every overload family the reader might call.
     */
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

        // ---- summary: per-bucket rows --------------------------------------

        val summaryHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = try {
                applySummary(param.result, param.args, 1, "summary")
            } catch (t: Throwable) {
                logError("summary hook failure: ${t.message}")
            }
        }
        register(
            usageStatsManagerClass, summaryHook,
            method = "queryUsageStats",
            params = arrayOf(intClass, longClass, longClass),
            beginIndex = 1
        )

        // ---- summary: merged map -------------------------------------------

        val aggregateHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = try {
                applySummary(param.result, param.args, 0, "aggregate")
            } catch (t: Throwable) {
                logError("aggregate hook failure: ${t.message}")
            }
        }
        register(
            usageStatsManagerClass, aggregateHook,
            method = "queryAndAggregateUsageStats",
            params = arrayOf(longClass, longClass),
            beginIndex = 0
        )

        // ---- timeline: raw events ------------------------------------------

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
        register(
            usageStatsManagerClass, eventsHook,
            method = "queryEvents",
            params = arrayOf(longClass, longClass),
            beginIndex = 0
        )

        // ---- timeline: events for package ----------------------------------

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
        register(
            usageStatsManagerClass, eventsForPackageHook,
            method = "queryEventsForPackage",
            params = arrayOf(stringClass, longClass, longClass),
            beginIndex = 1
        )

        reportCoverage(usageStatsManagerClass)
    }

    /** Hooks [method] plus its trailing-userId overload when present. */
    private fun register(
        usageStatsManagerClass: Class<*>,
        callback: XC_MethodHook,
        method: String,
        params: Array<Class<*>>,
        beginIndex: Int
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass, method, *params, callback
            )
        } catch (t: Throwable) {
            XposedBridge.log("[State-Machine] hook $method failed: ${t.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass, "${method}AsUser",
                *params, Int::class.javaPrimitiveType!!, callback
            )
        } catch (t: Throwable) {
            XposedBridge.log("[State-Machine] ${method}AsUser absent, skipped")
        }
    }

    /** Logs which query entry points exist on this build. */
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

    /**
     * Returns the current session snapshot, computing and caching on first use.
     *
     * Thread-safe: all transitions guarded by [stateLock].
     *
     * - Locked: returns frozen snapshot immediately. No provider lookup.
     * - Unlocked: resolves config, computes anchors, builds events, caches, locks.
     * - Cache reset: if queryEndTime drops significantly behind cachedEndTime,
     *   the lock releases and a fresh snapshot is computed on the next call.
     */
    private fun ensureLocked(
        queryBeginTime: Long,
        queryEndTime: Long,
        realForegroundMs: Long = 0L
    ): SessionSnapshot? {
        synchronized(stateLock) {
            // Cache reset: query window dropped significantly behind cached snapshot
            if (isSessionLocked && queryEndTime < cachedEndTime - CACHE_RESET_THRESHOLD_MS) {
                isSessionLocked = false
                cachedSyntheticEvents.clear()
                log("[State-Machine] Cache reset: queryEndTime dropped behind cached snapshot")
            }

            // Locked: serve from cache
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
                    events = cachedSyntheticEvents.toList()
                )
            }

            // Unlocked: resolve config
            val config = resolveConfig() ?: return null

            // HIDE mode does not participate in the state machine
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
                    events = emptyList()
                )
            }

            // Compute anchors
            val fakeLastUsedTime = queryEndTime - TAIL_ANCHOR_MS
            val durationMs = if (config.mode == MODE_ADD) {
                realForegroundMs + config.targetDurationMs
            } else {
                config.targetDurationMs
            }
            val sessionEndTime = fakeLastUsedTime
            val sessionStartTime = (sessionEndTime - durationMs).coerceAtLeast(queryBeginTime)

            // Build synthetic events
            val events = buildSyntheticEventParams(
                config.targetPackage, sessionStartTime, sessionEndTime
            )

            // Cache everything
            cachedDurationMs = durationMs
            cachedBeginTime = queryBeginTime
            cachedEndTime = queryEndTime
            cachedLastUsedTime = fakeLastUsedTime
            cachedSessionStartTime = sessionStartTime
            cachedSessionEndTime = sessionEndTime
            cachedTargetPackage = config.targetPackage
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
                events = events
            )
        }
    }

    /**
     * Builds the 4 mandatory sequential event parameters:
     * - Type 1 (MOVE_TO_FOREGROUND) at sessionStartTime
     * - Type 7 (USER_INTERACTION) at sessionStartTime + 5000
     * - Type 7 (USER_INTERACTION) at sessionEndTime - 5000
     * - Type 2 (MOVE_TO_BACKGROUND) at sessionEndTime
     */
    private fun buildSyntheticEventParams(
        packageName: String,
        sessionStartTime: Long,
        sessionEndTime: Long
    ): List<CachedEvent> {
        return listOf(
            CachedEvent(EVENT_MOVE_TO_FOREGROUND, packageName, sessionStartTime),
            CachedEvent(EVENT_USER_INTERACTION, packageName, sessionStartTime + INTERACTION_OFFSET_MS),
            CachedEvent(EVENT_USER_INTERACTION, packageName, sessionEndTime - INTERACTION_OFFSET_MS),
            CachedEvent(EVENT_MOVE_TO_BACKGROUND, packageName, sessionEndTime)
        )
    }

    // ============================================================ hook application

    /**
     * Applies the summary injection to either return shape:
     *   queryUsageStats             -> List<UsageStats>, found by mPackageName
     *   queryAndAggregateUsageStats -> Map<String, UsageStats>, keyed by package
     */
    private fun applySummary(
        result: Any?,
        args: Array<Any?>,
        beginIndex: Int,
        kind: String
    ) {
        try {
            val queryBeginTime = args.longAt(beginIndex) ?: return
            val queryEndTime = args.longAt(beginIndex + 1) ?: return

            // Determine target package: cached if locked, otherwise resolve config
            val targetPackage: String? = if (isSessionLocked) {
                cachedTargetPackage
            } else {
                resolveConfig()?.targetPackage
            } ?: return

            // Find target entry
            val targetEntry: Any = when (result) {
                is List<*> -> {
                    val found = result.firstOrNull { statsPackageName(it) == targetPackage }
                    if (found == null) {
                        log(
                            "[State-Machine] $kind: no row for $targetPackage " +
                                "(${result.size} rows)"
                        )
                    }
                    found
                }
                is Map<*, *> -> {
                    val found = result[targetPackage]
                        ?: result.entries.firstOrNull {
                            statsPackageName(it.value) == targetPackage
                        }?.value
                    if (found == null) {
                        log(
                            "[State-Machine] $kind: no entry for $targetPackage " +
                                "(${result.size} keys)"
                        )
                    }
                    found
                }
                else -> return
            } ?: return

            // Read real foreground time (only used when unlocked for ADD mode)
            val realForegroundMs = if (!isSessionLocked) {
                getLongField(targetEntry, "mTotalTimeInForeground")
            } else {
                0L
            }

            // Ensure locked (computes + caches on first pass, serves from cache after)
            val snapshot = ensureLocked(queryBeginTime, queryEndTime, realForegroundMs) ?: return

            // Handle HIDE mode
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

            // Apply values
            val failed = mutableListOf<String>()
            fun writeLong(field: String, value: Long) {
                if (!setLongField(targetEntry, field, value)) failed.add(field)
            }

            writeLong("mTotalTimeInForeground", snapshot.durationMs)
            writeLong("mTotalTimeVisible", snapshot.durationMs)
            writeLong("mBeginTimeStamp", snapshot.queryBeginTime)
            writeLong("mEndTimeStamp", snapshot.queryEndTime)
            writeLong("mLastTimeUsed", snapshot.lastUsedTime)
            writeLong("mLastTimeVisible", snapshot.lastUsedTime)

            val nextLaunchCount = getIntField(targetEntry, "mAppLaunchCount") + 1
            if (!setIntField(targetEntry, "mAppLaunchCount", nextLaunchCount)) {
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

    /**
     * Timeline mutation, shared by every events entry point.
     * Serves from cache when locked; builds and caches when unlocked.
     */
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

            // Wipe pre-existing events for target package
            val removedCount =
                container.removeAll { eventPackage(it) == snapshot.targetPackage }

            // Stream cached events (rebuild from params for safety)
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

    private fun statsPackageName(statsObj: Any?): String? {
        if (statsObj == null) return null
        return try {
            XposedHelpers.getObjectField(statsObj, "mPackageName") as? String
        } catch (t: Throwable) {
            null
        }
    }
}
