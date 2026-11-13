package com.hermesandroid.relay.script.runtime

object ScriptGuard {
    const val SYSTEM_UI = "guard_system_ui"
    const val PACKAGE_MISMATCH = "guard_package_mismatch"

    /**
     * Stops scripts in the wrong app, after an app switch, and on launcher/system UI.
     * Android cannot make the foreground check and a later gesture atomic, so a manual
     * switch in that millisecond-scale gap cannot be prevented; tapText is preferred.
     */
    fun rejection(packageName: String?, targetPackages: Collection<String>): String? = when {
        packageName == "com.android.systemui" || packageName?.startsWith("com.android.settings") == true -> SYSTEM_UI
        packageName.isNullOrBlank() || packageName !in targetPackages -> PACKAGE_MISMATCH
        else -> null
    }
}
