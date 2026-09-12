/**
 * Karten für das Geoinformation-Modul — OpenStreetMap-Kacheln mit
 * lokalem Offline-Rückfall.
 *
 * Basiskarte: echte OpenStreetMap-Rasterkacheln (https://tile.openstreetmap.org,
 * ODbL — © OpenStreetMap-Mitwirkende). Keine API-Schlüssel, keine proprietären
 * Dienste. Wenn die Kacheln nicht geladen werden können (kein Netz), schaltet
 * die Karte automatisch auf die lokal gebündelten Geodaten zurück:
 *
 *  - "Verwaltungsbereiche" (Standard): die Grenzen der Zuständigkeitsgebiete
 *    mit Flächenfarben, dauerhafte Gebietsnamen, optional die lokalen Adresspunkte.
 *  - "Straßenkarte": echte Straßen-Geometrie (OpenStreetMap/ODbL, lokal als
 *    GeoJSON gebündelt) für die Demo-Stadt Werder (Havel) — Linien, Straßen-
 *    namen und Adresspunkte aus denselben Offline-Daten. Die Gebietsgrenzen
 *    bleiben als dezenter Kontext erhalten.
 *
 * Bei aktiven Kacheln zeichnet die Straßenkarten-Ansicht nur die lokalen
 * Overlays (Adresspunkte, Gebietsgrenzen, Auswahl) — das Straßennetz und die
 * Beschriftung liefern die Kacheln selbst. Die Verwaltungsbereiche werden als
 * halbtransparente Flächen auf die Kacheln gelegt.
 *
 * Die Karte ist reine Visualisierung — Zuständigkeit bestimmt weiterhin der
 * GeoService (Punkt-in-Polygon), niemals die Karte. Marker, Umkreis und
 * Umgebungssuche sind an die Karte gebunden und überleben einen Ansichtswechsel.
 */
(function () {
    "use strict";

    var PALETTE = [
        "#c98a4b", "#4a8f8a", "#b0604f", "#c3a03f", "#7fa06b", "#7f9fb5",
        "#9a86c9", "#c98ab0", "#8fa3ad", "#d4b95c", "#6f9e5a", "#c77f9e"
    ];

    var TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    var TILE_ATTRIBUTION = "&copy; OpenStreetMap-Mitwirkende (ODbL)";
    /** Anzahl fehlgeschlagener Kacheln ohne eine einzige erfolgreiche → Offline-Rückfall. */
    var MAX_TILE_ERRORS = 3;

    var bezirkePromise = null;
    var bezirkeJson = null;
    var streetsPromise = null;
    var streetsJson = null;

    function getBezirke() {
        if (!bezirkePromise) {
            bezirkePromise = fetch('/geoinformation/api/bezirke')
                .then(function (r) { return r.json(); })
                .then(function (g) { bezirkeJson = g; return g; });
        }
        return bezirkePromise;
    }

    function getStreets() {
        if (!streetsPromise) {
            streetsPromise = fetch('/geoinformation/api/streets')
                .then(function (r) { return r.json(); })
                .then(function (g) { streetsJson = g; return g; });
        }
        return streetsPromise;
    }

    function streetKey(a) {
        return (a.street || '').toLowerCase().trim();
    }

    /**
     * Legt die OpenStreetMap-Basiskacheln auf die Karte (einmalig) und
     * richtet die Attributierung ein. Liefert true, solange die Kacheln
     * verfügbar sind; nach mehreren Fehlern ohne eine erfolgreiche Kachel
     * schaltet sie auf den lokalen Offline-Kontext zurück.
     */
    function ensureBaseTiles(map) {
        if (map.__baseTiles) return true;
        if (map.__tilesUnavailable) return false;
        if (!map.attributionControl) {
            L.control.attribution({ prefix: false }).addTo(map);
        }
        var tiles = L.tileLayer(TILE_URL, {
            maxZoom: 19,
            attribution: TILE_ATTRIBUTION
        }).addTo(map);
        map.__baseTiles = tiles;
        var failed = 0;
        var loaded = 0;
        tiles.on('tileload', function () { loaded++; });
        tiles.on('tileerror', function () {
            failed++;
            if (failed >= MAX_TILE_ERRORS && loaded === 0) {
                disableBaseTiles(map);
            }
        });
        return true;
    }

    /** Entfernt die Kacheln und zeichnet den aktuellen Kontext neu (lokale Geodaten). */
    function disableBaseTiles(map) {
        if (map.__baseTiles) {
            map.removeLayer(map.__baseTiles);
            map.__baseTiles = null;
        }
        map.__tilesUnavailable = true;
        var type = map.__geoViewType || 'bezirke';
        setViewType(map, type, { dots: type === 'bezirke' });
    }

    /** Adresspunkte (lokal, deterministisch) — gemeinsames Overlay beider Ansichten. */
    function drawAddressDots(group) {
        var addresses = window.__geoAddresses || [];
        addresses.forEach(function (a) {
            var dot = L.circleMarker([a.lat, a.lon], {
                radius: 3, color: '#5b6770', weight: 1,
                fillColor: '#5b6770', fillOpacity: 0.8
            });
            dot.bindTooltip(a.fullAddress ? a.fullAddress()
                    : a.street + ' ' + (a.houseNumber || ''), { direction: 'top', offset: [0, -4] });
            group.addLayer(dot);
        });
    }

    /** Gebietsgrenzen als dezenter Kontext (mit dauerhaften Namen). */
    function drawDistrictOutline(group, opts, labelOpacity) {
        opts = opts || {};
        getBezirke().then(function (geojson) {
            L.geoJSON(geojson, {
                style: { color: '#8a94a0', weight: 1.2, fillColor: '#9aa3ad', fillOpacity: 0.04 },
                onEachFeature: function (feature, layer) {
                    if (opts.labels !== false) {
                        layer.bindTooltip(feature.properties.name, {
                            permanent: true, direction: 'center',
                            className: 'geo-district-label', opacity: labelOpacity || 0.6
                        });
                    }
                }
            }).addTo(group);
        });
    }

    /** Sammelt alle [lon, lat]-Koordinaten (verschachtelte Polygon-Strukturen) in die Bounds. */
    function collectCoords(coords, bounds) {
        if (!coords) return;
        if (typeof coords[0] === 'number') {
            bounds.extend([coords[1], coords[0]]);
        } else {
            coords.forEach(function (c) { collectCoords(c, bounds); });
        }
    }

    /** Springt bei weiter Ansicht auf den Straßenbestand (nur wenn kein Marker gesetzt ist). */
    function zoomToStreets(map) {
        getStreets().then(function (geojson) {
            if (map.getZoom() >= 12) return;
            if (window.__marker) return;
            var bounds = L.latLngBounds([]);
            (geojson.features || []).forEach(function (f) {
                (f.geometry.coordinates || []).forEach(function (c) {
                    bounds.extend([c[1], c[0]]);
                });
            });
            if (bounds.isValid()) {
                map.fitBounds(bounds, { padding: [30, 30], maxZoom: 14 });
            }
        });
    }

    /**
     * Straßenkarten-Ansicht auf echten Kacheln: Adresspunkte + Gebietsgrenzen
     * als Overlay; Straßennetz und Beschriftung kommen aus den Kacheln.
     */
    function drawStreetsOnTiles(group, map, opts) {
        opts = opts || {};
        drawAddressDots(group);
        drawDistrictOutline(group, opts, 0.6);
        zoomToStreets(map);
    }

    /**
     * Straßenkarte mit ECHTER Straßen-Geometrie (Werder (Havel), OSM/ODbL) —
     * Offline-Rückfall ohne Kacheln: Linien je Straßenklasse (OSM-ähnliche
     * Farben), Straßennamen ab Zoom 13 mit Kollisionsvermeidung, Adresspunkte
     * mit Tooltip und die Verwaltungsgebiets-Grenzen als dezenter Kontext.
     */
    function drawStreets(group, map, opts) {
        opts = opts || {};
        var addresses = window.__geoAddresses || [];

        // 1) Straßenlinien aus der echten Geometrie
        getStreets().then(function (geojson) {
            var styleByType = {
                primary: { color: '#e8944a', weight: 3.2, opacity: 0.9 },
                secondary: { color: '#e8b25e', weight: 2.6, opacity: 0.9 },
                tertiary: { color: '#e8d07a', weight: 2.1, opacity: 0.9 },
                unclassified: { color: '#c6ccd4', weight: 1.6, opacity: 0.85 },
                residential: { color: '#aeb6c2', weight: 1.6, opacity: 0.85 },
                living_street: { color: '#aeb6c2', weight: 1.4, opacity: 0.85 },
                service: { color: '#c9ced6', weight: 1.1, opacity: 0.8 }
            };
            L.geoJSON(geojson, {
                style: function (feature) {
                    return styleByType[feature.properties.highway] || styleByType.residential;
                },
                onEachFeature: function (feature, layer) {
                    var name = feature.properties.name;
                    if (name) {
                        layer.bindTooltip(name, { direction: 'top', offset: [0, -4] });
                    }
                }
            }).addTo(group);

            // 2) Dauerhafte Straßennamen ab Zoom 13 mit Kollisionsvermeidung
            if (opts.labels !== false && map.getZoom() >= 13) {
                var placed = [];
                (geojson.features || []).forEach(function (f) {
                    var name = f.properties && f.properties.name;
                    if (!name) return;
                    var coords = f.geometry.coordinates;
                    if (!coords || coords.length < 2) return;
                    var mid = coords[Math.floor(coords.length / 2)];
                    var point = map.latLngToContainerPoint([mid[1], mid[0]]);
                    var w = Math.round(name.length * 6.2) + 10;
                    var h = 16;
                    var overlaps = placed.some(function (b) {
                        return point.x < b.x + b.w && point.x + w > b.x
                            && point.y < b.y + b.h && point.y + h > b.y;
                    });
                    if (overlaps) return;
                    placed.push({ x: point.x, y: point.y, w: w, h: h });
                    group.addLayer(L.marker([mid[1], mid[0]], {
                        icon: L.divIcon({
                            className: 'geo-street-label',
                            html: '<span>' + name + '</span>',
                            iconSize: null
                        }),
                        interactive: false
                    }));
                });
            }
        });

        // 3) Adresspunkte mit Tooltip
        drawAddressDots(group);

        // 4) Verwaltungsgebiets-Grenzen als dezenter Kontext
        drawDistrictOutline(group, opts, 0.55);

        // 5) Bei weitem Zoom auf den Straßenbestand springen (nur ohne Marker)
        zoomToStreets(map);
    }

    /**
     * Verwaltungsgebiete mit Füllung + Namen (+ optionale Adresspunkte).
     * Die Grenzen sind bewusst kräftig gezeichnet: ein breiter weißer
     * „Casing"-Rand hebt die dunkle Grenzlinie von jedem Kartenhintergrund
     * (auch OSM-Kacheln) klar ab.
     */
    function drawBezirke(group, map, opts, tilesActive) {
        opts = opts || {};
        getBezirke().then(function (geojson) {
            // Beim ersten Laden (Standard-Zoom, keine Auswahl): auf den gesamten
            // Bezirks-Ausschnitt zoomen, damit alle Verwaltungsgebiete sichtbar sind.
            if (opts.fitBounds !== false && map.getContainer().id === 'stadtkarte'
                    && !window.__marker && map.getZoom() >= 11) {
                var bounds = L.latLngBounds([]);
                (geojson.features || []).forEach(function (f) {
                    collectCoords(f.geometry && f.geometry.coordinates, bounds);
                });
                if (bounds.isValid()) {
                    map.fitBounds(bounds, { padding: [24, 24] });
                }
            }
            // 1) Weißer Casing-Rand unter der Grenzlinie
            L.geoJSON(geojson, {
                style: { color: '#ffffff', weight: 7, opacity: 0.9, fillOpacity: 0 }
            }).addTo(group);
            // 2) Grenzlinie + Flächenfüllung + dauerhafte Namen
            L.geoJSON(geojson, {
                style: function (feature) {
                    var idx = parseInt(feature.properties.id, 10) - 1;
                    return {
                        color: '#1f2937', weight: 2.5, opacity: 0.95,
                        fillColor: PALETTE[(idx >= 0 ? idx : 0) % PALETTE.length],
                        fillOpacity: tilesActive ? 0.32 : 0.42
                    };
                },
                onEachFeature: function (feature, layer) {
                    if (opts.labels !== false) {
                        layer.bindTooltip(feature.properties.name, {
                            permanent: true, direction: 'center',
                            className: 'geo-district-label', opacity: 0.95
                        });
                    }
                }
            }).addTo(group);
        });
        if (opts.dots && window.__geoAddresses && window.__geoAddresses.length) {
            var dots = L.layerGroup();
            window.__geoAddresses.forEach(function (a) {
                dots.addLayer(L.circleMarker([a.lat, a.lon], {
                    radius: 2.5, color: '#5b6770', weight: 1,
                    fillColor: '#5b6770', fillOpacity: 0.55
                }));
            });
            dots.addTo(group);
        }
    }

    /** Initialansicht (Standard: Verwaltungsbereiche). */
    function loadContext(map, opts) {
        setViewType(map, 'bezirke', opts || {});
    }

    /**
     * Wechselt die Kartenansicht. Marker, Umkreis und Auswahl bleiben
     * erhalten — nur der Kartenkontext wird ausgetauscht. Die Basiskacheln
     * bleiben liegen; bei Kachel-Ausfall wird automatisch auf die lokale
     * Vektor-Darstellung zurückgeschaltet.
     */
    function setViewType(map, type, opts) {
        opts = opts || {};
        if (map.__geoContext) {
            map.removeLayer(map.__geoContext);
        }
        var group = L.layerGroup().addTo(map);
        map.__geoContext = group;
        map.__geoViewType = type;
        var el = map.getContainer();
        if (el) {
            if (type === 'strassen') el.classList.add('geo-map-strassen');
            else el.classList.remove('geo-map-strassen');
        }
        var tilesActive = ensureBaseTiles(map);
        if (type === 'strassen') {
            if (tilesActive) {
                drawStreetsOnTiles(group, map, opts);
            } else {
                drawStreets(group, map, opts);
            }
        } else {
            drawBezirke(group, map, opts, tilesActive);
        }
    }

    window.geoMap = { loadContext: loadContext, setViewType: setViewType };
})();
