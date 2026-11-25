# Security policy

## Supported versions

Only the latest release receives security fixes. Please update (server image, Helm chart and APK share one version) before reporting.

## Reporting a vulnerability

Please report privately through GitHub's **private vulnerability reporting**: open the repository's *Security* tab and choose
*Report a vulnerability*. Do not open a public issue or pull request for a vulnerability.

You can expect an acknowledgement within a few days. This is a small, volunteer-maintained project: fixes are made on a best-effort basis
and the report will be credited in the release notes unless you prefer otherwise.

## Scope

In scope:

* the server (`server/`) and its HTTP API (`/v1`, the Stremio routes, the podcast feeds, the configure pages);
* the Android app (`app/`), including its update check, downloads and BitTorrent policy;
* the Helm chart (`helm/`), container image and the release pipeline (`.github/workflows/`).

Out of scope: the debrid services, Audible/Audnexus and the torrent indexes themselves; vulnerabilities that need a rooted device or
an already compromised server; denial of service by sheer traffic volume; missing hardening headers without a concrete impact.

## What makes a good report

* The affected version or commit and how you run it (server, app, chart).
* Steps to reproduce, or a proof of concept, and the impact: for example access to another user's data or debrid key, a sealed
  config or token that can be forged, a way to make the server fetch internal addresses, or a tampered APK accepted by the updater.
* Whether the issue is already exploitable on a default installation.

Never include real API keys or other people's data in a report; use throwaway accounts.

If you find a secret that was committed by mistake, report it the same way so it can be rotated.
