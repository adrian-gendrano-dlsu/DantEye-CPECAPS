# Danteye database (Firebase Realtime Database)

Project `esca-3-2526-c3-testing`, database `asia-southeast1`. Used by the Danteye app version 3
(`androidappV3/`) and CPECAPS app version 2 (`CPECAPS/androidappV2/`). The security rules are in
`database.rules.json` next to this file; the push function is `functions/index.js`.

## The four top-level sections

```
accounts/            one record per person: who they are and their role
drivers/             everything a driver's phone sends: live status, trips, alerts
supervisorPhones/    where supervisor notifications are sent
setup/               who became the first supervisor (written once)
```

Every person has one **account id** (`<uid>`, made by Firebase Authentication, e.g. `kdozegxdVBMZpIaJ…`).
The same id is used in `accounts/`, `drivers/` and `supervisorPhones/`. Find a person by email in `accounts/`.

## accounts/<uid>

| Field | Example | Meaning | Written by |
|---|---|---|---|
| `email` | `juan@example.com` | sign-in email | the person |
| `name` | `Juan Dela Cruz` | name shown to supervisors | the person |
| `role` | `driver` / `supervisor` | access. Missing = waiting for approval | a supervisor (Supervisor app > Settings > People) |
| `requestedRole` | `driver` | what the person asked for (removed when approved) | the person |
| `requestedAt` / `requestedAtMs` | `2026-11-27 09:15:02` | when they asked | the person |
| `lastSignIn` / `lastSignInMs` | `2026-11-27 18:02:41` | last sign-in | the person |

Fields ending in `Ms` are the same time as a number (milliseconds since 1970), used by the app for sorting.

## drivers/<uid>

| Path | Meaning |
|---|---|
| `name`, `email` | copied from the driver's account |
| `live/` | the driver right now; updated every 2 s while monitoring (see below) |
| `trips/<start time>` | one record per monitoring trip, named by its start time, e.g. `2026-11-27_17-30-05` |
| `alerts/<time>_<behaviour>` | one record per alert, e.g. `2026-11-27_17-42-10-123_eye-closure` |

**live/**: `risk` (No risk / Low / Medium / High risk, Calibrating, Not monitoring), `event` (what the driver is
doing, e.g. "Gaze deviation"), `alertState` (MONITORING, ALERTING = buzzer sounding, CLEARED, UNAVAILABLE = no camera
picture, STOPPED), `statuses` (each behaviour: Normal / Warning / High), `tripSeconds`, `speedKph`, `lat`, `lng`,
`locationSource`, `sessionId` (the phone's trip id), `test` (true = developer test values), `updated`
(readable time of this update) and `timestampMs`.

**trips/<start time>**: `started`, `ended` (readable), `minutes`, `alerts`, `highAlerts`, `maxLevel` (worst level),
`active` (true while the trip is running), plus `startedMs`, `endedMs`, `durationS`.

**alerts/<time>_<behaviour>**: `time` (readable), `trip` (the trip it belongs to), `behaviour`, `level`
(LOW / MEDIUM / HIGH), `sentToSupervisor` (true = this alert sent a phone notification), `test` (true = a developer
test, not a real detection), `timestampMs`.

## supervisorPhones/<uid>

The push address (FCM token) of each supervisor's phone. Written by the app when notifications are on;
removed when they are switched off.

## setup/

`firstSupervisor` (account id), `firstSupervisorEmail`, `created`: written once by the first person who taps
"Become the first supervisor" in a new, empty database. After that every role is given by a supervisor.

## Who can read and write (database.rules.json)

| Section | Read | Write |
|---|---|---|
| `accounts/` | supervisors: all; a person: their own | a person: their email / name / request / sign-in time; only supervisors: `role` |
| `drivers/` | supervisors: all; a driver: their own | a driver: their own |
| `supervisorPhones/` | nobody (the push function reads it) | a supervisor: their own |
| `setup/` | signed-in users | once, by the first supervisor |

## Common IT tasks

| Task | How |
|---|---|
| Give someone access | Supervisor app > Settings > People > Driver / Supervisor (or in the console: `accounts/<uid>/role` = `driver`) |
| Remove someone's access | Supervisor app > People > tap the person > Remove access (or delete `accounts/<uid>/role`) |
| Delete a driver's history | delete `drivers/<uid>/trips` and `drivers/<uid>/alerts` |
| Start over | delete everything in the database and the users in Authentication > Users; the first account then becomes the supervisor |
| Update the rules | Realtime Database > Rules > paste `database.rules.json` > Publish (or `firebase deploy --only database`) |
| Update the push function | `firebase deploy --only functions` (needs the Blaze plan) |

Alerts and trips wait on the driver's phone while it is offline and are sent when it is back online.
The database never contains camera pictures or face measurements.
