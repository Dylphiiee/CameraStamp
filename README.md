# JLMcam (CameraStamp)

Aplikasi kamera Android yang menempelkan **timestamp** (jam, tanggal, hari) dan **locstamp** (nama lokasi hasil GPS) langsung ke foto **dan video** yang diambil, lengkap dengan logo perusahaan — mirip aplikasi "GPS Map Camera".

## Fitur

- Preview kamera penuh (CameraX) + overlay live yang menampilkan pratinjau stempel sebelum diambil.
- Mode **Foto** dan **Video** (toggle di atas tombol jepret).
- Stempel otomatis dibakar (di-*burn*) permanen ke hasil:
  - Nama perusahaan (tetap, tidak bisa diubah dari dalam aplikasi — lihat bagian Kustomisasi).
  - Jam `HH.mm` + garis kuning + tanggal `DD BULAN` dan nama hari.
  - Lokasi hasil reverse-geocoding.
  - Logo di pojok kanan atas.
- Thumbnail foto/video terakhir di layar kamera → tap untuk buka pratinjau, lalu **bagikan ke WhatsApp**, bagikan umum, atau buka di galeri.
- Ganti kamera depan/belakang, toggle flash.
- Foto tersimpan ke `Pictures/GeoTagCamera`, video ke `Movies/GeoTagCamera`.

## Cara kerja stempel

- **Foto**: stempel digambar langsung di atas bitmap hasil jepretan lewat `Canvas` Android biasa (`StampRenderer`) — ringan, instan, tidak ada proses tambahan.
- **Video**: direkam dulu polos lewat CameraX `Recorder`, lalu diproses ulang memakai **MediaCodec + OpenGL ES bawaan Android** (tanpa library pihak ketiga apa pun): setiap frame di-*decode*, digambar ulang ke sebuah Surface lewat OpenGL bersama satu lapisan gambar stempel (nama perusahaan + jam/tanggal/hari + lokasi + logo), lalu di-*encode* ulang jadi video baru. Audio disalin apa adanya tanpa re-encode. Prosesnya otomatis berjalan setelah kamu menekan tombol stop — ada layar "Memproses video…" selama beberapa detik/menit tergantung panjang video.

  **Jam pada video dibekukan di waktu mulai rekam** (tidak ikut berjalan selama durasi video) — ini pilihan yang disengaja, supaya stempel cukup dihitung sekali per rekaman, bukan demi kesempurnaan real-time. Yang penting stempelnya ada dan permanen menempel di videonya.

## Struktur proyek

```
CameraStamp/
├── app/
│   ├── src/main/java/com/example/camerastamp/
│   │   ├── MainActivity.kt           # UI kamera, izin, mode foto/video, capture
│   │   ├── StampRenderer.kt          # Menggambar stempel + logo ke bitmap
│   │   ├── LocationHelper.kt         # GPS + reverse geocoding
│   │   ├── MediaStoreUtils.kt        # Simpan JPEG/MP4 ke galeri
│   │   ├── VideoOverlayProcessor.kt  # Pipeline MediaCodec+OpenGL: burn stempel ke video
│   │   ├── gl/                       # Helper EGL/OpenGL (EglCore, WindowSurface, OutputSurface, GlRenderer)
│   │   └── PreviewActivity.kt        # Layar pratinjau + share ke WhatsApp/galeri
│   ├── src/main/res/                 # Layout, warna, string, logo, ikon aplikasi
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── .github/workflows/build.yml       # Build otomatis via GitHub Actions
```

## Build otomatis via GitHub Actions (tanpa perlu install Android Studio)

1. Buat repository baru di GitHub, lalu push seluruh isi folder ini:
   ```bash
   cd CameraStamp
   git init
   git add .
   git commit -m "Initial commit: JLMcam app"
   git branch -M main
   git remote add origin https://github.com/USERNAME/REPO_NAME.git
   git push -u origin main
   ```
2. Buka tab **Actions** di repository GitHub kamu. Workflow **"Build APK"** akan berjalan otomatis.
3. Setelah selesai, buka run yang sukses lalu unduh artifact **`app-debug-apk`** — isinya adalah `app-debug.apk`.
4. Alternatif: cek tab **Releases** — setiap push ke `main` juga otomatis membuat release baru berisi APK.

APK hasil build ini **belum ditandatangani untuk Play Store** (masih debug build), tapi sudah bisa langsung di-install di HP Android (aktifkan "Install dari sumber tidak dikenal").

## Build lokal (opsional, via Android Studio)

1. Buka folder `CameraStamp` di Android Studio (disarankan versi terbaru).
2. Android Studio akan otomatis membuatkan `gradlew`/wrapper saat sinkronisasi pertama.
3. Klik Run ▶ atau `Build > Build Bundle(s) / APK(s) > Build APK(s)`.

## Kustomisasi

- **Logo**: ganti file `app/src/main/res/drawable-nodpi/logo.png` dan `ic_launcher_foreground.png` dengan logo lain (format PNG transparan disarankan).
- **Nama perusahaan**: ubah `company_default` di `app/src/main/res/values/strings.xml`. Ini sengaja dikunci (tidak ada UI untuk mengubahnya di dalam aplikasi) sesuai permintaan — kalau butuh diubah lagi, edit source code lalu build ulang.
- **Warna aksen (garis kuning, dsb.)**: ubah di `app/src/main/res/values/colors.xml`.
- **Format tanggal/jam**: ubah `SimpleDateFormat` di `MainActivity.kt` / `VideoOverlayProcessor.kt`.
- **Gaya teks stempel (ukuran, posisi, shadow)**: semua ada di `StampRenderer.kt`.

## Izin yang digunakan

- `CAMERA` — mengambil foto/video.
- `RECORD_AUDIO` — merekam suara saat mode video.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — mengambil koordinat GPS untuk locstamp.
- `WRITE_EXTERNAL_STORAGE` (khusus Android ≤ 9) — menyimpan ke galeri pada perangkat lama.

## Catatan jujur soal pipeline video (MediaCodec + OpenGL)

Pipeline ini (di `VideoOverlayProcessor.kt` + paket `gl/`) ditulis mengikuti pola standar yang banyak dipakai (mirip contoh referensi "Grafika" dari tim Android), tapi **belum pernah di-build dan dites di perangkat sungguhan** dari sisi saya (tidak ada akses ke Android Studio/emulator/HP di lingkungan ini). Area yang paling berisiko meleset kalau ada masalah setelah dites:

- **Orientasi video terbalik/miring** — logika rotasi ada di `GlRenderer.drawVideoFrame()` (parameter `rotationDegrees`). Kalau video hasil jadi miring atau ke-mirror, coba ganti tanda rotasinya (`rotationDegrees.toFloat()` → `-rotationDegrees.toFloat()`) di pemanggilan `Matrix.rotateM(...)`.
- **Device tertentu tidak mendukung encoder H.264 tertentu** — beberapa HP low-end punya batasan resolusi/bitrate encoder yang berbeda; kalau gagal di HP tertentu, coba turunkan `KEY_BIT_RATE` atau resolusi rekaman (`Quality.FHD` → `Quality.HD` di `MainActivity.kt`).
- Kirim saya pesan error/logcat kalau ada masalah setelah dites — saya bisa bantu sesuaikan lebih presisi dengan info itu dibanding menebak.

Tidak ada lagi dependency pihak ketiga untuk video (FFmpeg sudah dilepas total) — jadi ukuran APK jadi jauh lebih kecil dan tidak ada lagi risiko native crash dari library eksternal.

## Minimum SDK

- `minSdk 24` (Android 7.0) — `targetSdk 34`.
