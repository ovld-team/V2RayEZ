package com.v2rayez.app

import com.v2rayez.app.data.tor.BridgeProvider
import com.v2rayez.app.data.tor.TorController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProviderParseTest {

    @Test
    fun moatJsonExtractsObfs4Only() {
        val json = """
            {"settings":[
              {"bridges":{"type":"obfs4","source":"builtin","bridge_strings":[
                "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=abc iat-mode=0",
                "obfs4 1.2.3.4:443 AABBCCDDEEFF00112233445566778899AABBCCDD cert=x iat-mode=0"
              ]}},
              {"bridges":{"type":"snowflake","source":"builtin","bridge_strings":[
                "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://example.com/"
              ]}}
            ]}
        """.trimIndent()
        val obfs4 = BridgeProvider.parseCircumventionJson(json, setOf("obfs4"))
        assertEquals(2, obfs4.size)
        assertTrue(obfs4.all { it.startsWith("obfs4 ") })
        val snow = BridgeProvider.parseCircumventionJson(json, setOf("snowflake"))
        assertEquals(1, snow.size)
        assertTrue(snow.first().startsWith("snowflake "))
    }

    @Test
    fun htmlBrStripProducesCleanLines() {
        val html = """
            <div>obfs4 78.159.118.224:19998 9735DAE37918DD9F0BA9CF56D336294BCB4207CC cert=MIBlPdg69nSskD9Id8bLzwQFJ1zICUMwwG9apMlvF35Y6Z9W8AVbSlahxlY17l8zLvwdEA iat-mode=0 <br/>
            obfs4 103.17.153.52:443 487051F8352D0EB4D0277A4682694AF7A745D344 cert=isMTh9X1bKZy52LBBeuWgG9ZdapqCPbBeA9EhPRaLpgnYJpLQnl/tIV/t0TKVMAlUDomZg iat-mode=0 <br/></div>
        """.trimIndent()
        val lines = BridgeProvider.parseHtmlBridges(html)
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.contains("<") })
        assertTrue(lines.all { TorController.isPlausibleBridgeLine(it) })
    }

    @Test
    fun plausibleRejectsJunk() {
        assertFalse(TorController.isPlausibleBridgeLine("<html>captcha</html>"))
        assertFalse(TorController.isPlausibleBridgeLine("obfs4"))
        assertTrue(
            TorController.isPlausibleBridgeLine(
                "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=abc iat-mode=0"
            )
        )
    }
}
