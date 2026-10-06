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
 * Dynamic validation module for the "OpenTelemetry Sandbox Engine"
 * (com.example.telemetry.sandbox).
 *
 * Both hooks share one anchor-based baseline so the summary row and the event
 * timeline always agree, independent of absolute wall-clock time. All timing is
 * derived from the caller-supplied query window:
 *
 *   sessionEnd   = queryEnd   - TAIL_ANCHOR_MS
 *   sessionStart = sessionEnd - finalDurationMs   (clamped to >= queryBegin)
 */
class UsageStatsSpoofer : IXposedHookLoadPackage {

    private companion object {
        /** Our own package: never instrument ourselves. */
        private const val MODULE_PACKAGE = "com.yourname.spoofer"

        private const val CONFIG_AUTHORITY = "com.yourname.spoofer.configprovider"

        /** Simulated session exits this far before the query window closes. */
        private const val TAIL_ANCHOR_MS = 3000L

        /** Headroom kept inside the window when a requested duration overruns it. */
        private const val WINDOW_HEADROOM_MS = 5000L

        /** Interaction events are offset this far from each session boundary. */
        private const val INTERACTION_OFFSET_MS = 5000L

        /** Config is cached briefly to avoid three binder round-trips per query. */
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

    // ------------------------------------------------------------------ config

    private class SessionConfig(
        val targetPackage: String,
        val mode: Int,
        val targetDurationMs: Long
    )

    private class Anchors(
        val queryBegin: Long,
        val queryEnd: Long,
        val sessionStart: Long,
        val sessionEnd: Long,
        val durationMs: Long
    )

    private val configLock = Any()
    private var cachedConfig: SessionConfig? = null
    private var cachedConfigAt = 0L

    /**
     * Reads a single field from the configuration app over its ContentProvider
     * pipe. Returns "" on any failure so callers can treat absence as "unset".
     *
     * An empty [packageNameKey] addresses the global row set (used to discover
     * which package is currently selected in the UI).
     */
    private fun getRemoteConfig(packageNameKey: String, field: String): String {
        return try {
            val context = getContext() ?: return ""
            val uri = Uri.parse("content://$CONFIG_AUTHORITY/${Uri.encode(packageNameKey)}")
            context.contentResolver
                .query(uri, null, null, null, null)
                ?.use { cursor ->
                    // The provider returns a key/value matrix and ignores the
                    // projection argument, so column 0 is the KEY, not the value.
                    // Read by column name and match the row, otherwise every lookup
                    // silently returns the field name itself.
                    val keyIndex = cursor.getColumnIndex("key")
                    val valueIndex = cursor.getColumnIndex("value")
                    if (keyIndex < 0 || valueIndex < 0) return@use ""

                    // Position on the first row before iterating; moveToNext()
                    // alone would skip row 0.
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

        // Mode defaults to SET when the UI has never written an explicit value.
        val mode = getRemoteConfig(target, "mode").trim().toIntOrNull() ?: MODE_SET
        val durationMs = getRemoteConfig(target, "time").trim().toLongOrNull() ?: 0L

        log("config resolved: target=$target mode=$mode durationMs=$durationMs enabled=$enabled")
        return SessionConfig(target, mode, durationMs.coerceAtLeast(0L))
    }

    // ------------------------------------------------------------ anchor math

    /**
     * Single source of truth for timing. Both hooks call this so the summary
     * metrics and the event timeline can never drift apart.
     */
    private fun computeAnchors(
        queryBeginTime: Long,
        queryEndTime: Long,
        targetDurationMs: Long,
        realForegroundMs: Long,
        mode: Int
    ): Anchors {
        val windowMs = (queryEndTime - queryBeginTime).coerceAtLeast(0L)

        val requested = if (mode == MODE_ADD) targetDurationMs + realForegroundMs else targetDurationMs

        // Cap the *total* against the window so the synthetic session cannot claim
        // more time than the window can physically hold.
        //
        // In ADD mode the requested amount is real + added, so capping that sum
        // would silently discard most of the addition whenever the app queries a
        // window shorter than (real + requested). Cap the headroom instead and let
        // the addition survive; the remainder is genuinely unspent window time.
        val durationMs = when {
            mode == MODE_ADD -> {
                val available = (windowMs - WINDOW_HEADROOM_MS).coerceAtLeast(0L)
                // Never report less than real usage, and never invent time beyond
                // the window.
                (realForegroundMs + targetDurationMs)
                    .coerceAtMost(available.coerceAtLeast(realForegroundMs))
                    .coerceAtLeast(0L)
            }
            requested > windowMs -> (windowMs - WINDOW_HEADROOM_MS).coerceAtLeast(0L)
            else -> requested.coerceAtLeast(0L)
        }

        val sessionEnd = queryEndTime - TAIL_ANCHOR_MS
        val sessionStart = (sessionEnd - durationMs).coerceAtLeast(queryBeginTime)

        return Anchors(queryBeginTime, queryEndTime, sessionStart, sessionEnd, durationMs)
    }

    // ---------------------------------------------------------- safe mutation

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

    // ------------------------------------------------------ UsageEvents access

    /**
     * Lazily resolved hidden-API constructor for UsageEvents.Event.
     *
     * Two signatures exist across releases. Android 10+ added a trailing
     * `count` int to some builds, so try the four-arg form first and fall back.
     * Requires enableHiddenApiAccess() to have already granted an exemption on
     * API 28+, otherwise every attempt throws NoSuchMethodException.
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
            // Supply a trailing count for the 5-arg overload seen on newer
            // builds; the 4-arg form ignores it.
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

    // ---------------------------------------------------------------- logging

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
        XposedBridge.log("[Telemetry-Test] ERROR: $message")
        pushToModule("ERROR", message)
    }

    /**
     * Forwards a line into the module's own log file via its ContentProvider.
     *
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
            // Logging must never propagate into the host process.
            XposedBridge.log("[Telemetry-Test] log sink unavailable: ${t.message}")
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

                        // attach(Context) hands us the context directly. Reading it
                        // from currentApplication() here always returns null, because
                        // attach runs before the Application is registered - which
                        // silently skipped every process before.
                        val ctx = (param.args?.getOrNull(0) as? Context)
                            ?: AndroidAppHelper.currentApplication()
                        if (ctx == null) {
                            XposedBridge.log(
                                "[Telemetry-Test] no context available at attach; skipping"
                            )
                            return
                        }
                        appContext = ctx
                        log(
                            "[Telemetry-Test] host Application ready " +
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

    // ------------------------------------------------------- hidden API access

    /**
     * Which exemption route succeeded, or "none". Recorded so failures can be
     * reported with their likely cause instead of a bare boolean.
     */
    @Volatile
    private var hiddenApiExemption: String? = null

    /**
     * Final duration from the most recent summary injection.
     *
     * The timeline reuses it so a reader summing foreground spans sees exactly
     * what the summary row reports, instead of the requested amount alone.
     */
    @Volatile
    private var lastInjectedDurationMs: Long? = null

    /**
     * Grants this process a hidden API exemption before any framework reflection
     * is attempted.
     *
     * Android 9+ blocks non-SDK access, and Android 15 (API 35) tightened the
     * lists further. Without an exemption, `UsageEvents$Event`'s constructor and
     * the private `mTotalTimeInForeground`-style fields throw
     * NoSuchMethodException / NoSuchFieldException and the hooks silently degrade.
     *
     * Both entry points below are themselves blocked in the AOSP flags, so this
     * tries several routes and reports which (if any) succeeded. "L" is the
     * universal prefix-match token and exempts every class.
     *
     * Must be called before touching any non-SDK member: access flags are cached
     * once a member is first resolved, and an exemption applied afterwards does
     * not retroactively unlock it.
     */
    private fun enableHiddenApiAccess(): String {
        // Route 1: ActivityThread.setHiddenApiExemptions - the route Xposed
        // modules have traditionally used, and the one LSPosed leaves reachable.
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

        // Route 2: dalvik.system.VMRuntime.setHiddenApiExemptions.
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

    // ------------------------------------------------------------------ hooks

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Never instrument ourselves.
        if (lpparam.packageName == MODULE_PACKAGE) return

        // Claim the exemption first. On API 35 this is what makes the private
        // UsageStats/UsageEvents members reachable at all.
        if (hiddenApiExemption == null) {
            hiddenApiExemption = enableHiddenApiAccess()
            log("[Telemetry-Test] hidden API exemption route: $hiddenApiExemption")
        }

        // Which process gets instrumented is decided by the LSPosed scope array, not
        // by config. The configured package is the *subject* of the spoof, which is
        // independent of the *reader* app doing the query.
        //
        // currentApplication() is still null here, so defer until Application.attach.
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
                "[Telemetry-Test] attached to reader app ${lpparam.packageName} " +
                    "(pid=${android.os.Process.myPid()}), spoofing subject=$subject"
            )
            installUsageStatsHooks(lpparam.classLoader)
        }
    }

    /**
     * Registers a hook across every overload family the reader might call.
     *
     * The tricky part is that begin/end sit at different argument positions:
     *
     *   queryUsageStats(interval, begin, end)                    begin at 1
     *   queryAndAggregateUsageStats(begin, end)                   begin at 0
     *   queryEvents(begin, end)                                  begin at 0
     *   queryEventsForPackage(pkg, begin, end)                   begin at 1
     *
     * so each entry supplies its own begin index. Overloads absent on a given
     * build are skipped silently - that is normal, not a failure.
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

        // ---- summary: per-bucket rows and the merged map -------------------

        val summaryHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = try {
                applySummary(param.result, param.args, 1, "summary")
            } catch (t: Throwable) {
                logError("summary hook failure: ${t.message}")
            }
        }

        // queryUsageStats returns one UsageStats per package per bucket.
        register(
            usageStatsManagerClass, summaryHook,
            method = "queryUsageStats",
            params = arrayOf(intClass, longClass, longClass),
            beginIndex = 1
        )

        // queryAndAggregateUsageStats merges buckets and returns a
        // Map<package, UsageStats>. A progress-bar reader usually calls this
        // one, so it has to be covered or the summary fix is invisible.
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

        // ---- timeline: raw events -----------------------------------------

        val eventsHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = try {
                val begin = param.args.longAt(0)
                val end = param.args.longAt(1)
                if (begin == null || end == null) return
                applyTimeline(param.result, begin, end)
            } catch (t: Throwable) {
                logError("queryEvents hook failure: ${t.message}")
            }
        }

        register(
            usageStatsManagerClass, eventsHook,
            method = "queryEvents",
            params = arrayOf(longClass, longClass),
            beginIndex = 0
        )

        // queryEventsForPackage takes the package first.
        val eventsForPackageHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = try {
                val begin = param.args.longAt(1)
                val end = param.args.longAt(2)
                if (begin == null || end == null) return
                applyTimeline(param.result, begin, end)
            } catch (t: Throwable) {
                logError("queryEventsForPackage hook failure: ${t.message}")
            }
        }

        register(
            usageStatsManagerClass, eventsForPackageHook,
            method = "queryEventsForPackage",
            params = arrayOf(stringClass, longClass, longClass),
            beginIndex = 1
        )

        // ---- health probe: report which entry points actually exist ---------

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
            XposedBridge.log("[Telemetry-Test] hook $method failed: ${t.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass, "${method}AsUser",
                *params, Int::class.javaPrimitiveType!!, callback
            )
        } catch (t: Throwable) {
            XposedBridge.log("[Telemetry-Test] ${method}AsUser absent, skipped")
        }
    }

    /**
     * Logs which query entry points exist on this build.
     *
     * A reader that keeps showing real data is almost always calling an entry
     * point we did not recognise, so make the surface visible rather than
     * guessing which one is in use.
     */
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
        log("[Telemetry-Test] query surface present: ${present.joinToString(",")}")
        log("[Telemetry-Test] query surface absent: ${missing.joinToString(",")}")
    }

    private fun Array<Any?>.longAt(index: Int): Long? =
        if (index < size) getOrNull(index) as? Long else null

    /**
     * Applies the summary injection to either return shape:
     *
     *   queryUsageStats             -> List<UsageStats>, found by mPackageName
     *   queryAndAggregateUsageStats -> Map<String, UsageStats>, keyed by package
     */
    private fun applySummary(
        result: Any?,
        args: Array<Any?>,
        beginIndex: Int,
        kind: String
    ) {
        val queryBeginTime = args.longAt(beginIndex) ?: return
        val queryEndTime = args.longAt(beginIndex + 1) ?: return

        val config = resolveConfig() ?: return

        val targetEntry: Any? = when (result) {
            is List<*> -> {
                val found = result.firstOrNull { statsPackageName(it) == config.targetPackage }
                if (found == null) {
                    log(
                        "[Telemetry-Test] $kind: no row for ${config.targetPackage} " +
                            "(${result.size} rows)"
                    )
                }
                found
            }
            is Map<*, *> -> {
                val found = result[config.targetPackage]
                    ?: result.entries.firstOrNull {
                        statsPackageName(it.value) == config.targetPackage
                    }?.value
                if (found == null) {
                    log(
                        "[Telemetry-Test] $kind: no entry for ${config.targetPackage} " +
                            "(${result.size} keys)"
                    )
                }
                found
            }
            else -> return
        } ?: return

        if (config.mode == MODE_HIDE) {
            // Remove the row when the container is mutable, otherwise blank it.
            var removed = false
            if (result is MutableList<*>) removed = result.remove(targetEntry)
            if (result is MutableMap<*, *>) removed = result.remove(config.targetPackage)
            if (!removed) zeroOutStats(targetEntry)
            log("[Telemetry-Test] $kind HIDDEN ${config.targetPackage} removed=$removed")
            return
        }

        val realForegroundMs = getLongField(targetEntry, "mTotalTimeInForeground")
        val anchors = computeAnchors(
            queryBeginTime = queryBeginTime,
            queryEndTime = queryEndTime,
            targetDurationMs = config.targetDurationMs,
            realForegroundMs = realForegroundMs,
            mode = config.mode
        )

        val failed = mutableListOf<String>()
        fun writeLong(field: String, value: Long) {
            if (!setLongField(targetEntry, field, value)) failed.add(field)
        }

        writeLong("mTotalTimeInForeground", anchors.durationMs)
        writeLong("mTotalTimeVisible", anchors.durationMs)
        writeLong("mBeginTimeStamp", anchors.queryBegin)
        writeLong("mEndTimeStamp", anchors.queryEnd)
        writeLong("mLastTimeUsed", anchors.sessionEnd)
        writeLong("mLastTimeVisible", anchors.sessionEnd)

        val nextLaunchCount = getIntField(targetEntry, "mAppLaunchCount") + 1
        if (!setIntField(targetEntry, "mAppLaunchCount", nextLaunchCount)) {
            failed.add("mAppLaunchCount")
        }

        if (failed.isNotEmpty()) {
            logError(
                "$kind injection failed for ${config.targetPackage}; " +
                    "unwritable: ${failed.joinToString(",")} " +
                    "exemption=${hiddenApiExemption}"
            )
            return
        }

        // Share the final value so a reader deriving its total from the event
        // timeline reports the same duration the summary shows.
        lastInjectedDurationMs = anchors.durationMs

        val verified = getLongField(targetEntry, "mTotalTimeInForeground")
        log(
            "[Telemetry-Test] $kind injected ${config.targetPackage} " +
                "real=${realForegroundMs}ms wrote=${anchors.durationMs}ms " +
                "readBack=${verified}ms window=${queryEndTime - queryBeginTime}ms"
        )
    }

    /** Timeline mutation, shared by every events entry point. */
    private fun applyTimeline(result: Any?, queryBeginTime: Long, queryEndTime: Long) {
        try {
            val config = resolveConfig() ?: return
            val usageEvents = result as? UsageEvents ?: return

            val container = findEventContainer(usageEvents) ?: return

            if (config.mode == MODE_HIDE) {
                val removed = container.removeAll {
                    eventPackage(it) == config.targetPackage
                }
                log(
                    "[Telemetry-Test] TIMELINE HIDDEN ${config.targetPackage} " +
                        "($removed events removed)"
                )
                return
            }

            // Events carry no duration, so reuse the value the summary already
            // computed (real + added, capped) and fall back to the configured
            // amount before any summary query has run.
            val timelineDurationMs = lastInjectedDurationMs ?: config.targetDurationMs
            val anchors = computeAnchors(
                queryBeginTime = queryBeginTime,
                queryEndTime = queryEndTime,
                targetDurationMs = timelineDurationMs,
                realForegroundMs = 0L,
                mode = MODE_SET
            )

            val removedCount =
                container.removeAll { eventPackage(it) == config.targetPackage }

            val injected = mutableListOf<Any>()

            buildEvent(
                EVENT_MOVE_TO_FOREGROUND,
                config.targetPackage,
                config.targetPackage,
                anchors.sessionStart
            )?.let(injected::add)

            buildEvent(
                EVENT_MOVE_TO_BACKGROUND,
                config.targetPackage,
                config.targetPackage,
                anchors.sessionEnd
            )?.let(injected::add)

            val firstInteraction = anchors.sessionStart + INTERACTION_OFFSET_MS
            val lastInteraction = anchors.sessionEnd - INTERACTION_OFFSET_MS
            if (firstInteraction < lastInteraction) {
                buildEvent(
                    EVENT_USER_INTERACTION,
                    config.targetPackage,
                    config.targetPackage,
                    firstInteraction
                )?.let(injected::add)
                buildEvent(
                    EVENT_USER_INTERACTION,
                    config.targetPackage,
                    config.targetPackage,
                    lastInteraction
                )?.let(injected::add)
            } else {
                buildEvent(
                    EVENT_USER_INTERACTION,
                    config.targetPackage,
                    config.targetPackage,
                    anchors.sessionStart +
                        (anchors.sessionEnd - anchors.sessionStart) / 2
                )?.let(injected::add)
            }

            if (injected.isEmpty()) {
                logError("no synthetic events built for ${config.targetPackage}")
                return
            }

            container.addAll(injected)
            container.sortBy { eventTimestamp(it) }

            log(
                "[Telemetry-Test] TIMELINE injected ${config.targetPackage} " +
                    "removed=$removedCount injected=${injected.size} " +
                    "total=${container.size} " +
                    "span=${anchors.sessionEnd - anchors.sessionStart}ms"
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