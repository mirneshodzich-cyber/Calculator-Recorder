# v11 – Diktafon, eksterni šifrirani .cjn sef, obavijesti

**Na osnovu v10.1 build-fix izdanja.** Prijašnje funkcije ostaju: snimanje kamere/ekrana, izbor objektiva, 90-minutni prekid nakon izlaska iz aplikacije, AES-256-GCM kompatibilnost SVR1/SVR2, stvarni napredak i prekid pregleda/izvoza, jedan transfer odjednom te pojedinačno brisanje.

## Nove funkcije

- **Diktafon:** odaberi „♫ Diktafon”, dopuštenje za mikrofon, zatim pokreni/zaustavi. Snima originalni AAC-LC/MPEG-4 audio na 48 kHz / 256 kbps (.m4a) i šifrira ga u isti AES-256-GCM sef. Reprodukcija zvuka unutar aplikacije koristi Android MediaPlayer s pauzom i pozicioniranjem. Izvoz u `Music/Calculator Exports/`, bez ponovne kompresije. Foreground service ima `microphone` tip i vidljivu obavijest.
- **Zasebni `.cjn` fajlovi:** novi šifrirani zapisi se čuvaju kao `system_XXXXXXXXXXX.cjn` u `Android/data/com.privacystudio.calculator/files/SecureArchive/` na aplikacijskoj eksternoj pohrani. To je **izvan APK-a**, nije u galeriji, ali ostaje pod kontrolom aplikacije (na Android 11+ pristup iz običnih file-managera može biti ograničen). Podaci u `.cjn` su postojeći SVR2 AES-GCM bajtovi, samo drugo ime i fizička lokacija. Naziv/ekstenzija nisu dodatna sigurnosna zaštita.
- **Migracija starih snimaka:** `RecorderStore.repairIndex()` kopira stare `.svr` fajlove iz privatnog internog sefa u eksterni `system_<stari-id>.cjn`, radi `fsync` i provjeru veličine; tek potom uklanja staru kopiju. Indeks i Keystore alias ostaju isti. Ako nema vanjske pohrane, zapisivanje se prekida uz grešku umjesto nesigurnog kopiranja.
- **Brisanje stvarnog fajla:** „Obriši” briše odgovarajući `.cjn` i privremeno dešifrirani `.mp4`/`.m4a` cache, a ne samo stavku iz UI-ja. Već izvezene kopije u galeriji nisu dio sefa i ostaju; Android/flash storage ne garantuje forenzički sigurno prepisivanje obrisanih sektora.
- **Neutralna ikonica obavijesti:** umjesto kamere prikazuje se neutralan indikator kruga (`ic_recording_status.xml`). Ne uklanja sistemsku statusnu ikonu privatnosti niti samu obavijest.
- **Prilagodljiv naslov:** u Postavkama postavi 1–36 znakova; naslov obavijesti prikazuje `Naziv · 正在录制视频 / 正在录制屏幕 / 正在录音` i ostaje jasna oznaka aktivnog snimanja. Bez zasebnog stop dugmeta, dodirom se otvara Calculator. Promjena se prikazuje odmah, uključujući aktivnu obavijest.

## Vrlo važno: pohrana i nadogradnja

`getExternalFilesDir()` je aplikacijska ekstern(a) pohrana; **Android obično briše tu lokaciju pri deinstalaciji aplikacije**. Android Keystore ključ može se izgubiti pri deinstalaciji i izvezeni šifrirani `.cjn` bez njega neće biti čitljiv. Zato nemoj deinstalirati/brisati app data. Instaliraj APK **preko postojeće instalacije istim potpisnim ključem**. Ako korisnik treba trajni backup nakon deinstalacije, potreban je dodatni izvoz + portabilan sistem ključev(a) (nije dio v11). 

**Kompatibilnost:** v11 otvara SVR1, SVR2 i .cjn; stariji APK-ovi neće znati čitati eksterni `.cjn`. Nemoj vraćati stari APK nakon migracije. Pusti da prvi ulazak u sef dovrši migraciju prije snimanja/brisanja.

## Fajlovi za zamjenu na GitHubu

```
native/java/com/privacystudio/calculator/RecorderService.java
native/java/com/privacystudio/calculator/RecorderStore.java
native/java/com/privacystudio/calculator/SecureRecorderPlugin.java
native/java/com/privacystudio/calculator/PreviewActivity.java
native/res/drawable/ic_recording_status.xml     (NOVI)
www/app.js
www/index.html
README.md
```

`install-native.mjs` već kopira cijeli folder `native/res/drawable`, pa za novu ikonicu nije potrebna izmjena skripte.

**QA:** `node --check` i Playwright UI testovi s lažnim Android pozivima su prošli. Kompletan Android build, Keystore, stvarna kamera/mikrofon, MP4/M4A reprodukcija, migracija na fizičkom uređaju još **nisu testirani**.

---

# v10 – Popravka 0% izvoza, prekida i pojedinačno brisanje

**Promjene u odnosu na v9 (bez smanjenja kvaliteta i bez ponovnog kodiranja videa):**

- `RecorderStore.java` – novi snimci koriste blokovski AES-256-GCM format **SVR2** (256 KiB/blok, svaki blok ima zaseban IV i autentifikacijsku oznaku); otvaranje i izvoz imaju stvaran napredak i provjeru prekida između blokova. Ranije spremljeni **SVR1** snimci se i dalje dešifriraju, bez izmjene originala. SVR2 zapis nosi ukupnu veličinu i kriptografski veže redoslijed/veličinu blokova, pa se nepotpuni fajlovi otkrivaju. Dodano `deleteOne(id)` za pojedinačno trajno brisanje.
- `SecureRecorderPlugin.java` – izdvojen radnik za obradu videa, brže čitanje statusa, `cancelTransfer` sada postavlja oznaku prekida **i prekida aktivni radni thread**; dodan `deleteOne` poziv. Izvoz i pregled međusobno se isključuju.
- `RecorderService.java` – `MediaRecorder.stop()` i MP4 finalizacija rade na IO threadu umjesto blokiranja Android glavne niti. Aktivna sistemska obavijest traje dok se datoteka sprema u sef; čuvaju se neuspjeli privremeni MP4-ovi.
- `www/app.js`, `www/index.html` – napredak, prekid, otkrivanje izgubljenog callbacka, pojedinačno brisanje iz liste i detalja, potvrda prije brisanja.

**Za zamjenu na GitHubu:**

```
native/java/com/privacystudio/calculator/RecorderService.java
native/java/com/privacystudio/calculator/RecorderStore.java
native/java/com/privacystudio/calculator/SecureRecorderPlugin.java
www/app.js
www/index.html
README.md
```

**Važno o kompatibilnosti:** AES ključ u Android Keystoreu i naziv aplikacijskog paketa su nepromijenjeni. Nova v10 aplikacija čita stare SVR1 i nove SVR2 fajlove, ali stari APK ne može čitati nove SVR2 fajlove. Nemoj se vraćati na v9 bez prethodnog izvoza novih snimaka. Ne briši podatke/deinstaliraj aplikaciju; nadograđuj istim potpisnim ključem.

**Brisanje:** iz liste odaberi `✕` ili otvori snimak i odaberi „Obriši snimak iz sefa”. Aplikacija traži potvrdu. Ova radnja trajno briše šifriranu privatnu kopiju, ali namjerno ne briše eventualnu ranije izvezenu MP4 kopiju u galeriji.

**Napredak i prekid:** Za nove SVR2 snimke obrada teče u ograničenim blokovima. Za starije SVR1 fajlove napredak pokazuje pročitane šifrirane bajtove; stariji monolitni GCM format i dalje može imati dužu završnu provjeru. Prekinuti izvoz uklanja nepotpuni MediaStore zapis; pregled uklanja nepotpuni privatni cache MP4. Originalni šifrirani snimak ostaje netaknut.

**Provjere:** prošao `node --check www/app.js`, Playwright simulacija prikaza/procenta/jednog izvoza/oba prekida/brisanja, te Java JCE test blokovskog AES-GCM formata (razne veličine, detekcija preinake i nepotpunog fajla). **Kompletan Android build i ponašanje na fizičkom telefonu nisu ovdje testirani.**

---

# v9 – Napredak, prekid obrade i jedan izvoz u isto vrijeme

**Na osnovu funkcionalne v8 verzije (90 minuta izvan aplikacije).** Ova verzija mijenja isključivo obradu izvoza/pregleda i pripadajući UI; `RecorderService.java`, 90-minutni tajmer, enkripcijski ključ/alias i `.svr` format ostaju isti.

## Šta je novo

- **Stvarni napredak od 0 do 99%** kod izvoza i pripreme privatnog pregleda: stanje se čita od Android plugina (`transferStatus`) i temelji se na obrađenim bajtovima, ne na izmišljenom odbrojavanju. Završni dio (provjera AES-GCM autentifikacije, objava u galeriji ili otvaranje playera) može trajati i nakon 99%.
- **Prekini izvoz / Prekini otvaranje**: signal za prekid preko `cancelTransfer`; pri prekidu izvoza nepotpuni `MediaStore` unos se briše, a pri prekidu pripreme pregleda briše se nepotpuna privremeno dešifrirana MP4 kopija.
- **Samo jedan transfer u isto vrijeme**: zaštita u UI-ju i zasebno u Android pluginu preko `AtomicReference`. Dok jedan snimak traje, drugi izvoz/pregled nije dozvoljen.
- **Odvojena obrada stanja/liste sefa**: lagane operacije čitanja liste i dijagnostike rade u posebnom executor-u, kako ne bi ostale iza dugotrajnog izvoza.
- **Bez pada kvaliteta**: i pregled i izvoz dešifriraju originalni MP4 bajt-po-bajt, bez transkodiranja. Originalni šifrirani zapis ne mijenja se prekidanjem.

**Izmijenjeni fajlovi:** `www/index.html`, `www/app.js`, `native/java/com/privacystudio/calculator/SecureRecorderPlugin.java`, `native/java/com/privacystudio/calculator/RecorderStore.java`, `README.md`.

## Test na uređaju

1. Instaliraj kao **update** preko v8, potpisan **istim ključem**; nemoj deinstalirati ili brisati app data.
2. Otvori sef, izvezi veći video, provjeri da postotak i broj MB rastu. Pokreni drugi izvoz: njegov gumb mora biti onemogućen.
3. Prekini prvi izvoz na 20–50%. Provjeri da nepotpuna datoteka nije u galeriji i da original ostaje u sefu.
4. Otvori video za privatni pregled, pritisni Prekini tokom pripreme, zatim pokreni ponovno. Nakon uspjeha player se treba otvoriti normalno.
5. Provjeri da je 90-minutni automatski prekid snimanja ostao nepromijenjen.

**QA:** `node --check` prolazi. ZIP struktura je provjerena. Nije izvršen potpuni Android build niti testiran Android Keystore/MediaStore na fizičkom uređaju. Posebno je važno testirati vrlo velike video snimke: Android Keystore/GCM i pojedini uređaji mogu imati performansna ograničenja.

---

# v8 – 90 minuta izvan aplikacije

- Promijenjeni fajlovi: `native/java/com/privacystudio/calculator/RecorderService.java`, `www/app.js`, `www/index.html` i `README.md`.
- Android servis zaustavlja aktivno snimanje nakon 90 minuta neprekidno izvan aplikacije, uz zadržavanje postojeće vidljive obavijesti.
- Povratak u glavni ekran ili ugrađeni player resetuje tajmer. Za vrijeme otvorene aplikacije nije aktivan 90-minutni timeout.
- Ostale funkcije snimanja, enkripcije, pregleda i izvoza nisu mijenjane.
- **Nije testirano na fizičkom uređaju niti potpuno kompilirano u Android APK u ovom okruženju.**

---

# v7 – Pregled i izvoz iz sefa

- **Izmijenjeni fajlovi:** `www/app.js`, `www/index.html`, `native/java/com/privacystudio/calculator/SecureRecorderPlugin.java`, `native/java/com/privacystudio/calculator/RecorderStore.java`, `native/java/com/privacystudio/calculator/MainActivity.java`, `scripts/install-native.mjs`, `README.md`.
- **Novi fajl:** `native/java/com/privacystudio/calculator/PreviewActivity.java`.
- Novi izvorni Android video-player unutar aplikacije za pregled MP4; ne ovisi o Capacitor WebView-u i `file://` putanji. Privremeno dešifriran MP4 briše se pri zatvaranju playera.
- Svaki zapis u sefu ima zasebno **Izvoz** dugme; nije potrebno prvo reprodukovati. Modal ima posebno **Pusti video u aplikaciji**.
- Izvoz stvara nešifriranu kopiju u `Movies/Calculator Exports/`, postavlja Android MediaStore `IS_PENDING=0` ; nema reenkodiranja ni transkodiranja.
- Ako se vidi **Privremeni MP4** u dijagnostici, ali **Šifrirani fajlovi: 0**, prvo pritisni **Provjeri i obnovi pronađene snimke**. Video ne može biti pregledan/izvezen iz sefa dok ga Android uspješno ne šifrira i indeksira.
- **Ne deinstaliraj aplikaciju i ne briši podatke:** šifrirani snimci i ključ mogu postati nepovratni. APK mora biti potpisan istim ključem kao prethodno instalirana verzija.
- Validacije: `node --check`, arhiva i statički pregled. **Potpuni Android build i reprodukcija na uređaju nisu potvrđeni** u ovom okruženju.

---

# Calculator / Privacy Recorder — Capacitor 8 Android

Ovo je **Android izvorni projekt**, s interaktivnim `www/index.html` pregledom i vlastitim native Java pluginom. Nije gotov APK: potrebno ga je sastaviti pomoću Android SDK-a ili GitHub Actions. Nije testiran na stvarnom Android uređaju u ovom okruženju.

## v6: Ispravka AES-GCM IV greške (na osnovu dijagnostike v5)

Dijagnostika je potvrdila: **`Caller-provided IV not permitted`**. U v5 `RecorderStore.encryptRecording()` je unaprijed generisao IV i dodavao ga u `Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(...))`. **Android Keystore odbija IV koji je aplikacija sama poslala.**

- Sada `Cipher.init(Cipher.ENCRYPT_MODE, key())` dopušta Android Keystoreu da generiše sigurni 12-bajtni IV, a `cipher.getIV()` sprema stvarni IV u postojeći SVR1 format (`MAGIC` + 12-bajtni IV + ciphertext/tag).
- Dešifriranje i izvoz ostaju kompatibilni s formatom SVR1; **nije kreiran novi ključ i ne mijenja se AndroidKeyStore alias**.
- `RecorderService` i UI nisu mijenjani. Nakon ažuriranja u sefu klikni **„Provjeri i obnovi pronađene snimke”** da obradi sačuvani 47,5 MB MP4 i eventualne druge privremene snimke.
- Oporavak radi samo ako je MP4 ispravno dovršen i ako je update instaliran preko postojeće aplikacije **istim Android potpisom**. Ne deinstaliraj i ne briši podatke aplikacije.

**Izmijenjen je samo jedan izvorni fajl:** `native/java/com/privacystudio/calculator/RecorderStore.java`. Ovaj ZIP sadrži ostatak v5 bez drugih funkcionalnih izmjena.

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
- Snimanje se prekida nakon **90 minuta neprekidno izvan aplikacije**. Odbrojavanje počinje kada se aplikacija napusti, čak i kada je zadatak uklonjen iz nedavnih aplikacija. Povratak u aplikaciju poništava tajmer; novi izlazak pokreće novih 90 minuta. Dok je aplikacija otvorena, ovo ograničenje se ne odbrojava. Zaustavljanje može kasniti do 5 sekundi zbog provjere tajmera.
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


## v12: korisnički odabrana pohrana (SAF)

- U aplikaciji idi na **Postavke → Vanjska pohrana → Odaberi folder na telefonu**.
- U Android sistemskom odabiru otvori npr. **Documents**, napravi vlastiti folder **Moji šifrirani zapisi** i odaberi ga. Na Android 11+ ne može se izabrati root memorije ili root Downloads folder.
- Ovo je neovisni folder izvan `Android/data`, `getExternalFilesDir()` i APK-a. Za rad sa njim koristi se `ACTION_OPEN_DOCUMENT_TREE` + trajna URI dozvola. Nisu potrebne opšte dozvole za pristup svim fajlovima.
- **Prije prvog novog snimanja moraš izabrati folder.** Bez foldera UI vraća grešku i ne pokreće kameru/mikrofon.
- Za ranije šifrirane `.cjn` fajlove pritisni **Premjesti stare šifrirane snimke**. Stara datoteka ostaje dok nova nije uspješno provjerena SHA-256 poređenjem; tek tada se uklanja iz app-specific memorije. Nikada ne deinstaliraj staru aplikaciju prije potvrde migracije.
- Svi novi šifrirani zapisi se pišu direktno u odabrani folder. Tokom snimanja originalni MP4/M4A privremeno postoji u privatnoj memoriji, pa zauzeće **User data** privremeno raste. Nakon ispravnog završetka/šifriranja, privremeni fajl se uklanja; tokom pregleda privremena dešifrirana kopija može kratkotrajno zauzimati cache.
- Sam `.cjn` fajl može preživjeti deinstalaciju, ali ključ iz **Android Keystore** obično ne. Bez odgovarajućeg ključa šifrirani fajl neće biti moguće dešifrirati nakon deinstalacije ili brisanja podataka.
- Postojeći sef, AES-256-GCM (SVR1 i SVR2), diktafon, snimanje videa/ekrana, eksport i brisanje ostaju. Ne mijenjaj aktivni folder dok on sadrži šifrirane datoteke.
- Pohrana preko SAF može biti i na SD kartici / podržanom provideru. Izaberi **lokalni folder na telefonu** za rad s velikim videozapisima. Daljinski/cloud provideri mogu biti spori ili zahtijevati mrežu.
- Android foreground obavijesti i sistemski indikatori aktivnog snimanja ostaju vidljivi.

### Poznato ograničenje

Nije proveden stvarni Android build / test snimanja na telefonu u ovom okruženju. Pohrana preko SAF zavisi od kompatibilnosti Android `DocumentsProvider` implementacije. Testiraj prvo novi kratki zapis, pogledaj ga u sefu, izvezi i obriši, pa tek onda migiraj veće postojeće arhive.
