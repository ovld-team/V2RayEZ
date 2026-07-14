package com.v2rayez.app.data.fronting;

import java.util.Locale;

public final class DomainFrontTuning {
    public static final String MODE_FAST = "fast";
    public static final String MODE_BALANCED = "balanced";
    public static final String MODE_STEALTH = "stealth";
    public static final String MODE_CUSTOM = "custom";

    public static final String LOG_MINIMAL = "minimal";
    public static final String LOG_NORMAL = "normal";
    public static final String LOG_VERBOSE = "verbose";

    public static final int DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000;

    public String mode = MODE_BALANCED;
    public boolean fakeProbeEnabled = true;
    public int fakeProbeCount = 1;
    public int fakeProbeDelayMs = 50;
    public int multiFragmentSize = 96;
    public int sniSplitDelayMs = 45;
    public int tlsRecordDelayMs = 35;
    public int multiDelayMs = 3;
    public int halfDelayMs = 35;
    public int routeProbeTimeoutMs = 2800;
    public boolean strategyCacheEnabled = true;
    public int strategyCacheTtlMs = DEFAULT_CACHE_TTL_MS;
    public String logLevel = LOG_NORMAL;

    public static DomainFrontTuning balanced() {
        return new DomainFrontTuning().sanitize();
    }

    public static DomainFrontTuning fast() {
        DomainFrontTuning tuning = new DomainFrontTuning();
        tuning.mode = MODE_FAST;
        tuning.fakeProbeEnabled = false;
        tuning.fakeProbeCount = 0;
        tuning.fakeProbeDelayMs = 0;
        tuning.multiFragmentSize = 256;
        tuning.sniSplitDelayMs = 0;
        tuning.tlsRecordDelayMs = 0;
        tuning.multiDelayMs = 0;
        tuning.halfDelayMs = 0;
        tuning.routeProbeTimeoutMs = 1200;
        tuning.logLevel = LOG_MINIMAL;
        return tuning.sanitize();
    }

    public static DomainFrontTuning stealth() {
        DomainFrontTuning tuning = new DomainFrontTuning();
        tuning.mode = MODE_STEALTH;
        tuning.fakeProbeEnabled = true;
        tuning.fakeProbeCount = 2;
        tuning.fakeProbeDelayMs = 75;
        tuning.multiFragmentSize = 64;
        tuning.sniSplitDelayMs = 60;
        tuning.tlsRecordDelayMs = 50;
        tuning.multiDelayMs = 8;
        tuning.halfDelayMs = 50;
        tuning.routeProbeTimeoutMs = 3200;
        tuning.logLevel = LOG_NORMAL;
        return tuning.sanitize();
    }

    public static DomainFrontTuning preset(String mode) {
        String normalized = normalize(mode);
        if (MODE_FAST.equals(normalized)) {
            return fast();
        }
        if (MODE_STEALTH.equals(normalized)) {
            return stealth();
        }
        return balanced();
    }

    public DomainFrontTuning copy() {
        DomainFrontTuning out = new DomainFrontTuning();
        out.mode = mode;
        out.fakeProbeEnabled = fakeProbeEnabled;
        out.fakeProbeCount = fakeProbeCount;
        out.fakeProbeDelayMs = fakeProbeDelayMs;
        out.multiFragmentSize = multiFragmentSize;
        out.sniSplitDelayMs = sniSplitDelayMs;
        out.tlsRecordDelayMs = tlsRecordDelayMs;
        out.multiDelayMs = multiDelayMs;
        out.halfDelayMs = halfDelayMs;
        out.routeProbeTimeoutMs = routeProbeTimeoutMs;
        out.strategyCacheEnabled = strategyCacheEnabled;
        out.strategyCacheTtlMs = strategyCacheTtlMs;
        out.logLevel = logLevel;
        return out;
    }

    public DomainFrontTuning sanitize() {
        mode = normalize(mode);
        logLevel = normalizeLog(logLevel);
        fakeProbeCount = clamp(fakeProbeEnabled ? fakeProbeCount : 0, 0, 3);
        fakeProbeDelayMs = clamp(fakeProbeDelayMs, 0, 150);
        multiFragmentSize = clampToAllowed(multiFragmentSize, new int[]{64, 96, 128, 192, 256}, 96);
        sniSplitDelayMs = clamp(sniSplitDelayMs, 0, 100);
        tlsRecordDelayMs = clamp(tlsRecordDelayMs, 0, 100);
        multiDelayMs = clamp(multiDelayMs, 0, 25);
        halfDelayMs = clamp(halfDelayMs, 0, 100);
        routeProbeTimeoutMs = clamp(routeProbeTimeoutMs, 1000, 4000);
        strategyCacheTtlMs = clamp(strategyCacheTtlMs, 60 * 1000, 60 * 60 * 1000);
        return this;
    }

    public String summary() {
        return String.format(Locale.US, "%s, fake=%s/%d, multi=%d, timeout=%dms, log=%s",
                mode, fakeProbeEnabled ? "on" : "off", fakeProbeCount, multiFragmentSize,
                routeProbeTimeoutMs, logLevel);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampToAllowed(int value, int[] allowed, int fallback) {
        for (int option : allowed) {
            if (option == value) {
                return value;
            }
        }
        return fallback;
    }

    private static String normalize(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (MODE_FAST.equals(mode) || MODE_STEALTH.equals(mode) || MODE_CUSTOM.equals(mode)) {
            return mode;
        }
        return MODE_BALANCED;
    }

    private static String normalizeLog(String value) {
        String level = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (LOG_MINIMAL.equals(level) || LOG_VERBOSE.equals(level)) {
            return level;
        }
        return LOG_NORMAL;
    }
}
