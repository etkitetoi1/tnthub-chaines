# TNT Hub (Android / Android TV)

Grille de toutes les chaînes gratuites avec programme en cours et suivant, catégories,
favoris, et ouverture du direct dans l'application officielle (ou le navigateur).

## Compiler l'APK
1. Android Studio (Koala ou plus récent) > File > Open > dossier TNTHub (laisser Gradle se synchroniser).
2. Build > Build APK(s) -> app/build/outputs/apk/debug/app-debug.apk (déjà signé en debug, installable).
3. Android TV : activer le mode développeur, puis `adb connect IP_DE_LA_TV` et `adb install app-debug.apk`.

## Utilisation
- OK / toucher : ouvre le direct.  Appui long : programme de la chaîne + favori.
- Catégories : Tous, Favoris, TNT, Infos, Locales, Sport, Cinéma, Divertissement, Documentaires, Lifestyle, Jeunesse, Musique, Culture, Religion, Étrangères, UK, US, Espagnol, Allemand.

## A personnaliser
- app/src/main/assets/channels.json : chaînes, numéros, catégories, liens de direct, package de l'appli officielle.
  Pas besoin de toucher au code pour ajouter une chaîne.
- EpgRepository.XMLTV_URL : adresse du flux XMLTV (guide). A vérifier avant usage.
- Le guide est associé aux chaînes par leur nom (epgNames) : si une chaîne n'a pas de programme, ajouter son nom tel qu'il apparait dans le XMLTV.

## Notes de vérification (novembre 2026)
- Numérotation TNT en vigueur depuis le 6 juin 2025 (1 TF1 ... 8 LCP-Public Sénat ... 12 Gulli, 13-16 infos, 17 CStar, 18 T18, 19 Novo19, 20-25).
- C8 et NRJ 12 ont cessé d'émettre le 28 février 2025 et ne sont plus disponibles en direct nulle part : non intégrées.
- champ "verified" dans channels.json : free = lien Free TV relevé sur le compte ; ok = adresse vue dans une source ; site = plateforme confirmée, chemin déduit ; a_tester = non confirmé.
- RMC BFM Play a été remplacé par RMC+ (rmcplus.fr) le 1er septembre 2026.

## Mise à jour de la liste sans réinstaller
Hébergez channels.json sur GitHub, puis remplacez VOTRE_PSEUDO dans Model.kt (ChannelRepository.REMOTE_URL).
L'appli télécharge la liste au lancement et via « Actualiser le guide ». Si le téléchargement échoue, elle garde la dernière copie valide, ou la liste embarquée.

## Adresse du guide modifiable en ligne
Placez aussi config.json dans le dépôt GitHub (à côté de channels.json). Le champ epgUrl remplace XMLTV_URL du code.
Sans config.json (ou s'il est invalide), l'appli utilise l'adresse du code.

## Molotov et chaînes étrangères
- Le bouton « Via Molotov » (en haut) ouvre l'appli Molotov (tv.molotov.app) pour les chaînes marquées viaMolotov ; TF1 et M6 gardent leurs applis. Molotov s'ouvre sur son accueil : le lien direct vers une chaîne précise n'a pas pu être confirmé.
- Catégorie « Étrangères » : UK, US, ES, DE. Beaucoup de chaînes (BBC, ITV, RTVE, ZDF...) sont bloquées hors de leur pays et ne sont donc pas incluses.

## Free TV
tv.free.fr : gratuit avec un compte (170+ chaînes, 16 de la TNT hors TF1/M6). Le bouton « Via : » du haut fait tourner Free TV / Molotov / App officielle.
Pour ouvrir directement la page d'une chaîne, renseigner "freeUrl" dans channels.json (adresse relevée dans le navigateur sur tv.free.fr). Sans freeUrl, l'accueil de Free TV s'ouvre.

## Récupérer tous les liens Free TV
1. Sur tv.free.fr (connecté), F12 > Console, coller tools/free_ids.js, puis enregistrer le résultat dans ids.json.
2. `python3 tools/merge_free_ids.py ids.json` remplit freeUrl pour toutes les chaînes reconnues.
3. Envoyer channels.json sur GitHub : l'appli le récupère au lancement.
Déjà renseignés : France 2 (270), France 3 (348), BFM TV (429).

## Liste Free TV (novembre 2026)
218 chaînes : toutes celles de l'offre gratuite Free TV (16 TNT + FAST, locales, étrangères) + TF1, M6, W9, TMC, TFX, Gulli, LCI, TF1 Séries Films, 6ter (gratuites via leurs applis, absentes de Free TV) + quelques chaînes étrangères gratuites. Chérie 25 est devenue RMC Life (canal 25) le 1er octobre 2025.

## Guide des programmes
Source principale : l'API du guide de Free TV (ex-OQEE), `https://api.oqee.net/api/v1/epg/all/{heure}`, pour toutes les chaînes ayant un `freeUrl` (l'identifiant du lien sert aussi d'identifiant au guide).
Complément : le flux XMLTV, pour les chaînes absentes de Free TV (TF1, M6...) et pour les logos.
Après « Actualiser le guide », la barre d'état indique le nombre de chaînes reçues par chaque source (« Free TV : N chaînes · XMLTV : M »).
Si l'adresse de l'API change, ajouter `"freeEpgUrl": "https://.../epg/all/"` dans config.json (sans modifier l'appli).

## Vignettes
La carte d'une chaîne affiche l'image du programme en cours (champ `pictures` du guide Free TV, ou `<icon>` du programme dans l'XMLTV), avec le logo de la chaîne en petit dans le coin. Sans image, c'est le logo (ou les initiales) comme avant. L'appui long montre l'image en grand.
