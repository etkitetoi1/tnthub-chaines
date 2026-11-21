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
    for c in channels:
        m = re.search(r"included_channels/(\d+)", c.get("freeUrl") or "")
        if m:
            id_to_app[m.group(1)] = c["id"]
        if c.get("freeEpgId"):                      # identifiant du guide imposé à la main (facultatif)
            id_to_app[str(c["freeEpgId"])] = c["id"]
    first = int(now.timestamp()) // 3600 * 3600
    progs, all_entries = {}, {}                     # all_entries : toutes les chaînes du guide Free TV, même hors offre gratuite

    def load_hour(h):
        url = FREE_BASE + str(first + h * 3600)
        last = None
        for _ in range(3):
            try:
                with http_open(url, 60) as r:
                    return json.loads(r.read().decode("utf-8"))
            except Exception as ex:
                last = ex
                time.sleep(1.5)
        if h == 0:
            print(f"Free TV indisponible ({url}) : {last}", file=sys.stderr)
        return None

    data0 = load_hour(0)
    if data0 is None:
        return {}, {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:     # les autres heures en parallèle
        datas = [data0] + list(pool.map(load_hour, range(1, FREE_HOURS)))

    calls_ok, shown = 0, False
    for data in datas:
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
                return {}, {}
        for key, items in entries.items():
            app = id_to_app.get(str(key))
            for it in items:
                live = it.get("live") or {}
                pics = it.get("pictures") or {}
                img = picture_url(pics.get("preview") or pics.get("main"))
                s, e = live.get("start"), live.get("end")
                if not s or not e or e <= now.timestamp():
                    continue
                ds, de = datetime.fromtimestamp(s, UTC), datetime.fromtimestamp(e, UTC)
                desc = live.get("short_description") or live.get("description") or None
                prog = (ds, de, live.get("title") or "", desc, img)
                all_entries.setdefault(str(key), {})[ds] = prog
                if app:
                    progs.setdefault(app, {})[ds] = prog
    print(f"Free TV OK : {calls_ok}/{FREE_HOURS} heures, {len(progs)} chaînes, {len(all_entries)} chaînes au total dans le guide ({time.time()-t0:.0f} s)")
    return progs, all_entries


# ---------- Images Free TV pour les chaînes hors offre gratuite (TF1, M6...) ----------
def sim(a, b):
    na, nb = norm(a), norm(b)
    if not na or not nb:
        return 0.0
    if na in nb or nb in na:
        return 1.0
    return difflib.SequenceMatcher(None, na, nb).ratio()


def borrow_images(channels, merged, free_ids_done, all_free):
    """Pour chaque chaîne absente de Free TV (sans freeUrl) : retrouve son numéro dans le guide Free TV en comparant
    horaires et titres avec le guide XMLTV, puis récupère les images. Les programmes restent ceux de XMLTV."""
    TOL = timedelta(minutes=5)
    cands = [k for k in all_free if k not in free_ids_done]
    index = {k: sorted(all_free[k].values(), key=lambda x: x[0]) for k in cands}
    starts = {k: [x[0] for x in v] for k, v in index.items()}
    done, failed = 0, []
    for c in channels:
        cid = c["id"]
        if c.get("freeUrl") or c.get("freeEpgId"):
            continue
        xml = sorted(merged.get(cid, {}).values(), key=lambda x: x[0])
        if len(xml) < 4:
            if c.get("appPackage"):
                print(f"Images Free TV : {c['name']} ignorée, seulement {len(xml)} programmes dans XMLTV")
            continue
        scores = []
        for k in cands:
            st, v, score = starts[k], index[k], 0
            for s, e, title, desc, img in xml:
                i = bisect.bisect_left(st, s - TOL)
                while i < len(st) and st[i] <= s + TOL:
                    if sim(title, v[i][2]) >= 0.5:
                        score += 1
                        break
                    i += 1
            scores.append((score, k))
        scores.sort(reverse=True)
        best = scores[0] if scores else (0, None)
        second = scores[1][0] if len(scores) > 1 else 0
        if best[0] < 4 or best[0] < second + 2 or best[0] < 0.4 * len(xml):
            failed.append(f"{c['name']} (XMLTV {len(xml)} prog., meilleurs scores {[(sc, k) for sc, k in scores[:3]]})")
            continue
        k = best[1]
        st, v, n_img = starts[k], index[k], 0
        for s, e, title, desc, img in xml:
            if img:
                continue
            i = bisect.bisect_left(st, s - TOL)
            pick = None
            if i < len(st) and st[i] <= s + TOL:
                pick = v[i]
            else:                                   # sinon le programme Free TV en cours à l'heure de début
                j = bisect.bisect_right(st, s) - 1
                if j >= 0 and v[j][1] > s:
                    pick = v[j]
            if pick and pick[4]:
                merged[cid][s] = (s, e, title, desc, pick[4])
                n_img += 1
        print(f"Images Free TV : {c['name']} = identifiant {k} (score {best[0]}/{len(xml)}), {n_img} images ajoutées")
        done += 1
    print(f"Chaînes hors offre gratuite associées : {done}")
    if failed:
        print(f"Non associées ({len(failed)}) :", "; ".join(failed[:30]))


SCRIPT_VERSION = "2026-11-21 images Free TV + parallèle"


def main():
    print(f"build_epg.py version : {SCRIPT_VERSION}")
    channels = json.load(open(CHANNELS, encoding="utf-8"))
    now = datetime.now(UTC)
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:      # téléchargements simultanés
        f_xml = pool.submit(fetch_xmltv, channels, now)
        f_free = pool.submit(fetch_free, channels, now)
        (xml_progs, icons), (free_progs, all_free) = f_xml.result(), f_free.result()
    print(f"Sources récupérées en {time.time()-t0:.0f} s")
    merged = dict(xml_progs)
    merged.update({k: v for k, v in free_progs.items() if v})   # Free TV prioritaire
    if all_free:
        known = {str(m.group(1)) for c in channels for m in [re.search(r"included_channels/(\d+)", c.get("freeUrl") or "")] if m}
        known |= {str(c["freeEpgId"]) for c in channels if c.get("freeEpgId")}
        borrow_images(channels, merged, known, all_free)
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
        for s, e, title, desc, img in sorted(merged.get(c["id"], {}).values(), key=lambda x: x[0]):
            p = ET.SubElement(tv, "programme", {"start": fmt(s), "stop": fmt(e), "channel": c["id"]})
            ET.SubElement(p, "title", {"lang": "fr"}).text = title
            if desc:
                ET.SubElement(p, "desc", {"lang": "fr"}).text = desc
            if img:
                ET.SubElement(p, "icon", {"src": img})
    tmp = OUT + ".tmp"
    with gzip.GzipFile(tmp, "wb", mtime=0) as gz:
        ET.ElementTree(tv).write(gz, encoding="utf-8", xml_declaration=True)
    os.replace(tmp, OUT)
    print(f"{OUT} : {len(merged)} chaînes, {total} programmes, {os.path.getsize(OUT)//1024} Ko")
    avec_img = sum(1 for v in merged.values() for x in v.values() if x[4])
    print(f"Programmes avec image : {avec_img}/{total}")
    sans_img = [c["name"] for c in channels if merged.get(c["id"]) and not any(x[4] for x in merged[c["id"]].values())]
    print(f"Chaînes avec programmes mais sans aucune image ({len(sans_img)}) :", ", ".join(sans_img[:60]))
    manquantes = [c["name"] for c in channels if c["id"] not in merged]
    print(f"Sans programme ({len(manquantes)}) :", ", ".join(manquantes[:40]), "..." if len(manquantes) > 40 else "")


if __name__ == "__main__":
    main()
