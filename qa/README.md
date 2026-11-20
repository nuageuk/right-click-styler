# Right Click Styler v1.1.0 regression tests

This branch is the v1.1.0 release commit plus its regression tests, for reviewers who want to inspect or run them.
The plugin itself, `build.gradle`, `settings.gradle` and `runelite-plugin.properties` are identical to the release
commit `ff311447bc43ddc827a0e75705a3218706cf08b1`. Only `src/test` and this `qa` folder are added.

## Running the tests

You need Java 11 and network access to `repo.runelite.net` and Maven Central. From the repository root:

```bash
./gradlew -I qa/regression-tests.init.gradle test
```

On Windows, use `gradlew.bat` with the same arguments. Results are written to `build/test-results/test` and
`build/reports/tests/test/index.html`.

Expected result: **304 tests, 298 passed, 6 skipped, 0 failures, 0 errors.**

The six skips are expected. They are the `imageCache=false` cases of the parameterised `NativeSubmenuCycleTest`
(five tests) and `cacheOffNeverLooksUp[imageCache=true]`, each skipped by `assumeTrue` because it only applies to the
other parameter value.

The tests run on **Java 11 against RuneLite 1.13.7**, the versions the v1.1.0 release was verified and packaged
with. The init script adds JUnit 4.12, the RuneLite client and RuneLite's jshell as test-only dependencies, and pins
every RuneLite artifact to 1.13.7, including the plugin's own `compileOnly` dependency, which `build.gradle` leaves at
`latest.release` as the Plugin Hub template does. To try a newer client, change `runeLiteVersion` in the init script.
The release build itself does not use the init script, so the plugin JAR contains no test classes or dependencies.

## What the tests cover

- Native submenu probing, exact-coverage resolution and the native fallback: `NativeSubmenuCycleTest`,
  `ExactCoverageResolverTest`, `ExactAuthorityGateTest`, `ProbeBufferReuseTest`, `LoggedAmbiguousReplayTest`
- Probe behaviour against golden fingerprints: `ProbeSemanticsGoldenTest` with `ProbeStressCorpus`
- Styled submenu image reuse: `SubmenuImageCacheTest`
- Renderer geometry, clipping, colours and opacity: `MenuRendererGeometryTest`, `MenuHeaderGeometryTest`,
  `RowClipTest`, `MenuRendererColourTest`, `MenuRendererOpacityTest`
- Overlay behaviour, target classification and event handling: `MenuOverlayTest`
- Themes, settings, import/export and upgrades from the released v1.0.0 settings: `ThemeManagerTest`,
  `RightClickStylerPanelTest`, `ReleasedUpgradeCompatibilityTest` (with the in-memory `RuneLiteProfileStore`)
- The three final v1.1.0 fixes: `SubmenuDirectionLayoutTest` (right-aligned submenu arrows and text alignment),
  `SqueezedTextSpacingTest` (RuneScape-font spacing in narrowed rows) and `WornItemTargetKindTest` (Custom Item
  colours for worn equipment)

The fixtures in `src/test/resources/com/rightclickstyler` contain menu geometry, pixel counts and synthetic settings
only. Settings are tested in memory; the tests never read or write a real RuneLite profile.

## Notes

- `src/test/resources/logback-test.xml` is RuneLite's standard plugin-template logging file. Like any RuneLite
  plugin test run, it also appends to `~/.runelite/logs/client.log`. Some tests deliberately exercise failure
  paths, so warnings and stack traces in that log during a test run are expected.
- `./gradlew -I qa/regression-tests.init.gradle run` starts RuneLite in developer mode with the plugin loaded, as in
  the RuneLite plugin template. It is not needed for the tests.
