# 🛡️ Antigravity AdBlock Extension (Chrome & Opera - Manifest V3)

Ekstensi adblocker berperforma tinggi untuk **Google Chrome**, **Opera**, **Brave**, **Microsoft Edge**, dan browser Chromium lainnya yang dibangun berdasarkan basis filter dan sinkronisasi dari repositori [`axelbrux99-cyber/antigravity-adblock-android`](https://github.com/axelbrux99-cyber/antigravity-adblock-android).

---

## ✨ Fitur Utama

- **Declarative Net Request (DNR) Blocking:** Pemblokiran tingkat jaringan dengan kecepatan native browser tanpa membebani RAM/CPU.
- **Dukungan Multi-Subscription URL Filter:** Tambahkan dan kelola URL filter eksternal tak terbatas (EasyList, AdGuard, OISD, Peter Lowe's, GitHub Raw, dll).
- **Auto-Sync Otomatis Tiap 24 Jam:** Background service worker memperbarui semua URL langganan secara berkala di latar belakang.
- **Universal Filter Parser:** Mengenali berbagai format filter (Adblock Plus / ABP syntax `||domain^`, format `/etc/hosts` `0.0.0.0 domain`, dan plain domain list).
- **Cosmetic Filtering:** Menyembunyikan sisa kotak/placeholder iklan agar tampilan website tetap bersih dan rapi.
- **Cyber Dark Mode Popup UI:** Tampilan modern dengan navigasi tab Utama & Langganan URL, toggle on/off, penghitung statistik iklan yang diblokir, dan whitelist per situs.

---

## 🚀 Cara Instalasi di Google Chrome

1. Buka Google Chrome dan buka URL: `chrome://extensions`
2. Aktifkan **Developer mode** (Mode Pengembang) di pojok kanan atas.
3. Klik tombol **Load unpacked** (Muat yang belum dibongkar).
4. Pilih folder `antigravity-adblock-extension`.
5. Selesai! Ikon Antigravity AdBlock akan muncul di toolbar browser Anda.

---

## 🎭 Cara Instalasi di Opera Browser

1. Buka Opera Browser dan buka URL: `opera://extensions`
2. Aktifkan **Developer mode** di pojok kanan atas.
3. Klik tombol **Load unpacked**.
4. Pilih folder `antigravity-adblock-extension`.
5. Ekstensi langsung aktif dan siap digunakan.

---

## 🔗 Menambahkan URL Subscription Tambahan

Buka menu ekstensi $\rightarrow$ klik tab **Langganan URL** $\rightarrow$ masukkan Nama dan URL Filter, lalu klik **Tambah & Perbarui Filter**.

Contoh URL Subscription Populer:
- **EasyList Core:** `https://easylist.to/easylist/easylist.txt`
- **AdGuard Base Filter:** `https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_2_Base/filter.txt`
- **Peter Lowe's Ad Server List:** `https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext`
- **OISD Basic:** `https://small.oisd.nl`
- **Antigravity Custom List:** `https://raw.githubusercontent.com/axelbrux99-cyber/antigravity-adblock-android/main/app/src/main/assets/blocklist.txt`

---

## 📁 Struktur Berkas

```
antigravity-adblock-extension/
├── manifest.json       # Manifest V3 (Chrome & Opera)
├── background.js       # Service worker & Subscription auto-sync engine
├── rules.json          # Declarative Net Request rules
├── blocklist.txt       # Daftar domain iklan & tracker
├── content.js          # Cosmetic element hiding script
├── content.css         # CSS stylesheet penghilang placeholder iklan
├── popup/              # Antigravity Dark UI Popup (Tab Utama & Langganan)
│   ├── popup.html
│   ├── popup.css
│   └── popup.js
└── icons/              # Ikon ekstensi (16px, 48px, 128px)
```