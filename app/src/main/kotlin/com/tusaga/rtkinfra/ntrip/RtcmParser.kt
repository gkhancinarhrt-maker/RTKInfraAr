package com.tusaga.rtkinfra.ntrip

import timber.log.Timber

/**
 * Parses RTCM 3.x binary data stream from NTRIP into discrete messages.
 *
 * RTCM 3.x Frame structure:
 *   Byte 0:         Preamble = 0xD3
 *   Bytes 1-2:      Reserved(6 bits) + Message Length(10 bits)
 *   Bytes 3..N+2:   Message data (N bytes)
 *   Bytes N+3..N+5: 24-bit CRC (Qualcomm CRC-24Q)
 *
 * Total frame = 3 (header) + N (data) + 3 (CRC) bytes
 *
 * Common RTCM message types used by TUSAGA-Aktif:
 *   1001-1004  GPS RTK observables (L1/L2)
 *   1005       Station coordinates (ARP)
 *   1006       Station coordinates + antenna height
 *   1007/1008  Antenna descriptor
 *   1009-1012  GLONASS RTK observables
 *   1033       Receiver/antenna descriptor
 *   1074-1077  GPS MSM4-MSM7 (modern, with L5)
 *   1084-1087  GLONASS MSM4-MSM7
 *   1094-1097  Galileo MSM4-MSM7
 *   1114-1117  BeiDou MSM4-MSM7
 *   1230       GLONASS code-phase biases
 */
class RtcmParser {

    companion object {
        const val PREAMBLE: Byte = 0xD3.toByte()
        const val HEADER_SIZE = 3
        const val CRC_SIZE    = 3
        const val MIN_MSG_LEN = HEADER_SIZE + CRC_SIZE  // Empty message

        // Maximum valid RTCM message data length
        const val MAX_DATA_LEN = 1023
    }

    // Accumulate partial frames across multiple read() calls
    private val accumBuffer = ByteArray(MAX_DATA_LEN + HEADER_SIZE + CRC_SIZE + 64)
    private var accumSize = 0

    data class RtcmMessage(
        val messageType: Int,
        val data: ByteArray,
        val isValid: Boolean    // CRC passed
    )

    /** Callback for successfully parsed messages */
    var onMessageParsed: ((RtcmMessage) -> Unit)? = null

    /**
     * Feed raw bytes from the NTRIP socket into the parser.
     * Emits complete, CRC-validated RTCM messages via [onMessageParsed].
     */
    fun feed(bytes: ByteArray, length: Int) {
        // Append new bytes to accumulation buffer
        if (accumSize + length > accumBuffer.size) {
            // Buffer overflow – reset (shouldn't happen in normal ops)
            Timber.w("RTCM: Buffer overflow, resetting accumulator")
            accumSize = 0
        }
        System.arraycopy(bytes, 0, accumBuffer, accumSize, length)
        accumSize += length

        // Parse all complete frames in the buffer
        var pos = 0
        while (pos < accumSize) {
            // Scan for preamble byte
            if (accumBuffer[pos] != PREAMBLE) {
                pos++
                continue
            }

            // Need at least 3 bytes for header
            if (pos + HEADER_SIZE > accumSize) break

            // Extract message length from bits [6..15] of bytes [1..2]
            val byte1 = accumBuffer[pos + 1].toInt() and 0xFF
            val byte2 = accumBuffer[pos + 2].toInt() and 0xFF
            val msgLen = ((byte1 and 0x03) shl 8) or byte2

            if (msgLen > MAX_DATA_LEN) {
                // Invalid length – skip this preamble byte and search ahead
                pos++
                continue
            }

            val frameLen = HEADER_SIZE + msgLen + CRC_SIZE
            if (pos + frameLen > accumSize) break  // Need more data

            // Extract full frame
            val frame = accumBuffer.copyOfRange(pos, pos + frameLen)

            // Verify CRC-24Q
            val computedCrc = crc24q(frame, HEADER_SIZE + msgLen)
            val frameCrc = ((frame[frameLen - 3].toInt() and 0xFF) shl 16) or
                           ((frame[frameLen - 2].toInt() and 0xFF) shl 8)  or
                            (frame[frameLen - 1].toInt() and 0xFF)

            if (computedCrc != frameCrc) {
                Timber.v("RTCM: CRC mismatch at pos=$pos, skipping")
                pos++
                continue
            }

            // Extract message type from bits [0..11] of data bytes [0..1]
            val msgType = if (msgLen >= 2) {
                val d0 = frame[HEADER_SIZE].toInt() and 0xFF
                val d1 = frame[HEADER_SIZE + 1].toInt() and 0xFF
                (d0 shl 4) or (d1 ushr 4)
            } else 0

            val msgData = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + msgLen)
            val parsed  = RtcmMessage(msgType, msgData, true)

            Timber.v("RTCM: Type=$msgType len=$msgLen")
            onMessageParsed?.invoke(parsed)

            pos += frameLen
        }

        // Shift remaining bytes to front of buffer
        if (pos > 0 && pos < accumSize) {
            System.arraycopy(accumBuffer, pos, accumBuffer, 0, accumSize - pos)
        }
        accumSize = maxOf(0, accumSize - pos)
    }

    /**
     * Qualcomm CRC-24Q algorithm – used by RTCM 3.x for frame integrity.
     * Polynomial: 0x1864CFB
     */
    private fun crc24q(data: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 16)
            repeat(8) {
                crc = crc shl 1
                if (crc and 0x1000000 != 0) crc = crc xor 0x1864CFB
            }
        }
        return crc and 0xFFFFFF
    }

    /**
     * Extracts basic info from RTCM 1005/1006 (Station ARP coordinates).
     * Returns ECEF XYZ in meters.
     */
    fun parseStationType1005(data: ByteArray): Triple<Double, Double, Double>? {
        if (data.size < 19) return null
        return try {
            // RTCM 1005: Station ARP ECEF X/Y/Z
            // Bits 30-67: ECEF-X (38 bits, 0.0001m resolution)
            // Bits 68-105: ECEF-Y
            // Bits 106-143: ECEF-Z
            val bits = BitReader(data)
            bits.skip(12)   // Message type (already parsed)
            bits.skip(12)   // Station ID
            bits.skip(6)    // ITRF year
            bits.skip(1)    // GPS indicator
            bits.skip(1)    // GLONASS indicator
            bits.skip(1)    // Galileo indicator
            bits.skip(1)    // Reference station indicator
            val x = bits.readSigned38() * 0.0001  // meters
            bits.skip(1)    // single/half-cycle indicator
            val y = bits.readSigned38() * 0.0001
            bits.skip(1)
            val z = bits.readSigned38() * 0.0001
            Triple(x, y, z)
        } catch (e: Exception) {
            null
        }
    }
}

/** Simple bit-level reader for RTCM message parsing */
private class BitReader(private val data: ByteArray) {
    private var bitPos = 0

    fun skip(bits: Int) { bitPos += bits }

    fun readUnsigned(bits: Int): Long {
        var result = 0L
        for (i in 0 until bits) {
            val byteIdx = bitPos / 8
            val bitIdx  = 7 - (bitPos % 8)
            if (byteIdx < data.size) {
                result = (result shl 1) or ((data[byteIdx].toInt() ushr bitIdx) and 1).toLong()
            }
            bitPos++
        }
        return result
    }

    fun readSigned38(): Long {
        val raw = readUnsigned(38)
        // Two's complement for 38-bit signed integer
        return if (raw and (1L shl 37) != 0L) raw - (1L shl 38) else raw
    }
}
