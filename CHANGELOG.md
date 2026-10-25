# Änderungen

Neue Versionen stehen oben. Jede Version ist unter [Releases](https://github.com/matijatasic/anrufreminder-/releases) als APK verfügbar.

## 1.0.43
- Repository vervollständigt: Abbildungen im README, Release-Notizen mit Prüfsumme und Schlüssel-Fingerabdruck, Changelog, Sicherheitshinweise, Vorlage für Fehlermeldungen. Keine Änderung an der App selbst.

## 1.0.42 – erste öffentliche Version
Funktionsstand zum Zeitpunkt der Veröffentlichung:
- Erinnerung mit optionalem Titel und Text, Eingabe per Tastatur oder Diktat
- Wiederholung einmalig, täglich oder minütlich (alle X Minuten, Auswahl per Scroll-Rad)
- Simulierter Anruf auch bei gesperrtem Handy und geschlossener App, Klingeln 15 Sekunden
- Klingeln richtet sich nach dem Klingelmodus (normal, Vibration, lautlos)
- Ansage per Text-to-Speech über Hörmuschel oder Lautsprecher, im Gespräch per Knopf umschaltbar
- „Text mehrmals vorlesen“: Wiederholung bis zum Auflegen
- Countdown bis zum nächsten Anruf auf der Startseite, Liste nach nächstem Anruf sortiert
- Einmalige Erinnerungen werden nach dem Anruf ausgeschaltet und behalten ihr Datum

## 1.0.1 – 1.0.41 (vor der Veröffentlichung)
Entwicklung in einem privaten Repository. Wichtige Schritte:
- Anruf-Bildschirm im Stil der System-Telefon-App, Annehmen ohne Overlay-Berechtigung
- Hersteller-spezifische Hinweise für Autostart und Akku (Samsung, Xiaomi, Huawei u. a.)
- Ansage wird vorab als Audiodatei erzeugt und am Stück abgespielt, damit die Ausgabe nicht umspringt
- Design angelehnt an matija-tasic.de, App-Icon mit Puls-Ring
