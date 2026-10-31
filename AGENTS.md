# AGENTS.md — Labs64.IO :: Reporting

Read-only reports over the events a tenant's AuditFlow sinks stored: metrics, event lists and traces from ClickHouse,
archived bodies from S3. A tenant's backend calls it on behalf of one of its customers.

## Ecosystem role

Reads what AuditFlow's sinks wrote; never writes and owns no database. Reached through the API Gateway on
`/reporting/api/v1`. The chart is `charts/reporting` in labs64.io-helm-charts; a release is a GitHub Release whose tag
`X.Y.Z` becomes the jar, image and chart `appVersion` (`labs64io-docker-publish.yml` opens the chart PR).

## Critical guardrails

1. **Nothing here names a tenant.** A tenant's metrics, tables and SQL are its catalogue file, mounted from the
   deployment. This repository is public.
2. **A request never becomes SQL.** It picks names from the catalogue; every value is a bound parameter. A dimension,
   filter or metric that is not in the catalogue is refused.
3. **The scope is never request input.** It comes from `X-Reporting-Scope`, trusted only with the
   `reporting:read-delegated` scope from the gateway's context. Every query filters on it, and `SQL_scope` drives the
   server's row policy as a second line. `ReportingApiIT` proves that against a real ClickHouse: keep it green.
4. **OpenAPI-first.** `src/main/resources/openapi/openapi-reporting-v1.yaml`. Changing an operation also regenerates
   the policies and routes in labs64.io-helm-charts (`just generate-policies`).
5. **Every operation is POST.** The public edge passes only POST, and a backend reaches this service through it.
6. **Keep it small.** No schedules, alerts, exports, UI or further backends until a named consumer needs them.

## Catalogue

`<catalogue directory>/<tenant>.yaml`, read once at start; an invalid file fails the start. Example:
`examples/demo/catalogue/demo.yaml` (the tests keep their own copy in `src/test/resources/catalogue/`).

| Key | Meaning |
|---|---|
| `database` | The ClickHouse database |
| `metrics.<name>` | `group`, `table`, `scopeColumn`, `time`, `value`; optional `fullHistory`, `final`, `where`, `dimensions`, `filters` |
| `events` | The event list: `table`, `scopeColumn`, `time`, `id`, `correlation`, `columns` (with `eventId`, `time`, `received`), `filters` |
| `trace` | The sources a trace reads; default `events` |
| `status` | `table`, `scopeColumn`, `received` |
| `bodies` | `group`, `keyTemplate`: the object an unbatched S3 sink writes for one event |

## Build and test

```bash
just build   # mvn package, tests included
just test    # ReportingApiIT needs Docker and skips itself without it
```

`docker-compose.images.yml` runs the published image with the demo tenant, no build. Local image: `docker build -t
labs64/reporting:dev .` after `just build`, then `REPORTING_VERSION=dev docker compose -f docker-compose.images.yml up`.

With Docker Desktop, testcontainers may need `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`.

## Working rules

Judge every change against the **Quality bar** in `labs64.io-workspace/AGENTS.md`.
