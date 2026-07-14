package com.v2rayez.app.data.parser

/** Best-effort mapping of a server remark to an ISO country code + display name. */
object CountryGuesser {

    private val table: List<Triple<String, String, String>> = listOf(
        Triple("japan", "JP", "Japan"), Triple("tokyo", "JP", "Japan"), Triple("🇯🇵", "JP", "Japan"),
        Triple("united states", "US", "United States"), Triple("usa", "US", "United States"),
        Triple("new york", "US", "United States"), Triple("🇺🇸", "US", "United States"),
        Triple("germany", "DE", "Germany"), Triple("frankfurt", "DE", "Germany"), Triple("🇩🇪", "DE", "Germany"),
        Triple("singapore", "SG", "Singapore"), Triple("🇸🇬", "SG", "Singapore"),
        Triple("france", "FR", "France"), Triple("paris", "FR", "France"), Triple("🇫🇷", "FR", "France"),
        Triple("united kingdom", "GB", "United Kingdom"), Triple("london", "GB", "United Kingdom"), Triple("🇬🇧", "GB", "United Kingdom"),
        Triple("canada", "CA", "Canada"), Triple("toronto", "CA", "Canada"), Triple("🇨🇦", "CA", "Canada"),
        Triple("netherlands", "NL", "Netherlands"), Triple("🇳🇱", "NL", "Netherlands"),
        Triple("hong kong", "HK", "Hong Kong"), Triple("🇭🇰", "HK", "Hong Kong"),
        Triple("korea", "KR", "South Korea"), Triple("🇰🇷", "KR", "South Korea"),
        Triple("india", "IN", "India"), Triple("🇮🇳", "IN", "India"),
        Triple("australia", "AU", "Australia"), Triple("🇦🇺", "AU", "Australia"),
        Triple("russia", "RU", "Russia"), Triple("🇷🇺", "RU", "Russia"),
        Triple("turkey", "TR", "Turkey"), Triple("🇹🇷", "TR", "Turkey"),
        Triple("iran", "IR", "Iran"), Triple("🇮🇷", "IR", "Iran"),
        Triple("china", "CN", "China"), Triple("🇨🇳", "CN", "China"),
        Triple("brazil", "BR", "Brazil"), Triple("🇧🇷", "BR", "Brazil"),
        Triple("dubai", "AE", "UAE"), Triple("emirates", "AE", "UAE")
    )

    /** @return (countryCode, countryName). Defaults to ("UN", ""). */
    fun guess(name: String): Pair<String, String> {
        val lower = name.lowercase()
        for ((needle, code, display) in table) {
            if (lower.contains(needle)) return code to display
        }
        return "UN" to ""
    }
}
