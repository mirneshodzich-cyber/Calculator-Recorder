# Calculator / Privacy Recorder — Capacitor 8 Android

Ovo je **Android izvorni projekt**, s interaktivnim `www/index.html` pregledom i vlastitim native Java pluginom. Nije gotov APK: potrebno ga je sastaviti pomoću Android SDK-a ili GitHub Actions. Nije testiran na stvarnom Android uređaju u ovom okruženju.

## v5: Dijagnostika problema sa spremanjem

**Nije potvrđeno da je ovim riješen svaki kvar na svim Android uređajima.** Ovo izdanje adresira konkretne probleme pronađene statičkom analizom v4 i daje jasne rezultate testiranja:

- UI razlikuje **Priprema** od stvarnog **Snimanje u toku**; više ne tvrdi da snima prije `MediaRecorder.start()`.
- Ako kamera/enkoder ne krene za 20 sekundi, proces se prekida uz poruku greške.
- Snimanje kamere ne blokira se ako dozvola za mikrofon nije data; kamera ostaje obavezna, a zvuk je opcionalan.
- Biraju se Camera2 izlazne rezolucije koje uređaj prijavljuje kompatibilnim s H.264 enkoderom. Snima se najveća kompatibilna, do 4K, bez dodatne rekompresije pri spremanju ili izvozu. Nije moguće garantirati 4K ako kodek ne podržava.
- Greška video enkodera postaje čitljiva unutar aplikacije, umjesto da ostane samo u Android Logcatu.
- Nakon završavanja MP4 provjerava se ima li čitljiv video track. Neispravan privremeni fajl ostaje dostupan za dijagnostiku, ne prikazuje se kao uspješno spremljen.
- Oštećen `records.json` zadržava se pod rezervnim nazivom, a sef pokušava vratiti `.svr` zapise.
- U sefu je novo dugme **Dijagnostika snimanja i sefa**, koje prikazuje broj stvarnih šifriranih fajlova, broj evidentiranih fajlova, privremenih MP4 i nedovršenih šifriranih zapisa, veličinu posljednjeg snimanja, slobodnu memoriju i posljednju grešku.

### Testiranje v5

1. **Nemoj deinstalirati v4 niti očistiti podatke aplikacije.** Za update moraš imati APK sa istim signing ključem kao instalirana v4, inače možeš izgubiti AES ključ i snimke.
2. Nakon uspješnog updatea otvori aplikaciju i opciju **Sef → Dijagnostika snimanja i sefa**. Zapiši stanje prije probnog snimanja.
3. Pokreni kameru (sa dozvolom i vidljivom Android obavijesti) i sačekaj da se status promijeni iz *Priprema* u *Snimanje u toku*. Snimaj 15 sekundi.
4. Pritisni Stop u aplikaciji; sačekaj završetak *Spremanje u sef*. Ako snimak izostaje, opet otvori dijagnostiku i pogledaj posljednju grešku. Ne briši privremene podatke.
5. Ako piše `Privremeni MP4: 1` ili više, probaj **Provjeri i obnovi pronađene snimke**. Ne pokušavaj automatsko brisanje ni reinstalaciju.

**Napomena o kvalitetu:** nema dodanog transkodiranja. Izvorni H.264 kodirani zapis šifrira se bajt-po-bajt. Ograničavanje rezolucije na stvarne mogućnosti enkodera može spriječiti neuspjele startove, ali stvarna rezolucija ovisi o uređaju.


## Šta je novo u v4 (popravka praznog sefa)

- Dok se MP4 šifrira, prikazuje se **Spremanje u sef**; više ne javlja uspjeh prije završetka.
- Ako servis ne uspije pokrenuti snimanje, završiti MP4 ili sačuvati šifrirani zapis, greška se prikazuje na početnoj i u sefu.
- Ako šifriranje ili zapis indeksa ne uspije, postojeći privremeni MP4 se **čuva** umjesto automatskog brisanja.
- Sef automatski vraća u listu kompletne `.svr` fajlove koji su ostali izvan `records.json` zbog prekida procesa.
- U sefu je tipka **Provjeri i obnovi pronađene snimke**. Pokušava uvesti kompletne privremene MP4-ove iz interne memorije; ne može oporaviti neispravno završene videozapise.
- Finalizacija MP4-a radi dok su površine kamere/ekrana još žive. Bez rekompresije pri spremanju ili izvozu.

### Važno: nadogradnja i spašavanje postojećih zapisa

**Nemoj deinstalirati postojeću aplikaciju niti brisati njene podatke.** Enkripcija koristi Android Keystore; deinstalacija može trajno uništiti ključ i zapise. GitHub-hosted buildovi obično koriste novi debug potpis po izvođenju. Ako Android odbije novu verziju zbog različitog potpisa, nemoj deinstalirati staru. Za buduće ponovljive instalacije koristi isti signing key (`CALCULATOR_DEBUG_KEYSTORE_B64` repo secret); key mora biti identičan *onom korištenom za trenutno instalirani APK* da bi ažuriranje bez brisanja podataka radilo. Ako originalni debug ključ nije sačuvan, build se možda neće moći instalirati kao nadogradnja.

V4 ne može povratiti sirove snimke koje je ranija verzija već obrisala niti datoteke uništene Android force-stopom. Snimci ostaju privatni i nisu dostupni drugim aplikacijama bez izvoza.

Za dijagnostiku: snimaj 10–15 sekundi, zaustavi, sačekaj da prođe **Spremanje u sef**, otvori sef i provjeri poruku greške. Ako nema zapisa, klikni **Provjeri i obnovi pronađene snimke**. Pošalji prikazanu grešku ili Android Logcat tag `VideoRecorder`.

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
3. Kod dužih zapisa šifriranje može trajati nakon zaustavljanja. Testiraj da li OEM dopušta servisu da završi obradu. U v5 neuspjeh starta i spremanja prikazuje se u UI; smatraj je **razvojnom verzijom**, ne aplikacijom potvrđene pouzdanosti.
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
