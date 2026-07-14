package com.v2rayez.app.data.fronting;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TlsClientHello {
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String findSni(byte[] data) {
        SniLocation location = locateSni(data);
        if (location == null) {
            return "";
        }
        return new String(data, location.offset, location.length, StandardCharsets.US_ASCII);
    }

    public static byte[][] fragment(byte[] data, String strategy, int multiFragmentSize) {
        if (data.length < 2 || "raw".equals(strategy)) {
            return new byte[][]{data};
        }
        if ("half".equals(strategy)) {
            return fragmentHalf(data);
        }
        if ("multi".equals(strategy)) {
            return fragmentMulti(data, multiFragmentSize);
        }
        if ("tls_record_frag".equals(strategy)) {
            return tlsRecordFragment(data);
        }
        return fragmentAtSni(data);
    }

    public static byte[][] fragmentAtSni(byte[] data) {
        if (data.length < 2) {
            return new byte[][]{data};
        }
        SniLocation location = locateSni(data);
        int split = data.length / 2;
        if (location != null) {
            split = location.offset + Math.max(1, location.length / 2);
        }
        split = Math.max(1, Math.min(split, data.length - 1));
        return new byte[][]{
                Arrays.copyOfRange(data, 0, split),
                Arrays.copyOfRange(data, split, data.length)
        };
    }

    public static byte[][] fragmentHalf(byte[] data) {
        int split = Math.max(1, Math.min(data.length / 2, data.length - 1));
        return new byte[][]{
                Arrays.copyOfRange(data, 0, split),
                Arrays.copyOfRange(data, split, data.length)
        };
    }

    public static byte[][] fragmentMulti(byte[] data, int chunkSize) {
        List<byte[]> parts = new ArrayList<>();
        for (int offset = 0; offset < data.length; offset += chunkSize) {
            int end = Math.min(offset + chunkSize, data.length);
            parts.add(Arrays.copyOfRange(data, offset, end));
        }
        return parts.toArray(new byte[parts.size()][]);
    }

    private static byte[][] tlsRecordFragment(byte[] data) {
        if (data.length < 6 || unsigned(data[0]) != 0x16) {
            return new byte[][]{data};
        }
        byte[] version = Arrays.copyOfRange(data, 1, 3);
        byte[] handshake = Arrays.copyOfRange(data, 5, data.length);
        int split = Math.max(1, Math.min(handshake.length / 2, handshake.length - 1));
        return new byte[][]{
                tlsRecord(version, Arrays.copyOfRange(handshake, 0, split)),
                tlsRecord(version, Arrays.copyOfRange(handshake, split, handshake.length))
        };
    }

    public static byte[] buildFakeClientHello(String sni) {
        String host = sni == null || sni.trim().isEmpty() ? "www.hcaptcha.com" : sni.trim();
        byte[] random = new byte[32];
        byte[] sessionId = new byte[32];
        byte[] keyShare = new byte[32];
        RANDOM.nextBytes(random);
        RANDOM.nextBytes(sessionId);
        RANDOM.nextBytes(keyShare);

        byte[] extensions = concat(
                sniExtension(host),
                hex("000a00080006001d00170018"),
                hex("000b00020100"),
                hex("000d00120010040305030603080708080809080a080b"),
                hex("002b00050403040303"),
                keyShareExtension(keyShare),
                hex("0010000e000c02683208687474702f312e31")
        );
        byte[] padding = paddingExtension(517, 5 + 4 + 2 + 32 + 1 + sessionId.length
                + cipherSuites().length + 2 + extensions.length);
        if (padding.length > 0) {
            extensions = concat(extensions, padding);
        }

        byte[] body = concat(
                new byte[]{0x03, 0x03},
                random,
                concat(new byte[]{(byte) sessionId.length}, sessionId),
                cipherSuites(),
                new byte[]{0x01, 0x00},
                u16Bytes(extensions.length),
                extensions
        );
        byte[] handshake = concat(new byte[]{
                0x01,
                (byte) ((body.length >> 16) & 0xff),
                (byte) ((body.length >> 8) & 0xff),
                (byte) (body.length & 0xff)
        }, body);
        return tlsRecord(new byte[]{0x03, 0x01}, handshake);
    }

    private static byte[] tlsRecord(byte[] version, byte[] payload) {
        return concat(new byte[]{
                0x16,
                version[0],
                version[1],
                (byte) ((payload.length >> 8) & 0xff),
                (byte) (payload.length & 0xff)
        }, payload);
    }

    private static byte[] sniExtension(String sni) {
        byte[] sniBytes = sni.getBytes(StandardCharsets.US_ASCII);
        byte[] entry = concat(new byte[]{
                0x00,
                (byte) ((sniBytes.length >> 8) & 0xff),
                (byte) (sniBytes.length & 0xff)
        }, sniBytes);
        byte[] list = concat(u16Bytes(entry.length), entry);
        return concat(new byte[]{0x00, 0x00}, u16Bytes(list.length), list);
    }

    private static byte[] keyShareExtension(byte[] publicKey) {
        byte[] entry = concat(new byte[]{0x00, 0x1d, 0x00, 0x20}, publicKey);
        byte[] list = concat(u16Bytes(entry.length), entry);
        return concat(new byte[]{0x00, 0x33}, u16Bytes(list.length), list);
    }

    private static byte[] paddingExtension(int targetLength, int currentLength) {
        int needed = targetLength - currentLength - 4;
        if (needed <= 0) {
            return new byte[0];
        }
        return concat(new byte[]{0x00, 0x15}, u16Bytes(needed), new byte[needed]);
    }

    private static byte[] cipherSuites() {
        byte[] suites = hex("130213031301c02cc030c02bc02fcca9cca8c024c028c023c027009f009e006b006700ff");
        return concat(u16Bytes(suites.length), suites);
    }

    private static byte[] u16Bytes(int value) {
        return new byte[]{(byte) ((value >> 8) & 0xff), (byte) (value & 0xff)};
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, out, offset, array.length);
            offset += array.length;
        }
        return out;
    }

    private static byte[] hex(String value) {
        int length = value.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return out;
    }

    private static SniLocation locateSni(byte[] data) {
        if (data.length < 5 || unsigned(data[0]) != 0x16) {
            return null;
        }

        int pos = 5;
        if (pos + 4 > data.length || unsigned(data[pos]) != 0x01) {
            return null;
        }
        pos += 4;

        if (pos + 34 > data.length) {
            return null;
        }
        pos += 2;
        pos += 32;

        if (pos >= data.length) {
            return null;
        }
        int sessionIdLength = unsigned(data[pos]);
        pos += 1 + sessionIdLength;

        if (pos + 2 > data.length) {
            return null;
        }
        int cipherSuiteLength = u16(data, pos);
        pos += 2 + cipherSuiteLength;

        if (pos >= data.length) {
            return null;
        }
        int compressionLength = unsigned(data[pos]);
        pos += 1 + compressionLength;

        if (pos + 2 > data.length) {
            return null;
        }
        int extensionLength = u16(data, pos);
        pos += 2;
        int extensionEnd = Math.min(pos + extensionLength, data.length);

        while (pos + 4 <= extensionEnd) {
            int extensionType = u16(data, pos);
            int extensionDataLength = u16(data, pos + 2);
            pos += 4;
            if (pos + extensionDataLength > extensionEnd) {
                return null;
            }

            if (extensionType == 0x0000) {
                SniLocation location = parseServerNameExtension(data, pos, extensionDataLength);
                if (location != null) {
                    return location;
                }
            }
            pos += extensionDataLength;
        }
        return null;
    }

    private static SniLocation parseServerNameExtension(byte[] data, int offset, int length) {
        if (length < 5 || offset + length > data.length) {
            return null;
        }
        int listLength = u16(data, offset);
        int pos = offset + 2;
        int end = Math.min(pos + listLength, offset + length);
        while (pos + 3 <= end) {
            int nameType = unsigned(data[pos]);
            int nameLength = u16(data, pos + 1);
            pos += 3;
            if (nameLength <= 0 || pos + nameLength > end) {
                return null;
            }
            if (nameType == 0) {
                return new SniLocation(pos, nameLength);
            }
            pos += nameLength;
        }
        return null;
    }

    private static int u16(byte[] data, int offset) {
        return (unsigned(data[offset]) << 8) | unsigned(data[offset + 1]);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static final class SniLocation {
        final int offset;
        final int length;

        SniLocation(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }
}

