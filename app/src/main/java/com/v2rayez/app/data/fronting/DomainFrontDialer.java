package com.v2rayez.app.data.fronting;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UAC-style local domain-fronting dialer. Xray dials {@code listenHost:listenPort};
 * this process TCP-connects to a Cloudflare front IP, sends optional fake SNI probes,
 * fragments the real ClientHello, and relays.
 */
public final class DomainFrontDialer {
    private static final String TAG = "DomainFrontDialer";
    private static final int BUFFER_SIZE = 65535;
    private static final int MAX_LOG_LINES = 600;
    private static final int FAILOVER_THRESHOLD = 3;

    public static final String DEFAULT_LISTEN_HOST = "127.0.0.1";
    public static final int DEFAULT_LISTEN_PORT = 40443;
    public static final String DEFAULT_FRONT_ADDRESS = "104.19.229.21";
    public static final String DEFAULT_FALLBACK_ADDRESS = "104.19.230.21";
    public static final String DEFAULT_FAKE_SNI = "www.hcaptcha.com";

    private static final Object LOG_LOCK = new Object();
    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, String> PREFERRED_STRATEGY_BY_ROUTE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> PREFERRED_STRATEGY_TIME_BY_ROUTE = new ConcurrentHashMap<>();

    private static volatile boolean RUNNING = false;
    private static volatile String ACTIVE_TARGET = "";
    private static volatile String ACTIVE_SNI = "";
    private static volatile int ACTIVE_PORT = 443;
    private static volatile String TRAFFIC_SUMMARY = "0 B / 0 B";
    private static volatile String LAST_ERROR = "";

    public interface Listener {
        void onLogLine(String line);
        void onProxyState(boolean running, String targetLabel, String trafficSummary);
    }

    /** Optional VpnService.protect bridge so dialer sockets never loop into TUN. */
    public interface SocketProtector {
        boolean protect(Socket socket);
    }

    public static final class Config {
        public String listenHost = DEFAULT_LISTEN_HOST;
        public int listenPort = DEFAULT_LISTEN_PORT;
        public String frontAddress = DEFAULT_FRONT_ADDRESS;
        public String fallbackAddress = DEFAULT_FALLBACK_ADDRESS;
        public int frontPort = 443;
        public String fakeSni = DEFAULT_FAKE_SNI;
        public String method = "combined";
        public DomainFrontTuning tuning = DomainFrontTuning.balanced();
    }

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicInteger connectionCounter = new AtomicInteger(0);
    private final AtomicLong uploadBytes = new AtomicLong(0);
    private final AtomicLong downloadBytes = new AtomicLong(0);
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final Object targetLock = new Object();

    private ExecutorService ioPool;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int currentFailureCount = 0;
    private volatile SocketProtector socketProtector;

    private String listenHost = DEFAULT_LISTEN_HOST;
    private int listenPort = DEFAULT_LISTEN_PORT;
    private String primaryAddress = DEFAULT_FRONT_ADDRESS;
    private String fallbackAddress = DEFAULT_FALLBACK_ADDRESS;
    private int connectPort = 443;
    private String fakeSni = DEFAULT_FAKE_SNI;
    private String method = "combined";
    private DomainFrontTuning tuning = DomainFrontTuning.balanced();

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
        listener.onProxyState(RUNNING, getActiveTargetLabel(), TRAFFIC_SUMMARY);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean isRunning() { return RUNNING; }
    public static String getTrafficSummary() { return TRAFFIC_SUMMARY; }
    public static String getLastError() { return LAST_ERROR; }

    public static String getActiveTargetLabel() {
        if (!RUNNING || ACTIVE_TARGET.trim().isEmpty()) return "No active dialer";
        return ACTIVE_TARGET + ":" + ACTIVE_PORT + " / " + ACTIVE_SNI;
    }

    public static void clearStrategyCache() {
        PREFERRED_STRATEGY_BY_ROUTE.clear();
        PREFERRED_STRATEGY_TIME_BY_ROUTE.clear();
        emit("TUNING strategy cache cleared.");
    }

    public void setSocketProtector(SocketProtector protector) {
        this.socketProtector = protector;
    }

    public synchronized boolean start(Config config) {
        if (config == null) return false;
        stop();
        listenHost = nonempty(config.listenHost, DEFAULT_LISTEN_HOST);
        listenPort = config.listenPort > 0 && config.listenPort <= 65535 ? config.listenPort : DEFAULT_LISTEN_PORT;
        primaryAddress = nonempty(config.frontAddress, DEFAULT_FRONT_ADDRESS);
        fallbackAddress = config.fallbackAddress == null ? "" : config.fallbackAddress.trim();
        connectPort = config.frontPort > 0 && config.frontPort <= 65535 ? config.frontPort : 443;
        fakeSni = nonempty(config.fakeSni, DEFAULT_FAKE_SNI);
        method = nonempty(config.method, "combined");
        tuning = config.tuning != null ? config.tuning.copy().sanitize() : DomainFrontTuning.balanced();

        if (!active.compareAndSet(false, true)) {
            emit("RUN ignored: dialer already active.");
            return true;
        }
        LAST_ERROR = "";
        synchronized (targetLock) {
            ACTIVE_TARGET = primaryAddress;
            ACTIVE_PORT = connectPort;
            ACTIVE_SNI = fakeSni;
            currentFailureCount = 0;
        }
        uploadBytes.set(0);
        downloadBytes.set(0);
        TRAFFIC_SUMMARY = "0 B / 0 B";
        ioPool = Executors.newCachedThreadPool();
        try {
            serverSocket = openServerSocket();
            emit("READY listening on " + listenHost + ":" + listenPort);
        } catch (IOException e) {
            LAST_ERROR = "Cannot listen on " + listenHost + ":" + listenPort + ": " + e.getMessage();
            emit("START ERROR " + LAST_ERROR);
            stop();
            return false;
        }
        RUNNING = true;
        publishState();
        acceptThread = new Thread(this::acceptLoop, "df-accept");
        acceptThread.start();
        emit("RUN " + listenHost + ":" + listenPort + " -> " + getTarget() + ":" + connectPort
                + " sni=" + fakeSni + " method=" + method + " tuning=" + tuning.summary());
        return true;
    }

    public synchronized void stop() {
        boolean wasActive = active.getAndSet(false);
        boolean wasRunning = RUNNING;
        closeQuietly(serverSocket);
        serverSocket = null;
        for (Socket socket : openSockets) closeQuietly(socket);
        openSockets.clear();
        if (ioPool != null) {
            ioPool.shutdownNow();
            try { ioPool.awaitTermination(800, TimeUnit.MILLISECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ioPool = null;
        }
        if (acceptThread != null) {
            Thread thread = acceptThread;
            thread.interrupt();
            if (Thread.currentThread() != thread) {
                try { thread.join(300); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            acceptThread = null;
        }
        synchronized (targetLock) {
            ACTIVE_TARGET = "";
            ACTIVE_PORT = 443;
            ACTIVE_SNI = "";
            currentFailureCount = 0;
        }
        TRAFFIC_SUMMARY = "0 B / 0 B";
        RUNNING = false;
        publishState();
        if (wasActive || wasRunning) emit("STOP dialer stopped.");
    }

    private ServerSocket openServerSocket() throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(listenHost), listenPort));
        return server;
    }

    private void acceptLoop() {
        try {
            ServerSocket server = serverSocket;
            while (active.get()) {
                if (server == null || server.isClosed()) break;
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);
                openSockets.add(client);
                int id = connectionCounter.incrementAndGet();
                ioPool.execute(() -> handleClient(id, client));
            }
        } catch (SocketException e) {
            if (active.get()) emit("ERROR listener socket closed: " + e.getMessage());
        } catch (IOException e) {
            emit("ERROR cannot listen: " + e.getMessage());
            active.set(false);
            RUNNING = false;
            publishState();
        } finally {
            closeQuietly(serverSocket);
        }
    }

    private void handleClient(int id, Socket client) {
        String connId = String.format(Locale.US, "C%06d", id);
        Socket remote = null;
        String target = getTarget();
        AtomicBoolean serverResponded = new AtomicBoolean(false);
        try {
            emit(connId + " NEW " + client.getRemoteSocketAddress());
            client.setSoTimeout(30000);
            byte[] firstData = readFirstPacket(client.getInputStream());
            client.setSoTimeout(0);
            if (firstData.length == 0) {
                emit(connId + " CLOSE no initial data.");
                return;
            }
            String clientSni = TlsClientHello.findSni(firstData);
            if (clientSni.isEmpty()) {
                emit(connId + " TLS unknown first packet, " + firstData.length + " B");
            } else {
                emit(connId + " TLS real_sni=" + clientSni + " size=" + firstData.length + " B");
            }
            ConnectedAttempt connected = connectWithAdaptiveStrategies(connId, firstData, target);
            if (connected == null && !fallbackAddress.isEmpty() && !fallbackAddress.equals(target)) {
                emit(connId + " FAILOVER probe " + fallbackAddress + ":" + connectPort);
                connected = connectWithAdaptiveStrategies(connId, firstData, fallbackAddress);
            }
            if (connected == null) throw new IOException("no server response after all strategies");
            remote = connected.socket;
            target = connected.target;
            serverResponded.set(true);
            recordSuccess(target);
            client.getOutputStream().write(connected.firstResponse);
            client.getOutputStream().flush();
            downloadBytes.addAndGet(connected.firstResponse.length);
            updateTrafficSummary();
            String spoofNote = connected.fakeProbeUsed ? ", spoof active" : "";
            emit(connId + " SVR_RESP " + connected.firstResponse.length + " B via "
                    + connected.strategyName + spoofNote + ".");
            CountDownLatch firstRelayDone = new CountDownLatch(1);
            Socket finalRemote = remote;
            String finalTarget = target;
            Future<?> up = ioPool.submit(() -> relay(connId, client, finalRemote, false, finalTarget, serverResponded, firstRelayDone));
            Future<?> down = ioPool.submit(() -> relay(connId, finalRemote, client, true, finalTarget, serverResponded, firstRelayDone));
            firstRelayDone.await();
            closeQuietly(client);
            closeQuietly(remote);
            up.cancel(true);
            down.cancel(true);
            if (active.get() && !serverResponded.get()) recordFailure(target, "no server response");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (active.get()) {
                emit(connId + " ERROR " + e.getMessage());
                recordFailure(target, e.getMessage());
            }
        } finally {
            closeQuietly(client);
            closeQuietly(remote);
            openSockets.remove(client);
            if (remote != null) openSockets.remove(remote);
            emit(connId + " CLOSED");
        }
    }

    private ConnectedAttempt connectWithAdaptiveStrategies(String connId, byte[] firstData, String target) {
        List<StrategyAttempt> attempts = buildStrategyAttempts(firstData, target);
        for (StrategyAttempt attempt : attempts) {
            if (!active.get()) return null;
            Socket candidate = null;
            try {
                candidate = openRemote(target);
                if (!active.get()) {
                    closeQuietly(candidate);
                    openSockets.remove(candidate);
                    return null;
                }
                emit(connId + " CONNECT " + target + ":" + connectPort + " strategy=" + attempt.name);
                if (attempt.fakeProbe) {
                    for (int i = 0; i < tuning.fakeProbeCount; i++) {
                        if (!active.get()) break;
                        sendFakeSniProbe(connId, target, i + 1, tuning.fakeProbeCount);
                        if (tuning.fakeProbeDelayMs > 0) sleepQuietly(tuning.fakeProbeDelayMs);
                    }
                }
                writeAttempt(connId, candidate.getOutputStream(), firstData, attempt);
                candidate.setSoTimeout(tuning.routeProbeTimeoutMs);
                byte[] response = readFirstResponse(candidate.getInputStream());
                TlsProbeResult probe = classifyTlsProbeResponse(response);
                if (probe == TlsProbeResult.HANDSHAKE) {
                    candidate.setSoTimeout(0);
                    rememberPreferredStrategy(target, attempt.name);
                    return new ConnectedAttempt(candidate, target, response, attempt.name, attempt.fakeProbe);
                }
                if (probe == TlsProbeResult.ALERT) {
                    emit(connId + " SVR_ALERT " + response.length + " B strategy=" + attempt.name
                            + " (not cached)");
                } else if (response.length > 0) {
                    emit(connId + " BLOCKED non-handshake " + response.length + " B strategy=" + attempt.name);
                } else {
                    emit(connId + " BLOCKED no response strategy=" + attempt.name);
                }
            } catch (IOException e) {
                emit(connId + " TRY_FAIL " + attempt.name + " " + e.getMessage());
            }
            if (candidate != null) {
                closeQuietly(candidate);
                openSockets.remove(candidate);
            }
        }
        if (active.get()) recordFailure(target, "all strategies failed");
        return null;
    }

    /**
     * Accept only a TLS Handshake record (0x16). TLS Alerts (0x15) and other bytes must not
     * count as success or poison the strategy cache — a 7-byte alert is a common false positive.
     */
    public static TlsProbeResult classifyTlsProbeResponse(byte[] response) {
        if (response == null || response.length == 0) return TlsProbeResult.EMPTY;
        int contentType = response[0] & 0xff;
        if (contentType == 0x15) return TlsProbeResult.ALERT;
        // Prefer a plausible handshake record (ServerHello is typically well above alert size).
        if (contentType == 0x16 && response.length >= 40) return TlsProbeResult.HANDSHAKE;
        if (contentType == 0x16) return TlsProbeResult.NON_HANDSHAKE;
        return TlsProbeResult.NON_HANDSHAKE;
    }

    public enum TlsProbeResult {
        EMPTY, ALERT, HANDSHAKE, NON_HANDSHAKE
    }

    private Socket openRemote(String target) throws IOException {
        Socket socket = new Socket();
        openSockets.add(socket);
        try {
            socket.bind(new InetSocketAddress(0));
            SocketProtector protector = socketProtector;
            if (protector == null) {
                throw new IOException("VPN socket protector is not configured");
            }
            if (!protector.protect(socket)) {
                throw new IOException("VpnService.protect failed");
            }
            socket.connect(new InetSocketAddress(target, connectPort), 15000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            openSockets.remove(socket);
            throw e;
        }
    }

    private List<StrategyAttempt> buildStrategyAttempts(byte[] firstData, String target) {
        List<StrategyAttempt> attempts = new ArrayList<>();
        boolean tls = firstData.length > 5 && (firstData[0] & 0xff) == 0x16;
        String normalized = method == null ? "" : method.toLowerCase(Locale.US);
        boolean combined = normalized.contains("combined") || normalized.contains("fake");
        boolean fakeProbe = tuning.fakeProbeEnabled && tuning.fakeProbeCount > 0 && combined;
        if (tls) {
            if (DomainFrontTuning.MODE_FAST.equals(tuning.mode)) {
                attempts.add(new StrategyAttempt("raw", "raw", 0, false));
                attempts.add(new StrategyAttempt("fragment/half", "half", tuning.halfDelayMs, false));
                attempts.add(new StrategyAttempt("combined/tls_record_frag", "tls_record_frag", tuning.tlsRecordDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("combined/sni_split", "sni_split", tuning.sniSplitDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("combined/multi" + tuning.multiFragmentSize, "multi", tuning.multiDelayMs, fakeProbe));
            } else {
                attempts.add(new StrategyAttempt("combined/sni_split", "sni_split", tuning.sniSplitDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("combined/tls_record_frag", "tls_record_frag", tuning.tlsRecordDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("combined/multi" + tuning.multiFragmentSize, "multi", tuning.multiDelayMs, fakeProbe));
                attempts.add(new StrategyAttempt("fragment/half", "half", tuning.halfDelayMs, false));
                attempts.add(new StrategyAttempt("raw", "raw", 0, false));
            }
        } else {
            attempts.add(new StrategyAttempt("raw", "raw", 0, false));
        }
        String preferred = preferredStrategy(target);
        // Stealth never promotes a cached "raw" path — keep fragmentation-first order.
        boolean allowPreferred = preferred != null
                && !(DomainFrontTuning.MODE_STEALTH.equals(tuning.mode) && "raw".equals(preferred));
        if (allowPreferred) {
            for (int i = 0; i < attempts.size(); i++) {
                StrategyAttempt attempt = attempts.get(i);
                if (preferred.equals(attempt.name)) {
                    attempts.remove(i);
                    attempts.add(0, attempt);
                    break;
                }
            }
        }
        return attempts;
    }

    private String preferredStrategy(String target) {
        if (!tuning.strategyCacheEnabled) return null;
        String key = strategyCacheKey(target);
        String preferred = PREFERRED_STRATEGY_BY_ROUTE.get(key);
        Long savedAt = PREFERRED_STRATEGY_TIME_BY_ROUTE.get(key);
        if (preferred == null || savedAt == null) return null;
        if (System.currentTimeMillis() - savedAt > tuning.strategyCacheTtlMs) {
            PREFERRED_STRATEGY_BY_ROUTE.remove(key);
            PREFERRED_STRATEGY_TIME_BY_ROUTE.remove(key);
            return null;
        }
        return preferred;
    }

    private void rememberPreferredStrategy(String target, String strategyName) {
        if (!tuning.strategyCacheEnabled) return;
        String key = strategyCacheKey(target);
        PREFERRED_STRATEGY_BY_ROUTE.put(key, strategyName);
        PREFERRED_STRATEGY_TIME_BY_ROUTE.put(key, System.currentTimeMillis());
    }

    private String strategyCacheKey(String target) {
        return target + ":" + connectPort + "|" + fakeSni + "|" + method;
    }

    private void sendFakeSniProbe(String connId, String target, int index, int total) {
        Socket probe = null;
        try {
            probe = new Socket();
            probe.bind(new InetSocketAddress(0));
            SocketProtector protector = socketProtector;
            if (protector == null) throw new IOException("VPN socket protector is not configured");
            if (!protector.protect(probe)) throw new IOException("VpnService.protect failed");
            probe.connect(new InetSocketAddress(target, connectPort), 800);
            probe.setTcpNoDelay(true);
            byte[] fakeHello = TlsClientHello.buildFakeClientHello(fakeSni);
            probe.getOutputStream().write(fakeHello);
            probe.getOutputStream().flush();
            emit(connId + " FAKE_SNI probe " + index + "/" + total + " " + fakeSni + " " + fakeHello.length + " B");
        } catch (IOException e) {
            emit(connId + " FAKE_SNI probe " + index + "/" + total + " failed: " + e.getMessage());
        } finally {
            closeQuietly(probe);
        }
    }

    private void writeAttempt(String connId, OutputStream outputStream, byte[] firstData, StrategyAttempt attempt) throws IOException {
        byte[][] fragments = TlsClientHello.fragment(firstData, attempt.fragmentStrategy, tuning.multiFragmentSize);
        if (DomainFrontTuning.LOG_MINIMAL.equals(tuning.logLevel)) {
            emit(connId + " FRAGMENT " + attempt.name + " " + fragments.length + " pieces");
        } else {
            emit(connId + " FRAGMENT " + attempt.name + " " + fragments.length + " pieces: " + formatPieces(fragments));
        }
        for (int i = 0; i < fragments.length; i++) {
            outputStream.write(fragments[i]);
            outputStream.flush();
            if (shouldLogFragment(i, fragments.length)) {
                emit(connId + " PKT " + (i + 1) + "/" + fragments.length + " sent " + fragments[i].length + " B");
            }
            if (i < fragments.length - 1 && attempt.delayMs > 0) sleepQuietly(attempt.delayMs);
        }
    }

    private boolean shouldLogFragment(int index, int total) {
        if (DomainFrontTuning.LOG_MINIMAL.equals(tuning.logLevel)) return false;
        if (DomainFrontTuning.LOG_VERBOSE.equals(tuning.logLevel)) return true;
        return total <= 8 || index < 3 || index == total - 1 || ((index + 1) % 8 == 0);
    }

    private byte[] readFirstResponse(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read = inputStream.read(buffer);
        if (read <= 0) return new byte[0];
        // Assemble at least one full TLS record (ServerHello), then briefly drain more
        // handshake records that arrived in the same flight.
        int expected = tlsRecordLength(buffer, read);
        while (expected > read && read < buffer.length) {
            int next = inputStream.read(buffer, read, Math.min(buffer.length - read, expected - read));
            if (next <= 0) break;
            read += next;
        }
        return Arrays.copyOf(buffer, read);
    }

    private String formatPieces(byte[][] fragments) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fragments.length; i++) {
            if (i > 0) out.append(" + ");
            out.append(fragments[i].length).append(" B");
        }
        return out.toString();
    }

    private byte[] readFirstPacket(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read = inputStream.read(buffer);
        if (read <= 0) return new byte[0];
        int expected = tlsRecordLength(buffer, read);
        while (expected > read && read < buffer.length) {
            int next = inputStream.read(buffer, read, Math.min(buffer.length - read, expected - read));
            if (next <= 0) break;
            read += next;
        }
        return Arrays.copyOf(buffer, read);
    }

    private int tlsRecordLength(byte[] data, int read) {
        if (read < 5 || (data[0] & 0xff) != 0x16) return read;
        int length = ((data[3] & 0xff) << 8) | (data[4] & 0xff);
        int total = 5 + length;
        return total > 0 && total <= BUFFER_SIZE ? total : read;
    }

    private void relay(String connId, Socket source, Socket destination, boolean serverToClient,
                       String target, AtomicBoolean serverResponded, CountDownLatch firstRelayDone) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            InputStream input = source.getInputStream();
            OutputStream output = destination.getOutputStream();
            while (active.get()) {
                int read = input.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                output.write(buffer, 0, read);
                output.flush();
                if (serverToClient) {
                    long total = downloadBytes.addAndGet(read);
                    if (serverResponded.compareAndSet(false, true)) {
                        recordSuccess(target);
                        emit(connId + " SVR_RESP " + read + " B (relay).");
                    } else if (total % 16384 < read) {
                        emit(connId + " DOWN " + formatBytes(total));
                    }
                } else {
                    long total = uploadBytes.addAndGet(read);
                    if (total % 16384 < read) emit(connId + " UP " + formatBytes(total));
                }
                updateTrafficSummary();
            }
        } catch (IOException ignored) {
        } finally {
            firstRelayDone.countDown();
        }
    }

    private String getTarget() {
        synchronized (targetLock) { return ACTIVE_TARGET; }
    }

    private void recordFailure(String target, String reason) {
        synchronized (targetLock) {
            if (!target.equals(ACTIVE_TARGET)) return;
            LAST_ERROR = "Front " + target + ":" + connectPort + " failed: " + nonempty(reason, "unknown error");
            currentFailureCount++;
            emit("FAIL " + target + " " + currentFailureCount + "/" + FAILOVER_THRESHOLD + " " + reason);
            if (!fallbackAddress.isEmpty() && !fallbackAddress.equals(ACTIVE_TARGET)
                    && currentFailureCount >= FAILOVER_THRESHOLD) {
                ACTIVE_TARGET = fallbackAddress;
                currentFailureCount = 0;
                emit("FAILOVER switched to " + fallbackAddress + ":" + connectPort);
                publishState();
            }
        }
    }

    private void recordSuccess(String target) {
        synchronized (targetLock) {
            if (target.equals(ACTIVE_TARGET)) {
                currentFailureCount = 0;
                LAST_ERROR = "";
            }
        }
    }

    private void updateTrafficSummary() {
        TRAFFIC_SUMMARY = formatBytes(uploadBytes.get()) + " / " + formatBytes(downloadBytes.get());
        publishState();
    }

    private static void emit(String message) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + message;
        Log.i(TAG, line);
        synchronized (LOG_LOCK) {
            LOGS.addLast(line);
            while (LOGS.size() > MAX_LOG_LINES) LOGS.removeFirst();
        }
        for (Listener listener : LISTENERS) listener.onLogLine(line);
    }

    private static void publishState() {
        for (Listener listener : LISTENERS) {
            listener.onProxyState(RUNNING, getActiveTargetLabel(), TRAFFIC_SUMMARY);
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0);
    }

    private static String nonempty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static final class StrategyAttempt {
        final String name;
        final String fragmentStrategy;
        final int delayMs;
        final boolean fakeProbe;
        StrategyAttempt(String name, String fragmentStrategy, int delayMs, boolean fakeProbe) {
            this.name = name;
            this.fragmentStrategy = fragmentStrategy;
            this.delayMs = delayMs;
            this.fakeProbe = fakeProbe;
        }
    }

    private static final class ConnectedAttempt {
        final Socket socket;
        final String target;
        final byte[] firstResponse;
        final String strategyName;
        final boolean fakeProbeUsed;
        ConnectedAttempt(Socket socket, String target, byte[] firstResponse, String strategyName,
                         boolean fakeProbeUsed) {
            this.socket = socket;
            this.target = target;
            this.firstResponse = firstResponse;
            this.strategyName = strategyName;
            this.fakeProbeUsed = fakeProbeUsed;
        }
    }
}
