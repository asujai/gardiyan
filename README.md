# Gardiyan

Gardiyan, seçilen uygulamalar için günlük süre sınırı koyan bir Android uygulamasıdır. Uygulama ön plana geldiğinde Accessibility Service ile algılanır; süre bittiğinde overlay kilit ekranı gösterilir.

## Yerelde Çalıştırma

Gerekenler:

- Android Studio
- Android SDK
- USB hata ayıklaması açık fiziksel cihaz veya emulator

Adımlar:

1. Android Studio'da bu klasörü açın.
2. Gradle senkronizasyonunun tamamlanmasını bekleyin.
3. Cihazınızı seçip uygulamayı çalıştırın.
4. İlk açılışta istenen erişilebilirlik, kullanım istatistikleri, overlay ve pil optimizasyonu izinlerini verin.

## Test

```powershell
./gradlew.bat test
```

## Notlar

Bu proje artık Gemini/API anahtarı gerektirmez. Eski AI Studio şablonundan kalan `.env` ve Gemini bağımlılıkları kaldırılmıştır.
