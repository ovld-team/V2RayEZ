package com.v2rayez.app.util

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Minimal, dependency-free X25519 (RFC 7748) implementation used to generate a
 * WireGuard/Cloudflare WARP key pair on any API level (JCA's XDH requires API 33,
 * but this project's minSdk is 26). Not constant-time — fine for one-off key gen.
 */
object Curve25519 {

    private val TWO = BigInteger.valueOf(2)
    private val P: BigInteger = TWO.pow(255) - BigInteger.valueOf(19)
    private val A24: BigInteger = BigInteger.valueOf(121665)

    /** Generate a raw 32-byte private key (unclamped, as WireGuard stores it). */
    fun generatePrivateKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Derive the 32-byte public key (little-endian u-coordinate) from a private key. */
    fun publicKey(privateKey: ByteArray): ByteArray {
        val k = clamp(privateKey.copyOf())
        val basePoint = ByteArray(32).also { it[0] = 9 }
        return scalarMult(k, basePoint)
    }

    private fun clamp(k: ByteArray): ByteArray {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return k
    }

    private fun scalarMult(scalar: ByteArray, uBytes: ByteArray): ByteArray {
        val k = decodeLittleEndian(scalar)
        var x1 = decodeUCoordinate(uBytes)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = k.testBit(t).let { if (it) 1 else 0 }
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = (x2 + z2).mod(P)
            val aa = a.modPow(TWO, P)
            val b = (x2 - z2).mod(P)
            val bb = b.modPow(TWO, P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P)
            val cb = (c * b).mod(P)
            x3 = (da + cb).modPow(TWO, P)
            z3 = (x1 * (da - cb).modPow(TWO, P)).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e)).mod(P)
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        val result = (x2 * z2.modPow(P - TWO, P)).mod(P)
        return encodeLittleEndian(result)
    }

    private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        var result = BigInteger.ZERO
        for (i in 0 until 32) {
            result = result or (BigInteger.valueOf((bytes[i].toInt() and 0xff).toLong()) shl (8 * i))
        }
        return result
    }

    private fun decodeUCoordinate(bytes: ByteArray): BigInteger {
        val u = bytes.copyOf(32)
        u[31] = (u[31].toInt() and 127).toByte()
        return decodeLittleEndian(u)
    }

    private fun encodeLittleEndian(n: BigInteger): ByteArray {
        val out = ByteArray(32)
        var v = n.mod(P)
        for (i in 0 until 32) {
            out[i] = (v.and(BigInteger.valueOf(0xff))).toInt().toByte()
            v = v shr 8
        }
        return out
    }
}
