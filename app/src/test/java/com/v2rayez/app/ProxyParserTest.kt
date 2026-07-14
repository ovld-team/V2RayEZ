package com.v2rayez.app

import com.v2rayez.app.data.parser.ProxyParser
import com.v2rayez.app.domain.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxyParserTest {

    @Test
    fun parsesVlessLink() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?encryption=none&security=tls&type=ws&path=%2Fws&host=cdn.example.com&sni=example.com&fp=chrome#Tokyo%20Node"
        val s = ProxyParser.parse(uri)!!
        assertEquals(Protocol.VLESS, s.protocol)
        assertEquals("example.com", s.host)
        assertEquals(443, s.port)
        assertEquals("11111111-2222-3333-4444-555555555555", s.uuid)
        assertEquals("ws", s.network)
        assertEquals("tls", s.streamSecurity)
        assertEquals("/ws", s.path)
        assertEquals("cdn.example.com", s.requestHost)
        assertEquals("chrome", s.fingerprint)
        assertEquals("Tokyo Node", s.name)
    }

    @Test
    fun parsesTrojanLink() {
        val uri = "trojan://secretpass@t.example.com:8443?security=tls&type=tcp&sni=t.example.com#Trojan1"
        val s = ProxyParser.parse(uri)!!
        assertEquals(Protocol.TROJAN, s.protocol)
        assertEquals("secretpass", s.password)
        assertEquals("t.example.com", s.host)
        assertEquals(8443, s.port)
        assertEquals("Trojan1", s.name)
    }

    @Test
    fun parsesShadowsocksSip002() {
        val creds = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:mypassword".toByteArray())
        val uri = "ss://$creds@ss.example.com:8388#SS-Node"
        val s = ProxyParser.parse(uri)!!
        assertEquals(Protocol.SHADOWSOCKS, s.protocol)
        assertEquals("aes-256-gcm", s.method)
        assertEquals("mypassword", s.password)
        assertEquals("ss.example.com", s.host)
        assertEquals(8388, s.port)
    }

    @Test
    fun parsesVmessBase64Json() {
        val json = """{"v":"2","ps":"VM Node","add":"v.example.com","port":"443","id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":"0","scy":"auto","net":"ws","type":"none","host":"v.example.com","path":"/vm","tls":"tls","sni":"v.example.com"}"""
        val uri = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        val s = ProxyParser.parse(uri)!!
        assertEquals(Protocol.VMESS, s.protocol)
        assertEquals("v.example.com", s.host)
        assertEquals(443, s.port)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", s.uuid)
        assertEquals("ws", s.network)
        assertEquals("/vm", s.path)
        assertEquals("VM Node", s.name)
    }

    @Test
    fun parsesVlessUriPathFormLikeXeovo() {
        // host:port/path?query — path also duplicated in query (Xeovo style)
        val uri = "vless://45eb995b-b6c6-4e60-b960-e96eaa6f4297@us-slc-global1.xeovo.eu:443/potosi" +
            "?type=ws&encryption=none&security=tls&sni=us-slc-global1.xeovo.eu" +
            "&host=us-slc-global1.xeovo.eu&path=%2Fpotosi#US-SLC%20/%20VLESS"
        val s = ProxyParser.parse(uri)!!
        assertEquals("us-slc-global1.xeovo.eu", s.host)
        assertEquals(443, s.port)
        assertEquals("ws", s.network)
        assertEquals("/potosi", s.path)
    }

    @Test
    fun parsesVlessUriPathWhenQueryPathMissing() {
        val uri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@node.example.com:8443/ws-path" +
            "?type=ws&encryption=none&security=tls#Node"
        val s = ProxyParser.parse(uri)!!
        assertEquals("node.example.com", s.host)
        assertEquals(8443, s.port)
        assertEquals("/ws-path", s.path)
    }

    @Test
    fun parseManyDetailedReportsHysteria2Skipped() {
        val text = """
            hysteria2://token@1.2.3.4:443/?sni=h.example#Hy2
            vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@a.com:443?security=tls&type=tcp#A
            ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@b.com:8388#B
        """.trimIndent()
        val r = ProxyParser.parseManyDetailed(text)
        assertEquals(2, r.servers.size)
        assertEquals(1, r.skippedUnsupported)
        assertEquals(1, r.unsupportedSchemes["hysteria2"])
        assertTrue(r.summaryMessage().contains("hysteria2"))
    }

    @Test
    fun rejectsUnknownScheme() {
        assertNull(ProxyParser.parse("http://example.com"))
    }

    @Test
    fun parsesManyFromBase64Subscription() {
        val links = "vless://id@a.com:443?security=tls&type=tcp#A\n" +
            "trojan://pw@b.com:443?security=tls#B"
        val blob = Base64.getEncoder().encodeToString(links.toByteArray())
        val servers = ProxyParser.parseMany(blob)
        assertEquals(2, servers.size)
        assertEquals(Protocol.VLESS, servers[0].protocol)
        assertEquals(Protocol.TROJAN, servers[1].protocol)
    }

    @Test
    fun roundTripsVlessThroughSerializer() {
        val uri = "vless://id-1@host.com:443?encryption=none&security=reality&type=grpc&pbk=PUBKEY&sid=ab12&sni=host.com#R"
        val s = ProxyParser.parse(uri)!!.copy(rawUri = "")
        val out = ProxyParser.toUri(s)
        assertTrue(out.startsWith("vless://id-1@host.com:443"))
        val reparsed = ProxyParser.parse(out)!!
        assertEquals("host.com", reparsed.host)
        assertEquals(Protocol.VLESS, reparsed.protocol)
    }

    @Test
    fun preservesTrojanRealityFields() {
        val uri = "trojan://secret@reality.example:443?security=reality&type=tcp" +
            "&sni=www.example.com&pbk=PUBLIC_KEY&sid=abcd&spx=%2Fcrawl#Reality"
        val parsed = ProxyParser.parse(uri)!!
        assertEquals("PUBLIC_KEY", parsed.publicKey)
        assertEquals("abcd", parsed.shortId)
        assertEquals("/crawl", parsed.spiderX)
        val reparsed = ProxyParser.parse(ProxyParser.toUri(parsed.copy(rawUri = "")))!!
        assertEquals(parsed.publicKey, reparsed.publicKey)
        assertEquals(parsed.shortId, reparsed.shortId)
        assertEquals(parsed.spiderX, reparsed.spiderX)
    }

    @Test
    fun preservesShadowsocksSip003Plugin() {
        val uri = "ss://YWVzLTI1Ni1nY206cGFzcw@ss.example:8388" +
            "?plugin=v2ray-plugin%3Bmode%3Dwebsocket%3Bhost%3Dcdn.example#Plugin"
        val parsed = ProxyParser.parse(uri)!!
        assertEquals("v2ray-plugin", parsed.ssPlugin)
        assertEquals("mode=websocket;host=cdn.example", parsed.ssPluginOptions)
        val reparsed = ProxyParser.parse(ProxyParser.toUri(parsed.copy(rawUri = "")))!!
        assertEquals(parsed.ssPlugin, reparsed.ssPlugin)
        assertEquals(parsed.ssPluginOptions, reparsed.ssPluginOptions)
    }

    @Test
    fun vmessSerializerEscapesJsonStrings() {
        val original = ProxyParser.parse(
            "vmess://" + Base64.getEncoder().encodeToString(
                """{"v":"2","ps":"base","add":"v.example","port":"443","id":"id","net":"ws"}""".toByteArray()
            )
        )!!.copy(name = "quote \" and slash \\", path = "/a\"b", rawUri = "")
        val reparsed = ProxyParser.parse(ProxyParser.toUri(original))!!
        assertEquals(original.name, reparsed.name)
        assertEquals(original.path, reparsed.path)
    }

    @Test
    fun preservesSshPrivateKeyAndWireguardAllowedIps() {
        val ssh = ProxyParser.parse(
            "ssh://root@ssh.example:22?pk=" +
                Base64.getUrlEncoder().withoutPadding().encodeToString("PRIVATE KEY\nline2".toByteArray()) +
                "#SSH"
        )!!.copy(rawUri = "")
        assertEquals(ssh.sshPrivateKey, ProxyParser.parse(ProxyParser.toUri(ssh))!!.sshPrivateKey)

        val wg = ProxyParser.parse(
            "wireguard://private@wg.example:51820?publickey=peer" +
                "&address=10.0.0.2%2F32&allowedips=10.0.0.0%2F8%2C192.168.0.0%2F16#WG"
        )!!.copy(rawUri = "")
        val reparsedWg = ProxyParser.parse(ProxyParser.toUri(wg))!!
        assertEquals(listOf("10.0.0.0/8", "192.168.0.0/16"), reparsedWg.wgAllowedIps)
    }

    @Test
    fun guessesCountryFromRemark() {
        val s = ProxyParser.parse("trojan://pw@jp.example.com:443?security=tls#Japan Fast")!!
        assertEquals("JP", s.countryCode)
        assertNotNull(s.country)
    }
}
