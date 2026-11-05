# Calculator / Privacy Recorder — Capacitor 8 Android

Ovo je **Android izvorni projekt**, s interaktivnim `www/index.html` pregledom i vlastitim native Java pluginom. Nije gotov APK: potrebno ga je sastaviti pomoću Android SDK-a ili GitHub Actions. Nije testiran na stvarnom Android uređaju u ovom okruženju.

## Funkcije

- Kalkulator kao početni ekran i kao ikonica aplikacije; `73 + 27 =` otvara privatnu navigaciju. Izraz se može promijeniti u postavkama. **Ovo nije snažan oblik autentifikacije**; za ozbiljnu zaštitu podataka potreban je zaseban PIN/biometrija.
- Prednja/zadnja kamera preko Android Camera2 API-ja; snimanje ekrana preko MediaProjection (nova sistemska dozvola **svaki put**).
- Foreground service: snimanje može nastaviti nakon `Home`, zaključavanja UI-ja ili uklanjanja taska iz recent apps dok Android i uređaj dopuštaju servis. **Ne radi nakon `Force stop`, ubijanja procesa ili blokade servisa od proizvođača**.
- Snimanje se prekida nakon **20 minuta bez interakcije s aplikacijom**, uključujući kada nije otvorena. Pauza timera po dodiru/otvaranju. Odbrojavanje počinje od posljednje interakcije.
- Ongoing obavijest na pojednostavljenom kineskom: `正在录制视频`, bez vlastitog Stop dugmeta; tap obavijesti otvara app. Androidovi sistemski indikatori/čipovi ostaju.
- MP4 je unutar interne app memorije privremeno tijekom snimanja, zatim se po zaustavljanju šifrira AES-256-GCM ključem iz Android Keystorea; nakon uspjeha original se briše, a video se ne dodaje u galeriju. **Privremeni nešifrirani MP4 postoji dok snimanje i obrada traju**; ovo nije potpuna enkripcija u realnom vremenu.
- Pregled u sefu: datoteka se privremeno dešifrira u app-private cache; nakon zatvaranja pregleda/pozadine se briše. Izvoz ide direktno u `MediaStore`/`Movies/Private Video Exports`, bez ponovnog kodiranja. Izvezena kopija je vidljiva u galeriji i više nije šifrirana.
- Snimanje se kodira direktno u H.264/AAC, do 4K gdje Camera2/MediaRecorder podržava. **Video kompresija je dio snimanja**; ne postoji dodatna transkodacija pri spremanju/pregledu/izvozu. Rezolucija, fps i bitrate ovise o Android enkoderu.

## Napravi Android APK na računaru

Potrebni su Node.js 22+, JDK 21, Android Studio/Android SDK. Koraci iz korijena projekta:

```bash
npm install
npx cap add android
node scripts/install-native.mjs
npx cap sync android
cd android
./gradlew assembleDebug
```

Rezultat: `android/app/build/outputs/apk/debug/app-debug.apk`. Na Windowsu umjesto `./gradlew` koristi `gradlew.bat`.

### GitHub Actions (automatski build APK)

1. Kreiraj GitHub repozitorij.
2. Uploadaj **sadržaj** ovog ZIP-a u root repozitorija, uključujući `.github/workflows/android-debug.yml`.
3. Otvori **Actions → Build Calculator Android APK → Run workflow** (ili pokreni na push).
4. Preuzmi artifact **Calculator-debug-apk**; u njemu je `app-debug.apk`.

Workflow koristi Capacitor 8.6.0 i JDK 21. `android/` scaffold se generira tokom builda; native datoteke i AndroidManifest se automatski dodaju skriptom.

## Važna tehnička ograničenja i QA

1. Testiraj osobito **prednju/zadnju kameru**, i 4K kompatibilnost pojedinih uređaja: Camera2 veličina nije uvijek jednaka MediaRecorder mogućnosti. Ako MediaRecorder javlja grešku, u native kodu dodaj fallback na 1080p. Za svaki novi pokušaj prvo zaustavi staru sesiju.
2. Android 14+ ograničava pokretanje servisa s kamerom iz pozadine; pokreni snimanje dok je aplikacija vidljiva. MediaProjection traži odobrenje za svaku sesiju i može se sistemski prekinuti (npr. zaključavanjem ekrana).
3. Kod dužih zapisa šifriranje može trajati nakon zaustavljanja. Testiraj da li OEM dopušta servisu da završi obradu. Trenutna verzija nema UI s greškom za neuspjeli native start/save, samo Android Logcat; smatraj je **razvojnom verzijom**, ne aplikacijom potvrđene pouzdanosti.
4. Android Keystore ključ se ne može vratiti nakon brisanja aplikacije: backup samih `.svr` datoteka nije dovoljan za oporavak. Nema cloud-backupa.
5. Prije snimanja osiguraj da osobe i prostor koji snimaš dozvoljavaju snimanje, gdje je to potrebno.
6. Za ekranske klipove trenutno se bilježi slika ekrana, **bez internog sistemskog zvuka**. Kamerom se snima zvuk mikrofona uz dozvolu.
7. Obrada novonastalih MP4 datoteka i sirovi MP4 u `capture_pending` nemaju crash-recovery mehanizam; prisilno gašenje tokom snimanja može izgubiti trenutni klip.

## Gdje je šta

- `www/index.html` — web UI (radi kao vizualni pregled u browseru)
- `www/app.js` — UI logika + pozivanje native funkcija
- `native/java/.../SecureRecorderPlugin.java` — most WebView ↔ Android
- `native/java/.../RecorderService.java` — Camera2 / MediaProjection snimanje i watchdog
- `native/java/.../RecorderStore.java` — AES-GCM sef, pregled i izvoz
- `scripts/install-native.mjs` — kopira native kod u generirani Android projekt
