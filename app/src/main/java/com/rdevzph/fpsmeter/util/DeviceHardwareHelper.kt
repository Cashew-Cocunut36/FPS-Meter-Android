package com.rdevzph.fpsmeter.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.Locale

object DeviceHardwareHelper {

    @Volatile
    private var cachedSocModel: String? = null

    // Remembered from the previous CPU reading so we can compare
    private var prevCpuTotal = 0L
    private var prevCpuIdle = 0L
    private var lastCpuLoad = 0

    // Last few Samsung/MediaTek GPU readings, averaged to calm the jumps
    private val gpuHistory = ArrayDeque<Int>()

    // Which GPU load source this phone has. Detected once, then remembered.
    private enum class GpuSource { UNKNOWN, SAMSUNG, KGSL, NONE }
    @Volatile private var gpuSource = GpuSource.UNKNOWN
    private var lastGpuDetect = 0L
    private var gpuFailStreak = 0

    fun getSocModel(): String {
        cachedSocModel?.let { return it }
        var soc = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val model = Build.SOC_MODEL
            if (!model.isNullOrEmpty() && !model.equals("unknown", ignoreCase = true)) {
                soc = model
            }
        }
        if (soc.isEmpty()) {
            try {
                val p = Runtime.getRuntime().exec("getprop ro.soc.model")
                val line = p.inputStream.bufferedReader().readLine()?.trim()
                if (!line.isNullOrEmpty()) soc = line
            } catch (_: Exception) {}
        }
        if (soc.isEmpty()) {
            try {
                val p = Runtime.getRuntime().exec("getprop ro.board.platform")
                val line = p.inputStream.bufferedReader().readLine()?.trim()
                if (!line.isNullOrEmpty() && !line.equals("unknown", ignoreCase = true)) soc = line
            } catch (_: Exception) {}
        }
        if (soc.isEmpty()) {
            soc = if (!Build.HARDWARE.isNullOrEmpty() && !Build.HARDWARE.equals("unknown", ignoreCase = true)) {
                Build.HARDWARE
            } else {
                Build.BOARD
            }
        }
        val result = soc.uppercase(Locale.US)
        cachedSocModel = result
        return result
    }

    fun getCpuFrequencyMhz(): Int {
        return try {
            val f = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
            if (f.exists() && f.canRead()) {
                val khz = f.readText().trim().toIntOrNull() ?: 0
                khz / 1000
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    fun getUsedRamMb(context: Context): Int {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            if (memInfo.totalMem > 0L) {
                ((memInfo.totalMem - memInfo.availMem) / (1024L * 1024L)).toInt()
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Call when load monitoring (re)starts after a pause, so the first CPU
     * reading is not averaged over the whole time it was switched off.
     */
    fun resetLoadBaselines() {
        prevCpuTotal = 0L
        prevCpuIdle = 0L
        gpuHistory.clear()
    }

    /**
     * CPU load across all cores, measured over the time since the last call.
     * Reads the first line of /proc/stat (running totals) and compares
     * it with the previous reading. iowait is counted as idle, which is the
     * usual convention (htop and psutil do the same).
     */
    fun getCpuLoad(): Int {
        try {
            val line = ShellHelper.runShellCommand("head -1 /proc/stat").trim()
            val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 7) return lastCpuLoad

            val idle = parts[3] + parts[4]       // idle + iowait
            val total = parts.take(8).sum()      // user, nice, system, idle, iowait, irq, softirq, steal

            val isFirstReading = prevCpuTotal == 0L
            val deltaTotal = total - prevCpuTotal
            val deltaIdle = idle - prevCpuIdle
            prevCpuTotal = total
            prevCpuIdle = idle

            if (isFirstReading || deltaTotal <= 0L) return lastCpuLoad

            lastCpuLoad = (((deltaTotal - deltaIdle) * 100L) / deltaTotal).toInt().coerceIn(0, 100)
            return lastCpuLoad
        } catch (_: Exception) {
            return lastCpuLoad
        }
    }

    /**
     * GPU load (0-100), or -1 when this phone has no readable source (show N/A).
     * Sources:
     *   SAMSUNG: /sys/kernel/gpu/gpu_busy, a ready-made percentage.
     *   KGSL:    /sys/class/kgsl/kgsl-3d0/gpubusy, two numbers "busy total" in
     *            microseconds for the last ~1 s window (confirmed per-interval
     *            on a Snapdragon 680 / Adreno 610, so we divide, never subtract).
     */
    fun getGpuLoad(): Int {
        val now = SystemClock.elapsedRealtime()
        if (gpuSource == GpuSource.UNKNOWN ||
            (gpuSource == GpuSource.NONE && now - lastGpuDetect > 60_000L)
        ) {
            gpuSource = detectGpuSource()
            lastGpuDetect = now
            gpuFailStreak = 0
        }

        val value = when (gpuSource) {
            GpuSource.SAMSUNG -> trySamsungGpuBusy()
            GpuSource.KGSL -> tryKgslGpubusy()
            else -> -1
        }

        if (gpuSource == GpuSource.SAMSUNG || gpuSource == GpuSource.KGSL) {
            if (value < 0) {
                gpuFailStreak++
                // Source stopped answering: forget it and detect again next time
                if (gpuFailStreak >= 5) {
                    gpuSource = GpuSource.UNKNOWN
                    gpuFailStreak = 0
                }
            } else {
                gpuFailStreak = 0
            }
        }
        return value
    }

    /** Finds which source answers on this phone. Runs once, not every cycle. */
    private fun detectGpuSource(): GpuSource {
        return try {
            val s = ShellHelper.runShellCommand("cat /sys/kernel/gpu/gpu_busy 2>/dev/null").trim()
            if (Regex("""\d""").containsMatchIn(s)) return GpuSource.SAMSUNG

            val k = ShellHelper.runShellCommand("cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null").trim()
            if (Regex("""\d+\s+\d+""").containsMatchIn(k)) return GpuSource.KGSL

            GpuSource.NONE
        } catch (_: Exception) {
            GpuSource.NONE
        }
    }

    /**
     * Samsung/MediaTek: reads gpu_busy 4 times (0.2s apart) and averages,
     * then smooths over the last 3 results.
     */
    private fun trySamsungGpuBusy(): Int {
        return try {
            val output = ShellHelper.runShellCommand(
                "for i in 1 2 3 4; do cat /sys/kernel/gpu/gpu_busy 2>/dev/null || { echo SKIP; break; }; sleep 0.2; done"
            )
            if (output.contains("SKIP") || output.isBlank()) return -1

            val samples = Regex("""\d+""").findAll(output)
                .mapNotNull { it.value.toIntOrNull() }
                .map { it.coerceIn(0, 100) }
                .toList()
            if (samples.isEmpty()) return -1

            gpuHistory.addLast(samples.average().toInt())
            if (gpuHistory.size > 3) gpuHistory.removeFirst()
            gpuHistory.average().toInt()
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Qualcomm Adreno: one read, busy / total. The file already holds the
     * last ~1 s window, so no second read and no smoothing.
     * total == 0 means the GPU was idle or powered down: report 0%.
     */
    private fun tryKgslGpubusy(): Int {
        return try {
            val output = ShellHelper.runShellCommand("cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null")
            val nums = Regex("""\d+""").findAll(output)
                .mapNotNull { it.value.toLongOrNull() }
                .toList()
            if (nums.size < 2) return -1

            val busy = nums[0]
            val total = nums[1]
            if (total <= 0L) return 0
            ((busy * 100L) / total).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * GPU temperature is not available through this helper (it returns 0).
     * SocThermalMonitor provides GPU temperature on phones that expose it.
     */
    fun getGpuTemperature(): Float {
        return 0f
    }
}