#!/usr/bin/env python3
"""Usage: python3 tools/merge_free_ids.py ids.json
ids.json = {"004 - France 4Mystery Lane": 412, ...} (sortie de free_ids.js).
Association : 1) par numéro de chaîne (« 004 - ... » = numéro TNT de l'appli), 2) par nom (début du libellé).
Remplit freeUrl + viaFree dans channels.json (assets et copie à la racine)."""
import json, sys, unicodedata, re
def norm(s):
    s = unicodedata.normalize('NFD', s.lower())
    return re.sub(r'[^a-z0-9]', '', ''.join(c for c in s if unicodedata.category(c) != 'Mn'))
entries = []
for label, cid in json.load(open(sys.argv[1])).items():
    m = re.match(r'^\s*(\d+)\s*-\s*(.*)$', label)
    entries.append((int(m.group(1)) if m else None, norm(m.group(2) if m else label), cid, label))
URL = "https://tv.free.fr/home/included_channels/{}/play"
for path in ('app/src/main/assets/channels.json', '../channels.json'):
    try: chans = json.load(open(path))
    except FileNotFoundError: continue
    used, done = set(), 0
    for c in chans:
        names = [norm(n) for n in [c['name']] + c.get('epgNames', [])]
        hit = None
        for i, (num, text, cid, label) in enumerate(entries):
            if num is not None and c.get('number') == num and num <= 27: hit = i; break   # numéros TNT
        if hit is None:
            for i, (num, text, cid, label) in enumerate(entries):
                if any(text.startswith(n) for n in names if len(n) >= 3): hit = i; break
        if hit is not None:
            c['viaFree'] = True; c['freeUrl'] = URL.format(entries[hit][2]); used.add(hit); done += 1
    json.dump(chans, open(path, 'w'), ensure_ascii=False, indent=1)
    print(path, ':', done, 'chaînes mises à jour')
print('Non associées :', [entries[i][3] for i in range(len(entries)) if i not in used])
