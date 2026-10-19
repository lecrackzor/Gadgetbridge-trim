(function (root, factory) {
    var api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    else root.BinTrim = api;
})(typeof self !== "undefined" ? self : this, function () {
    // Records whose first two bytes are a signed offset from the last timestamp anchor.
    // Everything else that we keep (absolute GPS, laps, strength sets) inherits the current time.
    var OFFSET_TYPES = { 3: 1, 4: 1, 5: 1, 7: 1, 8: 1, 10: 1, 13: 1, 20: 1, 0x1f09: 1 };

    function view(u8) {
        return new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    }

    function i16(payload, at) {
        return view(payload).getInt16(at, true);
    }

    function i32(payload, at) {
        return view(payload).getInt32(at, true);
    }

    function u32(payload, at) {
        return view(payload).getUint32(at, true);
    }

    function parseBin(bytes) {
        var u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
        var dv = view(u8);
        var i = 0;
        var n = u8.length;
        var timestamp = 0;
        var offset = 0;
        var longitude = 0;
        var latitude = 0;
        var builder = {};
        var points = [];
        var records = [];
        var backwards = 0;
        var pauseAt = null;
        var pauses = [];
        var gpsTemplate = null;

        function i16at(o) { return dv.getInt16(o, true); }
        function i32at(o) { return dv.getInt32(o, true); }
        function u32at(o) { return dv.getUint32(o, true); }

        function addPoint() {
            if (!timestamp) return;
            var t = Math.round((timestamp + offset) / 1000) * 1000;
            if (points.length && t < points[points.length - 1].time) {
                backwards += 1;
                return;
            }
            var lat = latitude ? latitude / 3000000 : null;
            var lon = longitude ? longitude / 3000000 : null;
            if (lat != null && !(lat >= -90 && lat <= 90 && lon != null && lon >= -180 && lon <= 180)) {
                lat = null;
                lon = null;
            }
            var point = {
                time: t,
                rawLat: latitude,
                rawLon: longitude,
                lat: lat,
                lon: lon,
                hr: builder.hr || null,
                cadence: builder.cadence || null,
                stride: builder.stride || null,
                pace: builder.pace || null,
                speed: builder.speed || null,
                altitude: builder.altitude == null ? null : builder.altitude,
                distance: builder.distance == null ? null : builder.distance,
                temp: builder.temp == null ? null : builder.temp
            };
            if (points.length && points[points.length - 1].time === t) points[points.length - 1] = point;
            else points.push(point);
        }

        function setOffset(at) {
            addPoint();
            offset = i16at(at);
        }

        function remember(type, chunk, length, offsetBased) {
            records.push({
                type: type,
                time: timestamp + offset,
                payload: u8.slice(chunk, chunk + length),
                offsetBased: offsetBased
            });
        }

        function consumeTag() {
            var first = u8[i++];
            if ((first & 0x1f) !== 0x1f) return first;
            var tag = first;
            while (i < n) {
                var b = u8[i++];
                tag = (tag << 8) | b;
                if ((b & 0x80) === 0) break;
            }
            return tag;
        }

        function consumeLength() {
            var first = u8[i++];
            if ((first & 0x80) === 0) return first;
            var num = first & 0x7f;
            if (!num || num > 4) throw new Error("bad length");
            var value = 0;
            for (var k = 0; k < num; k++) value = (value << 8) | u8[i++];
            return value;
        }

        while (i + 1 < n) {
            var type;
            var length;
            var chunk;
            try {
                type = consumeTag();
                length = consumeLength();
            } catch (err) {
                break;
            }
            if (length < 0 || i + length > n) break;
            chunk = i;
            if (type === 1 && length === 12) {
                addPoint();
                timestamp = Number(dv.getBigUint64(chunk + 4, true));
                offset = 0;
            } else if (type === 2 && (length === 20 || length === 28)) {
                longitude = i32at(chunk + 6);
                latitude = i32at(chunk + 10);
                if (!gpsTemplate) {
                    gpsTemplate = {
                        head: Array.from(u8.subarray(chunk, chunk + 6)),
                        tail: Array.from(u8.subarray(chunk + 14, chunk + length))
                    };
                }
                remember(type, chunk, length, false);
            } else if (type === 3 && (length === 8 || length === 16)) {
                setOffset(chunk);
                if (!(longitude === 0 && latitude === 0)) {
                    longitude += i16at(chunk + 2);
                    latitude += i16at(chunk + 4);
                }
                remember(type, chunk, length, true);
            } else if (type === 4 && length === 4) {
                setOffset(chunk);
                var status = i16at(chunk + 2);
                var now = timestamp + offset;
                if (status === 4) pauseAt = now;
                else if ((status === 5 || status === 6) && pauseAt != null) {
                    pauses.push([pauseAt, now]);
                    pauseAt = null;
                }
                if (status === 6) addPoint();
                remember(type, chunk, length, true);
            } else if (type === 5 && length === 8) {
                setOffset(chunk);
                var cadence = i16at(chunk + 2);
                var stride = i16at(chunk + 4);
                var pace = i16at(chunk + 6);
                builder.cadence = cadence > 0 ? cadence : null;
                builder.stride = stride > 0 ? stride : null;
                builder.pace = pace > 0 ? pace : null;
                builder.speed = pace ? 1000 / pace : null;
                remember(type, chunk, length, true);
            } else if (type === 7 && (length === 6 || length === 7)) {
                setOffset(chunk);
                var rawAlt = i32at(chunk + 2);
                if (length === 7) {
                    if (rawAlt !== -1 && u8[chunk + 6] !== 0xff) builder.altitude = rawAlt / 100000;
                } else if (rawAlt !== -1) {
                    builder.altitude = rawAlt / 100;
                }
                remember(type, chunk, length, true);
            } else if (type === 8 && length === 3) {
                setOffset(chunk);
                var hr = u8[chunk + 2];
                builder.hr = hr ? hr : null;
                remember(type, chunk, length, true);
            } else if (type === 11 && length === 99) {
                remember(type, chunk, length, false);
            } else if (type === 13 && length === 7) {
                setOffset(chunk);
                var temp = dv.getFloat32(chunk + 2, true);
                if (temp > -40 && temp < 60) builder.temp = temp;
                remember(type, chunk, length, true);
            } else if (type === 15 && length === 34) {
                remember(type, chunk, length, false);
            } else if (type === 20 && length === 31) {
                setOffset(chunk);
                remember(type, chunk, length, true);
            } else if (type === 0x1f09 && length === 6) {
                setOffset(chunk);
                builder.distance = u32at(chunk + 2) / 100;
                remember(type, chunk, length, true);
            } else if (OFFSET_TYPES[type] || length >= 2) {
                // Unknown records stay attached to their own offset when they have one,
                // so a trim can keep them instead of dropping the bytes.
                if (length >= 2) setOffset(chunk);
                remember(type, chunk, length, length >= 2);
            }
            i = chunk + length;
        }
        addPoint();
        return {
            points: points,
            records: records,
            pauses: pauses,
            backwards: backwards,
            gpsTemplate: gpsTemplate
        };
    }

    function i16bytes(n) {
        var v = n < 0 ? (n + 65536) : n;
        return [v & 0xff, (v >> 8) & 0xff];
    }

    function i32bytes(n) {
        var v = n | 0;
        return [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >> 24) & 0xff];
    }

    function u32bytes(n) {
        var v = n >>> 0;
        return [v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff];
    }

    function u64bytes(n) {
        var x = BigInt(Math.round(n));
        var b = [];
        for (var k = 0; k < 8; k++) {
            b.push(Number(x & 255n));
            x >>= 8n;
        }
        return b;
    }

    function writeI16(payload, at, n) {
        var bytes = i16bytes(n);
        payload[at] = bytes[0];
        payload[at + 1] = bytes[1];
    }

    function writeU32(payload, at, n) {
        var bytes = u32bytes(n);
        payload[at] = bytes[0];
        payload[at + 1] = bytes[1];
        payload[at + 2] = bytes[2];
        payload[at + 3] = bytes[3];
    }

    function trimBin(records, startMs, endMs, template) {
        var lon = 0;
        var lat = 0;
        var baseCm = 0;
        var kept = [];

        function applyGps(rec) {
            if (rec.type === 2) {
                lon = i32(rec.payload, 6);
                lat = i32(rec.payload, 10);
            } else if (rec.type === 3 && !(lon === 0 && lat === 0)) {
                lon += i16(rec.payload, 2);
                lat += i16(rec.payload, 4);
            }
        }

        for (var i = 0; i < records.length; i++) {
            var rec = records[i];
            var rounded = Math.round(rec.time / 1000) * 1000;
            if (rounded < startMs) {
                applyGps(rec);
                if (rec.type === 0x1f09 && rec.payload.length >= 6) baseCm = u32(rec.payload, 2);
                continue;
            }
            if (rounded > endMs) continue;
            kept.push(rec);
        }
        if (!kept.length) throw new Error("That cut removes the whole workout");

        var out = [];
        function push(bytes) {
            for (var k = 0; k < bytes.length; k++) out.push(bytes[k] & 0xff);
        }
        function tlv(tag, payload) {
            if (payload.length < 128) {
                if (tag < 0x1f) push([tag, payload.length]);
                else push([0x1f, tag & 0xff, payload.length]);
            } else {
                var len = payload.length;
                var size = len < 256 ? 1 : 2;
                if (tag < 0x1f) push([tag, 0x80 | size]);
                else push([0x1f, tag & 0xff, 0x80 | size]);
                if (size === 1) push([len]);
                else push([(len >> 8) & 0xff, len & 0xff]);
            }
            push(payload);
        }
        function timestampRecord(ms) {
            tlv(1, [0x70, 0x73, 0x6d, 0x68].concat(u64bytes(ms)));
        }
        function absoluteGps(msLon, msLat) {
            var head = template && template.head ? template.head.slice() : [0, 0, 0, 0, 0, 0];
            var tail = template && template.tail ? template.tail.slice() : [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
            tlv(2, head.concat(i32bytes(msLon), i32bytes(msLat), tail));
        }

        var anchor = kept[0].time;
        timestampRecord(anchor);
        var seeded = false;
        if (lon !== 0 || lat !== 0) {
            absoluteGps(lon, lat);
            seeded = true;
        }
        var sawStop = false;
        for (var r = 0; r < kept.length; r++) {
            var item = kept[r];
            var off = item.time - anchor;
            if (item.offsetBased && (off > 30000 || off < 0)) {
                anchor = item.time;
                timestampRecord(anchor);
                off = 0;
            }
            var payload = item.payload.slice();
            if (item.offsetBased && payload.length >= 2) writeI16(payload, 0, off);
            if (item.type === 0x1f09 && payload.length >= 6) {
                writeU32(payload, 2, Math.max(0, u32(payload, 2) - baseCm));
            }
            if (item.type === 4 && payload.length >= 4 && i16(payload, 2) === 6) sawStop = true;
            if (item.type === 2) seeded = true;
            tlv(item.type, Array.from(payload));
        }
        if (!sawStop) tlv(4, i16bytes(0).concat(i16bytes(6)));
        return Uint8Array.from(out);
    }

    function pathDistance(points) {
        var total = 0;
        var prev = null;
        for (var i = 0; i < points.length; i++) {
            var p = points[i];
            if (p.lat == null || p.lon == null) continue;
            if (prev) total += haversine(prev.lat, prev.lon, p.lat, p.lon);
            prev = p;
        }
        return total;
    }

    function haversine(lat1, lon1, lat2, lon2) {
        var radius = 6371000;
        var p1 = lat1 * Math.PI / 180;
        var p2 = lat2 * Math.PI / 180;
        var dp = (lat2 - lat1) * Math.PI / 180;
        var dl = (lon2 - lon1) * Math.PI / 180;
        var a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * radius * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    return {
        parseBin: parseBin,
        trimBin: trimBin,
        pathDistance: pathDistance
    };
});
