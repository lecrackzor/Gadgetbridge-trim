(function () {
    var points = [];
    var records = [];
    var gpsTemplate = null;
    var fileName = "workout.bin";
    var startIdx = 0;
    var endIdx = 0;
    var keptLine = null;
    var cutLine = null;
    var startMarker = null;
    var endMarker = null;
    var saving = false;

    var map = L.map("map", { zoomControl: false, attributionControl: true });
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: "&copy; OpenStreetMap"
    }).addTo(map);
    map.setView([20, 0], 2);
    L.control.zoom({ position: "topright" }).addTo(map);

    var fileInput = document.getElementById("file");
    document.getElementById("openBtn").onclick = function () { fileInput.click(); };
    fileInput.onchange = function () {
        var file = fileInput.files && fileInput.files[0];
        if (!file) return;
        fileName = file.name || "workout.bin";
        var reader = new FileReader();
        reader.onload = function () {
            try {
                loadBytes(new Uint8Array(reader.result));
            } catch (err) {
                alert(err.message || String(err));
            }
        };
        reader.readAsArrayBuffer(file);
    };

    document.getElementById("startRange").oninput = function () {
        startIdx = Math.min(Number(this.value), endIdx);
        this.value = String(startIdx);
        draw();
    };
    document.getElementById("endRange").oninput = function () {
        endIdx = Math.max(Number(this.value), startIdx);
        this.value = String(endIdx);
        draw();
    };
    document.getElementById("startTime").onchange = function () { applyTypedTime("start", this.value); };
    document.getElementById("endTime").onchange = function () { applyTypedTime("end", this.value); };
    document.getElementById("saveBtn").onclick = save;

    function decodeBase64(b64) {
        var binary = atob(b64);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes;
    }

    function loadFromAndroid() {
        if (!window.Android || !Android.getBinLength) return false;
        try {
            var total = Number(Android.getBinLength());
            if (!total || total < 0) throw new Error("No raw details for this activity");
            fileName = Android.getFileName ? (Android.getFileName() || fileName) : fileName;
            var bytes = new Uint8Array(total);
            var offset = 0;
            var chunk = 48 * 1024;
            while (offset < total) {
                var len = Math.min(chunk, total - offset);
                var part = decodeBase64(Android.getBinChunk(offset, len));
                bytes.set(part, offset);
                offset += part.length;
            }
            loadBytes(bytes);
            return true;
        } catch (err) {
            document.getElementById("fileName").textContent = "Failed to load";
            document.getElementById("empty").textContent = err.message || String(err);
            document.getElementById("empty").hidden = false;
            document.getElementById("controls").hidden = true;
            return false;
        }
    }

    function loadBytes(bytes) {
        var parsed = BinTrim.parseBin(bytes);
        points = parsed.points;
        records = parsed.records || [];
        gpsTemplate = parsed.gpsTemplate || null;
        if (!points.length) throw new Error("No track points in that file");
        startIdx = 0;
        endIdx = points.length - 1;
        var startRange = document.getElementById("startRange");
        var endRange = document.getElementById("endRange");
        startRange.max = String(endIdx);
        endRange.max = String(endIdx);
        startRange.value = "0";
        endRange.value = String(endIdx);
        document.getElementById("fileName").textContent = fileName;
        document.getElementById("empty").hidden = true;
        document.getElementById("controls").hidden = false;
        draw(true);
    }

    function keptDistanceMeters() {
        var keptPoints = points.slice(startIdx, endIdx + 1);
        if (keptPoints.length && keptPoints[keptPoints.length - 1].distance != null && points[startIdx].distance != null) {
            return Math.max(0, keptPoints[keptPoints.length - 1].distance - (
                startIdx > 0 && points[startIdx - 1].distance != null ? points[startIdx - 1].distance : 0
            ));
        }
        return BinTrim.pathDistance(keptPoints);
    }

    function gpsIndexes() {
        var list = [];
        for (var i = 0; i < points.length; i++) {
            if (points[i].lat != null) list.push(i);
        }
        return list;
    }

    function nearest(latlng) {
        var best = startIdx;
        var bestD = Infinity;
        for (var i = 0; i < points.length; i++) {
            var p = points[i];
            if (p.lat == null) continue;
            var dlat = p.lat - latlng.lat;
            var dlon = p.lon - latlng.lng;
            var d = dlat * dlat + dlon * dlon;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    function handleIcon(fill) {
        return L.divIcon({
            className: "",
            iconSize: [22, 22],
            iconAnchor: [11, 11],
            html: '<div style="width:18px;height:18px;border-radius:50%;background:' + fill + ';border:2px solid #f4f1ea"></div>'
        });
    }

    function line(indexes, color, weight) {
        var latlngs = [];
        for (var i = 0; i < indexes.length; i++) {
            var p = points[indexes[i]];
            if (p.lat != null) latlngs.push([p.lat, p.lon]);
        }
        if (latlngs.length < 2) return null;
        return L.polyline(latlngs, { color: color, weight: weight, opacity: 0.95 }).addTo(map);
    }

    function draw(fit) {
        if (keptLine) map.removeLayer(keptLine);
        if (cutLine) map.removeLayer(cutLine);
        if (startMarker) map.removeLayer(startMarker);
        if (endMarker) map.removeLayer(endMarker);
        keptLine = cutLine = startMarker = endMarker = null;
        if (!points.length) return;

        var kept = [];
        var cut = [];
        for (var i = 0; i < points.length; i++) {
            if (i >= startIdx && i <= endIdx) kept.push(i);
            else cut.push(i);
        }
        cutLine = line(cut, "#b7b1a6", 3);
        keptLine = line(kept, "#0e6b4f", 5);

        var a = points[startIdx];
        var b = points[endIdx];
        if (a.lat == null) a = firstGps(startIdx, 1) || a;
        if (b.lat == null) b = firstGps(endIdx, -1) || b;
        if (a.lat != null) {
            startMarker = L.marker([a.lat, a.lon], { icon: handleIcon("#1c1915"), draggable: true, zIndexOffset: 600 }).addTo(map);
            startMarker.on("dragend", function (ev) {
                startIdx = Math.min(nearest(ev.target.getLatLng()), endIdx);
                document.getElementById("startRange").value = String(startIdx);
                draw();
            });
        }
        if (b.lat != null) {
            endMarker = L.marker([b.lat, b.lon], { icon: handleIcon("#0e6b4f"), draggable: true, zIndexOffset: 700 }).addTo(map);
            endMarker.on("dragend", function (ev) {
                endIdx = Math.max(nearest(ev.target.getLatLng()), startIdx);
                document.getElementById("endRange").value = String(endIdx);
                draw();
            });
        }

        var dist = keptDistanceMeters();
        document.getElementById("keptTime").textContent = duration(points[endIdx].time - points[startIdx].time);
        document.getElementById("keptDist").textContent = (dist / 1000).toFixed(2) + " km";
        var cutStart = points[startIdx].time - points[0].time;
        var cutEnd = points[points.length - 1].time - points[endIdx].time;
        document.getElementById("cutTime").textContent = cutLabel(cutStart, cutEnd);
        setTimeInput("startTime", points[startIdx].time);
        setTimeInput("endTime", points[endIdx].time);

        if (fit) {
            var withGps = gpsIndexes();
            if (withGps.length) {
                var bounds = [];
                for (var g = 0; g < withGps.length; g++) bounds.push([points[withGps[g]].lat, points[withGps[g]].lon]);
                map.fitBounds(bounds, { padding: [24, 24] });
            }
        }
    }

    function firstGps(from, step) {
        for (var i = from; i >= 0 && i < points.length; i += step) {
            if (points[i].lat != null) return points[i];
        }
        return null;
    }

    function localValue(ms) {
        var d = new Date(ms);
        function pad(n) { return String(n).padStart(2, "0"); }
        return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate())
            + "T" + pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
    }

    function setTimeInput(id, ms) {
        var el = document.getElementById(id);
        if (document.activeElement === el) return;
        el.min = localValue(points[0].time);
        el.max = localValue(points[points.length - 1].time);
        el.value = localValue(ms);
    }

    function nearestTime(ms) {
        var best = 0;
        var bestD = Infinity;
        for (var i = 0; i < points.length; i++) {
            var d = Math.abs(points[i].time - ms);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    function applyTypedTime(which, value) {
        var ms = Date.parse(value);
        if (!isFinite(ms) || !points.length) return;
        if (which === "start") {
            startIdx = Math.min(nearestTime(ms), endIdx);
            document.getElementById("startRange").value = String(startIdx);
        } else {
            endIdx = Math.max(nearestTime(ms), startIdx);
            document.getElementById("endRange").value = String(endIdx);
        }
        draw();
    }

    function duration(ms) {
        var s = Math.max(0, Math.round(ms / 1000));
        var h = Math.floor(s / 3600);
        var m = Math.floor((s % 3600) / 60);
        var sec = s % 60;
        if (h) return h + "h " + String(m).padStart(2, "0") + "m";
        if (m) return m + "m " + String(sec).padStart(2, "0") + "s";
        return sec + "s";
    }

    function cutLabel(startMs, endMs) {
        var parts = [];
        if (startMs >= 1000) parts.push(duration(startMs) + " off the start");
        if (endMs >= 1000) parts.push(duration(endMs) + " off the end");
        return parts.length ? parts.join(", ") : "None";
    }

    function save() {
        if (!points.length || saving) return;
        var startMs = points[startIdx].time;
        var endMs = points[endIdx].time;
        var dist = keptDistanceMeters();
        var bytes = BinTrim.trimBin(records, startMs, endMs, gpsTemplate);
        var name = fileName.toLowerCase().endsWith(".bin") ? fileName : fileName + ".bin";
        if (window.Android && Android.beginSave) {
            saving = true;
            document.getElementById("saveBtn").disabled = true;
            document.getElementById("saveBtn").textContent = "Saving…";
            try {
                Android.beginSave(startMs, endMs, dist);
                var chunk = 48 * 1024;
                for (var i = 0; i < bytes.length; i += chunk) {
                    var slice = bytes.subarray(i, Math.min(i + chunk, bytes.length));
                    var binary = "";
                    for (var j = 0; j < slice.length; j++) binary += String.fromCharCode(slice[j]);
                    Android.appendSaveChunk(btoa(binary));
                }
                Android.commitSave();
            } catch (err) {
                saving = false;
                document.getElementById("saveBtn").disabled = false;
                document.getElementById("saveBtn").textContent = "Save trim";
                alert(err.message || String(err));
            }
            return;
        }
        if (window.Android && Android.saveBin) {
            var binary = "";
            var chunk = 0x8000;
            for (var i = 0; i < bytes.length; i += chunk) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
            }
            Android.saveBin(name, btoa(binary));
            return;
        }
        var blob = new Blob([bytes], { type: "application/octet-stream" });
        var a = document.createElement("a");
        a.href = URL.createObjectURL(blob);
        a.download = name;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(function () { URL.revokeObjectURL(a.href); }, 2000);
    }

    if (window.Android && Android.getBinLength) {
        setTimeout(loadFromAndroid, 50);
    } else {
        document.getElementById("openBtn").style.display = "inline-block";
        document.getElementById("fileName").textContent = "No file open";
        document.getElementById("empty").textContent =
            "Open a Gadgetbridge .bin. Drag either end, or type the start and end time.";
    }
})();
