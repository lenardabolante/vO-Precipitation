#!/usr/bin/env python3
"""Build the World Simulation map assets from Natural Earth 110m (public domain).

    python -I tools/make_world_map.py [--offline] [--cache DIR] [--out DIR]

Downloads three GeoJSON files (once; they are cached in tools/.cache/natural_earth, and a rerun
uses the cache) and writes compact JSON for the Java viewer (viewer/src/precip/WorldMap.java):

    assets/world/land.json       coastlines        {"polygons":[[ring, hole, ...], ...]}
    assets/world/countries.json  countries         {"regions":[{"code":"PH","name":"Philippines",
                                                                 "bbox":[w,s,e,n],"polygons":[...]}]}
    assets/world/states.json     US states         the same, code "US-WA"

A ring is a flat [lon,lat,lon,lat,...] array rounded to 2 decimals, without the repeated closing
point. Region codes are ISO 3166-1 alpha-2 for countries and ISO 3166-2 ("US-XX") for US states:
the codes PROTOCOL.md uses in hello.world_map.regions. The game never needs the network: the
generated files are part of the project.

Python standard library only.
"""
import argparse
import json
import os
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/"
FILES = {
    "land": "ne_110m_land.geojson",
    "countries": "ne_110m_admin_0_countries.geojson",
    "states": "ne_110m_admin_1_states_provinces.geojson",
}
SOURCE = "Natural Earth 110m (naturalearthdata.com), public domain"

# Natural Earth leaves ISO_A2 at -99 for a few countries; ISO_A2_EH covers most of them and this
# table the rest (keyed by ADM0_A3). Territories without an ISO code keep an empty code.
A3_TO_A2 = {"FRA": "FR", "NOR": "NO", "KOS": "XK", "TWN": "TW", "PSX": "PS", "SAH": "EH"}


def here(*parts):
    return os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), *parts))


def fetch(name, cache, offline):
    path = os.path.join(cache, name)
    if os.path.isfile(path) and os.path.getsize(path) > 1000:
        print("  cached     %s (%d bytes)" % (name, os.path.getsize(path)))
    else:
        if offline:
            sys.exit("missing %s and --offline was given" % path)
        os.makedirs(cache, exist_ok=True)
        url = BASE + name
        print("  download   " + url)
        req = urllib.request.Request(url, headers={"User-Agent": "precipitation-make-world-map"})
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
        with open(path + ".part", "wb") as f:
            f.write(data)
        os.replace(path + ".part", path)
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def ring(coords):
    """One GeoJSON linear ring -> flat [lon,lat,...], 2 decimals, duplicates and the closing point dropped."""
    out = []
    last = None
    for p in coords:
        q = (round(float(p[0]), 2), round(float(p[1]), 2))
        if q != last:
            out.append(q)
            last = q
    if len(out) > 1 and out[0] == out[-1]:
        out.pop()
    if len(out) < 3:
        return None
    flat = []
    for lon, lat in out:
        flat.append(lon)
        flat.append(lat)
    return flat


def polygons(geom):
    """A Polygon / MultiPolygon geometry -> [[outer, hole, ...], ...]."""
    if geom is None:
        return []
    if geom["type"] == "Polygon":
        polys = [geom["coordinates"]]
    elif geom["type"] == "MultiPolygon":
        polys = geom["coordinates"]
    else:
        return []
    out = []
    for poly in polys:
        rings = [r for r in (ring(c) for c in poly) if r is not None]
        if rings:
            out.append(rings)
    return out


def bbox(polys):
    xs = [v for p in polys for v in p[0][0::2]]
    ys = [v for p in polys for v in p[0][1::2]]
    return [min(xs), min(ys), max(xs), max(ys)]


def prop(props, *names):
    for n in names:
        for k in (n, n.lower(), n.upper()):
            v = props.get(k)
            if v not in (None, "", "-99", -99):
                return str(v)
    return ""


def country_code(props):
    code = prop(props, "ISO_A2")
    if len(code) != 2:
        code = prop(props, "ISO_A2_EH")
    if len(code) != 2:
        code = A3_TO_A2.get(prop(props, "ADM0_A3"), "")
    return code.upper() if len(code) == 2 else ""


def dump(path, obj):
    text = json.dumps(obj, separators=(",", ":"), ensure_ascii=False)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
        f.write("\n")
    return os.path.getsize(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--cache", default=here(".cache", "natural_earth"))
    ap.add_argument("--out", default=here("..", "assets", "world"))
    ap.add_argument("--offline", action="store_true", help="never download; fail if a file is not cached")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    print("Natural Earth 110m -> " + a.out)

    land = fetch(FILES["land"], a.cache, a.offline)
    land_polys = []
    for f in land["features"]:
        land_polys.extend(polygons(f.get("geometry")))
    n_land = dump(os.path.join(a.out, "land.json"), {"version": 1, "source": SOURCE, "polygons": land_polys})

    countries = fetch(FILES["countries"], a.cache, a.offline)
    regions, uncoded, seen = [], [], set()
    for f in countries["features"]:
        p = f.get("properties", {})
        polys = polygons(f.get("geometry"))
        if not polys:
            continue
        code = country_code(p)
        name = prop(p, "NAME_LONG", "NAME", "ADMIN")
        if code in seen:
            code = ""
        if code:
            seen.add(code)
        else:
            uncoded.append(name)
        regions.append({"code": code, "name": name, "bbox": bbox(polys), "polygons": polys})
    regions.sort(key=lambda r: r["name"])
    n_countries = dump(os.path.join(a.out, "countries.json"), {"version": 1, "source": SOURCE, "regions": regions})

    states = fetch(FILES["states"], a.cache, a.offline)
    sregions = []
    for f in states["features"]:
        p = f.get("properties", {})
        code = prop(p, "iso_3166_2")
        if not code.startswith("US-"):
            continue
        polys = polygons(f.get("geometry"))
        if polys:
            sregions.append({"code": code, "name": prop(p, "name", "gn_name"), "bbox": bbox(polys), "polygons": polys})
    sregions.sort(key=lambda r: r["code"])
    n_states = dump(os.path.join(a.out, "states.json"), {"version": 1, "source": SOURCE, "regions": sregions})

    def points(polys):
        return sum(len(r) // 2 for p in polys for r in p)

    print("  land.json       %7d bytes  %4d polygons  %6d points" % (n_land, len(land_polys), points(land_polys)))
    print("  countries.json  %7d bytes  %4d countries %6d points  (%d with an ISO code)" % (
        n_countries, len(regions), sum(points(r["polygons"]) for r in regions), len(seen)))
    print("  states.json     %7d bytes  %4d US states %6d points" % (n_states, len(sregions), sum(points(r["polygons"]) for r in sregions)))
    print("  total           %7d bytes" % (n_land + n_countries + n_states))
    if uncoded:
        print("  no ISO code (drawn, not clickable by code): " + ", ".join(sorted(uncoded)))
    for want in ("US", "PH", "CN", "RU", "GB", "DE", "FR", "NO"):
        if want not in seen:
            sys.exit("expected country code %s is missing" % want)
    if not any(r["code"] == "US-WA" for r in sregions):
        sys.exit("expected state code US-WA is missing")


if __name__ == "__main__":
    main()
