package hev.htproxy

/**
 * JNI facade expected by `libhev-socks5-tunnel.so` ([JNI_OnLoad] registers these natives
 * against `hev/htproxy/TProxyService`).
 *
 * Library load is deferred and failure-soft so a missing/misaligned `.so` cannot crash
 * process startup on 16 KB page-size devices.
 */
object TProxyService {
    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary("hev-socks5-tunnel")
                loaded = true
                true
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
                false
            }
        }
    }

    fun loadError(): String? = loadError

    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray
}
