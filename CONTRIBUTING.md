# Contributing

Thanks for looking. Bug reports, small fixes and platform compatibility patches are all welcome.

## Before you open a pull request

- Build and test locally: `./gradlew build`. CI runs the same command on Java 21.
- Keep the change focused. One fix or one feature per pull request.
- Add a test for anything that touches the release client, the checksum path, the credential file,
  or the command handling.
- Match the surrounding code style. No new dependencies in `loader-common` without a good reason.
- Never log or echo a connector token.

## Commit messages

Conventional commit subjects, for example:

```
fix(loader): keep the cached bundle when the release API times out
feat(paper): support the Folia async scheduler
```

## Releasing

The version lives in `gradle.properties` as `loaderVersion`. Bump it, then also bump
`VelocityLoaderPlugin.VERSION`, which the Velocity plugin annotation needs as a compile-time
constant. Tests fail if the two drift apart. The Paper descriptors take their version from Gradle,
so they need no edit.

Push a tag `v<version>` matching `loaderVersion`. The release workflow builds the jars, writes a
`SHA256SUMS` file, and attaches all three to a GitHub release.

## Reporting a vulnerability

Do not open a public issue. See [SECURITY.md](SECURITY.md).
