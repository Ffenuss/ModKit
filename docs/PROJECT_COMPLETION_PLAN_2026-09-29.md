# ModKit — план доведения проекта до завершённого состояния

Дата фиксации: 2026-09-29  
Интеграционная база: `fix/single-overlay-menu-20260927` @ `3933baff576a563adf5b2ba7f589326b0a6062ad` (ModKit Test 0.0.35)  
Последний полностью зелёный CI этой базы: run `36331001772` — build SUCCESS, API 29 SUCCESS, API 35 SUCCESS.

## Правило интеграции

Новые изменения больше не развиваются параллельными несвязанными ветками. Эта ветка
`integration/road-to-1.0-20260929` — единая последовательная база. Для каждой задачи
создаётся отдельная короткая ветка **от текущего интеграционного состояния**, отдельный
draft PR обратно в эту интеграционную ветку, небольшие коммиты и собственные проверки.
После подтверждения результата задача может быть перенесена в интеграционную ветку.
`main` не изменяется без отдельного разрешения владельца.

Существующие PR #7–#23 остаются историей и источниками уже проверенных изменений.
Новые задачи не должны создавать новые независимые линии развития поверх старых PR.

## Текущее подтверждённое состояние

- DEX: анализ и поддерживаемые точные преобразования; обратимые runtime-переключатели.
- Native/IL2CPP ARM64: metadata/ELF binding, полный disk-backed binding index, безопасные
  скалярные рецепты, reversible native overlay.
- 30 000 больше не является потолком анализа: это только RAM/UI preview. Полный
  MethodDef binding index хранится на диске и поддерживает on-demand lookup.
- Остаётся отдельный metadata guard `300_000`, который должен стать streaming/disk-backed.
- Unreal: PAK/UASSET/IoStore structural inventory; gameplay Blueprint executor отсутствует.
- Flutter: AssetManifest/libflutter/libapp inventory; Dart AOT executor отсутствует.
- ELF: реальный прогресс по библиотекам и отдельный watchdog.
- Установка: PackageInstaller single/split, API29/API35 регрессии, signer-conflict UX.
- Overlay: одна внешняя MK-кнопка в simple mode; owned DEX/native fixtures подтверждают ON/OFF.
- Физический ARM64 с реальной целевой игрой ещё не является общим подтверждением.

## Последовательность работ

### 1. Единая интеграционная линия — P0
- Зафиксировать 0.0.35 как стабильную базу.
- Добавить CI для PR, направленных в `integration/road-to-1.0-20260929`.
- Не продолжать разработку напрямую в PR #7–#23 и не изменять `main`.
- Каждый следующий этап — отдельная ветка от текущей интеграционной базы.

**Готовность:** интеграционная ветка существует; workflow принимает PR в неё; baseline SHA и CI записаны.

### 2. Исправить Flutter E2E PR #23 — P0
- Воспроизвести падение `f_flutterResourceModChangesActualAssetBundleAndSurvivesRestart`.
- Не ослаблять ожидание игрового эффекта.
- Добиться стабильного baseline `20 → 13 → 6 → 0` на API29/API35.
- Затем доказать patched `99 → 92 → 85 → 78`, `damage=7`, restart => `health=99`.
- После Flutter добавить owned Unreal runtime fixture для поддерживаемого loose INI случая.

**Готовность:** build/JVM/lint, API29 и API35 зелёные; тест проверяет фактическое состояние приложения.

### 3. Все simple-mode моды должны быть обратимыми — P0
- Статические Flutter JSON / Unreal INI изменения не выдавать за overlay-переключатели.
- Пока нет runtime executor — держать их в expert/static разделе с явной маркировкой.
- Simple mode должен принимать только рецепты с реальным OFF/restore.
- Не смешивать runtime и static семантику одной кнопкой.

### 4. Installer/source compatibility — P0
- Продолжить работу от WIP `1493183b...`, но перенести её на текущую интеграционную базу.
- Сначала detector: точные PackageManager/InstallSourceInfo calls + dataflow/compare/branch.
- Применять compatibility rewrite только к доказанной локальной проверке в собственной/
  разрешённой тестовой сборке.
- Отдельно диагностировать reflection/JNI/native checks, signing checks, Play Integrity,
  server attestation. Не утверждать универсальный обход.
- Никогда автоматически не удалять оригинал и не маскировать signer conflict.

### 5. Убрать metadata ceiling 300k — P1
- 30k оставить только UI preview.
- Перевести MethodDef/type/field/image ownership в streaming/disk-backed representation.
- Убрать жёсткую необходимость держать <=300k MethodDef объектов в RAM.
- Ограничивать по валидированному размеру metadata, диску и проверяемым integer bounds.
- Fixtures минимум 350k и 500k MethodDef + low-memory Android test.

### 6. Расширить доказуемые IL2CPP рецепты — P1
- Field offsets + object-instance proof на owned fixtures.
- Getter/setter pairs, bounded state writes, более широкий AArch64 CFG.
- Поддерживать load/compare/branch и ограниченные side-effect patterns только при доказанной безопасности.
- Не снимать shared-body/unknown-call blockers без независимого доказательства.

### 7. Generic native ARM64 backend — P1
- ELF symbols/RTTI/strings/xrefs.
- Bounded CFG и scalar-return/field-load patterns.
- Existing reversible native overlay как executor.
- Owned C/C++ fixture с доказуемыми health/damage/state функциями.
- Stripped binary без семантики => research candidates, не выдуманные игровые моды.

### 8. Unreal deep backend — P1
- PAK entry parser.
- UE5 IoStore TOC/package graph.
- Blueprint bytecode/config/CVar correlation.
- Runtime engine adapter для обратимых изменений.
- Encrypted PAK без разрешённого ключа => unsupported, не fake zero-result.

### 9. Flutter Dart AOT backend — P1
- Version/snapshot detection.
- `libapp.so` function/code inventory.
- Доступные AOT metadata/cluster correlations.
- Safe native/AOT recipes + reversible runtime executor.
- JSON остаётся отдельным resource backend, не заменяет Dart AOT.

### 10. Unity Mono/.NET backend — P1
- Assembly metadata + CIL bodies + CFG.
- Scalar getters/fields и доказуемые project-code candidates.
- Reversible runtime instrumentation.
- Owned Mono fixture до любых общих claims.

### 11. Следующие движки — P2
Порядок: Godot → Cocos/Lua → Hermes/JSC → Defold → Qt/QML → WASM.

Для каждого обязательный контракт:
`detection → structural inventory → executable proof → candidate → reversible executor → owned fixture → API29/API35 → physical ARM64`.

Detection-only backend не может называться модификатором.

### 12. UX и диагностический экспорт — P0
- Разбивать недоступные варианты по причинам: field-offset, shared body, UI-only,
  unsupported return, side effects, ABI, missing source и т.д.
- Экспортировать engine timings, last heartbeat, routing decisions, coverage,
  installer compatibility и exact blockers.
- Раздельно показывать `candidate`, `recipe prepared`, `APK verified`,
  `runtime ON`, `runtime OFF restored`, `gameplay observed`.

### 13. Финальная Android QA — P0
- API29 single APK.
- API35 split APK.
- Физический ARM64 Android.
- Большой IL2CPP, DEX ON/OFF, native ON/OFF, restart, overlay permission revoke/regrant,
  split install, low storage, signer conflict, сохранность предыдущей сборки и данных.

### 14. Release candidate и main — P0
- Один RC branch из интеграционной линии.
- Полный CI и device matrix.
- APK SHA/signature/alignment/content verification.
- APK пользователю для физического теста.
- Только после отдельного подтверждения владельца обновлять `main`.
- Старые stacked PR закрывать как superseded только после подтверждённой интеграции.

## Stop-ship условия

Релиз не считается готовым, если:
- API29 или API35 красный;
- simple-mode выбор нельзя выключить/восстановить;
- сборка заявлена успешной без проверки APK;
- unsupported runtime отображается как «моды не найдены» без причины;
- приложение автоматически удаляет оригинальный пакет/данные;
- метод считается игровым только по имени без достаточного контекста;
- CI fixture выдается за подтверждение конкретной коммерческой игры;
- физический ARM64 не пройден для финального RC.

## Статус выполнения

- **Этап 1 — завершён:** создана `integration/road-to-1.0-20260929`, зафиксирована зелёная база 0.0.35, CI принимает последовательные PR в интеграционную линию.
- **Этап 2 — код и автоматические проверки завершены в draft PR #24:** run `36490135210` — build SUCCESS, API 29 **8/8**, API 35 **8/8**. Flutter проверяет baseline `20→13→6→0`, мод `99→92→85→78`, неизменённый damage=7 и restart persistence. Добавлен owned Unreal loose-INI fixture с реальным изменением состояния и сохранением после restart.
- **Этап 3 — завершён в draft PR #25:** простой режим выбирает только рецепты с реальным runtime OFF/restore; статические Flutter/Unreal resource rewrites остаются backend/test возможностью и не выдаются за обычные переключаемые моды. После исправления Android-10 закрытия панели run `36559043808` полностью зелёный: build SUCCESS, API 29 **8/8**, API 35 **8/8**.
- **Этап 4 — начат в draft PR #26:** добавлен консервативный DEX preflight, который отличает простое чтение источника установки от локальной проверки, реально управляющей `if`-веткой. Переписывание целевого кода на этом подэтапе ещё не выполняется.
- PR не сливаются без отдельного подтверждения владельца; `main` не изменён.

## Первый активный следующий этап

После фиксации этой интеграционной базы — задача 2: исправление Flutter E2E из PR #23,
начиная с воспроизводимого baseline failure и без ослабления проверки.
