# Rosh Schedule — a self-built WhatsApp message scheduler

(Repo/folder name: `scheduler-android`. The app itself is named and labeled
"Rosh Schedule" on your phone — the Kotlin package is still
`com.rosh.wascheduler` internally, since renaming that touches every file's
package declaration for no real benefit.)

You wrote this. You compile it. You install it yourself, never through the
Play Store or any third-party APK site. That's the whole point.

## Current feature set

- Schedule a text message to an **individual** contact (by phone number) or a
  **WhatsApp group** (by its exact chat-list name), for a specific future
  date and time.
- Real **@mentions** in a group message: write `@[Full Name]` anywhere in the
  message and it's picked from WhatsApp's own suggestion popup at send time,
  so it's a genuine tappable/notifying mention, not literal text.
- A **View / cancel scheduled messages** screen listing everything still
  pending, with a Cancel button per item.
- Text only — no photo/document attachments (you said that's not needed).
- Regular personal WhatsApp only (not WhatsApp Business).

## What this actually does (read before building)

WhatsApp (personal) has **no scheduling API and no built-in "send later" feature**
as of September 2026 — it's only in beta testing on Meta's side, not something an
outside app can call. So there is no clean, official way to schedule a message
from *your own personal number*. Every app that claims to do this (Tasker,
"WA Scheduler" apps on the Play Store, etc.) does the same thing under the hood:

1. It opens WhatsApp with your message pre-filled, using the standard
   `https://wa.me/<number>?text=<message>` deep link (this part is 100% official
   and documented by Meta).
2. It then **taps the Send button for you**, using Android's Accessibility API,
   at the time you scheduled.

There is no way around step 2 without either (a) automating the UI like this, or
(b) switching to Meta's official **WhatsApp Business Platform (Cloud API)**,
which requires a *separate* business phone number and a Meta developer account,
and is meant for business-initiated messaging, not "text my friend later from my
own number."

This project implements (a), because you said you want to keep using your
existing personal number and don't want to install anyone else's black-box app.
Building it yourself means you can read every line, and you're not trusting a
random Play Store developer with Accessibility permissions on your phone.

**Important caveats, honestly stated:**

- Automating WhatsApp's UI like this is technically against WhatsApp's Terms of
  Service (they prohibit automated/unofficial clients). For sending a handful of
  personal reminders to people you already message, the practical ban risk is
  low — but it is not zero, and it's not "officially sanctioned" the way the
  Business API is. Use it for yourself, not for bulk/marketing sends.
- WhatsApp changes its internal view IDs between app updates. The individual-
  chat path (find Send, click it) is simple and fairly resilient. The
  **group path is not** — it drives WhatsApp through several screens in a row
  (tap search, type a query, tap a result, type into the compose box, wait for
  the mention popup, tap a suggestion, tap Send), and every one of those steps
  depends on a resource ID or visible text that WhatsApp could rename in an
  update, or could be running as newer Jetpack Compose UI that doesn't expose
  stable resource IDs at all. Treat group sending as "usually works, verify
  each new build" rather than "fire and forget" — see "If a step stops
  working" below.
- I have not compiled this in this environment (no Android SDK here) — treat it
  as a working skeleton, not a finished, tested app. It's a normal-sized
  multi-activity app; expect maybe a few small fixes on first build (missing
  resource, a Gradle/AGP version mismatch with whatever Android Studio version
  you have).

## Why Claude couldn't just build and install this for you

I tried, from two places: this cloud sandbox, and the automation shell on your
linked Mac. Both are network-restricted and can't reach Android's SDK/build
servers (`dl.google.com`, `maven.google.com` — blocked outright), so neither can
compile an Android project. And even with a compiled APK in hand, neither
environment has a USB/adb path to your physical phone — there's no channel from
here to a screen you can tap "Install" on. So the last mile is unavoidably
yours; two ways to do it, pick whichever's less friction for you:

**Option A — Android Studio on your own Mac (not this sandbox).** Your Mac's
normal network (outside this locked-down automation VM) can reach Google's
servers fine. Steps are in "Building and installing it yourself" below —
it's a real Android Studio install (~1GB, one-time) plus a single Run click.

**Option B — GitHub Actions builds it for you, and you download straight to
your phone.** This project includes `.github/workflows/build-apk.yml`. Push
this folder to your private repo, then trigger the build (push to `main`, or
Actions tab → "Build debug APK and publish a Release" → Run workflow). It
deliberately publishes the result as a **GitHub Release** rather than a plain
workflow artifact — Actions artifacts come zipped and the official GitHub
mobile app is notoriously bad at downloading them (it just bounces you out to
a browser anyway), whereas a Release asset is one plain `.apk` file you can
grab directly.

To get it onto your phone, no computer involved at all:

1. On your phone, open your repo in a browser (Chrome/Safari — the GitHub app
   works too but browser is more reliable for this) and go to the **Releases**
   page (or tap the release note on the right side of the repo's main page).
2. Tap the latest release (e.g. "scheduler-android build 3"), then tap the
   `app-debug.apk` file under Assets.
3. Your phone downloads it. First time, Android will ask you to allow
   installs from that browser/app — approve it (this is the "install from
   unknown sources" permission, and it's scoped to just that browser, not a
   blanket setting).
4. Open the downloaded file from your notifications or Downloads folder and
   tap Install.

Every build is debug-signed (not for a store, just for your own device) and
tagged with the commit it came from, so you always know exactly what code
produced the APK sitting on your phone.

The workflow itself only uses GitHub's own `setup-java` action, the Gradle
Foundation's official `setup-gradle` action, Android's command-line SDK tools
pulled directly from Google's own servers, and GitHub's own `gh` CLI to publish
the release — no third-party marketplace actions in the chain.

## How it's built, in one paragraph

`MainActivity` is a form: Individual/Group toggle, recipient, message, date and
time. On "Schedule", it stores the entry in a local SQLite table (via
`ScheduleStore` — nothing leaves your device) and asks `AlarmManager` to fire
an exact alarm at that time (`setExactAndAllowWhileIdle`, so it still fires
under Doze). When the alarm fires, `AlarmReceiver` either opens WhatsApp
directly to that contact with the message pre-filled (individual — the
official `wa.me` deep link), or just brings WhatsApp's chat list to the front
and hands off a "job" describing the group name and message (group — no deep
link exists for a specific group). Either way, `WhatsAppAccessibilityService`
— granted once in Android's Accessibility settings, and structurally limited
to only ever seeing WhatsApp's own window — takes it from there: for an
individual chat it just finds and clicks Send; for a group it drives the
search box, opens the group, types the message (resolving any `@[Name]`
mention through WhatsApp's real suggestion popup), then clicks Send.
`PendingActivity` lists whatever's still in that SQLite table so you can
cancel an entry before its alarm fires.

## Files

- `app/src/main/java/com/rosh/wascheduler/MainActivity.kt` — the scheduling
  form (individual/group toggle, recipient, message, date/time).
- `app/src/main/java/com/rosh/wascheduler/PendingActivity.kt` — lists and
  cancels scheduled messages.
- `app/src/main/java/com/rosh/wascheduler/ScheduleStore.kt` — local SQLite
  helper (nothing here ever leaves the device).
- `app/src/main/java/com/rosh/wascheduler/AlarmReceiver.kt` — fires when the
  alarm goes off; branches individual vs. group.
- `app/src/main/java/com/rosh/wascheduler/WhatsAppAccessibilityService.kt` —
  drives WhatsApp's UI: click Send (individual) or search → open → type
  (with mentions) → Send (group).
- `app/src/main/AndroidManifest.xml` — permissions + component registration.
- `app/src/main/res/xml/accessibility_service_config.xml` — declares what the
  accessibility service is allowed to watch (WhatsApp's package only — it is
  structurally unable to see or touch any other app, or the lock screen).
- `app/src/main/res/layout/activity_main.xml`, `activity_pending.xml` — the
  two screens' UI.
- `app/src/main/res/drawable/ic_launcher_*.xml`,
  `res/mipmap-anydpi-v26/ic_launcher*.xml` — the app icon (see "About the
  icon" below).

## Building and installing it yourself

1. Install Android Studio (free, from Google) on a computer.
2. Open this `scheduler-android` folder as an existing project.
3. Let Gradle sync (it'll download the Android Gradle Plugin / Kotlin — you can
   audit `build.gradle` yourself, nothing unusual is in there).
4. Connect your phone over USB with Developer Options → USB debugging on, or use
   Android Studio's wireless pairing.
5. Click Run. Studio compiles the APK and installs it directly via `adb install`
   — this never touches the Play Store or any APK-sharing site, so it's exactly
   as trustworthy as the code you're looking at.
6. On the phone: open the app once, grant it "Display over other apps" if
   prompted (needed so it can bring WhatsApp to the front), then go to
   Settings → Accessibility → Installed apps → Rosh Schedule and turn it on.
   This is the one permission Android will show a scary-sounding warning for
   — that's normal for anything that clicks buttons on your behalf, and is
   exactly why you're building it yourself instead of granting it to someone
   else's app.
7. Also exempt the app from battery optimization (Settings → Apps → Rosh
   Schedule → Battery → Unrestricted), otherwise Doze can delay the alarm by
   several minutes on some OEM skins (Samsung/Xiaomi are notably aggressive
   about this).

## About the lock screen — what I did *not* build, and why

You asked whether you could just hand the app your phone PIN so it can unlock
the screen and send even while locked. I didn't build that, and it isn't a
matter of effort — Android doesn't allow it, for anyone:

The lock screen (the "keyguard") runs in its own hardened system window that
is deliberately walled off from every app, including ones with Accessibility
permission. An accessibility service can read and click things inside apps
*after* you've unlocked the phone, but it cannot see, read, or inject taps or
text into the keyguard itself — that restriction exists specifically to stop
exactly this kind of automated unlocking, since otherwise any app with
Accessibility access could silently unlock every phone that granted it. There
is no permission, including Accessibility, that lifts this. Storing your PIN
in an app would also just be a needless security liability even if it worked.

What actually happens if the phone is locked when the alarm fires: the alarm
itself still fires reliably (that part isn't blocked), but WhatsApp can't be
brought to the front over a locked screen the way it can when the screen is
on, so the send effectively waits until you next unlock the phone — at which
point it should proceed automatically once WhatsApp's window appears. For
messages you actually need sent at an exact moment, keep the phone unlocked
(or briefly turn the screen on) around the scheduled time; there's no way
around that short of not having a lock screen at all, which defeats the point
of one.

## About the icon

The launcher icon is an original mark I drew directly as Android vector XML
(`ic_launcher_background.xml` / `ic_launcher_foreground.xml`) — a white
message bubble with a small clock badge on it, on an indigo background. It's
not a copy of WhatsApp's or anyone else's logo; it's just meant to read as
"a message, timed" at a glance. Open the two files if you want to tweak the
colors — they're plain `<path>` shapes, easy to edit by hand.

## If a step stops working (send button, search, mention popup, …)

WhatsApp's send button currently has resource ID `com.whatsapp:id/send`; the
group flow also looks for `com.whatsapp:id/menuitem_search` (search icon) and
`com.whatsapp:id/entry` (message box), falling back to a generic "find any
editable/clickable-by-text node" search when those aren't found. If a
WhatsApp update changes one of these and a step stops working:

1. Open WhatsApp to the relevant screen (a chat with text typed in the box,
   the search screen, or a group chat with the mention popup open).
2. In Android Studio: Tools → Layout Inspector, targeting the WhatsApp
   process.
3. Click the element in the inspector's mirrored view; it shows you the
   current resource ID / content description.
4. Update the matching `id` string in `WhatsAppAccessibilityService.kt`.

This keeps the whole loop — read the ID, fix the code, rebuild, reinstall — under
your own control, with nothing depending on a third party keeping their app
updated for you. If `giveUp(...)` fires (you'll see a toast naming the stage),
that's exactly this happening — check that stage's resource ID first.

## Simpler alternative worth considering

If you're ever open to it: Meta's **WhatsApp Business Platform (Cloud API)** lets
you send messages from a script with a plain HTTPS POST, no UI automation, no
ToS gray area, no accessibility permission. The catch is it needs a second,
dedicated business phone number (it takes over WhatsApp on that number) and a
free Meta developer/business account. If you'd want that instead — e.g. for a
"send myself reminders" bot number — say so and I'll put together that version;
it's a much smaller and more robust piece of code than this one.
