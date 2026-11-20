package com.hermesandroid.relay.script.runtime

/** Shared cold-start loop; Android supplies window reads, thresholded page detection and frame size. */
internal suspend fun ScriptAccess.coldStartToPages(
    pkg: String?, bootPage: String, homePage: String,
    tapX: Int?, tapY: Int?, bootWaitMs: Long, homeWaitMs: Long, intervalMs: Long,
    popupPage: String, popupX: Int, popupY: Int, maxPopups: Int,
    acceptPages: List<String>, orientation: String,
    readForeground: () -> String?, detectPage: suspend () -> String,
    frameSize: suspend () -> Pair<Int, Int>?,
): String {
    val started = monotonicMs()
    var foregroundStreak = 0
    var unknownStreak = 0
    var ready = false
    var lastPackage: String? = null
    var lastLaunchAt = 0L
    var taps = 0
    var firstBootAt = 0L
    var lastPage = "?"
    var otherSince: Long? = null
    var rescues = 0
    var popupTaps = 0

    while (monotonicMs() - started < bootWaitMs) {
        if (ScriptRunControl.isCancelled()) return "fail:cancelled reason=${ScriptRunControl.reason} rescue=$rescues"
        if (!screenOn()) return "fail:screen_off rescue=$rescues"
        lastPackage = readForeground()
        if (pkg != null && lastPackage == pkg) {
            unknownStreak = 0
            foregroundStreak++
            if (foregroundStreak >= 2) { ready = true; break }
        } else {
            foregroundStreak = 0
            unknownStreak = if (lastPackage == null) unknownStreak + 1 else 0
            // Non-other is emitted only after PageDetector's configured checks/thresholds pass.
            // This admits the page loop, including boot taps; it is not an early success.
            if (unknownStreak >= 3 && detectPage() != "other") {
                ready = true
                break
            }
            if (lastPackage != null || unknownStreak >= 3) {
                if (monotonicMs() - lastLaunchAt >= 2_000) {
                    val name = pkg ?: return "fail:no_package rescue=$rescues"
                    lastLaunchAt = monotonicMs()
                    launchApp(name)
                }
            }
        }
        waitMs(intervalMs.coerceIn(300, 5_000))
    }
    if (!ready) return "fail:foreground_timeout last=${lastPackage ?: "unknown"}"

    val pageDeadline = monotonicMs() + homeWaitMs
    while (monotonicMs() < pageDeadline) {
        if (ScriptRunControl.isCancelled()) return "fail:cancelled reason=${ScriptRunControl.reason} rescue=$rescues"
        if (!screenOn()) return "fail:screen_off rescue=$rescues"
        lastPage = detectPage()
        // Popup always wins, even when it also appears in accept or the tap budget is spent.
        if (lastPage == popupPage) {
            if (popupTaps < maxPopups) {
                tap(popupX, popupY)
                popupTaps++
                waitMs(1_500)
            } else waitMs(intervalMs.coerceIn(300, 5_000))
            continue
        }
        if (lastPage == homePage) {
            return "ok:home_at_${monotonicMs() - started}ms taps=$taps popups=$popupTaps"
        }
        if (lastPage in acceptPages) return "ok:already_on_$lastPage"
        val now = monotonicMs()
        if (lastPage == "other") {
            val since = otherSince ?: now.also { otherSince = it }
            // 只在「还没点到启动页」的加载阶段自救；已经开始点 boot 之后就别再重开游戏了
            if (taps == 0 && pkg != null && now - since >= 20_000 && now - lastLaunchAt >= 20_000 && rescues < 3) {
                lastLaunchAt = now
                launchApp(pkg)
                rescues++
                otherSince = monotonicMs()
            }
        } else {
            otherSince = null
        }
        if (lastPage == bootPage) {
            if (firstBootAt == 0L) firstBootAt = monotonicMs()
            if (taps < 3 && monotonicMs() - firstBootAt < 15_000) {
                val size = if (tapX == null || tapY == null) frameSize() else null
                val (tx, ty) = ScriptRuntime.coldStartTapPoint(tapX, tapY, size) {
                    val (width, height) = screenSize()
                    when (orientation) {
                        "landscape" -> maxOf(width, height) to minOf(width, height)
                        "portrait" -> minOf(width, height) to maxOf(width, height)
                        else -> width to height
                    }
                }
                tap(tx, ty)
                taps++
                waitMs(2_000)
                continue
            }
        }
        waitMs(intervalMs.coerceIn(300, 5_000))
    }
    return "fail:no_home rescue=$rescues last=$lastPage taps=$taps elapsed=${monotonicMs() - started}ms"
}
