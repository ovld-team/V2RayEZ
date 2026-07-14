package com.v2rayez.app.ui.screens.donate

import com.v2rayez.app.domain.model.CryptoDonation

/**
 * Real, permanent donation destinations shown on the Donate screen.
 * These are production values (not mock data): the full [CryptoDonation.address] is what
 * gets copied to the clipboard, while [CryptoDonation.shortAddress] is the truncated
 * preview shown in the row.
 */
object Donations {

    private fun shorten(address: String): String =
        if (address.length <= 16) address else "${address.take(8)}…${address.takeLast(6)}"

    /** Real coin logos (PNG) from the widely-mirrored CoinCap asset icon CDN. */
    private fun iconFor(ticker: String) = "https://assets.coincap.io/assets/icons/${ticker.lowercase()}@2x.png"

    private fun entry(symbol: String, network: String, address: String, iconTicker: String, emoji: String) =
        CryptoDonation(
            symbol = symbol,
            network = network,
            address = address,
            shortAddress = shorten(address),
            iconUrl = iconFor(iconTicker),
            emoji = emoji
        )

    val list: List<CryptoDonation> = listOf(
        entry("BTC", "Bitcoin (BTC)", "bc1qmqcwg3upptsmyqk8qmeg3wq0ylg355zuqvyca8", "btc", "₿"),
        entry("ETH", "Ethereum (ETH)", "0x5fDFEe61Af72393F2d6528D2649Df7a968d6b82C", "eth", "Ξ"),
        entry("SOL", "Solana (SOL)", "GoxG26rdz5sNMVNgZtvhagnm1FRspGTf3s9brSC7kN1b", "sol", "◎"),
        entry("BNB", "BNB Chain", "0x5fDFEe61Af72393F2d6528D2649Df7a968d6b82C", "bnb", "🟡"),
        entry("TRX", "TRON (TRX)", "TKw7XbqzfqebvkUZahVct77BtuQNUEDj9y", "trx", "🔴"),
        entry("USDT", "USDT · TRC20", "TKw7XbqzfqebvkUZahVct77BtuQNUEDj9y", "usdt", "💵"),
        entry("USDT", "USDT · BEP20", "0x5fDFEe61Af72393F2d6528D2649Df7a968d6b82C", "usdt", "💵"),
        entry("USDT", "USDT · ERC20", "0x5fDFEe61Af72393F2d6528D2649Df7a968d6b82C", "usdt", "💵"),
    )
}
