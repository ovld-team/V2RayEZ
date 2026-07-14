package com.v2rayez.app.data.core

import com.v2rayez.app.domain.model.ProxyCoreType
import javax.inject.Inject
import javax.inject.Singleton

/** [ProxyCore] adapter over the bundled AndroidLibXrayLite [V2RayCore]. */
@Singleton
class XrayProxyCore @Inject constructor(
    private val xray: V2RayCore
) : ProxyCore {
    override val type: ProxyCoreType = ProxyCoreType.XRAY
    override val isRunning: Boolean get() = xray.isRunning
    override fun version(): String = xray.coreVersion()
    override suspend fun start(configText: String, tunFd: Int): Boolean =
        xray.startLoop(configText, tunFd)
    override suspend fun stop() = xray.stopLoop()
    override fun queryTrafficStats(): Pair<Long, Long> {
        val s = xray.queryTrafficStats()
        return s.totalDown to s.totalUp
    }
    override suspend fun measureDelay(configText: String, url: String): Long =
        xray.measureDelay(configText, url)
}
