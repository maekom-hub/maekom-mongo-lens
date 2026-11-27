# Contributing to Maekom Mongo Lens

Thank you for your interest in contributing to **Maekom Mongo Lens**!

## Development Environment

- **JDK**: Minimum **JDK 17**, Recommended **JDK 21** (matching IntelliJ Platform `2024.2+`)
- **IntelliJ IDEA**: `2024.2+` (Community or Ultimate)
- **Database Engines**: MongoDB `4.4 ~ 8.0` and/or AWS DocumentDB `3.6 / 4.0 / 5.0`

### Local Run & Build Commands

Run the plugin inside a sandboxed IntelliJ IDEA instance:

```bash
./gradlew runIde
```

Build and verify the distributable plugin archive:

```bash
./gradlew buildPlugin --no-daemon
```

## Coding & Commit Guidelines

1. **Conventional Commits**: Please format commit messages following [Conventional Commits](https://www.conventionalcommits.org/) (e.g., `feat: ...`, `fix: ...`, `docs: ...`, `refactor: ...`, `chore: ...`).
2. **Bilingual Localization (`I18n.kt`)**: Every user-facing string added or changed in `src/main/kotlin/com/maekom/mongolens/i18n/I18n.kt` **must** be synchronized in both **Korean (`ko`)** and **English (`en`)** maps.
3. **MongoDB & AWS DocumentDB Compatibility**:
   - Avoid unsupported aggregation stages (such as multi-condition `$lookup` without `let` + `pipeline`) and never attach `maxTimeMS` to write commands (`updateOne`, `insertMany`, etc.).
   - Keep UI updates non-blocking (`AppExecutorUtil` + `invokeLater` with `ModalityState.any()` inside modal dialogs).
4. **No Secrets or Local Artifacts**: Never commit `.env*`, real connection URIs, certificates, or local IDE files.

## License Agreement

By submitting a Pull Request or any Contribution to this repository, you agree that your contributions will be licensed under the **Apache License, Version 2.0** (see [LICENSE](./LICENSE)).
