# 🎮 PlayStation TV Switcher (Android TV / Google TV / Xiaomi Mi TV & Box)

Android TV, Google TV, Xiaomi Mi TV ve harici Android TV kutularında (Mi Box / Stick) ana ekrandan **tek tıkla PlayStation'a (PS4, PS5 ve gelecekteki PS6) geçiş yapmayı** sağlayan hafif ve otomatik bir Android TV uygulaması.

## 📥 Doğrudan APK İndir

TV'nize veya cihazınıza doğrudan kurmak için depodaki imzalı APK dosyasını kullanabilirsiniz:
👉 **[PlayStation-TV-Switcher.apk](https://github.com/yogruce/ps-switcher/raw/main/PlayStation-TV-Switcher.apk)**

---

## ✨ Özellikler

- **Otomatik Konsol Keşfi ve Ağdan Uyandırma (PS4 / PS5 / PS6):**
  - Yerel ağda Sony DDP (`UDP 987` & `UDP 9302`), SSDP (`UPnP`) ve **Wake-on-LAN (Magic Packet)** protokollerini kullanarak PlayStation konsolunu otomatik tespit eder ve uyandırır.
  - Konsol uyandığında **HDMI-CEC** üzerinden televizyonu otomatik olarak kendi HDMI portuna geçirir (harici TV kutusu / Mi Box bağlıyken bile çalışır).
- **Otomatik HDMI Port Tespiti (`TvInputManager`):**
  - Doğrudan Android TV / Xiaomi Mi TV üzerinde çalışırken HDMI girişlerini tarar, ismi veya CEC etiketi `PlayStation`, `PS4`, `PS5`, `PS6`, `Sony` olan portu otomatik bulur ve doğrudan o HDMI kaynağına geçiş yapar.
- **Android TV Ana Ekran (Leanback) Banner Desteği:**
  - TV ana ekranında orijinal PlayStation renkleri ve `△ ○ × □` sembolleriyle yatay TV uygulama kartı olarak görünür.
- **Tek Tıkla Normal Kullanım + Kumanda ile Ayar Menüsü:**
  - Uygulamaya tıkladığınızda hiçbir tuşa basmanıza gerek kalmadan konsolu uyandırır, HDMI girişini değiştirir ve kendisini kapatır.
  - Geçiş ekranındayken kumandadan `[OK]` veya `[MENÜ]` tuşuna basarak istediğiniz zaman HDMI port seçimini değiştirebilirsiniz.

---

## 🛠️ Derleme (Build)

Bilgisayarınızda Android SDK (`platforms/android-34` ve `build-tools/34.0.0`) ve JDK 17+ kuruluysa PowerShell üzerinden tek komutla imzalı APK üretebilirsiniz:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```
