#!/usr/bin/env python3
"""Génère epg.xml.gz : le guide TV des chaînes de channels.json.
Sources : Free TV (API du guide, prioritaire) + XMLTV France (xmltvfr.fr) pour les autres chaînes et les logos.
Le fichier produit est petit (seulement tes chaînes, 36 h) et lisible tel quel par l'appli TNT Hub."""
import bisect, concurrent.futures, difflib, gzip, io, json, os, re, sys, time, unicodedata, urllib.request
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


def http_open(url, timeout=60):
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
    t0 = time.time()
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
                            pic = el.find("icon")
                            progs.setdefault(app, {})[s] = (s, e, el.findtext("title") or "", el.findtext("desc"),
                                                            pic.get("src") if pic is not None else None)
                    el.clear()
            print(f"XMLTV OK ({url}) : {len(progs)} chaînes ({time.time()-t0:.0f} s)")
            return progs, icons
        except Exception as ex:
            last_err = ex
            print(f"XMLTV indisponible ({url}) : {ex}")
    print("XMLTV : aucune adresse n'a fonctionné", file=sys.stderr)
    return {}, {}


# ---------- Free TV ----------
def picture_url(raw):
    """Les images Free TV contiennent la hauteur voulue (h%d ou h1) : on demande 320 px."""
    if not raw:
        return None
    return re.sub(r"/h1$", "/h320", raw.replace("%d", "320"))


def fetch_free(channels, now):
    t0 = time.time()
    id_to_app = {}
    for c in
