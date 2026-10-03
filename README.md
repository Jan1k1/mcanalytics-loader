# MCAnalytics Loader

A small open source plugin for Velocity and BungeeCord proxies and Paper servers. It pairs your server with an
MCAnalytics network, downloads the MCAnalytics connector, checks the download against the checksum
and the Ed25519 signature the server published, and runs it. After that it keeps the connector up to date, and from
loader 1.0.5 it updates itself too (see [Loader self-update](#loader-self-update)), so you never have to replace a jar
by hand again.

The loader in this repository is MIT licensed. The connector bundle it downloads is proprietary
MCAnalytics software and is not part of this repository. See [Security notes](#security-notes).

## Install

1. Download `mcanalytics-loader-velocity-<version>.jar` (Velocity proxy),
   `mcanalytics-loader-bungee-<version>.jar` (BungeeCord or Waterfall proxy) or
   `mcanalytics-loader-paper-<version>.jar` (Paper or Folia server) from the
   [Releases](https://github.com/Jan1k1/mcanalytics-loader/releases) page.
2. Drop the jar in your `plugins` folder and start the server. The console prints
   `Server is not paired. Run 'mca pair <code>' in the console to connect.`
3. Open the setup page in the MCAnalytics dashboard, copy the pairing code, and run
   `mca pair <code>` in the server console, without a slash (the Velocity console does not
   accept one). The loader saves the credential, downloads the connector, and starts it.

The Velocity or BungeeCord jar goes on the proxy. The Paper jar goes on each backend server you want to
measure. Folia uses the Paper jar: the loader and the connector it downloads use Folia's region
schedulers there.

## Commands

All three commands need console access or the `mcanalytics.admin` permission. In game they
start with a slash, as below; in a console type them without it, such as `mca pair <code>`.

| Command | What it does |
| --- | --- |
| `/mca pair <code>` | Trades a pairing code from the dashboard for a connector credential, saves it, then downloads and starts the connector. |
| `/mca status` | Prints the loader version, the current state, the address it talks to, the paired server and network, and the loaded bundle. It never prints the token. |
| `/mca update` | Checks the release API again and, if it finds a verified connector, restarts the connector on it. The running connector is only stopped once that verified replacement is ready. If a check or update is already running, the second request is ignored with a message. It also runs the [loader self-update](#loader-self-update) check now, and says whether a newer loader is staged. |

`/mcanalytics` is an alias for `/mca`. Any other subcommand is passed to the running connector.

The loader can be in one of five states, which `/mca status` reports:

| State | Meaning |
| --- | --- |
| `UNPAIRED` | No credential on disk. Run `mca pair <code>` in the console. |
| `CHECKING` | Talking to the release API, or starting the connector. |
| `PAUSED_NO_PLAN` | The network has no active plan. The loader re-checks every 30 minutes. |
| `ACTIVE` | The connector is running. |
| `FAILED` | No usable connector bundle. The console log says why. When the cause is that mcanalytics.org cannot be reached, the loader tries again every 2 minutes on its own. For any other cause, such as a release that failed verification, it tries again every 30 minutes. |

## Configuration

The loader needs no configuration. It always talks to `https://mcanalytics.org`, and that address
cannot be changed in a config file or an environment variable.

Older versions read an address from `endpoint_url` / `api_url` in `config.toml`, `endpoint-url` /
`api-url` in `config.yml`, and the `MCANALYTICS_ENDPOINT_URL`, `MCANALYTICS_API_URL` and
`MCANALYTICS_DASHBOARD_URL` environment variables. All of them are ignored now. When an old config
file still has one of those keys, the console says so once at startup, and you can delete the line.

Two settings remain, and both are optional:

| Key | Where | Default |
| --- | --- | --- |
| `auto-update-loader` | `config.yml` (Paper, `plugins/MCAnalyticsLoader/`); `auto_update_loader` in `config.toml` (Velocity, `plugins/mcanalytics-loader/`; BungeeCord, `plugins/MCAnalyticsLoader/`) | `true` |
| `MCANALYTICS_API_TOKEN` | environment variable | unset |

`auto-update-loader` switches [loader self-update](#loader-self-update) on or off. It is on when the
key is missing, when there is no config file, and for any value other than `false`, `no`, `off` or
`0`. The loader reads the file at every check, so turning it off needs no restart, and it never
writes to the file: the connector owns it. The default block for the connector config is:

```yaml
# Update the MCAnalytics loader jar by itself. A verified newer loader is staged and used after
# the next restart. Set to false to update the loader by hand.
auto-update-loader: true
```

On Velocity the same setting in `config.toml` is `auto_update_loader = true`.

`MCANALYTICS_API_TOKEN` lets a container image start already paired. The loader accepts a token that
starts with `mca_live_` or `mca_test_`. A credential file on disk always wins over the variable.

Files the loader writes inside its own data folder:

| File | Contents |
| --- | --- |
| `credential.json` | The connector token and the network and server ids. Created owner-only from the first byte (mode 600 on POSIX, an owner-only ACL on Windows). |
| `cache/connector-<platform>-<version>.jar` | Connector bundles that passed the checksum and signature checks. |
| `cache/connector-<platform>-<version>.jar.verify.json` | The sha256 and signature the bundle was installed with. The loader checks the jar against it again before every load. |

The only files the loader writes outside its data folder are for self-update, and always inside
the plugins folder that holds its own jar: `plugins/update/<loader jar name>` on Paper, or
`<loader jar name>.pending` and `<loader jar name>.pending.verify.json` next to the jar on Velocity.

## How updates work

On every start, and on every `/mca update`, the loader asks
`GET /api/v1/connector/release?platform=<platform>` with its connector token. The reply must be a
JSON object with a `data` object that carries these fields, and the loader refuses the reply if
any required field is missing or malformed:

| Field | Required | Rule |
| --- | --- | --- |
| `version` | yes | Three numbers of one to four digits, `x.y.z`. Nothing else is accepted, because it becomes part of a file name. |
| `sha256` | yes | 64 hexadecimal characters, the SHA-256 of the jar. |
| `sizeBytes` | yes | The exact size of the jar in bytes. Above zero and at most 64 MB. |
| `signature` | yes | Base64 of the 64 byte Ed25519 signature over the raw bytes of the jar. |
| `downloadPath` | yes | A path on mcanalytics.org that starts with exactly one `/`. |
| `minLoader` | no | The lowest loader version the bundle needs. Defaults to `1.0.0`. |

- The reply is read up to 64 KB and nested no deeper than 32 levels. Larger or deeper replies are
  refused.
- The download address is the API address plus `downloadPath`. A path that would change the host,
  such as `@other.example/x` or `//other.example/x`, is refused before any request is sent. A
  redirect is followed only when it stays on the same scheme, host and port; a redirect to another
  host is refused, so the token is never sent anywhere else.
- If the cached jar for that version matches the manifest's size, sha256 and signature, the loader
  uses it and downloads nothing.
- Otherwise it downloads the jar to a temporary file. It stops reading at the declared size, never
  more than 64 MB, and checks the size, the SHA-256 and then the signature. A jar that fails any of
  them is deleted and never installed or run.
- Only a verified file is moved into the cache, and its sha256 and signature are saved next to it.
- Before every start, including a fallback to an older jar, the loader hashes the jar on disk again
  and verifies the saved signature against the trusted keys. A cached jar that was changed after
  install, has no record, or fails the signature is skipped and named in the log.
- If the API cannot be reached, or its reply is refused, the loader falls back to the newest cached
  jar that still verifies, so a network outage does not take your analytics down. The console gets
  one calm line that says so, for example `Cannot reach mcanalytics.org right now (HTTP 502).
  Starting the connector already saved on this server (connector-paper-1.0.10.jar).` Only the HTTP
  status or a short cause (`timed out`, `address lookup failed`, `connection failed`) is shown,
  never a raw error page. The loader does not compare cached versions against a server-side minimum,
  so this fallback can be an older release than the newest one.
- If a connector is already running and a check fails for any reason, it keeps running and the check
  is repeated later (every 2 minutes after a connection failure, every 30 minutes otherwise).
- If the API cannot be reached and nothing verified is cached, the loader prints one warning, tries
  again every 2 minutes, and repeats a short reminder at most every 30 minutes until the connection
  is back. If that lasts more than 30 minutes, open a ticket in our Discord:
  https://discord.gg/9MWENuGmYn
- If the loader is older than `minLoader`, it logs a warning telling you to download a newer loader,
  and still tries to run the bundle.
- HTTP 402 means the network has no active plan. The loader stops the connector, pauses, and
  re-checks every 30 minutes.
- HTTP 401 means the token was revoked. The loader deletes the credential and asks you to pair again.

## Loader self-update

From 1.0.5 the loader keeps itself up to date. Loaders 1.0.4 and older do not check for a newer
loader, so they need one manual update to 1.0.5 (download the jar from the dashboard and replace
the old one). After that no manual update is needed unless you turn it off.

**When it checks.** Once the server is paired, the loader asks
`GET /api/v1/connector/loader/release?platform=<platform>` with its connector token at start and
then every 6 hours, with 10 percent of random jitter either way. `/mca update` runs the check
right away. The reply has the same shape and the same validation as the connector release reply
(`version`, `sha256`, `sizeBytes`, `signature`, `downloadPath`). A 404 means no loader is
published, and the loader does nothing.

**What it accepts.** A jar is staged only when all of this holds:

- its version is higher than the running loader's, compared as three numbers (`1.0.10` is newer
  than `1.0.9`). The same or an older version is never staged, so the loader is never downgraded;
- it downloads from the locked host only, with the same size cap, redirect rule and download-path
  rule as the connector, and its size and SHA-256 match the reply;
- its Ed25519 signature verifies under a trusted key compiled into the loader;
- it is an MCAnalytics loader for this platform and says it is the version the reply named. A
  validly signed connector jar, or an old signed loader offered under a higher number, is refused.

A jar that fails any check is deleted and nothing is staged. The running loader and the running
connector are never touched by a check.

**Where it goes.** The loader finds its own jar through its class's code source. If that is not a
regular `.jar` file with a plain name, it does nothing. It never writes outside the folder that
holds its own jar.

- **Paper.** The verified jar is written to `plugins/update/` (or the update folder set in
  `bukkit.yml`, if that is inside `plugins/`) under exactly the file name of the running jar.
  Paper copies a file of the same name over the plugin before it loads plugins, so the swap
  happens at the next restart. The jar keeps its old file name, for example
  `mcanalytics-loader-paper-1.0.4.jar` now holds 1.0.5, and that is fine.
- **Velocity and BungeeCord.** Neither has an update folder, so the loader does the swap itself, in a way that
  cannot leave two loaders loaded. The verified jar is written next to the running jar as
  `<jar name>.pending`, with a record `<jar name>.pending.verify.json` holding its version, sha256
  and signature. Both proxies ignore the file because they only load `*.jar`. On proxy shutdown, and
  again at the next start in case the shutdown never ran, the loader verifies the pending jar once
  more (sha256, signature, loader descriptor, and that it is still newer than the running loader)
  and then renames it over the running jar's file name in one atomic move. The swap replaces the
  file instead of adding one, so exactly one loader jar exists at every moment. A pending jar
  that fails a check, has no record, or is no longer newer is deleted. If the rename fails, for
  example because the operating system keeps the jar locked, the pending file stays and the console
  says so. The pending file stays, the old loader keeps running, and the swap is tried again at
  the next start; you can also move the `.pending` file over the jar by hand while the proxy is
  stopped.

When a jar is staged the console prints one line:
`MCAnalytics loader X.Y.Z is ready and will be used after the next restart.`

**Turning it off.** Set `auto-update-loader: false` in `config.yml` (Paper) or
`auto_update_loader = false` in `config.toml` (Velocity). Nothing is downloaded or staged then, and a
Velocity `.pending` jar left over from earlier is deleted at the next start or shutdown instead of
installed.

## Security notes

What the loader downloads: one jar, the MCAnalytics connector for your platform, from
`https://mcanalytics.org`. Nothing else.

How it checks the download: the release manifest carries a SHA-256, a byte size and an Ed25519
signature over the jar. The loader verifies all three before the file is moved into place, and
verifies the checksum and signature again before every load of a cached jar. The signature is
checked with the JDK's built-in Ed25519 against public keys compiled into the loader (currently
one, in `BundleVerifier.TRUSTED_PUBLIC_KEYS_BASE64`; the list exists so the key can be rotated).
This means a checksum alone is not enough: someone who controls the download server but not the
signing key cannot get a jar of their own accepted. A jar that fails a check is deleted or skipped
and never loaded.

Where it runs the bundle: in a separate `URLClassLoader` with the loader as parent. That separates
class names and lets the connector be stopped and replaced without a server restart. It is not a
sandbox. The connector runs inside your server's JVM with the same permissions as every other
plugin, and can read files, open connections and call the server API. The protection is that only
jars signed with the MCAnalytics key are ever loaded, not any restriction on what a loaded jar can do.

What the loader never does:

- It never prints the connector token, in a log line or in a command reply. Errors from reading
  JSON never repeat the text that was being read.
- It never sends the token to any host but the one it is configured for, `https://mcanalytics.org`.
  Download paths are checked to stay on that host and redirects to another host are refused.
- It never talks to any other address, whatever a config file or the environment says. The one
  exception is an internal switch used by the test suite that accepts only `localhost`,
  `127.0.0.1` and `::1`.
- It never runs a bundle whose checksum or signature does not verify.
- It never keeps a revoked token: the credential file is deleted, not renamed.
- It never reads or writes outside its own plugin data folder, with one exception: loader
  self-update writes a verified newer loader jar inside the plugins folder that holds the loader's
  own jar (`plugins/update/` on Paper, `<jar>.pending` beside the jar on Velocity), and on Velocity
  renames that file over the loader's own jar at shutdown or start. It never downgrades the
  loader, and does nothing when it cannot locate its own jar or when `auto-update-loader` is false.
- It never contacts the release API before the server is paired.

Limits of these checks: the signature proves a jar was signed by a trusted key, not that it is the
newest one, so a validly signed older jar in the cache can still be started when the site is
unreachable. Loader self-update is stricter than that: it accepts only a version higher than the
running one, and only a jar whose own descriptor names the announced version, so it cannot be
used to roll a loader back. Loaders 1.0.4 and older do not update themselves. Anyone who can already write to your plugins folder can replace the loader itself,
which no check inside the loader can prevent. If the signing key were stolen, jars signed with it
would be trusted until a loader release removes that key.

The connector bundle itself is closed source MCAnalytics software under its own licence. The loader
being MIT means you can read and audit everything that decides what gets downloaded and run. It does
not make the bundle open source.

To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Build from source

You need JDK 21. The Gradle wrapper takes care of Gradle itself.

```
git clone https://github.com/Jan1k1/mcanalytics-loader.git
cd mcanalytics-loader
./gradlew build
```

The jars land in:

```
loader-velocity/build/libs/mcanalytics-loader-velocity-<version>.jar
loader-paper/build/libs/mcanalytics-loader-paper-<version>.jar
loader-bungee/build/libs/mcanalytics-loader-bungee-<version>.jar
```

`./gradlew test` runs the tests on their own. The Gradle wrapper checks the Gradle download against a
pinned SHA-256 (`distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`).

Modules:

| Module | Contents |
| --- | --- |
| `loader-common` | Config and credential handling, the release client, checksum and signature verification, the bundle cache, the isolated classloader, and the lifecycle and command logic. No platform code. |
| `loader-velocity` | The Velocity plugin entry point and command bridge. |
| `loader-paper` | The Paper and Folia plugin entry point and command bridge. |
| `loader-bungee` | The BungeeCord and Waterfall plugin entry point and command bridge. |

Pinned API versions:

| Dependency | Version | Note |
| --- | --- | --- |
| Velocity API | `3.3.0-SNAPSHOT` | Velocity publishes its API only as a snapshot. There is no release build to pin instead. |
| Paper API | `1.20.4-R0.1-SNAPSHOT` | Paper publishes its API only as a snapshot. The loader targets API level 1.20 and runs on later Paper builds. |
| BungeeCord API | `1.20-R0.2` | A release on Maven Central. Waterfall implements the same API. |

All three are compile-only, so none ends up in the shipped jars. The build compiles to Java 17
bytecode on a Java 21 toolchain, so the jars run on any server on Java 17 or newer.

## Licence

MIT. See [LICENSE](LICENSE).
