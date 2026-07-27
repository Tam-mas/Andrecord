# Calendar auto-record design

## 1. Purpose

Andrecord currently requires an explicit action to start a recording — the in-app button, Quick
Tap, or a volume-key hold. This feature adds a fourth, automatic trigger: when a calendar event
begins that has multiple participants, on a calendar you've chosen to watch, Andrecord starts
recording on its own, without you doing anything.

This is a new automation model for the app. Every existing trigger requires the user to act in
the moment; this one has the app decide to start recording unattended, based on calendar data.
Everything downstream of "start recording" (live transcript, diarization, Whisper refinement,
retention) is completely unchanged — this feature only adds a new way to trigger start and stop.

**Legal note, explicitly out of scope for this design:** recording other people's conversations
without their knowledge may require all-party consent depending on jurisdiction. The user is
investigating this separately; this spec does not attempt to solve or mitigate it, and the
feature should not be read as legal guidance that auto-recording is permissible.

## 2. Architecture

Android's `CalendarContract` content provider is a local, on-device database that calendar apps
(including Google Calendar) sync into — querying it involves no network access, consistent with
this app's existing fully-offline design (no `INTERNET` permission).

A new `CalendarAutoRecordScheduler` periodically re-scans the user's selected calendar(s) for the
next upcoming qualifying event, and schedules exactly **one** exact `AlarmManager` alarm for that
event's start time — not a recurring poll. When that alarm fires, a `BroadcastReceiver`
re-verifies the event is still valid, and if so starts a recording through the existing
`RecordingController`, with the calendar's name threaded through so the session title reflects
it. It then computes and schedules a second exact alarm to stop that specific recording later,
and re-scans to schedule the next upcoming event after that.

This was chosen over two alternatives, both rejected for precision/battery reasons:
- **WorkManager periodic polling** (its hard minimum interval is 15 minutes) would mean a
  recording could start up to 15 minutes after a meeting's actual start — a real problem for a
  meeting recorder, where missing the opening minutes is a meaningful loss, not a minor one.
- **A continuously-running foreground service polling every few seconds** would be maximally
  precise but drain battery running 24/7 for a feature that should be invisible until a meeting
  actually happens.

The exact-alarm approach needs the `SCHEDULE_EXACT_ALARM` permission, granted via a Settings
toggle (the same "banner + deep link to system Settings" pattern this app already uses for the
volume-key trigger's accessibility-service permission).

## 3. What counts as a qualifying event

An event qualifies for auto-record if **all** of the following hold:
- It falls on a calendar the user has explicitly selected to watch (see §5).
- It has at least 2 attendees listed **in total, including the user themselves** — i.e. at least
  one other invited person. This counts everyone listed regardless of RSVP response
  (accepted/declined/tentative/no-response), since RSVP status isn't reliably synced across all
  calendar providers and accounts, but attendee list membership is a much more dependable signal.
- It has a concrete start time (an all-day event, which has no specific time-of-day, does not
  qualify — there's no meaningful "start" moment to trigger on).

## 4. Auto-stop behavior

Auto-record also auto-stops the recording, rather than leaving that to the existing manual
triggers. The stop time is computed as:

- **Default**: the event's scheduled end time, plus a 5-minute grace period, to absorb the common
  case of a meeting running slightly long while still being fully automatic.
- **Exception**: if another qualifying event (per §3, on a watched calendar) begins during that
  5-minute grace window, the stop time is brought forward to that next event's start time instead
  (or the original end time, whichever is later) — so a back-to-back meeting gets its own clean,
  separate recording rather than one long session bleeding across both meetings.

The stop alarm is tagged to the specific session it's meant to stop. When it fires, it only acts
if that exact session is still the one actively recording — if the user already stopped it
manually, or a different recording is now running, it's a no-op rather than stopping the wrong
thing (see §7, `RecordingController`'s new session-scoped stop).

A meeting that runs longer than the 5-minute grace period will still get cut off at the computed
stop time. This is a known, accepted limitation, not a bug to design around — the user can always
notice and manually restart via the existing triggers if a meeting runs unusually long.

## 5. Settings

A new section in the existing Settings screen (`app/src/main/java/com/andrecord/app/ui/settings/`):

- An enable/disable toggle for the whole feature, off by default.
- Once enabled, a multi-select list of the device's synced calendars (queried live from
  `CalendarContract.Calendars` — not hardcoded to "work" and "personal", since this varies by
  device/account setup), letting the user check any number of them, including zero (which
  effectively pauses the feature without fully disabling it) up to all of them.
- If `SCHEDULE_EXACT_ALARM` hasn't been granted, a banner explaining it's needed, with a button
  deep-linking to the system settings screen where it's granted — matching the existing
  accessibility-service banner pattern exactly.

## 6. Components

**New:**
- `CalendarAutoRecordScheduler` — orchestrates finding the next qualifying event and scheduling
  the start/stop alarms. Re-runs whenever: its own alarm fires, the calendar's underlying data
  changes (via a `ContentObserver` registered while the app process is alive), the device
  reboots (a `BOOT_COMPLETED` receiver, since `AlarmManager` alarms don't survive a restart), or
  an infrequent periodic safety-net check (e.g. every few hours — a backstop only, not the
  precision mechanism, in case a change was missed while the app process wasn't running to
  observe it).
- `CalendarEventRepository` — pure query logic: given a set of selected
  calendar IDs, returns qualifying upcoming events with their attendee counts, sourced from
  `CalendarContract`. Fully unit-testable against a fake/injectable data source, following this
  app's existing `DiarizationEngine`/`OfflineAsrEngine` interface-and-fake pattern.
- `CalendarAlarmReceiver` — a `BroadcastReceiver` for both the start and stop alarms. On a start
  alarm: re-verifies the event still exists with an unchanged start time and still has 2+
  attendees, checks no recording is already active, then starts one via `RecordingController`
  with the calendar name attached. On a stop alarm: calls `RecordingController`'s new
  session-scoped stop for the tagged session id.
- Settings additions described in §5: the toggle, the calendar multi-select list, and the
  exact-alarm permission banner — following the existing `AppSettings`/`SettingsViewModel`/
  `SettingsScreen` pattern (`AppSettings` gets a new enabled flag and a persisted set of selected
  calendar IDs).

**Modified:**
- `RecordingController` gains a session-scoped stop method, mirroring the existing
  `reportRecordingEnded(sessionId)` pattern: a no-op unless the given session id still matches
  the currently active one. This is what makes the stop alarm safe to fire without risking
  stopping an unrelated recording that happens to be active at that moment.
- `SessionRepository.createSession` gains an optional calendar-name parameter. When present, the
  generated title becomes `"<existing date/time format> — <calendar name>"` (e.g. `"Jul 27,
  2026, 2:30 PM — Work"`) instead of the plain time-only title manual recordings get.
- `AndroidManifest.xml` gains the `READ_CALENDAR` and `SCHEDULE_EXACT_ALARM` permission
  declarations, and the new `BroadcastReceiver` registrations (for the alarms and for
  `BOOT_COMPLETED`).

## 7. Data flow

1. User enables the feature, grants `READ_CALENDAR` (a standard runtime permission prompt) and
   `SCHEDULE_EXACT_ALARM` (via the Settings banner deep-link), and selects which calendar(s) to
   watch.
2. `CalendarAutoRecordScheduler` queries qualifying events across the selected calendars within
   the next 7 days, picks the soonest one, and schedules one exact alarm for its start time
   (replacing any previously-scheduled start alarm). Nothing qualifying within that window simply
   means no alarm is scheduled until the next rescan finds something (a calendar change, or the
   periodic safety-net check).
3. **Start alarm fires**: re-verify the event (still exists, same start time, still 2+
   attendees). If a recording is already active, skip entirely (quiet no-op — see §8). Otherwise,
   start a recording via `RecordingController`, passing the calendar's name through to
   `SessionRepository.createSession` for the title. Compute the stop time per §4 and schedule the
   tagged stop alarm. Re-scan and schedule the next upcoming qualifying event.
4. **Stop alarm fires**: call `RecordingController`'s session-scoped stop for the tagged session
   id — a no-op if that session isn't the one currently active.
5. From here, the recording proceeds through the entire existing pipeline (live transcript,
   diarization, Whisper transcript refinement, 7-day retention) with zero changes — this feature
   only changes what triggers start and stop.

## 8. Error handling

- **Permission revoked later** (calendar read or exact-alarm access, both revocable any time via
  system Settings): detected and surfaced as a Settings banner, the same as an unresolved initial
  grant — the feature can't function without both, so it should be obviously non-functional
  rather than silently doing nothing forever.
- **Event deleted or moved** between scheduling and firing: caught by the start alarm's
  re-verification; skip and let the next scheduled scan pick up whatever's actually next.
- **Attendee count drops below 2** after scheduling (an edit, a decline): caught by the same
  re-verification, since it re-checks attendee count, not just existence.
- **A recording is already active** when the start alarm fires (a manual recording, or a
  previous meeting's recording still running past its expected stop): skip entirely, without
  interrupting it. This is a quiet no-op — nothing was actually missed, since a recording is
  already capturing whatever is happening.
- **Reboot**: alarms don't survive a restart; a `BOOT_COMPLETED` receiver re-runs the scheduler
  from scratch immediately. A meeting starting in the narrow window between reboot and that
  receiver running could theoretically be missed — accepted as a known, rare limitation.
- **Visibility**: the existing "recording in progress" foreground notification (shown for every
  recording regardless of trigger) includes the calendar name when calendar-triggered (e.g.
  "Recording… (Work)"), so an auto-started recording is visually distinguishable from a manual
  one without a new notification channel.

## 9. Testing

- `CalendarEventRepository`'s qualifying-event query logic (calendar selection, attendee-count
  filtering, all-day exclusion) is pure logic against injectable data — unit-testable with a
  fake, matching this app's existing engine-interface-and-fake pattern.
- The stop-time computation (grace period vs. back-to-back detection) is a pure function of
  event start/end times — straightforward to unit test with constructed event lists.
- `CalendarAutoRecordScheduler`'s orchestration (find next event → schedule → reschedule after)
  follows the same testable-companion-function pattern already established by
  `TranscriptionWorker` — the thin `AlarmManager`/`BroadcastReceiver` glue isn't unit tested
  directly, but the logic it delegates to is.
- `RecordingController`'s new session-scoped stop is tested the same way its existing
  `reportRecordingEnded(sessionId)` already is.
- Real `CalendarContract` queries against actual device calendar data are not unit-testable
  (same category as this app's native ASR/diarization engines) — verified via on-device manual
  testing against real calendar events instead.

## 10. Out of scope

- Any UX or technical mitigation for all-party-consent recording laws — explicitly the user's own
  call, being investigated separately.
- Auto-labeling or summarizing *which* calendar event a transcript belongs to beyond the title
  (e.g. pulling in the event description, location, or other attendees' names) — the calendar
  name in the title is the full extent of calendar-derived metadata for this feature.
- Any interaction with calendar events that lack a synced attendee list at all (some calendar
  configurations don't populate attendees reliably) — such events simply never qualify, since
  there's no way to evaluate the 2+ attendee rule against them.
- Supporting calendar accounts/providers beyond whatever `CalendarContract` already exposes
  (this is an OS-level abstraction that any synced calendar app already participates in, so no
  Google-specific or work-specific integration is needed).
