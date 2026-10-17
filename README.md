# Göz Jest — iki telefonlu jest kontrollü kamera + Gemini

İki ayrı Android uygulaması:

| APK | Kurulacağı telefon | Görevi |
|-----|--------------------|--------|
| `ana-cihaz.apk` | **Ana cihaz** (gösterge) | Ön kamerayla yüz + el takibi, jest algılama, Gemini'ye soru sorma, cevabı gösterme, ekranı karartma. İnterneti bu telefon sağlar (hotspot). |
| `foto-cihazi.apk` | **Foto cihazı** | Hiç jest algılamaz. Ana cihazdan "çek" komutu gelince arka kamerayla tek kare çeker, küçültür ve ana cihaza yükler. |

## Mekanizma

```
          ANA CİHAZ (hotspot + internet)              FOTO CİHAZI
          ön kamera                                   arka kamera
               │                                           │
   işaret parmağı → GÖZ   ─┐                               │
   işaret parmağı → BURUN ─┴─► "CAPTURE" ──(Wi-Fi)──────►  foto çek
                                                           küçült
          önbellek  ◄────────── JPEG yükle ◄───────────────┘
               │
   işaret parmağı → DUDAK ──► biriken tüm fotoğraflar ──► Gemini
                                                           │
                              cevap ana cihazın ekranında ◄┘

   gözler 5 sn kapalı  ──► ekran SİMSİYAH
   gözler açılır, tekrar 5 sn kapalı ──► ekran geri gelir
```

## Kurulum

### 1. APK'ları indir

Her `push` sonrası GitHub Actions iki APK'yı derler. İki yol var:

* **Releases** sekmesi → `apk-claude-beautiful-gates-d1uqqh` ön sürümü → telefon
  tarayıcısından doğrudan indir (giriş gerekmez).
* **Actions** sekmesi → son çalışma → `apk-ikili` artefaktı.

İki APK'nın paket adları farklı, aynı telefona ikisi de kurulabilir (test için).

> Derleme hata ayıklama (debug) imzasıyla yapılır. Her CI çalışması yeni bir
> imza üretebildiği için **güncellerken önce eskisini kaldırın**. Sabit imza
> isterseniz depoya `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`,
> `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` secret'larını ekleyin.

### 2. Gemini API anahtarı

İki seçenek:

* **Telefondan:** Ana cihaz → *Ayarlar* → *API anahtarı* alanına yapıştırın.
* **APK'ya gömülü:** Depoya `GEMINI_API_KEY` adlı bir repository secret ekleyin;
  CI anahtarı derlemeye gömer, telefonda hiçbir şey yazmanız gerekmez.

Anahtar [Google AI Studio](https://aistudio.google.com/apikey)'dan alınır.
Varsayılan model `gemini-2.5-flash`, Ayarlar'dan değiştirilebilir.

### 3. İki telefonu bağla

1. **Ana cihazda** mobil veriyi açın, sonra **hotspot**'u açın.
2. **Foto cihazını** bu hotspot'a bağlayın.
3. İki uygulamayı da açın, kamera izni verin.
4. Foto cihazı ana cihazı kendisi bulur. Üstteki satır **"Ana cihaza BAĞLI"**
   olmalı; ana cihazda da **"Foto telefonu: BAĞLI"** yazmalı.

Bulamazsa: ana cihaz ekranındaki IP adresini (`192.168.x.x:8787`) foto
cihazının *Ayarlar → IP adresi* alanına elle yazın.

## Kullanım

| Jest | Sonuç |
|------|-------|
| İşaret parmağı → **göz** | Foto cihazı bir kare çeker, ana cihazın önbelleğine ekler |
| İşaret parmağı → **burun ucu** | Aynı şey (ikinci tetikleyici) |
| İşaret parmağı → **dudak** | Önbellekteki tüm fotoğraflar Gemini'ye gider, cevap ekranda |
| Gözler **5 saniye** kapalı | Ekran tamamen kararır |
| Gözler açılır, tekrar **5 saniye** kapalı | Ekran geri gelir |

Her tetiklemeden sonra **parmağı yüzden uzaklaştırmanız** gerekir; aksi halde
ikinci tetikleme olmaz. Bu kasıtlıdır (bkz. aşağıdaki 5. filtre).

Titreşim geri bildirimi (Ayarlar'dan kapatılabilir):

* 1 kısa → fotoğraf istendi
* 2 kısa → Gemini'ye gönderildi
* 3 kısa → cevap hazır
* 1 uzun → hata

## Yanlış algılama nasıl engellendi

Bir jestin sayılması için **beş bağımsız filtrenin** aynı anda geçmesi gerekir:

1. **El şekli** — işaret parmağı açık, orta ve yüzük parmağı kapalı olmalı
   (`HandGeometry.isPointing`). Yüze dayanan avuç, saç düzeltme, yüz kaşıma bu
   filtreye takılır. Parmak uzunlukları avuç boyuna bölünerek ölçülür, yani
   elin kameraya uzaklığı sonucu değiştirmez.
2. **Mesafe** — parmak ucu, hedef bölgenin herhangi bir noktasına
   `touchRadius × gözler-arası-mesafe` kadar yakın olmalı. Piksel değil,
   yüz boyuna oranlı ölçü: yüz uzaklaşınca eşik de küçülür.
3. **Fark payı** — kazanan bölge, ikinciyi en az `%20` farkla geçmeli. Burun
   ile dudak arasında kalan bir parmak **tahmin etmez, hiçbir şey yapmaz**.
4. **Anatomi** — parmak ucu, yüzün kendi dikey ekseninde doğru bantta olmalı
   (göz ≈ 0.0, burun ≈ 0.40, ağız ≈ 0.66, çene = 1.0). Baş eğik olsa bile
   çalışır, çünkü eksen gözlerden çeneye çizilir.
5. **Zaman** — aynı cevap üst üste `stabilityFrames` kare (varsayılan 5)
   çıkmalı, sonra parmak yüzden uzaklaşmadan ikinci tetikleme olmaz.

Göz kapama için ayrıca:

* 5 saniye, normal göz kırpmanın (0.15–0.4 sn) ~20 katı;
* iki gözden **daha açık olanı** ölçülür, yani göz kırpma (tek göz) saymaz;
* algılama titremesine karşı kısa boşluklar tolere edilir, ama örneklenen
  karelerin en az `%75`'i kapalı okunmalı — eşiğin sınırında titreyen bir göz
  kapağı 5 saniyeye sürünemez;
* yüz kaybolursa sayaç **dondurulur**, ilerlemez — kamerayı kapatmak kestirme
  yol değildir;
* her geçişten sonra gözler en az 0.6 sn açılmalı.

Ayarlar ekranındaki *Ayıklama satırını göster* seçeneği ölçülen mesafeleri,
dikey oranı ve FPS'yi canlı gösterir — hassasiyet ayarlarken açın.

## "Simsiyah ekran" hakkında dürüst not

Karartmada üç şey birden yapılır: tüm ekranı kaplayan opak siyah katman,
pencere parlaklığının panel minimumuna indirilmesi, durum/gezinme çubuklarının
gizlenmesi ve kamera önizlemesinin ayrılması.

* **OLED ekranda** sonuç kapalı ekrandan ayırt edilemez (siyah piksel = kapalı piksel).
* **LCD ekranda** arka aydınlatma kalır; uygulamanın bunu kapatma yetkisi yoktur.

Ekran **kasıtlı olarak açık kalır**: gözün tekrar açıldığını görebilmek için ön
kameranın çalışması gerekir, ekran gerçekten kapanırsa uygulama durur ve geri
dönüş jesti çalışmaz.

**Acil çıkış:** ekrana 3 saniye basılı tutun veya ses tuşuna basın.

## Ayarlar (ana cihaz)

| Ayar | Varsayılan | Ne işe yarar |
|------|-----------|--------------|
| Dokunma yarıçapı | 0.38 | Küçült → parmağın tam değmesi gerekir, yanlış algılama azalır |
| Kararlılık | 5 kare | Artır → daha emin ama ~100 ms daha yavaş |
| Göz kapama süresi | 5.0 sn | Karartma eşiği |
| Sadece işaret parmağı | Açık | **Açık bırakın**, en güçlü filtre budur |
| Karartmada jestler | Açık | Ekran siyahken de fotoğraf çekilebilsin mi |
| Cevaptan sonra temizle | Açık | Her soru yeni bir fotoğraf grubuyla başlasın |

## Proje yapısı

```
core/        protokol, UDP cihaz keşfi, ağ yardımcıları, karartma denetçisi
app-main/    ana cihaz
  vision/    MediaPipe motoru, yüz/el geometrisi, iki jest durum makinesi
  net/       NanoHTTPD komut + yükleme sunucusu
  gemini/    generateContent istemcisi
  store/     fotoğraf önbelleği
app-camera/  foto cihazı (CameraX çekim + uzun yoklama istemcisi)
```

**Ağ protokolü** (ana cihaz sunucu, foto cihazı istemci, port 8787):

| Uç nokta | Açıklama |
|----------|----------|
| `GET /ping` | Canlılık yoklaması (cihaz keşfinde kullanılır) |
| `GET /poll` | Uzun yoklama; komut gelene kadar 25 sn bekletilir |
| `POST /photo?id=…` | Gövde ham JPEG |
| `POST /event` | Foto cihazından hata bildirimi |

UDP 8788'de `GOZJEST_DISCOVER_V1` yayınına ana cihaz kendi portuyla cevap verir.

## Yerelde derlemek

```bash
./gradlew :app-main:assembleDebug :app-camera:assembleDebug
```

MediaPipe model paketleri (~11 MB) depoda tutulmaz; `downloadMediapipeModels`
görevi ilk derlemede `app-main/src/main/assets/` içine indirir.

Gereken: JDK 17, Android SDK 34.

## Bilinen sınırlar

* Karanlık odada yüz takibi zayıflar; ekran karartılıysa aydınlatma da
  kalmadığı için geri dönüş jesti zorlaşabilir — acil çıkış bunun içindir.
* Foto cihazı arka planda kalırsa Android kamerayı geri alır; uygulama açık
  ve ekran uyanık kalmalıdır (uygulama ekranı uyanık tutar).
* Komut dağıtımı tek denemelik: yükleme anında bağlantı koparsa o kare
  kaybolur, tekrar dokunmak gerekir.
