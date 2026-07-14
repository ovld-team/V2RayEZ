package com.v2rayez.app.data.mitm

/**
 * A single MITM domain-fronting rule: traffic whose sniffed TLS SNI matches [domainPattern]
 * is re-established outbound with the fronted SNI [frontSni].
 *
 * @param domainPattern a bare host (`google.com`) or a wildcard (`*.google.com`).
 * @param frontSni the SNI presented on the wire for the outbound TLS leg (the "front").
 * @param dialHost optional EasySNI-style third column: the host whose IP the outbound leg
 *   dials while [frontSni] stays on the wire (Fastly/Tor parity). Empty means "dial the real
 *   host's IP" — the case Google/YouTube use, where only the SNI is swapped. Currently parsed
 *   for parity with the reference `DefaultRules`; the YouTube media path never needs it, so
 *   [MitmConfigBuilder] does not yet redirect on it.
 */
data class FrontRule(
    val domainPattern: String,
    val frontSni: String,
    val dialHost: String = ""
)

/**
 * Parses the free-text [MitmDomainFrontConfig.rulesText][com.v2rayez.app.domain.model.MitmDomainFrontConfig.rulesText]
 * into a list of [FrontRule]. Pure Kotlin (no Android deps) so it is unit-testable on the JVM.
 *
 * Grammar (one rule per line):
 * - Blank lines and lines starting with `#` are ignored.
 * - `domain = front` or `domain -> front` (whitespace around the separator is trimmed).
 * - Optional trailing `= host` (patterniha / EasySNI-style dial host) is captured into
 *   [FrontRule.dialHost]; the front SNI is always the first token after the separator.
 * - `domain` may be a bare host (`x.com`) or a wildcard (`*.example.com`).
 * - Invalid lines are skipped and reported via the optional [onInvalid] callback.
 */
object FrontRuleParser {

    // Prefer the "->" arrow before falling back to "=" so a front containing "=" is unlikely
    // to be mis-split (fronts are hostnames and never contain either separator anyway).
    private val separators = listOf("->", "=")

    private val hostLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")
    private val hostname = Regex("^${hostLabel.pattern}(?:\\.${hostLabel.pattern})+$")
    private val wildcardPattern = Regex("^\\*(?:\\.${hostLabel.pattern})+$")

    /**
     * @param rulesText the raw multi-line rule text.
     * @param onInvalid invoked once per skipped line with its original (untrimmed) content.
     */
    fun parse(rulesText: String, onInvalid: ((line: String) -> Unit)? = null): List<FrontRule> {
        val out = ArrayList<FrontRule>()
        for (rawLine in rulesText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val sep = separators.firstOrNull { line.contains(it) }
            if (sep == null) {
                onInvalid?.invoke(rawLine)
                continue
            }
            val idx = line.indexOf(sep)
            val domain = line.substring(0, idx).trim()
            // Right side: `<front> [= <dialHost>]`. First token is the front SNI; an optional
            // third column is the EasySNI-style dial host (Fastly/Tor parity, ignored for Google).
            val rhs = line.substring(idx + sep.length).trim()
            val rhsSep = separators.firstOrNull { rhs.contains(it) }
            val front: String
            val dialHost: String
            if (rhsSep != null) {
                val rhsIdx = rhs.indexOf(rhsSep)
                front = rhs.substring(0, rhsIdx).trim()
                dialHost = rhs.substring(rhsIdx + rhsSep.length).trim()
            } else {
                front = rhs
                dialHost = ""
            }

            // Front SNI is required and must be a plain host. A malformed dial host is tolerated
            // (dropped) rather than failing the whole rule, since it never affects the SNI swap.
            val cleanDial = dialHost.takeIf { isValidHostname(it) } ?: ""
            if (!isValidDomainPattern(domain) || !isValidHostname(front)) {
                onInvalid?.invoke(rawLine)
                continue
            }
            out.add(FrontRule(domainPattern = domain, frontSni = front, dialHost = cleanDial))
        }
        return out
    }

    /** A rule domain: a bare hostname or a `*.suffix` wildcard. */
    fun isValidDomainPattern(value: String): Boolean =
        value.isNotBlank() && (hostname.matches(value) || wildcardPattern.matches(value))

    /** A front SNI: a plain hostname (never a wildcard). */
    fun isValidHostname(value: String): Boolean =
        value.isNotBlank() && hostname.matches(value)
}
