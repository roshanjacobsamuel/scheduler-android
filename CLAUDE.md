# scheduler-android (Rosh Schedule)

## What this is

A self-built Android app that schedules WhatsApp messages (individual chats
and groups, with real @mentions) since WhatsApp has no native "send later"
for personal accounts and Rosh doesn't want to install any third-party
scheduler app or grant a stranger's app Accessibility permission. Built so
every line is auditable — no black-box automation, no third-party
marketplace GitHub Actions, no Play Store.

Repo: https://github.com/roshanjacobsamuel/scheduler-android
App label on the phone: "Rosh Schedule"
Kotlin package: `com.rosh.wascheduler` (kept from before the repo was
renamed from `WaScheduler` to `scheduler-android` — not worth a full package
rename for no functional benefit).

## Why it exists (context for a fresh session)

- WhatsApp (personal) has no scheduling API and no shipped "send later"
  feature as of Sept 2026 — only in Meta's beta testing, not callable by an
  outside app.
- Meta's official WhatsApp Business Cloud API would avoid all of this, but
  it needs a *separate* business phone number — Rosh wants to keep using his
  existing personal number, so that's out for now (offered as a fallback if
  he ever changes his mind — see README "Simpler alternative worth
  considering").
- So the only way to schedule from a personal number is UI automation: open
  WhatsApp with the message pre-filled (official `wa.me` deep link) and tap
  Send via Android's Accessibility API at the scheduled time. This is a grey
  area against WhatsApp's ToS for automated clients — acceptable risk for
  personal reminders, not for anything at scale.

## How it works

- `MainActivity` — form: Individual/Group toggle, recipient, message,
  date/time. Saves to local SQLite (`ScheduleStore`) and sets an exact
  `AlarmManager` alarm (`setExactAndAllowWhileIdle`, survives Doze).
- `AlarmReceiver` — fires at the scheduled time.
  - Individual: opens `https://wa.me/<number>?text=<message>` directly (a
    100% official deep link — no automation involved in this step).
  - Group: no deep link exists for a specific group, so it just launches
    WhatsApp's main chat list and hands a `GroupJob` (group name + parsed
    message segments) to the accessibility service via a static field.
- `WhatsAppAccessibilityService` — scoped via
  `accessibility_service_config.xml` (`android:packageNames="com.whatsapp"`)
  to ONLY ever see WhatsApp windows — structurally can't touch any other
  app, and can't touch the lock screen either (see Feature status table).
  - Individual: finds the send button (`com.whatsapp:id/send`) and clicks it.
  - Group: drives search → open group → type message → Send as a small
    polling state machine (`pollFor`, 300ms interval, per-stage timeout).
    Mentions written as `@[Full Name]` in the message get typed as raw text,
    then the code waits for WhatsApp's own suggestion popup and taps the
    matching name, so it becomes a real, notifying mention — not literal
    text. This is the most fragile part of the app (see below).
- `PendingActivity` — lists everything still in `ScheduleStore` with a
  Cancel button per row (rebuilds the matching `PendingIntent` and calls
  `alarmManager.cancel(...)`).

## Feature status

| Feature | Status |
|---|---|
| Schedule to an individual contact | done |
| Schedule to a group | done, fragile — see "Known fragile points" |
| Real @mentions in group messages (`@[Name]` syntax) | done |
| Cancel a pending scheduled message | done |
| Text messages | done |
| Photo/document attachments | explicitly out of scope — Rosh said text is enough for now |
| WhatsApp Business (vs. regular WhatsApp) | not supported — Rosh only wants regular WhatsApp |
| Recurring/repeating schedules | not built |
| Auto-unlock the phone with a stored PIN to send while locked | declined — not possible, not just unbuilt. Android's keyguard is a separate hardened window no accessibility service (or any app) can read or inject into, by OS design. If the phone is locked when the alarm fires, the alarm still fires but WhatsApp can't come to the foreground until the phone is unlocked — the send effectively waits for that. |

## Known fragile points

- WhatsApp's internal resource IDs (`com.whatsapp:id/send`,
  `.../menuitem_search`, `.../entry`) are best-guess values based on
  commonly-cited conventions, not confirmed against a real build. WhatsApp
  can rename them on any update, or migrate a screen to Jetpack Compose
  (which drops stable resource IDs entirely). The group flow touches
  several screens in a row, so it's more likely to break than the
  individual-chat path (one button).
- If a stage times out, `WhatsAppAccessibilityService.giveUp(reason)` shows
  a toast naming which stage failed — check that stage's resource ID first.
  Fix instructions (Android Studio Layout Inspector) are in README.md,
  "If a step stops working".
- No automated tests exist yet.

## Build status / environment constraints — read before assuming a build works

- This code has **not been compiled or run** as of this writing. It was
  written and reasoned through carefully but never built, because of
  environment restrictions hit during development:
  - The cloud sandbox Claude worked in has no general internet access at
    all (org network policy — even `google.com` is blocked there).
  - The Mac's own Claude-automation-shell VM (reached via `device_bash`) is
    also fully network-locked, no exceptions found.
  - Neither environment can reach `dl.google.com` / `maven.google.com`
    (needed for the Android SDK/AGP), and neither has any USB/adb path to a
    physical phone.
- Because of that, the intended build path is **GitHub Actions**:
  `.github/workflows/build-apk.yml` compiles the app on GitHub's own
  runners (using only official actions — `actions/setup-java`,
  `gradle/actions/setup-gradle`, Android cmdline-tools pulled directly from
  Google, and the `gh` CLI — deliberately no third-party marketplace
  actions) and publishes the APK as a **GitHub Release** asset rather than
  a raw workflow artifact (Actions artifacts come zipped and the GitHub
  mobile app handles them badly; a Release asset downloads as one plain
  file straight from a phone's browser). Trigger via push to `main` or
  manually from the Actions tab.
- Alternative: build locally with Android Studio on the real Mac (not the
  sandboxed automation VM) — normal internet there, should just work.
- Expect a few small fixes on the very first build (a missing resource, a
  Gradle/AGP version mismatch) since this was hand-written without a
  compiler in the loop the whole way. That's expected, not a sign something
  is fundamentally broken.

## Repo/naming history

- App started as "WaScheduler" (folder + package + Gradle project name).
- Renamed the on-phone app label to "Rosh Schedule" per Rosh's request;
  kept the Kotlin package `com.rosh.wascheduler` unchanged (a full rename
  is a big diff for no functional benefit).
- Repo/folder later renamed to `scheduler-android` (Gradle
  `rootProject.name` and README references updated to match; the package
  name was intentionally left as-is).
- Moved from `~/Downloads` to `~/personal` (this repo's permanent home) —
  this gets the personal Git identity/SSH key automatically per
  `/Users/rbih/personal/PERSONAL_GITHUB_SETUP.md` and the top-level
  `~/personal/CLAUDE.md` (always `roshanjacobsamuel` here, never
  `roshan-rbih`).

## Icon

Original vector-drawn mark (`app/src/main/res/drawable/ic_launcher_*.xml`)
— a white message bubble with a small clock badge, on an indigo background.
Not a copy of WhatsApp's or any other app's logo.

## Not yet done / possible next steps

- Nothing has been pushed to GitHub yet as of this writing — Rosh is
  creating the private repo `roshanjacobsamuel/scheduler-android`; once it
  exists this folder needs `git init` + first commit + push.
- First real build via the GitHub Actions workflow, then sideload and
  actually test both the individual and group send flows on Rosh's own
  phone — nothing has been verified end-to-end yet.
- If group sending turns out unreliable in practice, the resource IDs in
  `WhatsAppAccessibilityService.kt` are the first thing to re-check (see
  "Known fragile points").
- Possible future asks already discussed but not built: recurring
  schedules, and the WhatsApp Business Cloud API as a more robust
  alternative if Rosh ever wants a dedicated "reminders" number instead of
  UI automation on his personal number.
