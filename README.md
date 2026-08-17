# otelshow — how the OpenTelemetry Collector works, one branch at a time

A hands-on teaching repo for our shift in how applications ship telemetry.

## Two worlds

**Today (the old world):** every app does three different things, three different ways —

```
app ──publish──> Kafka topic  ──consume──> tracing backend
app ──publish──> Kafka topic  ──consume──> log backend
app <──scrape── Prometheus                 (metrics, pull)
```

Three formats, three pipelines, and the app knows the address of every backend.

**Where we're going (the OTel world):** the app speaks ONE protocol (OTLP) to ONE
local destination (the collector). The collector does the rest —

```
app ──OTLP push (gRPC :4317)──> OTel Collector ──> Tempo   (traces)
                                 receiver          Loki    (logs)
                                 processor         Prometheus (metrics)
                                 exporter
```

The collector is just three stages: **receivers** get data in (by listening OR by
scraping), **processors** mutate/filter/batch it in flight, **exporters** ship it out.
That's the whole product. This repo demonstrates each stage in isolation.

## The lab

| Piece | What | Where |
|---|---|---|
| `app/` | Spring Boot 4.1 / Java 25 dice game (`/play`, `/roll`, `/fail`) | http://localhost:8080 |
| `otelcol` | OpenTelemetry Collector **contrib 0.158.0** — the teaching subject (appears in branch 01) | :4317 gRPC / :4318 HTTP |
| `lgtm` | `grafana/otel-lgtm` all-in-one: Grafana + Tempo + Loki + Prometheus | http://localhost:3000 (no login) |

The OTel Java agent jar is baked into the app image from day one but **dormant** —
it only wakes up when compose sets `JAVA_TOOL_OPTIONS` (branch 03). That's the
punchline: the migration is env vars, not code.

## The ladder

Each branch adds exactly ONE concept. The lesson IS the diff:

| Branch | Concept | See it with |
|---|---|---|
| `main` | The old world, no collector | — |
| `01-prom-receiver` | **Receivers**: scraping moved into the collector | `git diff main..01-prom-receiver` |
| `02-prom-full-pipeline` | **Processors + exporters**: full pipeline, pull model | `git diff 01-prom-receiver..02-prom-full-pipeline` |
| `03-otlp-receiver` | **THE SHIFT**: app pushes 3 signals over OTLP gRPC | `git diff 02-prom-full-pipeline..03-otlp-receiver` |
| `04-otlp-processors` | **Processor chain**: filter, transform, enrich — order matters | `git diff 03-otlp-receiver..04-otlp-processors` |
| `05-otlp-exporters` | **Exporters**: fan-out per signal into Tempo/Loki/Prometheus | `git diff 04-otlp-processors..05-otlp-exporters` |
| `06-advanced` | **Connectors + tail sampling**: the collector as a platform | `git diff 05-otlp-exporters..06-advanced` |

Every branch keeps the `debug` exporter so you can *read* the telemetry in
`docker compose logs -f otelcol` — that log is our microscope.

## Run

```powershell
docker compose up -d --build
```

Traffic loop (PowerShell — note `curl.exe`, not the PS alias):

```powershell
1..30 | % { curl.exe -s "http://localhost:8080/play?player=alice" > $null; Start-Sleep -m 300 }
```

---

## This branch: `04-otlp-processors` — what processors can DO

**The diff:** `git diff 03-otlp-receiver..04-otlp-processors -- otel/`

The full chain, in an order that is itself the lesson:

```
memory_limiter → resource_detection → resource/env → attributes/team
              → filter/noise → transform/redact → batch
   (bouncer)      (self-aware)    (stamp)  (tag)     (drop)   (mutate)   (ship)
```

- `filter/noise` (OTTL) — drops the `GET /actuator/prometheus` spans our own
  scraper generates every 5 s. *Drop before you transform: the cheapest span is a
  dead span.*
- `transform/redact` (OTTL) — rewrites `url.full` to `player=REDACTED` and the log
  body to `player ***`. Compliance without touching app code.
- `resource_detection` / `attributes/team` — every record now carries `os.type`,
  `host.name`, `team=platform-observability`.

```powershell
docker compose restart otelcol
docker compose logs -f otelcol
curl.exe "http://localhost:8080/play?player=alice"
```

First observation: the actuator-scrape spans that spammed the log are **gone**.
Then look at a client span: `player=REDACTED`. Log body: `player ***`.

**Live exercise:** move `filter/noise` after `transform/redact` in the pipeline,
restart, and discuss what changed and why order matters. Then put it back.

**Discussion beat — find the leak:** even now, `player=alice` still hides in metric
**exemplars** (histogram datapoints keep the original span attributes) and in log
phrasings the regex doesn't match (`game finished for alice`). Regex redaction is
whack-a-mole; the durable fix is not recording sensitive values in the first place
(semantic conventions, SDK-side views). The collector is your safety net, not your
only defense.

Next: `git switch 05-otlp-exporters`

---

## Windows & demo notes

- **Tempo search lags ~30–60 s** behind ingestion (ingester flush). During a live
  demo, send traffic FIRST, talk through the config, then search — or tail sampling
  will look broken when it isn't.
- Before a session: `netstat -ano | findstr ":4317 :3000 :9090 :8080"` — Aspire/Alloy love to squat OTLP ports.
- Docker Desktop wants ≥ 6 GB WSL2 RAM (`%UserProfile%\.wslconfig` → `[wsl2] memory=8GB`, then `wsl --shutdown`).
- After laptop sleep, the WSL clock can drift → spans "in the future", empty Tempo windows. Fix: `wsl --shutdown` + restart Docker Desktop.
- Collector config changes: always `docker compose restart otelcol` (bind-mount file events don't propagate reliably).
- Maintenance: fix shared files on `main`, then cascade `git rebase main 01-prom-receiver`, `git rebase 01-prom-receiver 02-prom-full-pipeline`, … in order.
