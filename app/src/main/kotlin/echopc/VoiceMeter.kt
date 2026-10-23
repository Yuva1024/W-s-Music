package echopc

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlin.concurrent.thread

/**
 * How loud other apps are right now, from Windows' own audio meters (what the volume mixer's bars show). Used to
 * notice when someone talks on Discord: Discord only makes sound when a person in the call speaks.
 * Windows only; on any failure it reports silence.
 */
class VoiceMeter(
    /** Program file names to listen to (lower case). */
    private val programs: Set<String> = setOf("discord.exe", "discordptb.exe", "discordcanary.exe"),
    private val onLevel: (Float) -> Unit,
) : AutoCloseable {
    @Volatile private var running = true
    private val worker = thread(isDaemon = true, name = "voice-meter") { run() }

    override fun close() { running = false }

    /** A COM object: calls go through its function table. */
    private class Com(val p: Pointer) {
        private fun fn(i: Int) = Function.getFunction(p.getPointer(0).getPointer(i.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        fun call(i: Int, vararg args: Any?): Int = fn(i).invokeInt(arrayOf<Any?>(p, *args))
        fun release() { runCatching { call(2) } }
        fun query(iid: Guid.GUID): Com? { val ref = PointerByReference(); return if (call(0, iid, ref) == 0 && ref.value != null) Com(ref.value) else null }
    }

    private fun out(call: (PointerByReference) -> Int): Com? { val ref = PointerByReference(); return if (call(ref) == 0 && ref.value != null) Com(ref.value) else null }

    private fun run() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runCatching { Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED) }
        var meters = emptyList<Com>()
        var refreshed = 0L
        try {
            while (running) {
                // Discord's audio sessions come and go (joining a call, changing headset): look again every few seconds.
                if (System.currentTimeMillis() - refreshed > 3000) {
                    meters.forEach { it.release() }
                    meters = runCatching { findMeters() }.onFailure { AppHooks.log("Voice meter: ${it.message}") }.getOrDefault(emptyList())
                    refreshed = System.currentTimeMillis()
                }
                var peak = 0f
                for (m in meters) {
                    val v = FloatByReference()
                    if (runCatching { m.call(3, v) }.getOrDefault(-1) == 0) peak = maxOf(peak, v.value) // IAudioMeterInformation::GetPeakValue
                }
                onLevel(peak)
                Thread.sleep(50)
            }
        } finally {
            meters.forEach { it.release() }
            runCatching { Ole32.INSTANCE.CoUninitialize() }
        }
    }

    /** Meters of the listened-to programs' sessions, on every active output device. */
    private fun findMeters(): List<Com> {
        val found = ArrayList<Com>()
        val enumerator = out { Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, it).toInt() } ?: return found
        try {
            val devices = out { enumerator.call(3, 0 /* eRender */, 1 /* DEVICE_STATE_ACTIVE */, it) } ?: return found // EnumAudioEndpoints
            try {
                val count = IntByReference().also { devices.call(3, it) }.value // IMMDeviceCollection::GetCount
                for (d in 0 until count) {
                    val device = out { devices.call(4, d, it) } ?: continue // Item
                    try {
                        val manager = out { device.call(3, IID_IAudioSessionManager2, CLSCTX_ALL, Pointer.NULL, it) } ?: continue // Activate
                        try {
                            val sessions = out { manager.call(5, it) } ?: continue // GetSessionEnumerator
                            try {
                                val n = IntByReference().also { sessions.call(3, it) }.value
                                for (s in 0 until n) {
                                    val control = out { sessions.call(4, s, it) } ?: continue // GetSession
                                    val control2 = control.query(IID_IAudioSessionControl2)
                                    val pid = IntByReference().also { control2?.call(14, it) }.value // GetProcessId
                                    control2?.release()
                                    val name = ProcessHandle.of(pid.toLong()).flatMap { it.info().command() }.orElse("")
                                        .substringAfterLast('\\').lowercase()
                                    val meter = if (name in programs) control.query(IID_IAudioMeterInformation) else null
                                    control.release()
                                    if (meter != null) found += meter
                                }
                            } finally { sessions.release() }
                        } finally { manager.release() }
                    } finally { device.release() }
                }
            } finally { devices.release() }
        } finally { enumerator.release() }
        return found
    }

    private companion object {
        const val CLSCTX_ALL = 0x17
        val CLSID_MMDeviceEnumerator = Guid.GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
        val IID_IMMDeviceEnumerator = Guid.GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
        val IID_IAudioSessionManager2 = Guid.GUID("{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}")
        val IID_IAudioSessionControl2 = Guid.GUID("{BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D}")
        val IID_IAudioMeterInformation = Guid.GUID("{C02216F6-8C67-4B5B-9D00-D008E73E0064}")
    }
}
