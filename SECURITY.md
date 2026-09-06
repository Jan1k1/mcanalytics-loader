# Security policy

## Supported versions

The latest released version is supported. Fixes go into a new release rather than a patch of an
older one.

## Reporting a vulnerability

Do not open a public issue for a security problem.

Report it privately through
[GitHub security advisories](https://github.com/Jan1k1/mcanalytics-loader/security/advisories/new),
or by email to security@mcanalytics.org.

Please include what you found, the loader version and platform, and the steps to reproduce it. You
get an acknowledgement within 3 working days and a status update at least every 7 days until the
issue is closed. Please give us 90 days before public disclosure.

## Scope

In scope: this repository, meaning the loader, its pairing flow, its release and download client,
its checksum verification, its credential handling, and its build and release workflows.

Out of scope: the MCAnalytics connector bundle and the MCAnalytics service itself. Report those to
security@mcanalytics.org as well, and say which part is affected.

## What the loader handles

The loader stores one secret, the connector token, in `credential.json` inside its plugin data
folder, with mode 600 where the filesystem supports POSIX permissions. It never writes the token to
a log or a command reply. It only talks to the endpoint in its configuration, which must be HTTPS
outside `localhost`. It only runs a downloaded bundle whose SHA-256 and byte size match the release
manifest.
