# Naskah Video — "Wow! Kalian Wajib Tahu Database Ini : ClickHouse"

Dokumen ini adalah panduan konten video YouTube, disusun per chapter. Tiap chapter
berisi tujuan, poin pembahasan (bisa dijadikan naskah narasi), arahan layar
(apa yang ditampilkan), dan kesimpulan.

Repo demo: `github.com/ProgrammerZamanNow/oltp-olap-demo`

---

## Deskripsi YouTube (copy-paste)

```
Di video ini kita bahas ClickHouse — database OLAP columnar yang super cepat
untuk analytics. Kita bangun pipeline real-time dari PostgreSQL ke ClickHouse
pakai Change Data Capture, lalu eksplorasi fitur-fitur ClickHouse lewat dashboard
demo: streaming analytics, funnel, distribution, pre-aggregation, dan time travel.

⏱ Chapters:
00:00  Intro
00:45  Problem: OLTP vs OLAP
04:00  Solusi: Sinkronisasi CDC
06:30  Teknologi yang Digunakan
08:30  Debezium — Change Data Capture
12:00  ClickHouse — Database OLAP
16:00  Aplikasi Demo
19:00  Halaman Streaming
24:00  Halaman Funnel
29:00  Halaman Distribution
34:00  Halaman Pre-Aggregate
39:00  Halaman Time Travel
44:00  Penutup

Source code: https://github.com/ProgrammerZamanNow/oltp-olap-demo
```

> Timestamp di atas estimasi — sesuaikan setelah editing.

---

## Chapter 1 — Intro

**Durasi:** ~45 detik

**Tujuan:** Hook penonton, jelaskan apa yang akan dipelajari.

**Poin pembahasan:**
- Kebanyakan developer cuma kenal satu jenis database: yang transaksional —
  MySQL, PostgreSQL. Padahal ada satu kategori database yang dipakai
  perusahaan besar untuk analytics, dan kecepatannya bikin kaget.
- Hari ini kita kenalan dengan **ClickHouse** — dan kita nggak cuma teori,
  kita bangun pipeline data lengkap dari nol.
- Yang akan dibahas: kenapa butuh database analytics terpisah, cara
  sinkronisasi data secara real-time, dan demo langsung fitur-fitur ClickHouse.

**Arahan layar:**
- Cuplikan cepat dashboard demo (streaming, funnel, time travel) sebagai teaser.

---

## Chapter 2 — Problem: OLTP vs OLAP

**Durasi:** ~3 menit

**Tujuan:** Penonton paham kenapa satu database saja tidak cukup.

**Poin pembahasan:**
- Database yang biasa kita pakai disebut **OLTP** — Online Transaction
  Processing. Tugasnya melayani transaksi: simpan order, update stok,
  login user. Operasinya kecil-kecil tapi sangat banyak.
- OLTP disimpan secara **row-oriented** — satu baris data disimpan
  berdekatan. Cocok untuk "ambil 1 order lengkap" atau "update 1 row".
- Masalah muncul saat kita mau **analytics**: "total revenue per kota bulan
  ini", "produk terlaris per jam". Query begini harus scan jutaan baris
  dan agregasi. Di OLTP ini lambat.
- Lebih parah: query analytics yang berat **mengganggu transaksi
  production**. Bayangkan laporan bulanan bikin checkout customer lemot.
- Solusinya adalah kategori database kedua: **OLAP** — Online Analytical
  Processing. Disimpan **column-oriented**: data per kolom disimpan
  berdekatan. Saat query cuma butuh 3 kolom dari 50 kolom, dia cuma baca 3.
- OLAP dirancang untuk: scan besar, agregasi, `GROUP BY` jutaan baris —
  dalam hitungan milidetik.

**Arahan layar:**
- Tabel perbandingan OLTP vs OLAP.
- Ilustrasi row-oriented vs column-oriented storage.

**Kesimpulan chapter:**
> OLTP untuk menjalankan bisnis, OLAP untuk memahami bisnis. Dua kebutuhan
> berbeda — butuh dua database berbeda.

---

## Chapter 3 — Solusi: Sinkronisasi CDC

**Durasi:** ~2.5 menit

**Tujuan:** Jelaskan ide besar: pisahkan database, sambungkan dengan CDC.

**Poin pembahasan:**
- Kalau pakai dua database, muncul pertanyaan: gimana data dari OLTP
  sampai ke OLAP?
- Cara lama: **batch ETL**. Tiap tengah malam, copy semua data. Masalahnya:
  data di OLAP selalu basi — telat berjam-jam.
- Cara modern: **Change Data Capture (CDC)**. Setiap perubahan di OLTP
  langsung mengalir ke OLAP — real-time, dalam hitungan detik.
- Bagaimana CDC bekerja? Setiap database transaksional sudah menulis
  **transaction log** (di PostgreSQL namanya WAL — Write-Ahead Log) untuk
  keperluan durability & recovery.
- CDC tinggal "menguping" log itu. Tidak perlu query polling, tidak perlu
  kolom `updated_at`, tidak membebani tabel production.
- Setiap `INSERT`, `UPDATE`, `DELETE` jadi sebuah event yang bisa dikirim
  ke mana saja.

**Arahan layar:**
- Diagram: OLTP → (WAL) → CDC → OLAP.
- Bandingkan timeline batch ETL (telat berjam-jam) vs CDC (detik).

**Kesimpulan chapter:**
> Pisahkan beban OLTP dan OLAP, lalu sambungkan dengan CDC. Database sudah
> mencatat semua perubahan — CDC tinggal memanfaatkannya.

---

## Chapter 4 — Teknologi yang Digunakan

**Durasi:** ~2 menit

**Tujuan:** Perkenalkan semua komponen sebelum masuk detail.

**Poin pembahasan:**
- Kita akan bangun pipeline lengkap dengan komponen berikut:
  - **PostgreSQL** — database OLTP, sumber data transaksi.
  - **Debezium** — engine CDC, membaca WAL PostgreSQL.
  - **Apache Kafka** — message broker, menampung & mendistribusikan event.
  - **ClickHouse** — database OLAP, tujuan akhir untuk analytics.
  - **Spring Boot** — aplikasi generator yang mensimulasikan transaksi
    e-commerce, sekaligus menyajikan dashboard demo.
- Semua dijalankan dalam container pakai **Podman** + compose — satu
  perintah `make up`, semua nyala.
- Domain demo: e-commerce sederhana — tabel `customers`, `products`,
  `orders`, `order_items`.
- Alur lengkap: Spring Boot menulis order ke PostgreSQL → Debezium menangkap
  perubahan → publish ke Kafka → ClickHouse consume dari Kafka.

**Arahan layar:**
- Diagram arsitektur 5 komponen.
- Tunjukkan `podman-compose.yml` sekilas, lalu `make up` dan container nyala.

**Kesimpulan chapter:**
> Lima komponen, satu pipeline. Sekarang kita bedah dua bintang utamanya:
> Debezium dan ClickHouse.

---

## Chapter 5 — Debezium: Change Data Capture

**Durasi:** ~3.5 menit

**Tujuan:** Penonton paham peran Debezium dan cara setup-nya.

**Poin pembahasan:**
- **Debezium** adalah platform CDC open-source. Dia berjalan di atas
  **Kafka Connect** dan bertugas membaca transaction log database, lalu
  mengubahnya jadi event yang rapi di Kafka.
- Supaya PostgreSQL bisa di-CDC, ada 3 hal yang harus disiapkan:
  - `wal_level = logical` — WAL menyimpan info cukup untuk direkonstruksi.
  - `REPLICA IDENTITY FULL` — event `UPDATE`/`DELETE` membawa data lengkap,
    bukan cuma primary key.
  - `PUBLICATION` — daftar tabel yang ingin di-CDC.
- Debezium kita konfigurasi via satu file JSON connector. Beberapa setting
  penting:
  - `plugin.name = pgoutput` — pakai plugin logical replication bawaan
    PostgreSQL, tidak perlu install ekstensi tambahan.
  - **`unwrap` SMT** (Single Message Transform) — secara default payload
    Debezium itu bersarang (`before`, `after`, `op`...). SMT ini membuatnya
    **flat**, langsung field-field tabel. Jadi gampang di-parse ClickHouse.
  - `delete.handling.mode = rewrite` — operasi `DELETE` jadi event biasa
    dengan flag `__deleted = true`, bukan pesan kosong (tombstone).
- Hasilnya: setiap tabel PostgreSQL punya satu topik Kafka sendiri —
  `shop.public.orders`, `shop.public.customers`, dan seterusnya.

**Arahan layar:**
- Tunjukkan `postgres/init.sql` — bagian `REPLICA IDENTITY` & `PUBLICATION`.
- Tunjukkan `debezium/postgres-connector.json`.
- Jalankan `make register`, lalu `make status` → connector `RUNNING`.
- `make topics` → 4 topik Kafka muncul otomatis.
- Bonus: intip isi satu pesan Kafka — JSON flat dengan field `__deleted`.

**Kesimpulan chapter:**
> Debezium mengubah perubahan database mentah jadi stream event JSON yang
> rapi di Kafka. Sekarang tinggal ClickHouse yang consume.

---

## Chapter 6 — ClickHouse: Database OLAP

**Durasi:** ~4 menit

**Tujuan:** Perkenalkan ClickHouse dan cara dia menerima data — inti video.

**Poin pembahasan:**
- **ClickHouse** adalah database OLAP columnar, open-source, dibuat awalnya
  oleh Yandex. Terkenal sangat cepat untuk query analytics — bisa scan
  miliaran baris per detik.
- Kenapa cepat?
  - **Columnar storage** — query yang baca 3 kolom tidak perlu sentuh
    kolom lain.
  - **Vectorized execution** — proses data per batch di register SIMD,
    bukan baris demi baris.
  - **Sparse primary index** — filter range (terutama waktu) sangat cepat.
- Yang menarik: sinkronisasi Kafka → ClickHouse **100% deklaratif, tanpa
  kode aplikasi**. Caranya pakai dua fitur:
  - **Kafka engine table** — sebuah tabel yang sebenarnya adalah Kafka
    consumer. ClickHouse otomatis subscribe ke topik dan parse JSON-nya.
  - **Materialized View** — bukan view biasa. Ini trigger: setiap ada
    pesan baru di Kafka engine table, MV langsung transform & `INSERT` ke
    tabel tujuan.
- Tabel tujuan pakai engine **`ReplacingMergeTree`**. Karena OLAP umumnya
  append-only, setiap `UPDATE` di OLTP jadi baris versi baru. ReplacingMergeTree
  otomatis menyimpan versi terbaru per primary key.
- Operasi `DELETE` ditangani sebagai **soft delete** — kolom `is_deleted`,
  bukan hapus fisik. Saat query pakai modifier `FINAL` untuk dedup.
- Penting: consumer-nya berjalan **di dalam ClickHouse** (native C++) —
  bukan thread aplikasi yang harus kita tulis & maintain.

**Arahan layar:**
- Buka `clickhouse/init.sql` — tunjukkan 3 lapis: `kafka_orders` (Kafka
  engine), `mv_orders` (Materialized View), `orders` (ReplacingMergeTree).
- Buka ClickHouse Play UI di `localhost:8123/play`.
- Jalankan `SELECT count() FROM orders FINAL` — angka terus bertambah saat
  di-refresh, bukti data mengalir real-time.

**Kesimpulan chapter:**
> ClickHouse menerima stream Kafka tanpa satu baris kode pun — cukup
> Kafka engine + Materialized View. Sekarang kita lihat demonya.

---

## Chapter 7 — Aplikasi Demo

**Durasi:** ~3 menit

**Tujuan:** Orientasi: jelaskan struktur aplikasi demo sebelum bedah tiap halaman.

**Poin pembahasan:**
- Aplikasi demo ini dibangun pakai **Spring Boot** dan punya dua peran:
  - **Data generator** — tiap 2 detik membuat order baru, customer baru,
    dan menggerakkan status order. Ini yang bikin data terus mengalir.
  - **Dashboard web** — UI bergaya terminal di `localhost:8080` untuk
    eksplorasi data.
- Generator mensimulasikan **state machine e-commerce** yang realistis:
  order bergerak `PLACED → PAID → SHIPPED → DELIVERED`, atau bisa
  `CANCELLED` di tengah jalan. Lajunya diatur proporsional supaya antrian
  tiap status stabil.
- Dashboard punya 8 halaman, dibagi 2 kelompok:
  - **OLTP Inspector** (Customers, Products, Orders) — lihat data mentah
    langsung dari PostgreSQL via REST API.
  - **OLAP Analytics** (Streaming, Funnel, Distribution, Pre-Aggregate,
    Time Travel) — tiap halaman query langsung ke ClickHouse dan
    menampilkan satu kategori fitur analitik.
- Hal keren: tiap halaman OLAP menampilkan **waktu eksekusi query** dan
  **SQL yang dipakai** — jadi penonton lihat langsung query-nya dan
  betapa cepatnya.

**Arahan layar:**
- Buka `localhost:8080`, tunjukkan navigasi 8 halaman.
- Klik tab Orders — data mentah PostgreSQL, auto-refresh, order baru muncul.
- Tunjukkan badge live count yang berkedip saat ada data baru.

**Kesimpulan chapter:**
> Generator memompa data, dashboard memvisualisasikan. Sekarang kita masuk
> ke lima halaman analitik ClickHouse — satu per satu.

---

## Chapter 8 — Halaman Streaming

**Durasi:** ~5 menit

**Tujuan:** Tunjukkan pola analitik time-series real-time ClickHouse.

**Konsep halaman:** Query langsung ke `orders FINAL`, auto-refresh tiap 2 detik.
Fokus: pola streaming / time-series.

**Poin pembahasan:**
- **8 tile metrik dalam 1 query.** Di bagian atas ada 8 angka: jumlah order
  dan revenue untuk window 1 menit, 5 menit, 15 menit, 1 jam. Yang spesial:
  semua 8 angka ini dihitung dalam **satu query single-pass** pakai
  `countIf()` dan `sumIf()`. Di PostgreSQL ini butuh subquery atau CTE
  bertingkat. Di ClickHouse cukup satu kali scan tabel.
- **Throughput sparkline dengan WITH FILL.** Grafik throughput 60 menit
  terakhir. Masalah klasik time-series: menit yang tidak ada order akan
  hilang dari hasil — grafik jadi bolong. ClickHouse punya `WITH FILL` yang
  otomatis mengisi menit kosong dengan nol. Ada toggle untuk
  menyalakan/mematikan — tunjukkan bedanya saat dimatikan.
- **Velocity — turunan pertama.** Grafik Δ orders per menit: selisih jumlah
  order menit ini dengan menit sebelumnya. Pakai window function
  `lagInFrame()`. Positif berarti throughput sedang naik (akselerasi).
- **Anomaly — rolling z-score.** Grafik deteksi anomali: untuk tiap menit,
  hitung rata-rata dan standar deviasi dari 10 menit sebelumnya, lalu hitung
  z-score. Kalau `|z| > 2`, menit itu dianggap anomali — spike atau drop
  mendadak. Pakai window function `avg() OVER (ROWS BETWEEN 10 PRECEDING
  AND 1 PRECEDING)`.

**Arahan layar:**
- Buka halaman Streaming. Tunjuk 8 tile, refresh — angka window pendek
  berubah cepat.
- Tunjukkan label "QUERY" — waktu eksekusi dalam milidetik.
- Toggle WITH FILL on/off — perlihatkan grafik jadi bolong saat off.
- Tunjuk grafik velocity & anomaly, jelaskan saat ada spike.

**Kesimpulan chapter:**
> Satu halaman, empat pola time-series — multi-window, gap-fill, velocity,
> anomaly. Semua native, semua sub-detik.

---

## Chapter 9 — Halaman Funnel

**Durasi:** ~5 menit

**Tujuan:** Tunjukkan analisis urutan event — kekuatan unik ClickHouse.

**Konsep halaman:** Query ke `orders_events` (menyimpan SEMUA versi status,
bukan cuma yang terbaru), auto-refresh tiap 5 detik.

**Poin pembahasan:**
- Pertanyaannya: dari semua order, berapa yang berhasil melewati seluruh
  perjalanan `PLACED → PAID → SHIPPED → DELIVERED` **secara berurutan**?
  Ini namanya **funnel analysis**.
- Untuk ini, kita butuh **riwayat lengkap** tiap order — bukan cuma status
  terakhirnya. Karena itu ada tabel khusus `orders_events` (engine MergeTree
  biasa) yang menyimpan setiap perubahan status sebagai baris terpisah.
- Bintang utamanya: fungsi **`windowFunnel()`**. Dalam satu fungsi, dia
  mendeteksi apakah serangkaian event terjadi berurutan dalam jendela waktu
  tertentu. Hasilnya angka "level" — level 4 berarti order itu menyentuh
  keempat tahap secara berurutan.
- Di PostgreSQL, analisis seperti ini butuh self-join berlapis yang panjang
  dan lambat. Di ClickHouse: satu baris fungsi.
- **4 tile statistik:** total order, completed (level 4), conversion %,
  dan ever cancelled — semua diturunkan dari hasil `windowFunnel`.
- **Grafik funnel:** visualisasi corong PLACED → PAID → SHIPPED → DELIVERED,
  terlihat drop-off di tiap tahap.
- **Histogram time-to-conversion:** berapa lama (detik) order dari PLACED
  sampai DELIVERED. Pakai fungsi `histogram(20)` yang otomatis membagi data
  ke 20 bin. PostgreSQL butuh `width_bucket` manual.
- **Sequence pattern breakdown:** dari kombinasi funnel level + flag,
  ClickHouse bisa mendeteksi pola menarik:
  - *Stuck at PLACED* — order placed tapi belum maju.
  - *Abandoned at PLACED* — placed lalu cancelled, tanpa pernah bayar.
  - *Cancelled after PAID* — sudah bayar tapi tetap batal (pola chargeback).
  - *Shipped without PAID* — lompat tahap, anomali data.
  - *Full journey* — selesai sempurna.

**Arahan layar:**
- Buka halaman Funnel. Tunjuk grafik corong & drop-off antar tahap.
- Tunjuk 4 tile statistik, terutama conversion %.
- Tunjuk histogram time-to-conversion.
- Bahas tabel sequence pattern — kaitkan tiap pola dengan kasus bisnis nyata.

**Kesimpulan chapter:**
> `windowFunnel` mengubah analisis urutan event yang biasanya rumit jadi
> satu fungsi. Inilah jenis query yang bikin ClickHouse menonjol.

---

## Chapter 10 — Halaman Distribution

**Durasi:** ~5 menit

**Tujuan:** Tunjukkan fungsi statistik & aproksimasi khas ClickHouse.

**Konsep halaman:** Query ke `orders FINAL`, auto-refresh tiap 3 detik.
Fokus: distribusi statistik.

**Poin pembahasan:**
- **Cardinality: exact vs aproksimasi.** Menghitung jumlah unik (`COUNT
  DISTINCT`) itu mahal di data besar. ClickHouse menawarkan dua cara:
  - `uniqExact()` — hitung persis, akurat 100%.
  - `uniqHLL12()` — pakai algoritma **HyperLogLog** 12-bit. Tidak 100%
    persis (error rate ~0.81%), tapi jauh lebih cepat dan hemat memori di
    skala besar.
  - Halaman ini menjalankan keduanya dan menampilkan **speedup**-nya.
    Pesan utama: di analytics, sering kali "cukup akurat tapi cepat" lebih
    berharga daripada "persis tapi lambat".
- **Quantile bands.** Grafik P50, P95, P99 nilai order per menit. P95/P99
  menunjukkan perilaku "ekor" distribusi — penting untuk SLO dan deteksi
  outlier. Pakai fungsi `quantile()`. ClickHouse menghitung ini sub-detik
  di jutaan baris; `percentile_cont` di PostgreSQL jauh lebih lambat.
- **Period-over-period.** Tabel yang membandingkan tiap menit dengan menit
  sebelumnya: delta order, delta revenue, persen perubahan. Pakai window
  function `lagInFrame()` untuk mengambil nilai baris sebelumnya.
- **Top-K per menit.** Tabel 3 produk teratas tiap menit, dibobot revenue.
  Pakai `topKWeighted(3)()` — algoritma top-K aproksimatif dalam satu
  fungsi. Di PostgreSQL butuh `row_number() OVER PARTITION BY` yang lebih
  verbose dan lambat.

**Arahan layar:**
- Buka halaman Distribution. Tunjuk tile cardinality — bandingkan angka
  exact vs HLL dan waktu eksekusinya.
- Tunjuk grafik quantile bands — jelaskan arti P95/P99.
- Tunjuk tabel period-over-period & top-K.

**Kesimpulan chapter:**
> ClickHouse punya gudang fungsi statistik & aproksimasi siap pakai —
> quantile, HyperLogLog, top-K. Analitik canggih jadi satu baris query.

---

## Chapter 11 — Halaman Pre-Aggregate

**Durasi:** ~5 menit

**Tujuan:** Tunjukkan pre-agregasi — cara membuat dashboard tetap cepat di
skala besar.

**Konsep halaman:** Dashboard yang seluruhnya ditenagai data pre-computed,
auto-refresh tiap 3 detik.

**Poin pembahasan:**
- Masalah: walaupun ClickHouse cepat, men-scan ulang seluruh tabel setiap
  kali dashboard refresh tetap boros. Di skala miliaran baris, ini terasa.
- Solusi: **pre-aggregation**. Hitung agregat **sekali saat data masuk**,
  simpan hasilnya, dashboard tinggal baca hasil jadi.
- ClickHouse mewujudkan ini dengan engine **`AggregatingMergeTree`** plus
  rantai Materialized View:
  - `kafka_orders` → MV → `orders_events` (riwayat lengkap)
  - `orders_events` → MV → `orders_per_minute` (`AggregatingMergeTree`)
  - Ini **MV-on-MV**: MV yang memicu MV lain. ClickHouse mendukungnya.
- Tabel `orders_per_minute` tidak menyimpan angka jadi, tapi **state**
  agregat — `countState()`, `sumState()`, `quantileState()`. State ini
  **composable**: bisa di-merge ulang ke resolusi apa pun.
- Saat query, kita pakai pasangan fungsi `*Merge`: `countMerge()`,
  `sumMerge()`, `quantileMerge()`.
- **Composable — satu sumber, banyak resolusi.** Halaman ini punya pemilih
  resolusi 1m / 5m / 15m / 1h / 1d. Semua membaca tabel `orders_per_minute`
  yang sama, cuma beda `GROUP BY toStartOfInterval(...)`. Tidak perlu tabel
  agregat terpisah per resolusi.
- **Bukti: RAW vs PRE-AGG.** Bagian bawah halaman menjalankan query yang
  sama logikanya dengan dua cara — scan mentah `orders_events` vs baca
  state `orders_per_minute` — lalu menampilkan:
  - Waktu eksekusi keduanya dan **speedup**-nya.
  - Grafik parity: dua garis harus tumpang tindih sempurna (angkanya sama).
  - SQL kedua pendekatan, berdampingan.
  - Pesan: pre-agg menghasilkan angka **identik**, tapi jauh lebih murah.

**Arahan layar:**
- Buka halaman Pre-Aggregate. Tunjuk tile & grafik live.
- Klik-klik pemilih resolusi 1m → 1d, tunjukkan grafik re-bucket instan.
- Scroll ke bagian RAW vs PRE-AGG — tunjuk speedup & grafik parity yang
  bertumpuk.
- Tunjukkan SQL berdampingan — `sum()...GROUP BY` vs `sumMerge()`.

**Kesimpulan chapter:**
> Pre-aggregation dengan AggregatingMergeTree: hitung sekali saat insert,
> baca instan selamanya. State yang composable bikin satu sumber melayani
> semua resolusi.

---

## Chapter 12 — Halaman Time Travel

**Durasi:** ~5 menit

**Tujuan:** Tunjukkan query point-in-time — melihat keadaan data di masa lalu.

**Konsep halaman:** Slider waktu untuk melihat snapshot order pada timestamp
mana pun di masa lalu.

**Poin pembahasan:**
- Pertanyaan menarik: "Seperti apa keadaan semua order **15 menit yang
  lalu**?" Bukan sekarang — tapi snapshot di titik waktu tertentu.
- Ini mungkin karena tabel `orders_events` menyimpan **setiap versi** dari
  setiap order. Riwayat lengkap tidak dibuang.
- Kuncinya fungsi **`argMax(kolom, updated_at)`**. Artinya: "ambil nilai
  `kolom` dari baris dengan `updated_at` terbesar". Kalau kita batasi
  `WHERE updated_at <= T`, maka untuk tiap order kita dapat **versi
  terakhir yang masih berlaku pada waktu T**.
- Hasilnya: rekonstruksi keadaan seluruh sistem persis seperti pada waktu
  T. Ini pola **event sourcing** + **point-in-time query**.
- Halaman ini punya **slider waktu**: geser ke kiri untuk mundur ke masa
  lalu, ada tombol preset (-1 menit, -5 menit, -15 menit, -1 jam, -1 hari).
- Saat slider digeser, seluruh dashboard di bawahnya dihitung ulang untuk
  waktu itu:
  - Total order, customer unik, total revenue pada waktu T.
  - Distribusi status — berapa PLACED/PAID/SHIPPED/DELIVERED/CANCELLED
    saat itu. Geser slider ke masa kini, lihat DELIVERED makin menumpuk.
  - Distribusi "active" — hanya order yang masih in-flight.
  - Statistik nilai order: rata-rata, median, P95, max/min pada waktu T.
- Kegunaan nyata: audit, debugging ("kenapa angka jam 2 siang beda?"),
  analisis historis, regulatory compliance.

**Arahan layar:**
- Buka halaman Time Travel. Geser slider perlahan dari NOW mundur ke masa
  lalu — tunjukkan angka berubah.
- Klik preset -1h, -15m — dashboard langsung update.
- Tunjuk distribusi status berubah: di masa lalu DELIVERED sedikit, makin
  ke kini makin banyak.
- Tunjuk waktu eksekusi query — tetap cepat walau merekonstruksi state.

**Kesimpulan chapter:**
> Dengan menyimpan riwayat event dan fungsi `argMax`, ClickHouse bisa
> "kembali ke masa lalu" — merekonstruksi keadaan data di titik waktu mana
> pun.

---

## Chapter 13 — Penutup

**Durasi:** ~1.5 menit

**Tujuan:** Rangkum & call-to-action.

**Poin pembahasan:**
- Yang sudah kita pelajari:
  - **Kenapa** butuh OLAP terpisah dari OLTP.
  - **Bagaimana** CDC menjembatani keduanya secara real-time.
  - **Debezium** menangkap perubahan PostgreSQL.
  - **ClickHouse** menerima data tanpa kode, dan punya fitur analitik luar
    biasa: streaming, funnel, distribution, pre-aggregation, time travel.
- ClickHouse bukan pengganti database transaksional — dia pelengkap.
  Pakai alat yang tepat untuk pekerjaan yang tepat.
- Semua kode demo ini open-source. Silakan clone, jalankan `make up`,
  dan eksplorasi sendiri.
- Ajak: like, subscribe, dan komentar — fitur ClickHouse mana yang paling
  bikin penonton kaget?

**Arahan layar:**
- Recap cepat 5 halaman OLAP.
- Tampilkan URL repo GitHub.
- End screen.

**Kesimpulan chapter:**
> Dari transaksi ke insight, dalam hitungan detik. Itulah kekuatan
> ClickHouse.

---

## Catatan Produksi

- **Sebelum rekaman:** jalankan `make up` lalu `make register`, dan biarkan
  generator jalan **minimal 1-2 jam**. Halaman seperti Time Travel,
  Pre-Aggregate, dan funnel histogram butuh data historis yang cukup supaya
  grafiknya menarik.
- **Tampilkan SQL & waktu eksekusi.** Tiap halaman OLAP menampilkan label
  "QUERY" dengan waktu milidetik — selalu sorot ini, ini bukti utama
  kecepatan ClickHouse.
- **ClickHouse Play UI** (`localhost:8123/play`) bagus untuk demo query
  manual — login `analytics` / `analytics`, prefix tabel dengan
  `shop_analytics.`.
- **Urutan ideal:** OLTP Inspector dulu (lihat data mentah) → baru halaman
  OLAP — supaya penonton paham data yang sama, hanya cara pandang berbeda.
- File `clickhouse.md` di repo berisi puluhan query siap pakai kalau mau
  demo tambahan di Play UI.
