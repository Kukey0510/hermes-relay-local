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
        // 冷启动/切前台的瞬间，无障碍读不到活动窗口 → packageName 为 null。
        // 这时拦下来会把整条冷启动脚本判死（实测：launch 之后第一步 wait 就 guard_package_mismatch），
        // 而此时点击本身是空操作，不会落到别的 App 上，所以放行；
        // 真正的「切到了别的 App」仍由下面的不匹配分支拦住。
        packageName.isNullOrBlank() -> null
        packageName !in targetPackages -> PACKAGE_MISMATCH
        else -> null
    }
}
