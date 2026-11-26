# Labs64.IO :: Reporting

**Read-only reports over the events your AuditFlow sinks stored: each customer sees its own rows only.** It serves metrics, event lists, traces and archived originals from ClickHouse and S3, defined in a reviewed catalogue per tenant, and a request never becomes SQL. A tenant's backend calls it on behalf of its customers; browsers and customers never reach it directly.

[![CI](https://img.shields.io/github/actions/workflow/status/Labs64/labs64.io-reporting/labs64io-ci.yml?label=CI&logo=github)](https://github.com/Labs64/labs64.io-reporting/actions/workflows/labs64io-ci.yml)
[![Docker Image Version](https://img.shields.io/docker/v/labs64/reporting?label=Docker&logo=docker&logoColor=white&color=1C90ED)](https://hub.docker.com/r/labs64/reporting)
[![Artifact Hub](https://img.shields.io/endpoint?url=https://artifacthub.io/badge/repository/labs64io-helm-charts)](https://artifacthub.io/packages/helm/labs64io-helm-charts/reporting)
[![📖 Documentation](https://img.shields.io/badge/📖-Documentation-AB6543.svg)](https://labs64.io/docs/modules/reporting/)
[![Labs64.IO Ecosystem](https://img.shields.io/badge/Labs64.IO-Ecosystem-E14817.svg?logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9Ii01IC01IDE1NSAxNTUiPjxwYXRoIGZpbGw9IiNmZmYiIGQ9Ik0zIDNoMTM5djEzOUgzWk02OSAzdjEzOWg3VjNaTTQzIDQzdjU5aDI2VjQzWk03NiA0M3Y1OWgyNlY0M1oiLz48cGF0aCBmaWxsPSIjYmJiIiBkPSJNNDkgNDloNDd2NDdINDlaIi8%2BPC9zdmc%2B)](https://labs64.io)

<p align="center"><img src="assets/reporting-hero.svg" alt="Reporting: One ClickHouse table holds the rows of many customers. The same calls metric, requested for acme and then globex, shows each customer its own rows and its own numbers while every other row stays masked; metrics come from a reviewed catalogue, never from SQL." width="880"></p>

## The Labs64.IO Ecosystem

Reporting is one module of the [Labs64.IO Ecosystem](https://labs64.io). Its documentation is at [labs64.io/docs/modules/reporting](https://labs64.io/docs/modules/reporting/); [all modules are listed in the documentation](https://labs64.io/docs/services/).

---

<p align="center">
  <a href="https://labs64.io"><img alt="Labs64.IO" src="https://labs64.io/docs/assets/images/labs64-logo.png" width="110"></a><br>
  <sub>Made by the <a href="https://labs64.io">Labs64</a> team and community.</sub>
</p>
