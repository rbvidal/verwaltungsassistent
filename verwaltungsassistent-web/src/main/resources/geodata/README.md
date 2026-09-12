# Geodaten des Verwaltungsassistenten (Demo)

## Verwaltungsgebiete — `brandenburg-verwaltungsgebiete.geojson`
- Quelle: **OpenStreetMap** (Grenzen der Verwaltungseinheiten, abgerufen über
  polygons.openstreetmap.fr, Stand 2026-08-25).
- Umfang: 7 Zuständigkeitsgebiete der Brandenburger Demo:
  - Landkreis Potsdam-Mittelmark
  - Landeshauptstadt Potsdam (kreisfreie Stadt)
  - Landkreis Dahme-Spreewald
  - Landkreis Havelland
  - Stadt Brandenburg an der Havel (kreisfreie Stadt, MultiPolygon)
  - Landkreis Oberhavel
  - Landkreis Barnim
- Lizenz: **ODbL 1.0** — © OpenStreetMap contributors. Für die Demo dezimiert
  (Abstand ≈ 250 m). Punkt-in-Polygon-Auswertung über die volle Geometrie
  (Polygon + MultiPolygon, Ring-Parität für Löcher).

## Demo-Adressen — `brandenburg-demo-addresses.json`
- Quelle: **OpenStreetMap** (Abfrage über die Overpass-API, Stand 2026-08-25):
  reale Adressen (Straße, Hausnummer, PLZ, Koordinaten) aus den Demo-Orten
  Potsdam, Oranienburg, Brandenburg an der Havel, Werder (Havel), Teltow,
  Falkensee, Bernau bei Berlin und Königs Wusterhausen.
- Umfang: **255 Adressen** (Potsdam 72, Brandenburg an der Havel 40,
  Oranienburg 36, Werder 29, Falkensee 29, Königs Wusterhausen 22,
  Bernau 20, Teltow 7), deterministisch auf die Zielgrößen pro Ort gesampelt.
- Lizenz: **ODbL 1.0** — © OpenStreetMap contributors.
- Das Zuständigkeitsgebiet jeder Adresse wird deterministisch per
  Punkt-in-Polygon über die Verwaltungsgebiets-Grenzen bestimmt (nicht aus
  OSM-Tags übernommen); Adressen außerhalb der erfassten Gebiete werden
  verworfen. Die Demo-Adresssuche arbeitet ausschließlich mit diesem lokalen
  Datensatz — keine externe Geokodierung zur Laufzeit.

## Ersetzte Berlin-Daten (historisch, nicht mehr geladen)
- `berlin-bezirke.geojson` / `berlin-demo-addresses.json` wurden durch die
  Brandenburger Daten ersetzt und sind nicht mehr Teil der Demo.

## Demo-Fotos — archivierte Übungsaufnahmen (Brandenburg)
- Die 14 Geo-Demo-Fotos sind **echte Fotografien** (archivierte
  Übungsaufnahmen aus der früheren Berlin-Demo, unverändert unter
  `uploads/backup/berlin/` erhalten). Sie werden als Demo-Assets unter
  `src/main/resources/demo/geo-photos/` mitgeliefert (web-tauglich verkleinert,
  Metadaten entfernt) und beim Import mit den Szenario-Dateinamen abgelegt.
- Die GPS-Positionen sind **fest vorgegebene Demo-Koordinaten**: Sie liegen
  innerhalb der sieben gebündelten Zuständigkeitsgebiete und lösen über den
  GeoService deterministisch auf die beabsichtigte Behörde auf (Potsdam,
  Brandenburg an der Havel, Oranienburg, Werder (Havel), Falkensee, Bernau
  bei Berlin, Königs Wusterhausen). Die Aufnahmen stammen nicht von diesen
  Orten — die Demo kennzeichnet das im UI („Demo-Koordinaten").
- Erzeugung: `SyntheticPhotoFactory.demoPhoto(...)` bettet die Demo-Koordinaten
  und den Aufnahmezeitpunkt deterministisch als EXIF ein (Bildpixel bleiben
  unverändert); der GeoWorkflow liest GPS weiterhin aus dem EXIF
  (gpsSource = "EXIF"). Die ausgelieferte Anzeige-/Download-Kopie wird wie
  bei allen Fotos EXIF-bereinigt.
- Bestehende Demo-Foto-Datensätze werden beim Start aktualisiert, wenn sich
  die Quelldatei geändert hat (z. B. Upgrade von der früheren synthetischen
  Erzeugung auf die Archivaufnahme).
