# -*- coding: utf-8 -*-
"""Builds brandenburg-demo-addresses.json from Overpass address files.

Deterministic: each address is assigned to its Zuständigkeitsgebiet via
point-in-polygon over the bundled administrative boundaries (never from OSM
tags), deduplicated, then sampled deterministically (sorted, every Nth) to
the configured per-city target. Addresses outside all known areas are dropped.
"""
import io
import json
import math
import os
import sys

TARGETS = {
    "Werder (Havel)": 30,
    "Falkensee": 30,
    "Potsdam": 80,
    "Oranienburg": 40,
    "Brandenburg an der Havel": 40,
    "Teltow": 25,
    "Bernau bei Berlin": 25,
    "Königs Wusterhausen": 25,
}

def point_in_polygon(lat, lon, ring):
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        pi, pj = ring[i], ring[j]
        if ((pi[1] > lat) != (pj[1] > lat)) and \
           (lon < (pj[0] - pi[0]) * (lat - pi[1]) / (pj[1] - pi[1]) + pi[0]):
            inside = not inside
        j = i
    return inside

def point_in_rings(lat, lon, rings):
    crossings = 0
    for ring in rings:
        if point_in_polygon(lat, lon, ring):
            crossings += 1
    return crossings % 2 == 1

def load_areas():
    base = os.path.dirname(os.path.abspath(__file__))
    geodata = os.path.join(base, "..", "..", "main", "resources", "geodata")
    d = json.load(io.open(os.path.join(geodata, "brandenburg-verwaltungsgebiete.geojson"), encoding="utf-8"))
    areas = []
    for f in d["features"]:
        g = f["geometry"]
        rings = []
        if g["type"] == "MultiPolygon":
            for poly in g["coordinates"]:
                rings.extend(poly)
        else:
            rings = g["coordinates"]
        areas.append((f["properties"]["name"], f["properties"]["authority"], rings))
    return areas

def load_city(path):
    if not os.path.exists(path):
        return []
    d = json.load(io.open(path, encoding="utf-8"))
    out = []
    for e in d.get("elements", []):
        t = e.get("tags", {})
        street = t.get("addr:street")
        num = t.get("addr:housenumber")
        if not street or not num:
            continue
        if e["type"] == "node":
            lat, lon = e.get("lat"), e.get("lon")
        else:
            c = e.get("center")
            if not c:
                continue
            lat, lon = c.get("lat"), c.get("lon")
        if lat is None or lon is None:
            continue
        out.append({
            "street": street, "houseNumber": num,
            "postalCode": t.get("addr:postcode", ""),
            "city": t.get("addr:city", ""),
            "lat": lat, "lon": lon,
        })
    return out

def main(src_dir, out_path):
    areas = load_areas()
    collected = {}
    for city in TARGETS:
        rows = load_city(os.path.join(src_dir, city + ".json"))
        if not rows:
            print("no data for", city)
            continue
        # dedupe by street+number
        seen = set()
        uniq = []
        for r in rows:
            key = (r["street"].lower(), r["houseNumber"].lower())
            if key in seen:
                continue
            seen.add(key)
            uniq.append(r)
        # deterministic sample: sorted, every Nth
        uniq.sort(key=lambda r: (r["street"].lower(), r["houseNumber"]))
        target = TARGETS[city]
        step = max(1, math.ceil(len(uniq) / target))
        sampled = uniq[::step][:target]
        # assign area via point-in-polygon, keep only inside
        kept = []
        for r in sampled:
            name = None
            authority = None
            for (an, aa, rings) in areas:
                if point_in_rings(r["lat"], r["lon"], rings):
                    name, authority = an, aa
                    break
            if name is None:
                continue
            r["district"] = name
            r["city"] = r["city"] or city
            kept.append(r)
        collected[city] = kept
        print(city, "raw=%d uniq=%d sampled=%d kept=%d" % (len(rows), len(uniq), len(sampled), len(kept)))

    all_rows = []
    for city in collected:
        all_rows.extend(collected[city])
    # deterministic final order: by district, street, number
    def num_key(hn):
        d = ""
        for ch in hn:
            if ch.isdigit():
                d += ch
        return int(d) if d else 0
    all_rows.sort(key=lambda r: (r["district"], r["street"], num_key(r["houseNumber"]), r["houseNumber"]))
    json.dump(all_rows, io.open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("TOTAL addresses:", len(all_rows))

if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "..", "main", "resources",
        "geodata", "brandenburg-demo-addresses.json")
    main(src, out)
