# Tarcza „Szklane kapsuły” – Xiaomi Watch 5 (Wear OS, Watch Face Format)

Osobna gałąź, niezwiązana ze Stash. GitHub Actions buduje `out/tarcza.apk` przy każdym pushu.

- `tarcza/` – tarcza (res/raw/watchface.xml, grafiki, manifest)
- `tools/` – symulator (`sim.html`), render grafik (`render.js`) i generator XML (`gen.py`)

Instalacja: `adb connect <IP>:<port>` → `adb install -r out/tarcza.apk`
