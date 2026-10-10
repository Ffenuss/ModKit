# Каталог поиска мобильных игровых механик, версия 1

Каталог встроен в ModKit, а не рассчитан на ручной анализ каждой игры помощником.
30 наборов запускаются для каждого выбранного жанра, включая неизвестный жанр и приложение.
Жанр меняет порядок проверок, но не исключает наборы. Это гарантия применения
словаря к доступным символам; это не гарантия нахождения адреса или рабочего мода.
Жанры пересекаются, поэтому конечного списка «вообще всех игр» не существует.

## Жанры и переносимые цели

| Семейство и поджанры | Что обычно меняют | Наборы каталога |
| --- | --- | --- |
| RPG, ARPG, JRPG, коллекционные RPG | HP, мана, опыт, предметы, стоимость, дроп | health, damage, stamina, progression, inventory, currency, rewards |
| Экшен, beat-em-up, hack-and-slash | урон, защита, движение, способности | health, damage, movement, cooldown |
| Шутеры FPS/TPS, shoot-em-up | патроны, отдача, разброс, перезарядка | ammunition, weapon-control, damage, cooldown, camera |
| Платформеры, раннеры, metroidvania | прыжок, скорость, коллизии, жизнь | movement, collision, health |
| Roguelike/roguelite | здоровье, предметы, случайные награды, действие | health, rewards, inventory, cards |
| Выживание, survival horror | голод, кислород, износ, производство | survival-needs, health, inventory, crafting |
| Гонки, аркадные и реалистичные | тяга, нитро, сцепление, топливо, таймер | vehicle, race-clock |
| RTS, TBS, 4X, auto-battler | ресурсы, лимиты, строительство, волны | strategy-rules, crafting, currency, turns |
| Tower Defense | темп огня, дальность, волны, ресурсы | strategy-rules, damage, cooldown, currency |
| Puzzle, match-3, merge, физические, слова | ходы, подсказки, время, перемешивание | puzzle-budget, puzzle-actions |
| Карточные CCG/TCG, deck-builder | энергия, цена карты, добор, рука | cards, stamina, turns |
| Настольные, шахматы, шашки, кости | время хода, локальные правила, лимиты | turns, puzzle-budget |
| Музыкальные и ритм | окно попадания, скорость нот, комбо | rhythm |
| Спорт: футбол, баскетбол, гольф и др. | сила удара, выносливость, физика мяча | sports, stamina, movement |
| Файтинги | шкалы, блок, stun, окна комбо | fighting, health, damage |
| Idle, incremental, clicker | доход, сила клика, производство, локальный офлайн-доход | idle, currency, world-clock |
| Симуляторы фермы, города, бизнеса, жизни | время, производство, потребности, экономика | world-clock, crafting, survival-needs, currency |
| Приключения, квесты, escape room | предметы, условия событий, подсказки | adventure, inventory, puzzle-budget |
| Визуальные новеллы, dating sim | текст, выборы, отношения, сохранения | adventure, local-settings |
| Песочницы, voxel, строительство | правила мира, ресурсы, коллизии, контент | strategy-rules, crafting, collision, content |
| MMO, MOBA, battle royale, сетевые гибриды | локальная камера и интерфейс; игровые цели требуют установления authority | camera, content, local-settings, health |
| Stealth и horror | обнаружение, звук, обзор, движение, ограничения | camera, movement, health, content; специальный анализ AI пока отсутствует |
| Gacha, лотереи, casino-подобные системы | локальная визуализация и сохранения; результаты часто определяет сервер | rewards, currency, local-settings; серверный эффект не предполагается |
| Обычные приложения | настройки, интерфейс, текст, локальные данные | content, local-settings |

## Что переносят из готовых модов

Исходники показывают разные способы, которые нельзя свести к возврату одной константы.
Примеры прочитаны, но сами моды в этой работе не запускались. Desktop-примеры
показывают механизм; совместимость их загрузчиков с Android не предполагается.

| Пример | Устройство изменения | Что обязан иметь исполнитель |
| --- | --- | --- |
| [CJB InfiniteHealth](https://github.com/CJBok/SDV-Mods/blob/master/CJBCheatsMenu/Framework/Cheats/PlayerAndTools/InfiniteHealthCheat.cs) | поддерживает здоровье живого игрока в обновлениях | объект игрока, поток игры, проверка смены сцены, снятие обработчика |
| [CJB MoveSpeed](https://github.com/CJBok/SDV-Mods/blob/master/CJBCheatsMenu/Framework/Cheats/PlayerAndTools/MoveSpeedCheat.cs) | добавляет и обновляет собственный эффект скорости | идентификатор собственного эффекта, удаление именно этого эффекта |
| [BaseMod InfiniteEnergy](https://github.com/daviscook477/BaseMod/blob/master/mod/src/main/java/basemod/patches/com/megacrit/cardcrawl/ui/panels/EnergyPanel/InfiniteEnergy.java) | сохраняет энергию до вызова и восстанавливает после | типизированный before/after hook, состояние отдельного вызова |
| [Hackustry worldoptions](https://github.com/QmelZ/hackustry/blob/master/scripts/features/v4/worldoptions.js) | меняет правила мира и лимиты | объект правил, различение локального мира и сетевого клиента |
| [DebugToolkit Noclip](https://github.com/harbingerofme/DebugToolkit/blob/master/Code/DT-Commands/Command_Noclip.cs) | согласует коллизии, гравитацию и движение | групповая операция с восстановлением и обновлением объекта |
| [Mindustry Rules](https://github.com/Anuken/Mindustry/blob/master/core/src/mindustry/game/Rules.java) | игровые правила отдельно от текущего состояния | различение конфигурации и количества ресурсов |
| [osu HitWindows](https://github.com/ppy/osu/blob/master/osu.Game/Rulesets/Scoring/HitWindows.cs) | решение о попадании зависит от окна времени | анализ пути judgement, а не только переменной счёта |
| [Shattered Pixel Dungeon Hero](https://github.com/00-Evan/shattered-pixel-dungeon-gdx/blob/master/core/src/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java) | потребности и эффекты живут в связанных игровых объектах | поиск владельца эффекта и связей между объектами |

Подробный разбор первых пяти механизмов: [OPEN_MOD_MECHANISMS_20261010.md](OPEN_MOD_MECHANISMS_20261010.md).

## Статические наборы и структурный анализ

Машиночитаемый источник: `StaticModSearchCatalog.kt`. Каждый набор имеет ID,
название, жанры для приоритета, словарь целых токенов и предполагаемые механизмы.
CamelCase, snake_case и аббревиатуры нормализуются; подстроки вроде GoldenRatio
или Coincidence не должны становиться валютой. Словарь содержит сигналы, а не
готовые адреса, значения или разрешения на изменение.

Чтобы найти тот же смысл при скрытых именах, нужны структурные проверки:

| Форма механики | Что проверить в коде | Что проверить во время выполнения |
| --- | --- | --- |
| Возврат поля / константы | тип возврата, загрузка поля, отсутствие побочных записей | влияет ли значение на игровую логику, какой объект владелец |
| Расход ресурса | read → subtract → clamp → write, ветвление при нехватке | локален ли ресурс, не пересчитан ли он другим источником |
| Урон / лечение | источник, получатель, тип, знаки, clamp, события смерти | принадлежность игроку, сохранение обязательных событий |
| Таймер / cooldown | delta-time, накопитель, сравнение с порогом, сброс | часы мира или UI, единицы времени, сцена |
| Скорость / сила / множитель | путь от параметра к движению или расчёту эффекта | сохранение оригинального вызова, единицы, допустимый диапазон |
| Правило мира | булевое условие, потребители настройки | кто владеет миром, scope команды/игрока |
| Коллизии / noclip | физические компоненты и слои | текущий объект, согласованность гравитации и коллизий, восстановление |
| Judgment / RNG | путь от ввода или случайного значения к решению | решение локальное или серверное, влияние на результат |
| Контент / script / save | формат, версия, загрузчик, ссылки на данные | перезагрузка, checksum, срок жизни, состояние игры |

Эта таблица задаёт требования следующего структурного анализатора. В версии 1
не реализовано универсальное распознавание этих графов. Уже существующие
проверки тела DEX и native-байтов продолжают определять допустимость рецептов.

## Движки и доставка кода

| Среда | Доступный статический материал | Требуемый адаптер |
| --- | --- | --- |
| Java/Kotlin/DEX | методы, поля, инструкции, подписи, JNI | типизированные DEX hooks и связанные объекты |
| Unity IL2CPP | metadata, native ELF, регистрация, точные binding | IL2CPP runtime, native ABI, объекты и оригинальные вызовы |
| Unity Mono | managed assemblies и runtime | Mono adapter; IL2CPP не заменяет его |
| Unreal / C++ / custom engine | ELF, экспорт, RTTI/описания при наличии | engine-specific/native structural + runtime adapter |
| Godot | ресурсы, native engine, scripts/managed слой | Godot adapter с учётом языка и экспорта |
| Lua / JS / Cocos / WebView | script bundles, bytecode, native мосты | adapter соответствующего runtime и версии |
| Загружаемые packs / plugins | отдельные файлы после установки | переиндексация доступных пакетов и наблюдение загрузки |

Unity документирует различие Mono JIT и IL2CPP AOT: [Scripting backends](https://docs.unity3d.com/Manual/scripting-backends-intro.html).
Android Asset Delivery допускает fast-follow и on-demand packs: [интеграция](https://developer.android.com/guide/playcore/asset-delivery/integrate-java).
Godot имеет разные Android-пути экспорта: [официальная документация](https://docs.godotengine.org/en/stable/tutorials/export/exporting_for_android.html).
Поэтому отсутствие цели в base APK не доказывает отсутствие механики.

## Space и root

Общий каталог должен использоваться обоими исполнителями. Space работает с
доступными артефактами и своим процессом; root-адаптер сможет добавлять снимки
загруженных модулей, метаданные и наблюдения объектов целевого процесса, если
права и политика устройства это позволяют. Root сам не восстанавливает смысл
обфусцированного имени: нужны типы, связи, графы кода и runtime-наблюдения.

Root меняет доступ на телефоне, но не права на удалённом сервере. При server
authority окончательное состояние определяет сервер; локальное изменение
может быть визуальным либо перезаписываться синхронизацией. Нужны состояния
`local`, `server`, `mixed`, `unknown`, установленные доказательствами, а не
названием жанра или наличием root. См. [Unity authority](https://mp-docs.dl.it.unity3d.com/netcode/current/terms-concepts/authority/)
и [NetworkTransform](https://docs-multiplayer.unity3d.com/netcode/current/components/networktransform).
В этом изменении root backend и автоматический authority-анализ не реализованы.

## Реально подключённое поведение версии 1

- DEX scanner передаёт названия методов и полей не исключённых классов в каталог,
  включая методы, для которых нет готового scalar replacement.
- Space дополняет проход доступными методами и полями IL2CPP metadata.
- Late IL2CPP discovery использует каталог: nitro, hunger, hit-window и другие
  сигналы больше не теряются из-за отдельного узкого списка имён.
- Профиль Space содержит version, symbolsExamined, scope, все 30 ID, hits и
  требуемые mechanisms. `nameMatchesAreExecutable=false` фиксирует границу.
- Счётчики хранят только агрегаты, а не полный список имён; отмена проверяется
  регулярно. Дубликаты символов считаются как отдельные просмотренные элементы;
  hits не являются числом уникальных или рабочих модов.
- Отсутствующие metadata, пропущенные DEX, исключённые классы, неподдерживаемые
  движки и обфускация ограничивают охват. Профиль не утверждает полноту анализа.
- Текущий Space backend `native_v1` остаётся исполнителем проверенных byte patches.
  Названия 12 механизмов не означают, что все 12 исполнителей уже существуют.

## Проверки

Добавлены тесты применения всех наборов во всех жанрах, достижимости каждой
словарной фразы, CamelCase/аббревиатур, ложных подстрок, ограниченных счётчиков,
отмены, DEX-цели без автоматического рецепта и late IL2CPP binding трёх новых
механик. Результат текущего CI указывается отдельно после его завершения.
