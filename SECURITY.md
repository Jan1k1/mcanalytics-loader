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
its checksum and signature verification, its credential handling, and its build and release workflows.

Out of scope: the MCAnalytics connector bundle and the MCAnalytics service itself. Report those to
security@mcanalytics.org as well, and say which part is affected.

## What the loader handles

The loader stores one secret, the connector token, in `credential.json` inside its plugin data
folder. The file is created owner-only and written afterwards, so it is never readable by other
users, not even for a moment (mode 600 on POSIX; an owner-only ACL on Windows). A revoked token is
deleted, not kept under another name. The loader never writes the token to a log or a command reply,
and parse errors never repeat the text they were parsing.

It only talks to `https://mcanalytics.org`, and that address cannot be changed in configuration. The
download address is built from that host and a path the server sends; a path that would change the
host is refused, and a redirect to another host is never followed, so the token cannot be sent
elsewhere.

It only runs a downloaded bundle that passes all of these:

- a `sha256` and a size above zero in the release manifest (a manifest without them is refused), and
  a download that matches both, read no further than the declared size and at most 64 MB;
- an Ed25519 signature in the manifest over the raw jar bytes, verified against the public keys
  compiled into the loader (a missing or invalid signature is refused);
- the same checksum and signature check again before every load of a cached jar, including a
  fallback to an older jar; a jar that fails is skipped.

From 1.0.5 the loader also updates itself, on by default (`auto-update-loader: false` turns it
off). Every 6 hours, with jitter, and on `/mca update`, it asks for a newer loader jar on the same
locked host and stages one only when it passes the checks below, in addition to the ones above. A
staged loader is used at the next restart; the running loader and connector are not touched.

- the version is higher than the running loader's (never the same, never lower), and the jar's own
  plugin descriptor names that version and this platform, so a signed connector jar or an old
  signed loader cannot be staged as a new loader;
- the sha256, size and Ed25519 signature verify, with the same caps and host lock as the connector
  download;
- the running jar was located from the class's code source as a regular `.jar` with a plain
  name; otherwise nothing is done;
- every file written is inside the plugins folder that holds the loader's own jar:
  `plugins/update/<jar name>` on Paper (Paper does the swap), or `<jar name>.pending` beside the
  jar on Velocity. On Velocity the loader re-verifies the pending jar and then renames it over its
  own jar name in one atomic move, so two loaders are never loaded. A pending jar that fails is
  deleted.

This widens what a compromised signing key or server could do: with the key, an attacker could
publish a loader that runs on every server that has not turned self-update off. Without the key, a
compromised server or download host cannot get a jar accepted. If the signing key were stolen,
release a loader that drops the key, and ask operators to update it by hand.

Replies from the site are limited to 64 KB and 32 levels of JSON nesting, and a version string is
accepted only as `x.y.z` before it is used in a file name.

The connector runs in a separate class loader inside the server's JVM. This isolates class names;
it is not a sandbox and does not limit what the connector can do. Trust in the connector comes from
the signature check, not from the class loader.

Known limits: loaders 1.0.4 and older never update themselves. On Windows the operating system may
keep the Velocity loader jar locked, in which case the rename fails, the pending file stays, and
the console says so. A validly signed older jar in the cache can be started as a fallback when the site is
unreachable, because the loader has no server-side minimum version to compare with. Someone who can
already write to the plugins folder can replace the loader. If the signing key were stolen, jars
signed with it would stay trusted until a loader release drops that key.
