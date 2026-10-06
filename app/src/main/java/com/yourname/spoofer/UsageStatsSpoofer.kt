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
        private const val HOST_APP = "com.example.telemetry.sandbox"
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
            val uri = Uri.parse("content://$CONFIG_AUTHORITY/$packageNameKey")
            context.contentResolver
                .query(uri, arrayOf(field), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) ?: "" else ""
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

        // Cap overruns so the synthetic session never claims more time than the
        // requested window can physically hold.
        val durationMs = when {
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

    /** Lazily resolved, hidden-API constructor for UsageEvents.Event. */
    private val eventConstructor: Constructor<*>? by lazy {
        try {
            Class.forName("android.app.usage.UsageEvents\$Event")
                .getDeclaredConstructor(
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    Long::class.javaPrimitiveType
                )
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            logError("UsageEvents.Event constructor unavailable: ${t.message}")
            null
        }
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
            ctor.newInstance(eventType, packageName, className, timestamp)
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

    private fun awaitApplication(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (appContext == null) {
                            appContext = getContext()
                            log(
                                "[Telemetry-Test] host Application ready " +
                                    "(pid=${android.os.Process.myPid()})"
                            )
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logError("could not hook Application.attach: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ hooks

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != HOST_APP) return

        log("[Telemetry-Test] Validation module attached to $HOST_APP (pid=${android.os.Process.myPid()})")

        // currentApplication() is often still null at this point in startup, so hook
        // Application.attach to establish the context used by the log sink.
        awaitApplication(lpparam.classLoader)

        try {
            val usageStatsManagerClass = XposedHelpers.findClass(
                "android.app.usage.UsageStatsManager",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass,
                "queryUsageStats",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.args.size < 3) return
                            val queryBeginTime = param.args[1] as? Long ?: return
                            val queryEndTime = param.args[2] as? Long ?: return

                            val config = resolveConfig() ?: return
                            val resultList = param.result as? List<*> ?: return

                            val targetEntry = resultList.firstOrNull { entry ->
                                statsPackageName(entry) == config.targetPackage
                            } ?: return

                            if (config.mode == MODE_HIDE) {
                                // Star projection: we only need mutation, and an explicit
                                // type argument here would be an erased-type check.
                                val mutableResult = resultList as? MutableList<Any?>
                                if (mutableResult != null) {
                                    if (!mutableResult.remove(targetEntry)) {
                                        zeroOutStats(targetEntry)
                                    }
                                } else {
                                    zeroOutStats(targetEntry)
                                }
                                return
                            }

                            val realForegroundMs =
                                getLongField(targetEntry, "mTotalTimeInForeground")

                            val anchors = computeAnchors(
                                queryBeginTime = queryBeginTime,
                                queryEndTime = queryEndTime,
                                targetDurationMs = config.targetDurationMs,
                                realForegroundMs = realForegroundMs,
                                mode = config.mode
                            )

                            var allWritesOk = true
                            allWritesOk = setLongField(
                                targetEntry, "mTotalTimeInForeground", anchors.durationMs
                            ) && allWritesOk
                            allWritesOk = setLongField(
                                targetEntry, "mTotalTimeVisible", anchors.durationMs
                            ) && allWritesOk
                            allWritesOk = setLongField(
                                targetEntry, "mBeginTimeStamp", anchors.queryBegin
                            ) && allWritesOk
                            allWritesOk = setLongField(
                                targetEntry, "mEndTimeStamp", anchors.queryEnd
                            ) && allWritesOk
                            allWritesOk = setLongField(
                                targetEntry, "mLastTimeUsed", anchors.sessionEnd
                            ) && allWritesOk
                            allWritesOk = setLongField(
                                targetEntry, "mLastTimeVisible", anchors.sessionEnd
                            ) && allWritesOk

                            val nextLaunchCount = getIntField(targetEntry, "mAppLaunchCount") + 1
                            allWritesOk = setIntField(
                                targetEntry, "mAppLaunchCount", nextLaunchCount
                            ) && allWritesOk

                            if (allWritesOk) {
                                log(
                                    "[Telemetry-Test] Synchronized session timeline " +
                                        "injected successfully for ${config.targetPackage}"
                                )
                            } else {
                                logError(
                                    "summary injection partially failed for " +
                                        config.targetPackage
                                )
                            }
                        } catch (t: Throwable) {
                            logError("queryUsageStats hook failure: ${t.message}")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                usageStatsManagerClass,
                "queryEvents",
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.args.size < 2) return
                            val queryBeginTime = param.args[0] as? Long ?: return
                            val queryEndTime = param.args[1] as? Long ?: return

                            val config = resolveConfig() ?: return
                            val usageEvents = param.result as? UsageEvents ?: return

                            val container = findEventContainer(usageEvents) ?: return

                            if (config.mode == MODE_HIDE) {
                                val removed = container.removeAll {
                                    eventPackage(it) == config.targetPackage
                                }
                                log(
                                    "[Telemetry-Test] Synchronized session timeline " +
                                        "injected successfully for ${config.targetPackage} " +
                                        "(hidden, $removed events removed)"
                                )
                                return
                            }

                            // Identical anchor math to the summary hook.
                            //
                            // Timeline events carry no duration field, so ADD mode has
                            // no real foreground total to accumulate here. The summary
                            // hook is the authority for that value; passing 0 keeps the
                            // injected timeline consistent with the requested duration.
                            val anchors = computeAnchors(
                                queryBeginTime = queryBeginTime,
                                queryEndTime = queryEndTime,
                                targetDurationMs = config.targetDurationMs,
                                realForegroundMs = 0L,
                                mode = config.mode
                            )

                            // Drop the package's real triggers so nothing collides.
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
                                // Session shorter than 2 * offset: one midpoint
                                // interaction keeps the lifecycle well-formed.
                                buildEvent(
                                    EVENT_USER_INTERACTION,
                                    config.targetPackage,
                                    config.targetPackage,
                                    anchors.sessionStart +
                                        (anchors.sessionEnd - anchors.sessionStart) / 2
                                )?.let(injected::add)
                            }

                            if (injected.isEmpty()) {
                                logError(
                                    "no synthetic events could be built for " +
                                        config.targetPackage
                                )
                                return
                            }

                            container.addAll(injected)
                            // The sandbox engine parses a chronological timeline.
                            container.sortBy { eventTimestamp(it) }

                            log(
                                "[Telemetry-Test] Synchronized session timeline " +
                                    "injected successfully for ${config.targetPackage}"
                            )
                        } catch (t: Throwable) {
                            logError("queryEvents hook failure: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logError("critical initialization failure: ${t.message}")
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