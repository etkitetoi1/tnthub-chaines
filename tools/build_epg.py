#!/usr/bin/env python3
"""Génère epg.xml.gz : le guide TV des chaînes de channels.json.
Sources : Free TV (API du guide, prioritaire) + XMLTV France (xmltvfr.fr) pour les autres chaînes et les logos.
Le fichier produit est petit (seulement tes chaînes, 36 h) et lisible tel quel par l'appli TNT Hub."""
import gzip, io, json, os, re, sys, time, unicodedata, urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHANNELS = os.environ.get("CHANNELS_JSON", os.path.join(ROOT, "channels.json"))
OUT = os.environ.get("EPG_OUT", os.path.join(ROOT, "epg.xml.gz"))
XMLTV_URLS = [u for u in os.environ.get(
    "XMLTV_URLS", "https://xmltvfr.fr/xmltv/xmltv_fr.xml.gz,https://xmltvfr.fr/xmltv/xmltv.xml.gz").split(",") if u]
FREE_BASE = os.environ.get("FREE_EPG_BASE", "https://api.oqee.net/api/v1/epg/all/")
FREE_HOURS = int(os.environ.get("FREE_HOURS", "36"))
HORIZON = timedelta(hours=int(os.environ.get("HORIZON_HOURS", "36")))
UTC = timezone.utc
UA = {"User-Agent": "Mozilla/5.0 (compatible; TNTHub-EPG)", "Accept": "*/*"}


def norm(s):  # même normalisation que l'appli
    s = unicodedata.normalize("NFD", (s or "").lower())
    return re.sub(r"[^a-z0-9]", "", "".join(c for c in s if unicodedata.category(c) != "Mn"))


def http_open(url, timeout=120):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout)


def parse_t(s):
    if not s:
        return None
    s = s.strip()
    for fmt in ("%Y%m%d%H%M%S %z", "%Y%m%d%H%M%S"):
        try:
            d = datetime.strptime(s, fmt)
            return d.astimezone(UTC) if d.tzinfo else d.replace(tzinfo=UTC)
        except ValueError:
            pass
    return None


# ---------- XMLTV France ----------
def fetch_xmltv(channels, now):
    by_name = {}
    for c in channels:
        for n in [c["name"]] + c.get("epgNames", []):
            by_name[norm(n)] = c["id"]
    end = now + HORIZON
    last_err = None
    for url in XMLTV_URLS:
        try:
            raw = io.BufferedReader(http_open(url))
            stream = gzip.GzipFile(fileobj=raw) if raw.peek(2)[:2] == b"\x1f\x8b" else raw
            xml_to_app, icons, progs = {}, {}, {}
            for _, el in ET.iterparse(stream, events=("end",)):
                if el.tag == "channel":
                    xid = el.get("id", "")
                    names = [d.text or "" for d in el.findall("display-name")]
                    ic = el.find("icon")
                    src = ic.get("src") if ic is not None else None
                    app = next((by_name[norm(n)] for n in names if norm(n) in by_name), None) \
                        or by_name.get(norm(xid.split(".")[0]))
                    if app:
                        xml_to_app[xid] = app
                        if src:
                            icons.setdefault(app, src)
                    el.clear()
                elif el.tag == "programme":
                    app = xml_to_app.get(el.get("channel", ""))
                    if app:
                        s, e = parse_t(el.get("start")), parse_t(el.get("stop"))
                        if s and e and e > now and s < end:
                            progs.setdefault(app, {})[s] = (s, e, el.findtext("title") or "", el.findtext("desc"))
                    el.clear()
            print(f"XMLTV OK ({url}) : {len(progs)} chaînes")
            return progs, icons
        except Exception as ex:
            last_err = ex
            print(f"XMLTV indisponible ({url}) : {ex}")
    print("XMLTV : aucune adresse n'a fonctionné", file=sys.stderr)
    return {}, {}


# ---------- Free TV ----------
def fetch_free(channels, now):
    id_to_app = {}
    for c in channels:
        m = re.search(r"included_channels/(\d+)", c.get("freeUrl") or "")
        if m:
            id_to_app[m.group(1)] = c["id"]
    first = int(now.timestamp()) // 3600 * 3600
    progs, calls_ok, shown = {}, 0, False
    for h in range(FREE_HOURS):
        url = FREE_BASE + str(first + h * 3600)
        data = None
        for attempt in range(3):
            try:
                with http_open(url, 60) as r:
                    data = json.loads(r.read().decode("utf-8"))
                break
            except Exception as ex:
                if h == 0 and attempt == 2:
                    print(f"Free TV indisponible ({url}) : {ex}", file=sys.stderr)
                    return {}
                time.sleep(1.5)
        if data is None:
            continue
        entries = (data.get("result") or {}).get("entries") or {}
        calls_ok += 1
        if not shown:
            shown = True
            keys = list(entries.keys())
            hit = [k for k in keys if str(k) in id_to_app]
            print(f"Free TV : {len(keys)} chaînes dans la réponse, {len(hit)} reconnues sur {len(id_to_app)} attendues")
            if not hit:
                print("  ! Aucun identifiant en commun. Exemple d'identifiants de l'API :", keys[:15], file=sys.stderr)
                return {}
        for key, items in entries.items():
            app = id_to_app.get(str(key))
            if not app:
                continue
            for it in items:
                live = it.get("live") or {}
                s, e = live.get("start"), live.get("end")
                if not s or not e or e <= now.timestamp():
                    continue
                ds, de = datetime.fromtimestamp(s, UTC), datetime.fromtimestamp(e, UTC)
                desc = live.get("short_description") or live.get("description") or None
                progs.setdefault(app, {})[ds] = (ds, de, live.get("title") or "", desc)
        time.sleep(0.2)
    print(f"Free TV OK : {calls_ok}/{FREE_HOURS} heures, {len(progs)} chaînes")
    return progs


def main():
    channels = json.load(open(CHANNELS, encoding="utf-8"))
    now = datetime.now(UTC)
    xml_progs, icons = fetch_xmltv(channels, now)
    free_progs = fetch_free(channels, now)
    merged = dict(xml_progs)
    merged.update({k: v for k, v in free_progs.items() if v})   # Free TV prioritaire
    total = sum(len(v) for v in merged.values())
    if total == 0:
        print("Aucun programme récupéré : le fichier existant est conservé.", file=sys.stderr)
        sys.exit(1)

    tv = ET.Element("tv", {"generator-info-name": "tnthub-epg"})
    fmt = lambda d: d.strftime("%Y%m%d%H%M%S +0000")
    for c in channels:
        if c["id"] not in merged:
            continue
        ch = ET.SubElement(tv, "channel", {"id": c["id"]})
        ET.SubElement(ch, "display-name").text = c["name"]
        if c["id"] in icons:
            ET.SubElement(ch, "icon", {"src": icons[c["id"]]})
    for c in channels:
        for s, e, title, desc in sorted(merged.get(c["id"], {}).values()):
            p = ET.SubElement(tv, "programme", {"start": fmt(s), "stop": fmt(e), "channel": c["id"]})
            ET.SubElement(p, "title", {"lang": "fr"}).text = title
            if desc:
                ET.SubElement(p, "desc", {"lang": "fr"}).text = desc
    tmp = OUT + ".tmp"
    with gzip.GzipFile(tmp, "wb", mtime=0) as gz:
        ET.ElementTree(tv).write(gz, encoding="utf-8", xml_declaration=True)
    os.replace(tmp, OUT)
    print(f"{OUT} : {len(merged)} chaînes, {total} programmes, {os.path.getsize(OUT)//1024} Ko")
    manquantes = [c["name"] for c in channels if c["id"] not in merged]
    print(f"Sans programme ({len(manquantes)}) :", ", ".join(manquantes[:40]), "..." if len(manquantes) > 40 else "")


if __name__ == "__main__":
    main()
