# JARVIS

Persönlicher Sprachassistent als Android App für das Galaxy Z Fold8. Läuft kostenlos und offline auf dem Gerät.

- **Gehirn:** Gemini Nano über die ML Kit Prompt API (on device)
- **Sprache:** Android Spracherkennung und Sprachausgabe (Deutsch)
- **Interface:** 3D Kern mit Three.js in einer WebView
- **Daten:** Obsidian Vault, nur lesend, über einen freigegebenen Ordner

## Installieren

1. Unter *Releases* die Datei `jarvis.apk` vom Release **latest** auf dem Handy herunterladen.
2. Öffnen und die Installation aus dieser Quelle erlauben.
3. In der App auf **Vault wählen** tippen und `Documents/second brain` freigeben.
4. Auf den Kern tippen und sprechen.

Beim ersten Start lädt das Gerät eventuell Gemini Nano herunter (WLAN empfohlen).

## Bauen

Jeder Push auf `main` baut die APK mit GitHub Actions und ersetzt das Release `latest`.
