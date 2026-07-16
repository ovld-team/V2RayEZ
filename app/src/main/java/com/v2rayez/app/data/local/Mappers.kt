package com.v2rayez.app.data.local

import com.v2rayez.app.domain.model.CorePreference
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.ServerGroup
import com.v2rayez.app.domain.model.Subscription

fun ServerEntity.toModel(): Server = serverFieldsToModel(
    id = id, name = name, country = country, countryCode = countryCode, protocol = protocol,
    transport = transport, security = security, sni = sni, address = address, pingMs = pingMs,
    signal = signal, group = group, isFavorite = isFavorite, host = host, port = port, uuid = uuid,
    password = password, method = method, ssPlugin = ssPlugin, ssPluginOptions = ssPluginOptions,
    alterId = alterId, flow = flow, network = network, headerType = headerType, path = path,
    requestHost = requestHost, streamSecurity = streamSecurity, alpn = alpn, fingerprint = fingerprint,
    allowInsecure = allowInsecure, publicKey = publicKey, shortId = shortId, spiderX = spiderX,
    subscriptionId = subscriptionId, frontProxyId = frontProxyId, userModified = userModified,
    customGroup = customGroup, preferredCore = preferredCore, sshUser = sshUser,
    sshPrivateKey = sshPrivateKey, sshHostKey = sshHostKey, wgPrivateKey = wgPrivateKey,
    wgPeerPublicKey = wgPeerPublicKey, wgPreSharedKey = wgPreSharedKey,
    wgLocalAddresses = wgLocalAddresses, wgAllowedIps = wgAllowedIps, wgReserved = wgReserved,
    wgMtu = wgMtu, dnsTunnelDomain = dnsTunnelDomain, dnsTunnelPubKey = dnsTunnelPubKey,
    dnsTunnelResolver = dnsTunnelResolver, dnsTunnelMode = dnsTunnelMode,
    rawUri = rawUri, psiphonConfig = psiphonConfig
)

/** Maps list projection rows; omitted blob columns default to empty. */
fun ServerListEntity.toModel(): Server = serverFieldsToModel(
    id = id, name = name, country = country, countryCode = countryCode, protocol = protocol,
    transport = transport, security = security, sni = sni, address = address, pingMs = pingMs,
    signal = signal, group = group, isFavorite = isFavorite, host = host, port = port, uuid = uuid,
    password = password, method = method, ssPlugin = ssPlugin, ssPluginOptions = ssPluginOptions,
    alterId = alterId, flow = flow, network = network, headerType = headerType, path = path,
    requestHost = requestHost, streamSecurity = streamSecurity, alpn = alpn, fingerprint = fingerprint,
    allowInsecure = allowInsecure, publicKey = publicKey, shortId = shortId, spiderX = spiderX,
    subscriptionId = subscriptionId, frontProxyId = frontProxyId, userModified = userModified,
    customGroup = customGroup, preferredCore = preferredCore, sshUser = sshUser,
    sshPrivateKey = "", sshHostKey = "", wgPrivateKey = "", wgPeerPublicKey = "", wgPreSharedKey = "",
    wgLocalAddresses = wgLocalAddresses, wgAllowedIps = wgAllowedIps, wgReserved = wgReserved,
    wgMtu = wgMtu, dnsTunnelDomain = dnsTunnelDomain, dnsTunnelPubKey = dnsTunnelPubKey,
    dnsTunnelResolver = dnsTunnelResolver, dnsTunnelMode = dnsTunnelMode,
    rawUri = "", psiphonConfig = ""
)

private fun serverFieldsToModel(
    id: String,
    name: String,
    country: String,
    countryCode: String,
    protocol: Protocol,
    transport: String,
    security: String,
    sni: String,
    address: String,
    pingMs: Int,
    signal: Int,
    group: ServerGroup,
    isFavorite: Boolean,
    host: String,
    port: Int,
    uuid: String,
    password: String,
    method: String,
    ssPlugin: String,
    ssPluginOptions: String,
    alterId: Int,
    flow: String,
    network: String,
    headerType: String,
    path: String,
    requestHost: String,
    streamSecurity: String,
    alpn: String,
    fingerprint: String,
    allowInsecure: Boolean,
    publicKey: String,
    shortId: String,
    spiderX: String,
    subscriptionId: String?,
    frontProxyId: String?,
    userModified: Boolean,
    customGroup: String?,
    preferredCore: String,
    sshUser: String,
    sshPrivateKey: String,
    sshHostKey: String,
    wgPrivateKey: String,
    wgPeerPublicKey: String,
    wgPreSharedKey: String,
    wgLocalAddresses: String,
    wgAllowedIps: String,
    wgReserved: String,
    wgMtu: Int,
    dnsTunnelDomain: String,
    dnsTunnelPubKey: String,
    dnsTunnelResolver: String,
    dnsTunnelMode: String,
    rawUri: String,
    psiphonConfig: String
): Server = Server(
    id = id, name = name, country = country, countryCode = countryCode, protocol = protocol,
    transport = transport, security = security, sni = sni, address = address, pingMs = pingMs,
    signal = signal, group = group, isFavorite = isFavorite, host = host, port = port, uuid = uuid,
    password = password, method = method, ssPlugin = ssPlugin, ssPluginOptions = ssPluginOptions,
    alterId = alterId, flow = flow, network = network,
    headerType = headerType, path = path, requestHost = requestHost, streamSecurity = streamSecurity,
    alpn = alpn, fingerprint = fingerprint, allowInsecure = allowInsecure, publicKey = publicKey,
    shortId = shortId, spiderX = spiderX, subscriptionId = subscriptionId, rawUri = rawUri,
    frontProxyId = frontProxyId, userModified = userModified, customGroup = customGroup,
    preferredCore = runCatching { CorePreference.valueOf(preferredCore) }
        .getOrDefault(CorePreference.SYSTEM),
    sshUser = sshUser, sshPrivateKey = sshPrivateKey, sshHostKey = sshHostKey,
    wgPrivateKey = wgPrivateKey, wgPeerPublicKey = wgPeerPublicKey, wgPreSharedKey = wgPreSharedKey,
    wgLocalAddresses = wgLocalAddresses.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    wgAllowedIps = wgAllowedIps.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    wgReserved = wgReserved.split(",").mapNotNull { it.trim().toIntOrNull() },
    wgMtu = wgMtu,
    dnsTunnelDomain = dnsTunnelDomain, dnsTunnelPubKey = dnsTunnelPubKey,
    dnsTunnelResolver = dnsTunnelResolver, dnsTunnelMode = dnsTunnelMode,
    psiphonConfig = psiphonConfig
)

fun Server.toEntity(sortOrder: Int = 0): ServerEntity = ServerEntity(
    id = id, name = name, country = country, countryCode = countryCode, protocol = protocol,
    transport = transport, security = security, sni = sni, address = address, pingMs = pingMs,
    signal = signal, group = group, isFavorite = isFavorite, host = host, port = port, uuid = uuid,
    password = password, method = method, ssPlugin = ssPlugin, ssPluginOptions = ssPluginOptions,
    alterId = alterId, flow = flow, network = network,
    headerType = headerType, path = path, requestHost = requestHost, streamSecurity = streamSecurity,
    alpn = alpn, fingerprint = fingerprint, allowInsecure = allowInsecure, publicKey = publicKey,
    shortId = shortId, spiderX = spiderX, subscriptionId = subscriptionId, rawUri = rawUri,
    frontProxyId = frontProxyId,
    userModified = userModified,
    sortOrder = sortOrder,
    customGroup = customGroup,
    preferredCore = preferredCore.name,
    sshUser = sshUser, sshPrivateKey = sshPrivateKey, sshHostKey = sshHostKey,
    wgPrivateKey = wgPrivateKey, wgPeerPublicKey = wgPeerPublicKey, wgPreSharedKey = wgPreSharedKey,
    wgLocalAddresses = wgLocalAddresses.joinToString(","),
    wgAllowedIps = wgAllowedIps.joinToString(","),
    wgReserved = wgReserved.joinToString(","),
    wgMtu = wgMtu,
    dnsTunnelDomain = dnsTunnelDomain, dnsTunnelPubKey = dnsTunnelPubKey,
    dnsTunnelResolver = dnsTunnelResolver, dnsTunnelMode = dnsTunnelMode,
    psiphonConfig = psiphonConfig
)

fun SubscriptionEntity.toModel(): Subscription =
    Subscription(id = id, name = name, url = url, enabled = enabled, lastUpdated = lastUpdated, serverCount = serverCount)

fun Subscription.toEntity(): SubscriptionEntity =
    SubscriptionEntity(id = id, name = name, url = url, enabled = enabled, lastUpdated = lastUpdated, serverCount = serverCount)
