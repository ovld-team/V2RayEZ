package com.v2rayez.app.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.v2rayez.app.data.service.MitmProxyService
import com.v2rayez.app.data.service.MitmProxyStateHolder
import com.v2rayez.app.domain.repository.MitmProxyController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealMitmProxyController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: MitmProxyStateHolder
) : MitmProxyController {

    override val running: StateFlow<Boolean> = stateHolder.running
    override val lastError: StateFlow<String?> = stateHolder.error

    override fun start() {
        val intent = Intent(context, MitmProxyService::class.java)
            .setAction(MitmProxyService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stop() {
        val intent = Intent(context, MitmProxyService::class.java)
            .setAction(MitmProxyService.ACTION_STOP)
        context.startService(intent)
    }

    override fun clearError() {
        stateHolder.setError(null)
    }
}
