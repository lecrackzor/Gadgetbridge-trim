/*  Copyright (C) 2026

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kotlin port of TrimWorkout's BinTrim (Huami/Zepp TLV sports-details `.bin`).
 */
object HuamiBinTrim {
    private val OFFSET_TYPES = setOf(3, 4, 5, 7, 8, 10, 13, 20, 0x1f09)

    data class GpsTemplate(val head: ByteArray, val tail: ByteArray)

    data class Point(
        val time: Long,
        val lat: Double?,
        val lon: Double?,
        val altitude: Double? = null,
    )

    data class Record(
        val type: Int,
        val time: Long,
        val payload: ByteArray,
        val offsetBased: Boolean,
    )

    data class Parsed(
        val points: List<Point>,
        val records: List<Record>,
        val gpsTemplate: GpsTemplate?,
    )

    fun parseBin(bytes: ByteArray): Parsed {
        val u8 = bytes
        val dv = ByteBuffer.wrap(u8).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        val n = u8.size
        var timestamp = 0L
        var offset = 0
        var longitude = 0
        var latitude = 0
        var altitude: Double? = null
        val points = ArrayList<Point>()
        val records = ArrayList<Record>()
        var gpsTemplate: GpsTemplate? = null

        fun i16at(o: Int): Int = dv.getShort(o).toInt()
        fun i32at(o: Int): Int = dv.getInt(o)

        fun addPoint() {
            if (timestamp == 0L) return
            val t = ((timestamp + offset) / 1000.0).roundToLong() * 1000
            if (points.isNotEmpty() && t < points.last().time) return
            var lat = if (latitude != 0) latitude / 3_000_000.0 else null
            var lon = if (longitude != 0) longitude / 3_000_000.0 else null
            if (lat != null && lon != null) {
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    lat = null
                    lon = null
                }
            } else {
                lat = null
                lon = null
            }
            val point = Point(t, lat, lon, altitude)
            if (points.isNotEmpty() && points.last().time == t) {
                points[points.lastIndex] = point
            } else {
                points.add(point)
            }
        }

        fun setOffset(at: Int) {
            addPoint()
            offset = i16at(at)
        }

        fun remember(type: Int, chunk: Int, length: Int, offsetBased: Boolean) {
            records.add(
                Record(
                    type = type,
                    time = timestamp + offset,
                    payload = u8.copyOfRange(chunk, chunk + length),
                    offsetBased = offsetBased,
                )
            )
        }

        fun consumeTag(): Int {
            val first = u8[i++].toInt() and 0xff
            if ((first and 0x1f) != 0x1f) return first
            var tag = first
            while (i < n) {
                val b = u8[i++].toInt() and 0xff
                tag = (tag shl 8) or b
                if ((b and 0x80) == 0) break
            }
            return tag
        }

        fun consumeLength(): Int {
            val first = u8[i++].toInt() and 0xff
            if ((first and 0x80) == 0) return first
            val num = first and 0x7f
            if (num == 0 || num > 4) throw IllegalArgumentException("bad length")
            var value = 0
            repeat(num) { value = (value shl 8) or (u8[i++].toInt() and 0xff) }
            return value
        }

        while (i + 1 < n) {
            val type: Int
            val length: Int
            try {
                type = consumeTag()
                length = consumeLength()
            } catch (_: Exception) {
                break
            }
            if (length < 0 || i + length > n) break
            val chunk = i
            when {
                type == 1 && length == 12 -> {
                    addPoint()
                    val lo = dv.getInt(chunk + 4).toLong() and 0xffffffffL
                    val hi = dv.getInt(chunk + 8).toLong() and 0xffffffffL
                    timestamp = lo or (hi shl 32)
                    offset = 0
                }
                type == 2 && (length == 20 || length == 28) -> {
                    longitude = i32at(chunk + 6)
                    latitude = i32at(chunk + 10)
                    if (gpsTemplate == null) {
                        gpsTemplate = GpsTemplate(
                            head = u8.copyOfRange(chunk, chunk + 6),
                            tail = u8.copyOfRange(chunk + 14, chunk + length),
                        )
                    }
                    remember(type, chunk, length, false)
                }
                type == 3 && (length == 8 || length == 16) -> {
                    setOffset(chunk)
                    if (!(longitude == 0 && latitude == 0)) {
                        longitude += i16at(chunk + 2)
                        latitude += i16at(chunk + 4)
                    }
                    remember(type, chunk, length, true)
                }
                type == 4 && length == 4 -> {
                    setOffset(chunk)
                    remember(type, chunk, length, true)
                }
                type == 5 && length == 8 -> {
                    setOffset(chunk)
                    remember(type, chunk, length, true)
                }
                type == 7 && (length == 6 || length == 7) -> {
                    setOffset(chunk)
                    val rawAlt = i32at(chunk + 2)
                    altitude = when {
                        length == 7 && rawAlt != -1 && (u8[chunk + 6].toInt() and 0xff) != 0xff ->
                            rawAlt / 100000.0
                        length != 7 && rawAlt != -1 -> rawAlt / 100.0
                        else -> altitude
                    }
                    remember(type, chunk, length, true)
                }
                type == 8 && length == 3 -> {
                    setOffset(chunk)
                    remember(type, chunk, length, true)
                }
                type == 11 && length == 99 -> remember(type, chunk, length, false)
                type == 13 && length == 7 -> {
                    setOffset(chunk)
                    remember(type, chunk, length, true)
                }
                type == 15 && length == 34 -> remember(type, chunk, length, false)
                type == 20 && length == 31 -> {
                    setOffset(chunk)
                    remember(type, chunk, length, true)
                }
                type == 0x1f09 && length == 6 -> {
                    setOffset(chunk)
                    remember(type, chunk, length, true)
                }
                type in OFFSET_TYPES || length >= 2 -> {
                    if (length >= 2) setOffset(chunk)
                    remember(type, chunk, length, length >= 2)
                }
            }
            i = chunk + length
        }
        addPoint()
        return Parsed(points, records, gpsTemplate)
    }

    fun trimBin(records: List<Record>, startMs: Long, endMs: Long, template: GpsTemplate?): ByteArray {
        var lon = 0
        var lat = 0
        var baseCm = 0L
        val kept = ArrayList<Record>()

        fun applyGps(rec: Record) {
            if (rec.type == 2) {
                lon = i32(rec.payload, 6)
                lat = i32(rec.payload, 10)
            } else if (rec.type == 3 && !(lon == 0 && lat == 0)) {
                lon += i16(rec.payload, 2)
                lat += i16(rec.payload, 4)
            }
        }

        for (rec in records) {
            val rounded = (rec.time / 1000.0).roundToLong() * 1000
            if (rounded < startMs) {
                applyGps(rec)
                if (rec.type == 0x1f09 && rec.payload.size >= 6) {
                    baseCm = u32(rec.payload, 2)
                }
                continue
            }
            if (rounded > endMs) continue
            kept.add(rec)
        }
        if (kept.isEmpty()) throw IllegalArgumentException("That cut removes the whole workout")

        val out = ArrayList<Int>()
        fun push(bytes: IntArray) {
            for (b in bytes) out.add(b and 0xff)
        }
        fun push(bytes: ByteArray) {
            for (b in bytes) out.add(b.toInt() and 0xff)
        }

        fun tlv(tag: Int, payload: ByteArray) {
            if (payload.size < 128) {
                if (tag < 0x1f) push(intArrayOf(tag, payload.size))
                else push(intArrayOf(0x1f, tag and 0xff, payload.size))
            } else {
                val len = payload.size
                val size = if (len < 256) 1 else 2
                if (tag < 0x1f) push(intArrayOf(tag, 0x80 or size))
                else push(intArrayOf(0x1f, tag and 0xff, 0x80 or size))
                if (size == 1) push(intArrayOf(len))
                else push(intArrayOf((len shr 8) and 0xff, len and 0xff))
            }
            push(payload)
        }

        fun timestampRecord(ms: Long) {
            val payload = ByteArray(12)
            payload[0] = 0x70
            payload[1] = 0x73
            payload[2] = 0x6d
            payload[3] = 0x68
            val u64 = u64bytes(ms)
            System.arraycopy(u64, 0, payload, 4, 8)
            tlv(1, payload)
        }

        fun absoluteGps(msLon: Int, msLat: Int) {
            val head = template?.head?.copyOf() ?: ByteArray(6)
            val tail = template?.tail?.copyOf() ?: ByteArray(14)
            val payload = ByteArray(head.size + 8 + tail.size)
            System.arraycopy(head, 0, payload, 0, head.size)
            System.arraycopy(i32bytes(msLon), 0, payload, head.size, 4)
            System.arraycopy(i32bytes(msLat), 0, payload, head.size + 4, 4)
            System.arraycopy(tail, 0, payload, head.size + 8, tail.size)
            tlv(2, payload)
        }

        var anchor = kept[0].time
        timestampRecord(anchor)
        if (lon != 0 || lat != 0) {
            absoluteGps(lon, lat)
        }
        var sawStop = false
        for (item in kept) {
            var off = item.time - anchor
            if (item.offsetBased && (off > 30000 || off < 0)) {
                anchor = item.time
                timestampRecord(anchor)
                off = 0
            }
            val payload = item.payload.copyOf()
            if (item.offsetBased && payload.size >= 2) writeI16(payload, 0, off.toInt())
            if (item.type == 0x1f09 && payload.size >= 6) {
                writeU32(payload, 2, maxOf(0L, u32(payload, 2) - baseCm))
            }
            if (item.type == 4 && payload.size >= 4 && i16(payload, 2) == 6) sawStop = true
            tlv(item.type, payload)
        }
        if (!sawStop) {
            tlv(4, i16bytes(0) + i16bytes(6))
        }
        return ByteArray(out.size) { out[it].toByte() }
    }

    fun pathDistance(points: List<Point>): Double {
        var total = 0.0
        var prev: Point? = null
        for (p in points) {
            if (p.lat == null || p.lon == null) continue
            val previous = prev
            if (previous?.lat != null && previous.lon != null) {
                total += haversine(previous.lat, previous.lon, p.lat, p.lon)
            }
            prev = p
        }
        return total
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * radius * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun i16(payload: ByteArray, at: Int): Int =
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getShort(at).toInt()

    private fun i32(payload: ByteArray, at: Int): Int =
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(at)

    private fun u32(payload: ByteArray, at: Int): Long =
        i32(payload, at).toLong() and 0xffffffffL

    private fun i16bytes(n: Int): ByteArray {
        val v = if (n < 0) n + 65536 else n
        return byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
    }

    private fun i32bytes(n: Int): ByteArray {
        val v = n
        return byteArrayOf(
            (v and 0xff).toByte(),
            ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(),
            ((v shr 24) and 0xff).toByte(),
        )
    }

    private fun u32bytes(n: Long): ByteArray {
        val v = n
        return byteArrayOf(
            (v and 0xff).toByte(),
            ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(),
            ((v shr 24) and 0xff).toByte(),
        )
    }

    private fun u64bytes(n: Long): ByteArray {
        var x = n
        val b = ByteArray(8)
        for (k in 0 until 8) {
            b[k] = (x and 0xff).toByte()
            x = x ushr 8
        }
        return b
    }

    private fun writeI16(payload: ByteArray, at: Int, n: Int) {
        val bytes = i16bytes(n)
        payload[at] = bytes[0]
        payload[at + 1] = bytes[1]
    }

    private fun writeU32(payload: ByteArray, at: Int, n: Long) {
        val bytes = u32bytes(n)
        payload[at] = bytes[0]
        payload[at + 1] = bytes[1]
        payload[at + 2] = bytes[2]
        payload[at + 3] = bytes[3]
    }
}
