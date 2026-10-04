package com.yourname.spoofer

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageStatsTracer : IXposedHookLoadPackage {

    private fun formatTime(ms: Long): String {
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Change this if you want to trace a different app in the future
        val targetApp = "com.rayole.cashromeo2" 
        
        if (lpparam.packageName != targetApp) return

        XposedBridge.log("[GodMode Tracer] 👁️ Eye of Agamotto opened inside: $targetApp")

        try {
            val usageStatsManagerClass = XposedHelpers.findClass("android.app.usage.UsageStatsManager", lpparam.classLoader)

            // Sniff every single method inside the UsageStatsManager class
            for (method in usageStatsManagerClass.declaredMethods) {
                
                // We cannot hook abstract or interface methods directly
                if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) continue

                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val logBuilder = StringBuilder()
                        logBuilder.append("\n======================================================\n")
                        logBuilder.append("[GodMode Tracer] 🎯 INTERCEPTED: ${method.name}\n")
                        
                        // 1. Unpack and translate the Arguments
                        if (param.args != null && param.args.isNotEmpty()) {
                            logBuilder.append("   ┣━ 📦 Arguments Passed:\n")
                            param.args.forEachIndexed { index, arg ->
                                val argType = arg?.javaClass?.simpleName ?: "Unknown/Null"
                                
                                // Automatically format timestamp arguments into readable dates
                                val argValue = if (arg is Long && arg > 1000000000000L) {
                                    "$arg (${formatTime(arg)})"
                                } else {
                                    arg.toString()
                                }
                                
                                logBuilder.append("   ┃  [$index] ($argType) = $argValue\n")
                            }
                        } else {
                            logBuilder.append("   ┣━ 📦 Arguments: None\n")
                        }

                        // 2. Extract the Stack Trace to see WHERE they called it from
                        val rawTrace = android.util.Log.getStackTraceString(Throwable())
                        
                        // Clean the trace so we don't see all the Xposed framework background noise
                        val cleanTrace = rawTrace.lines()
                            .filter { line -> 
                                line.isNotBlank() && 
                                !line.contains("de.robv.android.xposed") && 
                                !line.contains("java.lang.reflect") &&
                                !line.contains("com.android.internal.os.ZygoteInit")
                            }
                            .joinToString("\n   ┃  ")

                        logBuilder.append("   ┗━ 📜 Execution Path (Who called this?):\n   ┃  $cleanTrace")
                        logBuilder.append("\n======================================================")

                        // Dump it to the LSPosed logs
                        XposedBridge.log(logBuilder.toString())
                    }
                })
            }
        } catch (e: Exception) {
            XposedBridge.log("[GodMode Tracer] ❌ CRITICAL TRACING ERROR: ${e.message}")
        }
    }
}
