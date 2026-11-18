# Releasing on Google Play

Everything in the repository is ready: signing comes from GitHub secrets, CI builds a signed
App Bundle (`rosso-remote.aab`) and APK, the app targets Android 16 (API 36) as Play has required
since 31 August 2026, it ships in English, Russian and Uzbek, and it shows a Google AdMob banner
with Google's consent form. What is left needs your accounts, so only you can do it.

## 1. Signing key (5 minutes)

The old `app/remote.keystore` and its password were public, so it is retired. A new **upload key**
was generated for you (it is in the project files, not in the repo).

1. In GitHub → repo **Settings → Secrets and variables → Actions → Secrets**, add:
   `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`upload`),
   `RELEASE_KEY_PASSWORD`. The values are in `github-secrets.txt` next to the key.
2. Save `upload.jks` and its password in a password manager. Then delete the project-files copy.
3. Without these secrets CI still builds, but unsigned, and does not publish a release.

Phones that installed an older APK from GitHub must uninstall it once, because the new builds
are signed with a different key. The Play version and the GitHub APK are also separate installs:
Play re-signs the app with Google's own key (Play App Signing).

## 2. AdMob (20 minutes, then a few days for approval)

1. Sign up at [admob.google.com](https://admob.google.com) with the same Google account you
   will use for Play. Fill in payment details (address, tax info, bank account for payouts).
2. **Apps → Add app → Android →** "not listed on a supported app store yet" → name *Rosso Remote*.
   Copy the **App ID** (`ca-app-pub-…~…`).
3. In that app, **Ad units → Add ad unit → Banner**. Copy the **ad unit ID** (`ca-app-pub-…/…`).
4. In GitHub → **Settings → Secrets and variables → Actions → Variables**, add
   `ADMOB_APP_ID` and `ADMOB_BANNER_ID` with those two values. Until then the app shows Google's
   test ads, which earn nothing. Never tap your own live ads.
5. **Privacy & messaging**: create and publish a *European regulations* (GDPR) message and a
   *US state regulations* message for the app. The app already shows them where needed and has
   an "Ad choices" button under **About · Privacy policy**.
6. After the app is live on Play, link it in AdMob (**App settings → Link to app store**).
   Optional but recommended: publish an `app-ads.txt` on a website you list as the developer
   website in Play (AdMob shows the exact line to put in it).

## 3. Play Console account (one time)

1. Register at [play.google.com/console](https://play.google.com/console) as a **personal**
   account: US$25 one-time fee (needs an international Visa or Mastercard; local-only Humo/Uzcard
   cards are not accepted), identity verification with your passport, and verifying a phone
   with the Play Console app.
2. New personal accounts must run a **closed test with at least 12 testers who stay opted in
   for 14 days in a row** before they can apply for production. Start recruiting testers early
   (friends' Gmail addresses or a Google Group).

## 4. Create the app in Play Console

**Create app**: name *Remote for Rosso & Android TV*, default language English (US), App, Free.

Then work through **Dashboard → Set up your app**:

| Section | Answer |
|---|---|
| Privacy policy | `https://github.com/icmrnbw/tv-remote/blob/main/PRIVACY.md` |
| App access | All functionality is available without special access. Note: "Needs a smart TV on the same Wi‑Fi. Without one, the TV list shows no TVs and buttons report 'Not connected'." |
| Ads | Yes, the app contains ads |
| Content rating | Category *Utility / productivity / communication / other*; answer No to all content questions; no user interaction or sharing; no purchases. Expect *Everyone / 3+* |
| Target audience | 18 and over (keeps the app out of the Families program, which restricts ads) |
| Data safety | See below |
| Advertising ID | Yes, used for *Advertising or marketing* |
| Government / financial / health apps | No / none / no |
| News app | No |

**Data safety** (what the AdMob SDK collects; the app itself collects nothing):

- Does the app collect or share user data? **Yes**. Is all of it encrypted in transit? **Yes**.
  Can users request deletion? **No** (the app holds no user data on a server).
- **Location → Approximate location**: collected and shared; *Advertising or marketing*, *Analytics*,
  *Fraud prevention, security and compliance*. Processed ephemerally: No. Collection is required.
- **Device or other IDs**: collected and shared; same purposes and answers.
- **App activity → App interactions**: collected and shared; *Advertising or marketing*, *Analytics*.
- **App info and performance → Crash logs, Diagnostics**: collected and shared; *Analytics*.
- Everything else: not collected. Google's own guide for AdMob apps:
  [support.google.com/admob/answer/10972802](https://support.google.com/admob/answer/10972802).

**Store listing**: copy texts from [store/listing.md](store/listing.md) and upload
[store/graphics](store/graphics/) plus screenshots (run **Actions → Store screenshots**).
Add Russian and Uzbek under **Manage translations**.

**Countries**: Uzbekistan, plus any others you want (Kazakhstan, Kyrgyzstan, Tajikistan, Russia…).

## 5. Upload and test

1. Merge to `main`. CI publishes release `v1.1.N` with `rosso-remote.aab`.
2. **Test and release → Testing → Internal testing → Create release**. Accept **Play App Signing**
   (let Google create the app signing key) and upload `rosso-remote.aab`. Add yourself as tester
   and install from the Play link.
3. **Closed testing**: same AAB, add your 12+ testers, keep it running 14 days.
4. **Production → Apply for production**, answer the questions about the test, then roll out.
   Each new upload needs a higher version code; CI's run number takes care of that.

## Things to know

- **The name "Rosso".** Rosso is someone else's brand. Play rejects apps that look official.
  The listing title says *Remote for …*, and the app and listing state it is independent and
  unaffiliated. If Play still objects, rename to something like *Smart TV Remote* (change
  `app_name` in `app/src/main/res/values/strings.xml`).
- **Honest compatibility.** Full control is confirmed only for the Android TV protocol (tested
  against a simulated TV in `tools/`). Rosso Linux sets get volume, mute and YouTube/Netflix only.
  Bluetooth mode is untested on a real TV. Reviews will reflect this, so the listing says it plainly.
- **Ads SDK.** The app uses `play-services-ads` 25.x. Google now calls this the legacy SDK and
  recommends the "Next-Gen" SDK; 25.x keeps serving ads until mid-2028, but plan a migration in 2027.
- The Uzbek and Russian texts were written by Claude; a quick read by a native speaker is worth it.
