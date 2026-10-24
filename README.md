# Anruf Reminder

Android-App ohne Werbung, ohne Konto und ohne Internetzugriff: Zur eingestellten Zeit kommt ein simulierter Anruf. Nimmst du ab, wird deine Erinnerung per Text-to-Speech vorgelesen, wahlweise über die Hörmuschel wie bei einem echten Telefonat.

## Funktionen

- Erinnerung mit Titel (optional) und Text, Eingabe per Tastatur oder Diktat
- Datum und Uhrzeit, Wiederholung einmalig, täglich oder minütlich (alle X Minuten)
- Anruf auch bei gesperrtem Handy und geschlossener App
- Klingeln richtet sich nach dem Klingelmodus (normal, Vibration, lautlos), 15 Sekunden lang
- Vorlesen über Hörmuschel oder Lautsprecher (im Gespräch per Knopf umschaltbar), auf Wunsch in Schleife bis zum Auflegen
- Erinnerungen überstehen Neustarts
- Countdown bis zum nächsten Anruf auf der Startseite

## Installation

1. Unter **Releases** die neueste `anruf-reminder-….apk` auf dem Handy herunterladen.
2. Datei öffnen und die Installation aus dieser Quelle einmalig erlauben. Fragt Play Protect nach, die App scannen lassen.
3. App öffnen und unter **Einrichtung** alle offenen Punkte antippen, bis überall ein Haken ist.
4. Eine Erinnerung in ein paar Minuten anlegen und das Handy sperren.

Bei Herstellern mit strenger Akku-Verwaltung (u. a. Samsung, Xiaomi, Huawei, Oppo, OnePlus, Vivo) öffnet die App direkt die passende Autostart-Einstellung und verlinkt die bebilderte Anleitung auf [dontkillmyapp.com](https://dontkillmyapp.com). „Beenden erzwingen“ in den App-Infos löscht unter Android alle Wecker einer App; danach die App einmal öffnen.

## Datenschutz

Die App hat keine Internet-Berechtigung. Erinnerungen bleiben ausschließlich auf dem Gerät. Die Spracherkennung beim Diktieren übernimmt die Spracheingabe des Systems (z. B. Google).

## Bauen

Jeder Push baut die APK per GitHub Actions (`.github/workflows/build.yml`) und hängt sie an ein neues Release. Signiert wird mit einem Schlüssel aus den Repository-Secrets `KEYSTORE_BASE64` und `KEYSTORE_PASSWORD`; der Schlüssel liegt nicht im Repo. Lokal: `./gradlew assembleRelease` (Android SDK nötig, ergibt ohne Schlüssel eine unsignierte APK).

## Lizenz

MIT, siehe `LICENSE`. Ohne Gewähr: Die App ersetzt keinen Wecker für wichtige Termine.

Schriften: Inter (SIL OFL, `LICENSE-Inter.txt`), DejaVu Sans Mono (`LICENSE-DejaVu.txt`). Design angelehnt an [matija-tasic.de](https://github.com/matijatasic/matijatasic.github.io).
