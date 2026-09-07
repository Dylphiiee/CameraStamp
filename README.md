# GeoTag Camera (CameraStamp)

Aplikasi kamera Android yang menempelkan **timestamp** (jam, tanggal, hari) dan **locstamp** (nama lokasi hasil GPS) langsung ke foto **dan video** yang diambil, lengkap dengan logo perusahaan — mirip aplikasi "GPS Map Camera".

## Fitur

- Preview kamera penuh (CameraX) + overlay live yang menampilkan pratinjau stempel sebelum diambil.
- Mode **Foto** dan **Video** (toggle di atas tombol jepret).
- Stempel otomatis dibakar (di-*burn*) permanen ke hasil:
  - Nama perusahaan (tetap, tidak bisa diubah dari dalam aplikasi — lihat bagian Kustomisasi).
  - Jam `HH.mm` + garis kuning + tanggal `DD BULAN` dan nama hari (untuk video, jam terus berjalan sesuai durasi rekaman).
  - Lokasi hasil reverse-geocoding.
  - Logo di pojok kanan atas.
- Thumbnail foto/video terakhir di layar kamera → tap untuk buka pratinjau, lalu **bagikan ke WhatsApp**, bagikan umum, atau buka di galeri.
- Ganti kamera depan/belakang, toggle flash.
- Foto tersimpan ke `Pictures/GeoTagCamera`, video ke `Movies/GeoTagCamera`.

## Cara kerja stempel video

Video mentah direkam dulu tanpa stempel (CameraX `Recorder`), lalu diproses ulang oleh **FFmpeg**: satu lapisan gambar PNG statis (nama perusahaan + lokasi + logo) dan satu seri PNG "detak jam" (satu gambar per detik) ditempelkan ke video pakai filter `overlay`, sementara audio disalin langsung tanpa re-encode. Proses ini berjalan otomatis setelah kamu menekan tombol stop — ada layar "Memproses video…" selama beberapa detik/menit tergantung panjang video.

## Struktur proyek

```
CameraStamp/
├── app/
│   ├── src/main/java/com/example/camerastamp/
│   │   ├── MainActivity.kt          # UI kamera, izin, mode foto/video, capture
│   │   ├── StampRenderer.kt         # Menggambar stempel + logo ke bitmap
│   │   ├── LocationHelper.kt        # GPS + reverse geocoding
│   │   ├── MediaStoreUtils.kt       # Simpan JPEG/MP4 ke galeri
│   │   ├── VideoStampProcessor.kt   # Pipeline FFmpeg: burn stempel ke video
│   │   └── PreviewActivity.kt       # Layar pratinjau + share ke WhatsApp/galeri
│   ├── src/main/res/                # Layout, warna, string, logo, ikon aplikasi
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── .github/workflows/build.yml      # Build otomatis via GitHub Actions
```

## Build otomatis via GitHub Actions (tanpa perlu install Android Studio)

1. Buat repository baru di GitHub, lalu push seluruh isi folder ini:
   ```bash
   cd CameraStamp
   git init
   git add .
   git commit -m "Initial commit: GeoTag Camera app"
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
- **Format tanggal/jam**: ubah `SimpleDateFormat` di `MainActivity.kt` / `VideoStampProcessor.kt`.
- **Gaya teks stempel (ukuran, posisi, outline)**: semua ada di `StampRenderer.kt`.

## Izin yang digunakan

- `CAMERA` — mengambil foto/video.
- `RECORD_AUDIO` — merekam suara saat mode video.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — mengambil koordinat GPS untuk locstamp.
- `WRITE_EXTERNAL_STORAGE` (khusus Android ≤ 9) — menyimpan ke galeri pada perangkat lama.

## Catatan tentang dependency FFmpeg

Fitur burn-stempel-ke-video memakai `dev.ffmpegkit-maintained:ffmpeg-kit-free` — kelanjutan komunitas dari proyek `arthenica/ffmpeg-kit` yang di-*archive* April 2025. Ini menambah ukuran APK cukup signifikan (native library FFmpeg untuk beberapa arsitektur CPU).

Jika build gagal spesifik di tahap video/FFmpeg (bukan di bagian foto), kemungkinan penyebabnya:
- Versi artifact `6.0.2` sudah ditarik/diganti — cek halaman Maven Central proyek tersebut untuk versi terbaru, lalu update baris dependency di `app/build.gradle`.
- Nama paket Kotlin (`com.arthenica.ffmpegkit.*`) berubah di fork — sesuaikan import di `VideoStampProcessor.kt`.

`VideoStampProcessor` sudah mencoba 3 encoder video secara berurutan (`h264_mediacodec` → `libopenh264` → `mpeg4`) supaya tetap jalan walau salah satu tidak tersedia di build FFmpeg tertentu.

## Minimum SDK

- `minSdk 24` (Android 7.0) — `targetSdk 34`.
