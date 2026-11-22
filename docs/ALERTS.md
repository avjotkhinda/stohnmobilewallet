# Alert channel setup

Three workflows can send a message to a chat channel, all through
`scripts/notify-pin-liveness.sh`:

| Workflow | Schedule | What it sends | Missing channel |
| --- | --- | --- | --- |
| `pin-liveness` | weekly | a dead-pin failure, and the all-clear that closes it | **fails the run** when there is a message to send; otherwise a warning |
| `schedule-watchdog` | daily | a stopped-schedule warning, and its all-clear | warning only |
| `alert-test` | hand-run | one labelled test message | **fails the run** |

Nothing is sent until one of the names below exists in the repository. The
schedule checks still run and still fail on their own without it; only the chat
message is missing.

## Add the channel

The quickest route is
`https://github.com/<owner>/<repo>/settings/secrets/actions/new`, or in the UI:
**Settings → Secrets and variables → Actions → Secrets → New repository secret**.

1. **Name**: `PIN_ALERT_WEBHOOK_URL` — exactly this. A near-miss name is not read.
2. **Secret**: the webhook URL, for example a Slack incoming webhook.
3. **Add secret**, then re-run `alert-test` to prove delivery.

If you would rather not store it as a secret, the same name as a **repository
variable** works too - see below. Either tab fixes the "No alert channel
configured" warning on the scheduled runs.

**It must be a repository secret, not an environment secret.** The jobs that
read it declare no `environment:`, so an environment secret is invisible to
them and the run behaves as if nothing were configured.

### Which steps read it

Every step that can send a message passes the name through as
`PIN_ALERT_WEBHOOK_URL` (or `PIN_ALERT_WEBHOOK_URL_FALLBACK`) to
`scripts/notify-pin-liveness.sh`; no workflow file holds a URL.

- `pin-liveness`:
  - **Alert the chat channel when a pin is dead**
  - **Tell the chat channel the pins are healthy again**
  - **Record the alert channel in the job summary**
  - **Warn when no alert channel is configured**
  - **Check the schedule watchdog is still running**
- `schedule-watchdog`:
  - **Check the watchdog's own schedule still fires**
  - **Check the weekly schedule still fires**
  - **Warn when no alert channel is configured**
- `alert-test`:
  - **Post a test message to the alert channel**

### Variable instead of secret

A repository **variable** (`Settings → Secrets and variables → Actions →
Variables`, or `https://github.com/<owner>/<repo>/settings/variables/actions/new`)
with the same name is also delivered, because saving the name in the wrong tab
should not cost a silent week. The difference is privacy: anyone
who can read the repository's Actions settings can read a variable. When both
exist the secret is used, and the run summary says which route carried the
message so a variable in the wrong tab is visible rather than merely tolerated.

### Optional second route

`PIN_ALERT_WEBHOOK_URL_FALLBACK` (secret or variable) takes the same kind of
URL. Routes are tried in this order until one answers 2xx:

1. `PIN_ALERT_WEBHOOK_URL` (secret)
2. `PIN_ALERT_WEBHOOK_URL` (variable)
3. `PIN_ALERT_WEBHOOK_URL_FALLBACK` (secret)
4. `PIN_ALERT_WEBHOOK_URL_FALLBACK` (variable)

Two routes mean one broken channel cannot silence the alarm on its own, and a
refusal is reported in the summary rather than swallowed. The body is always
`{"text": ...}`, so the fallback can be another Slack channel or workspace,
Discord, Teams or Google Chat. URLs must be HTTPS; the script refuses anything
else (`refusing to send a runtime pin alert to a non-HTTPS ... webhook`), except
loopback for local testing.

## Prove delivery with `alert-test`

**Actions → alert-test → Run workflow.** It is hand-run only (no schedule, no
issue), posts one labelled test message through the same script and the same
secret, and is strict: green means one of the routes answered 2xx and the
summary names which, red means nothing was delivered and the log gives the HTTP
code each route answered. Run it once when setting the channel up, and again
after rotating or changing a webhook.

## Reading a red run

The `schedule-watchdog` run summary and the strict error annotations on
`alert-test` and `pin-liveness` each link here, so a red run can be read from
the run page alone.

- **`alert-test` red** — the channel is missing or refused the message. The
  summary line starts with `Alert channel: **not configured**` or
  `**delivery failed**`; the log names the HTTP code each route answered. This
  run is deliberately strict: a test that goes green without sending anything
  would hide the exact problem it exists to find.
- **`schedule-watchdog` red** — a schedule really has stopped, or the schedule
  is no longer wired up. The failure is not about the channel; the watchdog
  opens one issue and exits 1 until the schedule fires again. See the next
  section for which of its two checks went red and what threshold it crossed.
- **`schedule-watchdog` run with a warning named "No alert channel
  configured"** — expected when no channel is set, and about the channel only:
  the same run still fails if the schedule itself has stopped. Why it warns
  rather than fails is below.
- **`pin-liveness` red** — either a pinned runtime URL failed its digest check,
  or delivery was refused; the summary separates the two.
- **`pin-liveness` green with a "No alert channel configured" warning** — the
  pins and the schedule are fine; only the chat message is missing. Its sending
  steps are strict, so the same gap turns red only on a run that had something
  to send.

## Red or warning: what the schedule checks actually decide

`schedule-watchdog` runs the *same* age check twice, aimed at two different
schedules. Either one failing is enough to make the run red, so a red daily run
is always a statement about a schedule, never about the channel.

| Step | Watches | Cron | Limit | Red when |
| --- | --- | --- | --- | --- |
| **Check the watchdog's own schedule still fires** (`WATCH_TARGET=watchdog`) | `schedule-watchdog.yml` | daily, `41 5 * * *` | `PIN_WATCHDOG_MAX_AGE_DAYS`, default **3 days** | no scheduled run for more than 3 days |
| **Check the weekly schedule still fires** (the default target) | `pin-liveness.yml` | weekly, `23 6 * * 1` | `PIN_SCHEDULE_MAX_AGE_DAYS`, default **9 days** | no scheduled run for more than 9 days |

Both steps run `scripts/watch-pin-schedule.sh`, and each asks two questions. A
"no" to either exits 1 and fails the step, and with it the run:

1. **Is the schedule still wired up?** `scripts/verify-pin-schedule.sh` reads the
   workflow file for a `cron:` line and asks GitHub for the workflow's `state`.
   `disabled_inactivity` (GitHub disables a schedule after 60 days without
   repository activity), `disabled_manually`, or any unexpected state fails.
2. **Has it actually fired?** The newest run with `event=schedule` is looked up
   and its `created_at` compared with the limit. A hand dispatch is ignored: it
   proves the file parses, not that the cron fires. Ages are whole days and the
   comparison is strictly greater than the limit, so the daily watchdog's 3 days
   allow two missed mornings and the weekly check's 9 days allow one slow Monday,
   while a last run 3 days and 23 hours old still counts as 3 days. A workflow
   that has never fired on schedule is only late once the workflow itself is
   older than the limit, so a check added yesterday is not red on day one.

The log says which question failed and by how much: either the wiring line
(`GitHub disabled this workflow after 60 days…`, `this workflow is disabled
manually`, `declares no schedule cron`) or the age line (`no scheduled run for
N days (limit M); the last was …`).

What a red run does, besides being red: it opens exactly one issue whose title
starts with `[pin-schedule]` (the weekly check) or `[watchdog-schedule]` (its own
schedule), and sends the channel one message the first time. While that issue
stays open, later runs are still red but send nothing new, so a long outage
does not cost one message per morning. When the schedule fires again the issue
is closed, an all-clear is sent, and the run goes green.

The weekly `pin-liveness` run performs the watchdog check too
(`WATCH_TARGET=watchdog`), so a daily watchdog whose own cron stopped is still
named within a week. The two schedules only fall quiet together through
repository-wide inactivity, which no schedule inside the repository can report
on.

### Why a missing channel is a warning, not a failure

A missing alert channel is a *setting*, and the run that would tell you about it
is the same run that reports the outage. Making it red too would put a red on
the run page every single morning - or every week for `pin-liveness`, whose red
is about a dead pin - and a red that repeats without news is how the real outage
gets ignored. So the channel gap lives in its own step in both scheduled
workflows, **Warn when no alert channel is configured**. It runs with
`if: always()` (and, in `pin-liveness`, only outside a dry run), so it is named
on the same run that just went red rather than hiding behind the outage that run
was about. It then prints one annotation titled `No alert channel configured`
and a run summary holding the exact fix, and exits 0, so the gap is visible on
every run without borrowing the schedule's signal. One script prints all of it,
`scripts/report-missing-alert-channel.sh`, so the two workflows cannot disagree;
each passes only its own sentence naming what still fails the run. The summary
and the annotation both give:

- the exact name `PIN_ALERT_WEBHOOK_URL`, with the settings URL for the secret
  tab and the one for the variable tab, so the fix is one click away;
- the repository-versus-environment caveat: neither job declares `environment:`,
  so an environment-scoped name is invisible to it and behaves exactly like no
  setting at all;
- the `alert-test` workflow, to prove delivery before relying on the channel;
- the link back to this page.

Hence the two outcomes in the table: the weekly check sends only from **Alert
the chat channel when a pin is dead** and **Tell the chat channel the pins are
healthy again**, which run only when a pin failed or just recovered. A missing
channel therefore fails that run exactly when there was something to send, and
a routine green week carries the watchdog's warning instead.

`alert-test` stays strict on purpose (`PIN_ALERT_STRICT=1`): proving delivery is
its whole job, so a missing or refused channel is a red there. Delivery failures
inside a schedule check are warnings as well — `scripts/notify-pin-liveness.sh`
warns and exits 0 unless strict — so a refused message never turns a schedule
run red on its own. A red `schedule-watchdog` run is only ever the checks'
verdict about a schedule.

## Troubleshooting

- **"No alert channel configured" although a secret exists.** Check the exact
  name, then check whether it was saved as an *environment* secret. The log
  attempts one best-effort probe of the repository's secret names and reports a
  similar name when it finds one.
- **Every route answered a non-2xx.** A revoked webhook, or one copied from a
  different workspace, answers a 4xx (Slack reports `no_service`); a Slack
  channel the app was removed from refuses with `403`. Rotate the webhook in the
  chat app, then update the secret.
- **Sending from a terminal.** Running
  `PIN_ALERT_WEBHOOK_URL=... bash scripts/notify-pin-liveness.sh test ""` by hand
  warns instead of failing on a delivery problem, so a chat issue never looks
  like a code issue. Add `PIN_ALERT_STRICT=1` to make it fail like CI does.
- **A workflow stopped firing.** GitHub schedules only from the default branch
  and disables a schedule after 60 days without repository activity. Re-enable
  it from the Actions tab, or push any commit to restore it. The watchdog
  tracks the weekly schedule as `[pin-schedule]` and its own as
  `[watchdog-schedule]`; pin failures use `[pin-liveness]`.

To see the verdict the watchdog would report — both questions, without touching
GitHub, an issue or the channel:

```bash
WATCH_DRY_RUN=1 bash scripts/watch-pin-schedule.sh
```

Never commit a webhook URL, paste it into an issue, or put it in a workflow
file: it belongs in the repository secret, and a leaked one should be rotated
in the chat app rather than merely deleted.
