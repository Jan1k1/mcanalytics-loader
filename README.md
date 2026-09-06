# MCAnalytics Loader

A small open source plugin for Velocity proxies and Paper servers. It pairs your server with an
MCAnalytics network, downloads the MCAnalytics connector, checks the download against the checksum
the server published, and runs it. After that it keeps the connector up to date, so you never have
to replace a jar by hand again.

The loader in this repository is MIT licensed. The connector bundle it downloads is proprietary
MCAnalytics software and is not part of this repository. See [Security notes](#security-notes).

## Install

1. Download `mcanalytics-loader-velocity-<version>.jar` (Velocity proxy) or
   `mcanalytics-loader-paper-<version>.jar` (Paper or Folia server) from the
   [Releases](https://github.com/Jan1k1/mcanalytics-loader/releases) page.
2. Drop the jar in your `plugins` folder and start the server. The console prints
   `Server is not paired. Run '/mca pair <code>' in console to connect.`
3. Open the setup page in the MCAnalytics dashboard, copy the pairing code, and run
   `/mca pair <code>` in the server console. The loader saves the credential, downloads the
   connector, and starts it.

The Velocity jar goes on the proxy. The Paper jar goes on each backend server you want to measure.

## Commands

All three commands need console access or the `mcanalytics.admin` permission.

| Command | What it does |
| --- | --- |
| `/mca pair <code>` | Trades a pairing code from the dashboard for a connector credential, saves it, then downloads and starts the connector. |
| `/mca status` | Prints the loader version, the current state, the endpoint in use, the paired server and network, and the loaded bundle. It never prints the token. |
| `/mca update` | Checks the release API again, downloads a newer connector if there is one, and restarts it. |

`/mcanalytics` is an alias for `/mca`. Any other subcommand is passed to the running connector.

The loader can be in one of five states, which `/mca status` reports:

| State | Meaning |
| --- | --- |
| `UNPAIRED` | No credential on disk. Run `/mca pair <code>`. |
| `CHECKING` | Talking to the release API, or starting the connector. |
| `PAUSED_NO_PLAN` | The network has no active plan. The loader re-checks every 30 minutes. |
| `ACTIVE` | The connector is running. |
| `FAILED` | No usable connector bundle. The console log says why. |

## Configuration

The loader needs no configuration for normal use. Every key below is optional.

| Key | Where | Default |
| --- | --- | --- |
| `endpoint_url` (or `api_url`) | `plugins/<loader data folder>/config.toml` | `https://mcanalytics.org` |
| `endpoint-url` (or `api-url`) | `plugins/<loader data folder>/config.yml` | `https://mcanalytics.org` |
| `MCANALYTICS_ENDPOINT_URL` (or `MCANALYTICS_API_URL`) | environment variable | unset |
| `MCANALYTICS_DASHBOARD_URL` | environment variable | derived from the endpoint |
| `MCANALYTICS_API_TOKEN` | environment variable | unset |

The order is environment variable, then `config.toml`, then `config.yml`, then the default. The
endpoint must use HTTPS. Plain HTTP is accepted only for `localhost` and `127.0.0.1`, which is there
for local development against a test instance.

`MCANALYTICS_API_TOKEN` lets a container image start already paired. The loader accepts a token that
starts with `mca_live_` or `mca_test_`. A credential file on disk always wins over the variable.

Files the loader writes inside its own data folder:

| File | Contents |
| --- | --- |
| `credential.json` | The connector token and the network and server ids. Written with mode 600 where the filesystem supports POSIX permissions. |
| `cache/connector-<platform>-<version>.jar` | Verified connector bundles. |

## How updates work

On every start, and on every `/mca update`, the loader asks
`GET /api/v1/connector/release?platform=<platform>` with its connector token. The reply is a
manifest with the bundle `version`, its `sha256`, its `sizeBytes`, a `downloadPath`, and the
`minLoader` version the bundle needs.

- If the cached bundle for that version already matches the checksum, the loader uses it and
  downloads nothing.
- Otherwise it downloads the bundle to a temporary file, hashes it while it streams, and compares
  the size and the SHA-256 against the manifest. A mismatch deletes the temporary file and the
  bundle is never installed or run.
- Only a verified file is moved into the cache and loaded.
- If the API cannot be reached, the loader falls back to the newest bundle already in its cache, so
  a network outage does not take your analytics down.
- If the loader is older than `minLoader`, it logs a warning telling you to download a newer loader,
  and still tries to run the bundle.
- HTTP 402 means the network has no active plan. The loader pauses and re-checks every 30 minutes.
- HTTP 401 means the token was revoked. The loader clears the credential and asks you to pair again.

## Security notes

What the loader downloads: one jar, the MCAnalytics connector for your platform, from the endpoint
in your configuration. Nothing else.

How it checks the download: the release manifest carries a SHA-256 and a byte size. The loader
verifies both before the file is moved into place. A bundle that fails either check is deleted and
never loaded.

Where it runs the bundle: in a separate `URLClassLoader` with the loader as parent, so the connector
is isolated from the rest of your plugins and can be stopped and replaced without a server restart.

What the loader never does:

- It never prints the connector token, in a log line or in a command reply.
- It never accepts a plain HTTP endpoint outside `localhost` and `127.0.0.1`.
- It never runs a bundle whose checksum does not match the manifest.
- It never reads or writes outside its own plugin data folder.
- It never contacts the release API before the server is paired.

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
```

`./gradlew test` runs the tests on their own.

Modules:

| Module | Contents |
| --- | --- |
| `loader-common` | Config and credential handling, the release client, checksum verification, the bundle cache, the isolated classloader, and the lifecycle and command logic. No platform code. |
| `loader-velocity` | The Velocity plugin entry point and command bridge. |
| `loader-paper` | The Paper and Folia plugin entry point and command bridge. |

Pinned API versions:

| Dependency | Version | Note |
| --- | --- | --- |
| Velocity API | `3.3.0-SNAPSHOT` | Velocity publishes its API only as a snapshot. There is no release build to pin instead. |
| Paper API | `1.20.4-R0.1-SNAPSHOT` | Paper publishes its API only as a snapshot. The loader targets API level 1.20 and runs on later Paper builds. |

Both are compile-only, so neither ends up in the shipped jars. The build compiles to Java 17
bytecode on a Java 21 toolchain, so the jars run on any server on Java 17 or newer.

## Licence

MIT. See [LICENSE](LICENSE).
