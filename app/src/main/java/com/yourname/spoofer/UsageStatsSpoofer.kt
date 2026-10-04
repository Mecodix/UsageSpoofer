package com.yourname.spoofer

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Restored the original class name so LSPosed finds it seamlessly
class UsageStatsSpoofer : IXposedHookLoadPackage {

    private fun formatTime(ms: Long): String {
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val targetApp = "com.rayole.cashromeo2" 
        
        if (lpparam.packageName != targetApp) return

        XposedBridge.log("[GodMode Tracer] 👁️ Eye of Agamotto opened inside: $targetApp")

        try {
            val usageStatsManagerClass = XposedHelpers.findClass("android.app.usage.UsageStatsManager", lpparam.classLoader)

            for (method in usageStatsManagerClass.declaredMethods) {
                
                if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) continue

                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val logBuilder = StringBuilder()
                        logBuilder.append("\n======================================================\n")
                        logBuilder.append("[GodMode Tracer] 🎯 INTERCEPTED: ${method.name}\n")
                        
                        if (param.args != null && param.args.isNotEmpty()) {
                            logBuilder.append("   ┣━ 📦 Arguments Passed:\n")
                            param.args.forEachIndexed { index, arg ->
                                val argType = arg?.javaClass?.simpleName ?: "Unknown/Null"
                                
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

                        val rawTrace = android.util.Log.getStackTraceString(Throwable())
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

                        XposedBridge.log(logBuilder.toString())
                    }
                })
            }
        } catch (e: Exception) {
            XposedBridge.log("[GodMode Tracer] ❌ CRITICAL TRACING ERROR: ${e.message}")
        }
    }
}
