package com.v2rayez.app.data.local

import com.v2rayez.app.domain.model.CorePreference
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.Subscription

fun ServerEntity.toModel(): Server = Server(
    id = id, name = name, country = country, countryCode = countryCode, protocol = protocol,
    transport = transport, security = security, sni = sni, address = address, pingMs = pingMs,
    signal = signal, group = group, isFavorite = isFavorite, host = host, port = port, uuid = uuid,
    password = password, method = method, alterId = alterId, flow = flow, network = network,
    headerType = headerType, path = path, requestHost = requestHost, streamSecurity = streamSecurity,
    alpn = alpn, fingerprint = fingerprint, allowInsecure = allowInsecure, publicKey = publicKey,
    shortId = shortId, spiderX = spiderX, subscriptionId = subscriptionId, rawUri = rawUri,
    frontProxyId = frontProxyId, userModified = userModified, customGroup = customGroup,
    preferredCore = runCatching { CorePreference.valueOf(preferredCore) }
        .getOrDefault(CorePreference.SYSTEM),
    sshUser = sshUser, sshPrivateKey = sshPrivateKey, sshHostKey = sshHostKey,
    wgPrivateKey = wgPrivateKey, wgPeerPublicKey = wgPeerPublicKey, wgPreSharedKey = wgPreSharedKey,
    wgLocalAddresses = wgLocalAddresses.split(",").map { it.trim() }.filter { it.isNotEmpty() },
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
    password = password, method = method, alterId = alterId, flow = flow, network = network,
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
