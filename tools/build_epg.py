name: Guide TV

on:
  schedule:
    - cron: "17 */3 * * *"   # toutes les 3 heures
  workflow_dispatch:          # bouton "Run workflow" pour lancer à la main

permissions:
  contents: write

concurrency: guide-tv

jobs:
  epg:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-python@v6
        with:
          python-version: "3.12"
      - name: Vérifier le script
        run: |
          echo "Branche : $(git branch --show-current) | commit : $(git rev-parse --short HEAD)"
          echo "--- racine du dépôt ---"; ls -la
          echo "--- dossier tools ---"; ls -la tools || echo "le dossier tools n'existe pas"
          if [ -f build_epg.py ]; then echo "ATTENTION : un build_epg.py existe aussi à la racine (il n'est PAS utilisé, le bon est tools/build_epg.py)"; fi
          if ! grep -q "def borrow_images" tools/build_epg.py; then
            echo "::error::tools/build_epg.py n'est pas la dernière version (fonction borrow_images absente). Remplace ce fichier."
            exit 1
          fi
          echo "tools/build_epg.py : dernière version OK"
      - name: Générer le guide
        run: python tools/build_epg.py
      - name: Publier sur la branche epg (un seul commit, l'historique ne grossit pas)
        run: |
          cp epg.xml.gz /tmp/epg.xml.gz
          git config user.name "epg-bot"
          git config user.email "epg-bot@users.noreply.github.com"
          git checkout --orphan epg-pub
          git rm -rf . > /dev/null
          cp /tmp/epg.xml.gz .
          git add epg.xml.gz
          git commit -m "Guide TV $(date -u '+%Y-%m-%d %H:%M') UTC"
          git push --force origin epg-pub:epg
