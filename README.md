# TeslaMirror

Android telefonun ekranını ve sesini, internet kullanmadan Wi-Fi (telefon hotspot'u) üzerinden Tesla'nın tarayıcısına aktarır. Kişisel kullanım içindir.

## APK'yı indirme (GitHub Actions)
1. Depoda **Actions** sekmesine gir ve en son yeşil ✓ işaretli **Build APK** çalışmasını aç.
2. Sayfanın altındaki **Artifacts** bölümünden **TeslaMirror-apk** dosyasını indir. Bu bir zip dosyası; içinden `app-release.apk` çıkar.
3. APK'yı telefona kopyala ve aç. Gerekirse "bilinmeyen kaynaklardan yükleme" iznini **yalnızca bu dosya için** ver.

## Kullanım
1. Telefonda **Wi-Fi hotspot**'u aç.
2. Tesla'da **Kontroller > Wi-Fi** menüsüne girip telefonun hotspot ağına bağlan.
3. TeslaMirror uygulamasını aç ve **Yayını başlat**'a bas. Ses ve bildirim izinlerini ver. Ekran paylaşımı sorulduğunda **Tüm ekran**'ı seç.
4. Uygulamada görünen ve yanında `hotspot` yazan adresi Tesla tarayıcısında aç. Örnek: `http://10.x.x.x:8080`
5. **▶ Başlat**'a dokun. Ses için bu dokunuş zorunlu.

## Sınırlar
- Netflix gibi DRM korumalı uygulamalarda görüntü siyah çıkar ve/veya ses gelmez.
- Telefonun ekranı kapanırsa görüntü de durur.
- Tesla, sürüş sırasında tarayıcıda video oynatmayı kısıtlayabilir. Sürüş sırasında video izlemek de önerilmez.
- Gecikme yaklaşık 0,3 saniyedir (bilgisayardaki Chrome testinde ölçüldü).

## Teknik özet
- MediaProjection → H.264 (donanım) ve AudioPlaybackCapture → AAC
- fMP4 parçaları → yerel WebSocket → tarayıcıda Media Source Extensions
- Hiçbir dış kütüphane kullanılmaz ve hiçbir sunucuya bağlanılmaz. Veri yalnızca telefon ile araç arasında akar.
