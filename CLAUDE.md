# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

greenDAO Gradle plugin — a build-time code generator for the greenDAO Android ORM. It scans `@Entity`-annotated Java classes, generates DAO/DaoMaster/DaoSession classes via greenDAO's generator, and modifies entity source files to inject constructors, getters, setters, and relation methods.

## Build & Test

```bash
# Full build + tests (includes check task)
./gradlew clean check

# Build a specific module
./gradlew :greendao-code-modifier:build
./gradlew :greendao-gradle-plugin:build
./gradlew :greenrobot-jdt:build

# Run tests for a single module
./gradlew :greendao-code-modifier:test
./gradlew :greendao-gradle-plugin:test

# Run a specific test class (Kotlin test naming)
./gradlew :greendao-code-modifier:test --tests "org.greenrobot.greendao.codemodifier.VisitorTest"
```

## Architecture

Three Gradle subprojects, versioned at `3.3.2-SNAPSHOT`:

### `greendao-gradle-plugin`
The Gradle plugin entry point (`Greendao3GradlePlugin.kt`). Applied as `apply plugin: 'org.greenrobot.greendao'`, it registers two tasks:
- **`greendaoPrepare`** (`DetectEntityCandidatesTask`) — scans `.java` sources for the string `org.greenrobot.greendao.annotation` to find entity candidates. Supports Gradle incremental builds.
- **`greendao`** — invokes `Greendao3Generator` on the candidate list. Supports multiple schemas, each with their own `daoPackage`, `schemaVersion`, and `targetGenDirTests`.

### `greendao-code-modifier`
The core code generation engine, using Eclipse JDT for Java source parsing and transformation:

- **`Greendao3Generator`** — main orchestrator: parses entity sources via `EntityClassParser`, runs `DaoGenerator` to produce DAO classes, then transforms each entity source file using `EntityClassTransformer`.
- **`EntityClassParser`** — parses a `.java` file using Eclipse `ASTParser` (JLS8), then walks the AST with `EntityClassASTVisitor`. Ignores common JDT binding errors for external types.
- **`EntityClassASTVisitor`** — extracts entity metadata: `@Entity` config, properties (`@Id`, `@Property`, `@Index`, `@Unique`, `@Convert`), relations (`@ToOne`, `@ToMany`, `@JoinEntity`), methods, constructors, and `@Keep`/`@Generated` hints.
- **`ParsedModel.kt`** — data classes: `ParsedEntity`, `ParsedProperty`, `Variable`, `VariableType`, relation types, `GeneratorHint` (Keep/Generated).
- **`EntityClassTransformer`** — modifies entity sources using `ASTRewrite`. Replaces `@Generated`-annotated code with fresh output, preserves `@Keep`-annotated code, and removes stale generated nodes. Detects source formatting (tabulation, line width) for consistent output.
- **`GreendaoModelTranslator`** — translates `ParsedEntity` lists into greenDAO's `Schema`/`Entity` model, resolving to-one/to-many relations and converting Java types to `PropertyType`.
- **`Templates.kt`** — Freemarker templates (`.ftl` files in resources) for generated code snippets (constructors, getters, setters, relation methods).

### `greenrobot-jdt`
Repackaged Eclipse JDT 3.20.0 as a shadowed jar. Uses the `com.github.johnrengelman.shadow` plugin to relocate `org.eclipse` → `org.greenrobot.eclipse` (and `org.apache`, `org.osgi`) to prevent classpath conflicts with Android's ECJ.

## Plugin Extension DSL

Consumer projects configure the plugin via the `greendao { }` block (defined in `GreendaoOptions.kt`). Key options: `daoPackage`, `schemaVersion`, `targetGenDir`, `generateTests`, `formatting { }`, and `schemas { }` for multi-schema setups.

## Key Dependencies

- **Eclipse JDT Core 3.20.0** — Java AST parsing and rewriting (repackaged in `greenrobot-jdt` with shadow relocation)
- **greenDAO API 3.3.0** — entity annotations
- **greenDAO Generator 3.3.0** — `DaoGenerator` produces DAO/DaoMaster/DaoSession
- **Kotlin 2.3.21** — all modules written in Kotlin
- **FreeMarker 2.3.29** — code generation templates
- **Shadow plugin 9.0.0-beta8** (GradleUp fork) — jar repackaging for `greenrobot-jdt`

## CI

Jenkinsfile at `ci/Jenkinsfile`. Pipeline stages: `init` → `build` (`./gradlew clean check`) → `upload-to-internal`. Publishes via `uploadArchives` to an internal Nexus repo.

## Publish to Maven Central (manual)

Set `sonatypeUsername`/`sonatypePassword` in global `gradle.properties`, then run:
```
:greendao-code-modifier:publish
:greendao-gradle-plugin:publish
```
Then close + release from https://oss.sonatype.org/#stagingRepositories.
