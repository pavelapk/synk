# Repository Guidelines

## Project Structure & Module Organization
- Root modules: `:synk` (KMP library), `:libs:*`, `:extension:kotlin-serialization`, `:metastores:delightful-metastore`, `:plugins:ksp-adapter-codegen`.
- Source sets (KMP): `synk/src/commonMain/kotlin`, `synk/src/androidMain/kotlin`, tests in `synk/src/commonTest/kotlin`.
- Build scripts: `build.gradle.kts`, module `build.gradle.kts`, and `settings.gradle.kts` for module inclusion and repositories.
- Docs and metadata: `README.md`, `docs/`, `.editorconfig` for formatting rules.

## Build, Test, and Development Commands
- Build all: `./gradlew build` — compiles, runs checks across modules.
- Common code compile check: `./gradlew :synk:compileCommonMainKotlinMetadata` — validates `commonMain` compiles.
- Run JVM tests for `:synk`: `./gradlew :synk:jvmTest` — executes common/JVM tests and reports to `synk/build/test-results/jvmTest`.
- Run tests for `ksp-adapter-codegen`: `./gradlew :plugins:ksp-adapter-codegen:test`
- Lint Kotlin (ktlint): `./gradlew lintKotlin` | Auto-format: `./gradlew formatKotlin`.
- WSL note: `cmd.exe /c gradlew <task>` to run Gradle from WSL.

## Coding Style & Naming Conventions
- Kotlin style: `ktlint_official`, 4-space indent, LF line endings, UTF-8, max line length 160.
- Prefer explicit names: `PascalCase` for types, `camelCase` for functions/vals, `CONSTANT_CASE` for constants.
- Trailing commas allowed (multiline). Keep public APIs documented with KDoc.

## Testing Guidelines
- Framework: Kotlin test (`kotlin("test")`), with fakes via Okio FakeFileSystem and Faker where helpful.
- Location: `synk/src/commonTest/kotlin` (and platform-specific tests if needed).
- Naming: mirror source package; test files end with `*Test.kt`; methods use `should...`/`when...then...` patterns.
- Run locally: `./gradlew :synk:jvmTest`.

## Commit & Pull Request Guidelines
- Commits: use Conventional Commits where possible (e.g., `feat:`, `fix:`, `chore:`). Keep scope small and messages imperative ("add", "fix").
- PRs: include summary, motivation, screenshots/logs if user-facing, and link issues. Note any API or behavior changes.
- CI must be green: build, lint, and tests should pass.

## Security & Configuration Tips
- GitHub Packages: set `ghPackagesReadUser` and `ghPackagesReadPassword` via env (`ORG_GRADLE_PROJECT_<key>`) or `local.properties` as referenced in `settings.gradle.kts`.
- Avoid committing secrets. Use `.gitignore` patterns already provided.
