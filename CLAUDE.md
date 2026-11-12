# TransferingFileByAir

Android-first app for sliding files between devices that are physically next to each other. You lift a
file card and drag it off the screen edge, and it continues onto the neighbouring device's screen. The
codebase is Kotlin Multiplatform + Compose Multiplatform from day one (Android target only for now), so
a desktop and iOS receiver can be added later.

- **Product spec, video study and phased roadmap:** `docs/PLAN.md`. Read it before starting a phase.
- **Reference frames from the target video:** `docs/reference/`

## Git workflow (mandatory)

- **Every feature or change starts on its own branch off `main`**: `feature/<slug>`, `fix/<slug>`,
  `chore/<slug>`. Never commit directly to `main`. Don't merge without the user's go-ahead.
- **Commit messages use Conventional Commits** (`feat:`, `fix:`, `chore:`, `refactor:`, `test:`, `docs:`).
- **Commit messages must NOT contain any AI attribution**: no `Co-Authored-By` trailer, and no mention
  of Claude or AI in any form.

## Build and test

The `java` on PATH is JDK 8. Gradle 9.6 needs JDK 17+, so use Android Studio's bundled JBR:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:assembleDebug                          # build the app
./gradlew :core:domain:testAndroidHostTest            # unit tests of one KMP module
./gradlew testAndroidHostTest                         # unit tests of all KMP modules
```

## Module structure (feature-layered)

```
:app                          Thin Android shell: Application (startKoin), MainActivity → App()
:shared                       App() root composable, root NavHost, list of Koin modules (appModules)
:core:domain                  Shared domain models + Result / Error / DataError
:core:presentation            Shared presentation helpers (ObserveAsEvents)
:core:designsystem            AirTheme, AirColors, AirDimens, reusable effect composables
:feature:<name>:domain        Feature models, repository interfaces, use cases  (pure Kotlin)
:feature:<name>:data          Repository implementations, data sources, mappers
:feature:<name>:presentation  MVI ViewModel, Root/Screen composables, feature nav graph, Koin module
```

- **Dependency direction:** `presentation → domain ← data`. Domain depends on nothing but `:core:domain`.
  Presentation never imports data, and data never imports presentation.
- **Placement:** code used by one feature stays in that feature. It moves to a `:core:*` module only when
  a second feature needs it.
- **Create modules only when there is code for them.** No empty placeholder modules.
- **Convention plugins in `build-logic/`:**
  - `air.kmp.library`: KMP + Android target, namespace derived from the module path, SDK levels from
    the catalog, host tests, kotlin-test + AssertK
  - `air.kmp.compose`: the above + Compose Multiplatform
  - `air.kmp.feature`: the above + core modules, lifecycle/ViewModel, navigation, Koin, serialization,
    Turbine
- **Versions live only in `gradle/libs.versions.toml`.** Never hard-code a version or SDK level in a
  module build file.

## KMP rules

- `commonMain` must not import `android.*`, `java.io.File`, or any other platform API. Put platform
  capabilities (file access, haptics, blur support, display metrics, Nearby) behind an interface in
  common code, implemented in `androidMain`, and bind the implementation in a platform Koin module.
- Use multiplatform libraries: Compose Multiplatform (`org.jetbrains.compose.*`), JetBrains
  lifecycle/navigation, Koin, kotlinx.*, Coil 3, Okio/kotlinx-io.

## Presentation: MVI

Every screen has the full triad in its feature's `presentation` package:

- `XState`: immutable `data class`, the single source of truth for the UI. Exposed as `StateFlow<XState>`.
- `XAction`: `sealed interface` of user intents. The UI calls `viewModel.onAction(action)` only.
- `XEvent`: `sealed interface` of one-off effects (navigation, snackbar, haptic). Sent through a
  `Channel<XEvent>`, exposed with `receiveAsFlow()`, and consumed with `ObserveAsEvents`.
- `XViewModel`: holds `_state: MutableStateFlow`, `eventChannel`, and `fun onAction(action: XAction)`.
  It talks only to domain abstractions (repositories/use cases), never to platform APIs.
- Composables are split in two:
  - `XRoot(viewModel = koinViewModel())`: collects state with `collectAsStateWithLifecycle()`, observes
    events, and passes navigation callbacks in.
  - `XScreen(state, onAction)`: stateless and previewable. Every screen gets a `@Preview`.
- **Navigation:** type-safe `@Serializable` routes. Each feature exposes one
  `NavGraphBuilder.<feature>Graph()` with a public `<Feature>Graph` entry route. Cross-feature navigation
  goes through lambdas passed into the graph function, never a shared NavController inside a feature.

## Domain and data

- Use cases only when they add logic (orchestration, validation, combining sources). No pass-through use
  cases that only forward to a repository.
- **Errors:** functions that can fail return `Result<T, E : Error>` / `EmptyResult<E>`
  (`core.domain.util`). Data sources catch platform exceptions and map them to `DataError`. Nothing
  above the data layer throws for expected failures.
- **DI:** Koin. One module per layer per feature (`shelfDataModule`, `shelfPresentationModule`…),
  added to `appModules` in `:shared`. Platform-specific bindings are added in the platform shell.

## Testing

- Tests go in `commonTest`: `kotlin.test` annotations, **AssertK** assertions, **Turbine** for Flows,
  `kotlinx-coroutines-test` with `UnconfinedTestDispatcher`, and hand-written fakes instead of mocks.
- Every ViewModel gets State/Action/Event tests. Every use case and mapper gets unit tests.
- Test names are backticked sentences describing behaviour.

## Animation and performance (the product *is* the motion)

- Follow the `compose-motion-design` skill. Use springs for anything the user drives.
- Read animated values only inside `graphicsLayer {}` / `drawBehind {}` / lambda modifiers, so per-frame
  animation never recomposes.
- Real blur (`Modifier.blur`, `RenderEffect`) is API 31+, and AGSL shaders are API 33+. Always provide a
  fallback (scrim/alpha) below those levels. minSdk is 26.
- The app is dark-only. Use `AirColors` tokens and never hard-code colours in features.
