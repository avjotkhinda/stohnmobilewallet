# Alert channel setup

Three workflows can send a message to a chat channel, all through
`scripts/notify-pin-liveness.sh`:

| Workflow | Schedule | What it sends | Missing channel |
| --- | --- | --- | --- |
| `pin-liveness` | weekly | a dead-pin failure, and the all-clear that closes it | **fails the run** |
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

**It must be a repository secret, not an environment secret.** The jobs that
read it declare no `environment:`, so an environment secret is invisible to
them and the run behaves as if nothing were configured.

### Variable instead of secret

A repository **variable** (`Settings → Secrets and variables → Actions →
Variables`) with the same name is also delivered, because saving the name in
the wrong tab should not cost a silent week. The difference is privacy: anyone
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

## Reading a red run

The `schedule-watchdog` run summary and the strict error annotations on
`alert-test` and `pin-liveness` each link here, so a red run can be read from
the run page alone.

- **`alert-test` red** — the channel is missing or refused the message. The
  summary line starts with `Alert channel: **not configured**` or
  `**delivery failed**`; the log names the HTTP code each route answered. This
  run is deliberately strict: a test that goes green without sending anything
  would hide the exact problem it exists to find.
- **`schedule-watchdog` red** — a schedule really has stopped. The failure is
  not about the channel; the watchdog opens one issue and exits 1 until the
  schedule fires again.
- **`schedule-watchdog` run with a warning named "No alert channel
  configured"** — expected when no channel is set. It is a warning rather than a
  failure on purpose: a run that is red every morning for a setting would hide
  the schedule outage that run exists to report behind a red that says nothing
  new. The same run still fails if the schedule itself has stopped.
- **`pin-liveness` red** — either a pinned runtime URL failed its digest check,
  or delivery was refused; the summary separates the two.

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

To inspect the watchdog without touching GitHub, an issue or the channel:

```bash
WATCH_DRY_RUN=1 bash scripts/watch-pin-schedule.sh
```

Never commit a webhook URL, paste it into an issue, or put it in a workflow
file: it belongs in the repository secret, and a leaked one should be rotated
in the chat app rather than merely deleted.
