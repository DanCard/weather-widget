fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android build

```sh
[bundle exec] fastlane android build
```

Build a signed release AAB (requires RELEASE_STORE_* signing props / release.keystore)

### android validate

```sh
[bundle exec] fastlane android validate
```

Validate service-account auth and metadata without uploading anything

### android internal

```sh
[bundle exec] fastlane android internal
```

Build and upload to the internal testing track

### android beta

```sh
[bundle exec] fastlane android beta
```

Build and upload to the open beta testing track

### android production

```sh
[bundle exec] fastlane android production
```

Build and upload to production (use after beta has soaked)

### android metadata

```sh
[bundle exec] fastlane android metadata
```

Push only listing metadata/changelogs (no binary)

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
