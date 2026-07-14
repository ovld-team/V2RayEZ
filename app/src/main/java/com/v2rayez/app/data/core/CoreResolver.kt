package com.v2rayez.app.data.core

import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.CorePreference
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.model.Server

/** Resolves which [ProxyCoreType] runs a given [Server] under [AppSettings]. */
object CoreResolver {
    fun resolve(server: Server, settings: AppSettings): ProxyCoreType =
        server.preferredCore.toCoreTypeOrNull() ?: settings.defaultCore

    fun resolve(preference: CorePreference, settings: AppSettings): ProxyCoreType =
        preference.toCoreTypeOrNull() ?: settings.defaultCore
}
