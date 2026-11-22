# DroidReel example

`app/` and `modules/` form **DroidReel**, the integration example that ships with droid-spi. It is
a small Compose short-video app whose modules stay dependency-isolated: the app compiles only
against the contract interfaces in `modules/base`, and the build-time provider table connects the
callers to implementations they never import.

The example exists to exercise the framework, not as a product. There is no backend, the media
URLs are public demo assets, accounts use one fixed credential, and published works are
session-only.

## Modules

```
:app                       application, splash, Play delivery bridge, debug diagnostics provider
:modules:base              contracts, models, session core, theme, META-INF/services media catalog
:modules:account           login screen and LocalAccountService (@ProviderFactory)
:modules:community         feed, player, profile, repository, keyed FeedSources, FeedPublisher
:modules:production        on-demand dynamic feature: pick a clip, publish through FeedPublisher
```

Dependency direction:

```
app ──▶ base ◀── account
 │        ▲
 │        └── community
 └── dynamic feature ──▶ production
```

`account` and `community` depend only on `base`. `production` depends on `app` and `base` (the
first edge is required by AGP). No module depends on `production`, and no caller imports another
provider module's classes.

## Provider table

| Service | Key | Provider | Module | Variants |
|---|---|---|---|---|
| `MediaCatalog` | `""` | `BundledMediaCatalog` | base (`META-INF/services`) | all |
| `AccountService` | `""` | `LocalAccountService` | account | all |
| `LoginEntry` | `""` | `AccountEntryImpl` | account | all |
| `CommunityEntry` | `""` | `CommunityEntryImpl` | community | all |
| `FeedSource` | `""` | `RecommendFeedSource` | community | all |
| `FeedSource` | `"following"` | `FollowingFeedSource` | community | all |
| `FeedPublisher` | `""` | `CommunityFeedPublisher` | community | all |
| `ChannelService` | `""` | `FreeChannelServiceImpl` / `VipChannelServiceImpl` | community flavor sources | free / vip |
| `ProductionEntry` | `""` | `ProductionEntryImpl` | production split | all |
| `DynamicModuleInstaller` | `""` | `PlayModuleInstaller` | app | all |
| `DemoInfoService` | `"debug"` | `DebugInfoServiceImpl` | app `src/debug` | debug only |

Expected table size: **11 entries in debug and 10 in release**. `services()` and `keys()` are
index-aligned; the generated class has no `<clinit>` and constructs its providers with `new` /
`INVOKESTATIC` only.

What each entry demonstrates:

- `MediaCatalog` — a provider declared by a JDK-style `META-INF/services` file instead of an
  annotation. Its class has a public no-arg constructor, as that path requires.
- `AccountService` — a private constructor and a `@ProviderFactory`; the table emits
  `INVOKESTATIC LocalAccountService.create()` and shares one cached instance.
- `LoginEntry` / `CommunityEntry` / `ProductionEntry` — UI entry points. Callers pass a
  `Context`; the provider opens its own Activity, so callers never learn the Activity class name.
- `FeedSource` default / `"following"` — two keyed implementations of one interface.
- `FeedPublisher` — a resident provider invoked by the on-demand production split, which has no
  project dependency on `community`.
- `ChannelService` — a flavor-scoped provider; `free` and `vip` builds get disjoint tables.
- `ProductionEntry` — a provider whose class lives only in the `production` split. Its table entry
  is a class literal resolved on each `load()`, so it is `MISSING` before install and ready on the
  next `load()` after install, with no refresh.
- `DynamicModuleInstaller` — app-owned Play Feature Delivery behind a contract, keeping Play Core
  out of every other module.
- `DemoInfoService` — a build-type-only entry; release tables contain no entry for it.

## Build and run

Requires JDK 17 and an Android SDK (`local.properties`). The example consumes the framework from
this build's `local-repo`, so publish it first on a fresh clone:

```bash
./gradlew :annotation:publish :runtime:publish :plugin:publish
./gradlew :app:assembleFreeDebug
```

Variants are `free` / `vip` × `debug` / `release`. Distinct application ids let all four APKs be
installed side by side:

```bash
./gradlew :app:assembleFreeDebug :app:assembleVipDebug :app:assembleFreeRelease :app:assembleVipRelease
./gradlew :app:bundleFreeDebug   # AAB with the on-demand production split
```

A plain `installDebug` installs the base APK only, so tapping Create reports that dynamic delivery
is unavailable. To exercise the split locally, install the AAB through bundletool local testing:

```bash
./gradlew :app:bundleFreeDebug
bundletool build-apks \
  --bundle=app/build/outputs/bundle/freeDebug/app-free-debug.aab \
  --output=build/droidreel.apks \
  --local-testing
bundletool install-apks --apks=build/droidreel.apks
```

`DroidReelApp` extends `SplitCompatApplication`, so a local-testing split enters the running
process and the production hub opens without restarting the app.

## Demo behavior

- Login uses `demo` / `123456` (nickname `Xiao D`). The session survives a restart; no password is
  stored.
- The Recommend tab uses the default `FeedSource`; Following uses the `"following"` key and is
  gated behind login.
- Create requires login. If the production split is absent, the app requests it through
  `DynamicModuleInstaller` and opens the publisher as soon as it is installed.
- Profile → **SPI status** lists the generated table and reports the active `ChannelService`, the
  debug-only `DemoInfoService`, and the current `ProductionEntry` state. This is the on-device
  check used by the framework verification checklist.
- Public sample clips come from W3C media; covers are deterministic local gradients, so the feed
  renders offline.

## Relationship to the full DroidReel demo

This copy is deliberately smaller than the standalone DroidReel demo. It keeps every framework
capability the repository verifies and drops the parts that only exist to make the app feel
complete: the vertical player pager, comments, likes and favorites, registration, creator
profiles, camera capture, import, trim editing, and the multi-step publication flow. Creation is
reduced to picking a seeded clip and publishing it through `FeedPublisher`.
