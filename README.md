# MythicWardrobe v1.10.0

> 🌐 **Веб-студия онлайн (GitHub Pages):** **[https://shokydo.github.io/MythicWardrobe/](https://shokydo.github.io/MythicWardrobe/)**  
> **Studio v2.5:** полноценный осмотр персонажа **2D + 3D одновременно** (все 15 слоёв 2D-скина + все 3D-модели MythicArmors, 5 поз, 8 ракурсов, 3 режима освещения с тестом `_e.png` и прозрачный PNG-скриншот), 3D-комбайнер `.bbmodel`, авто-доделка служебных текстур (`_layer_1`/`_layer_2`/`_t`/`_e`), настройка 3D-причёсок под шлемами, 2D-скины с блокировками, и визуальный GUI-редактор `menus.yml` со встроенными **149 текстурами Minecraft 1.21.4** — работает прямо в браузере!

Плагин-система модульной 2D-одежды, конструктора слоёв скина (`layers.yml` + `priority.yml`), типа тела (`slim` / `wide`), стартового пака из **68 текстур 64×64** по 15 категориям, косметической и 3D-брони **MythicArmors** (включая **мультимодальную косметику `cosmetic-variants.yml`**, 3D-причёски, 3D-рюкзаки, 3D-маски, 3D-пояса и скрещённые комбо-модели с нулевым лагом), системы блокировки слоёв/слотов (`Blocks` / `BlockMode`), нативного запуска триггеров **`~onEquip` / `~onUnequip`** при надевании и снятии вещей в меню, и полностью настраиваемых GUI-меню (`menus.yml`) для **Paper 1.21.8 – 1.21.11** с интеграцией **SkinsRestorer v15**, **MineSkin API v2**, **MythicMobs 5.13.0**, **MythicArmors** и **PacketEvents**.

### Ключевые возможности (v1.10.0)
- **🎨 Мультимодальная косметика (`cosmetic-variants.yml` + `Wardrobe.Variants:`):** **1 косметический предмет** (например `WhiteVestPaint` — «Белая краска для бронежилета» из любого материала `WHITE_DYE`/`PAPER`) автоматически адаптирует модель под надетую реальную броню (`LightMilitaryVest` → `LightMilitaryVest_White`, `HeavyMilitaryVest` → `HeavyMilitaryVest_White`, перекраска `dye-color`, подмена `equippable-model`/`item-model`/`custom-model-data`, режимы `KEEP_REAL`/`KEEP_EMPTY`/`FALLBACK_MODEL`/`USE_SELF`). Мгновенно перестраивается при смене реальной брони и полностью совместима с `combo-overrides.yml` и `hair-3d.yml`!
- **⚡ Триггеры `~onEquip` / `~onUnequip` в меню гардероба:** стандартные `Skills:` предметов MythicMobs / MythicCrucible (`~onEquip`, `~onUnequip`, `~onUnEquip`, `~onEquipArmor`, `~onUnequipArmor`, условия `?condition`, таргетеры `@self` и т.д.) автоматически срабатывают при надевании, снятии, замене, авто-снятии блокировкой (`UNEQUIP`) и смерти в кастомных слотах меню (и на реальной броне в меню, если не стоит MythicCrucible — без двойных срабатываний!).
- **💇 3D-Причёски (`hair-3d.yml`):** автопривязка 3D-волос к 2D-волосам (`bindings:`) + отключение под шлемами по списку (`ALL` / `WHITELIST` / `BLACKLIST` + `exceptions`) ИЛИ простая замена на другую 3D-модель (`helmet-mode: SWAP_MODEL`, `helmet-model: "..."`) / частичное скрытие хвоста (`PARTIAL_MODEL`).
- **🔒 Система блокировок слоёв и слотов (`Blocks` / `BlockMode`):** цельные костюмы (например `WardrobeHazmatSuit` в слоте `jacket`) могут автоматически снимать (`UNEQUIP`), запрещать (`PREVENT`) или скрывать (`HIDE`) конфликтующие слои/слоты.
- **🪟 Полностью настраиваемые GUI (`menus.yml`):** режимы `COMPACT` (1-рядные на каждый слот), `TABBED`, `UNIFIED`, кастомные иконки пустых слотов (`empty-slots` с `CustomModelData` и `ItemModel`), кнопка `BACK` (возвращает назад / открывает «Инвентарь и Экипировку» без закрытия инвентаря), кнопка `CLOSE` (полное закрытие), и защита от закрытия GUI при смене скина.
- **🧩 Комбинирование 3D-моделей (0 лага):** самостоятельное ношение всех аксессуаров (маска без шлема, рюкзак без нагрудника, пояс без поножей), `combo-overrides.yml` для тела/ног и `/wardrobe buildheadpack` (`minecraft:composite`) для безлимитного наложения 3D-моделей на голову.
- **📦 Готовые сборки:** `releases/MythicWardrobe-1.10.0.jar`, `releases/MythicWardrobe_MythicArmors_TestPack.zip` (17 `.bbmodel` + конфиги), `releases/MythicWardrobeStudio.html` (оффлайн-версия сайта v2.5 с полным осмотром 2D + 3D).

---

## Возможности

### 1. Конструктор слоёв скина (`layers.yml`) + `body_skin` + `bodytype (slim / wide)`
- Вы сами управляете всеми слоями в файле `plugins/MythicWardrobe/layers.yml` — достаточно дописать новую секцию и выполнить `/wardrobe reload`:
  ```yaml
  layers:
    body_skin:
      priority: 0
      folder: "body_skin"
      replaces-base-skin: true
      aliases: ["body", "bodyskin", "skin_color"]

    eyes:
      priority: 5
      folder: "eyes"

    tattoos:           # ← ваш новый слой!
      priority: 13
      folder: "tattoos"
  ```
- После `/wardrobe reload` плагин **автоматически создаёт** папку `plugins/MythicWardrobe/overlays/<folder>/` и сразу добавляет новый слой в `type` (в Tab-дополнении `/wardrobe set`, `/wardrobe clear` и в скиллах MythicMobs!).
- **`body_skin` (`replaces-base-skin: true`):** полностью заменяет базовый скин игрока на вашу заготовку цвета кожи/тела (приоритет `0`), а все остальные слои (`eyes`, `eyebrows`, `hair`, `beard`, `scars`, `pants`, `undershirt`, `socks`, `jacket`, `boots_skin`, `gloves`, `mask_skin`, `hat` и ваши кастомные слои) рисуются поверх неё в строгом порядке `priority`.
- **`bodytype` (`slim` / `wide`):** смена ширины рук игрока — узкие 3px (`slim`) или широкие 4px (`wide`). Сохраняется в SQLite и автоматически подбирает нужные PNG из подпапок `wide/` и `slim/`:
  ```text
  plugins/MythicWardrobe/overlays/body_skin/
  ├── wide/demo_light.png   # под широкие руки (4px)
  └── slim/demo_light.png   # под узкие руки (3px)
  ```
  В командах и скиллах достаточно указать `texture=demo_light.png` — плагин сам выберет `wide/` или `slim/` под текущий `bodytype` игрока!
- **MineSkin API v2** (`/v2/queue`) + постоянный **SQLite SHA-256 кэш** (`wardrobe.db`, ключ учитывает `slim`/`wide`) + **debounce** при закрытии GUI.
- Автоматическая пересборка слоёв поверх нового скина, если игрок прописал `/skin <ник>` в SkinsRestorer.

### 2. Настраиваемые переключаемые GUI-меню (`menus.yml`) + Косметическая броня
- **3 готовых режима меню** (переключаются в `menus.yml`, кнопкой прямо внутри GUI или командой `/wardrobe mode <compact|tabbed|unified|default>`, сохраняются в SQLite):
  1. **`COMPACT`** *(по умолчанию)* — отдельные компактные меню в **1 строчку (9 слотов)** для каждого слота брони: **Шлем**, **Нагрудник**, **Поножи**, **Ботинки** + 1-строчный хаб по `/wardrobe`.
  2. **`TABBED`** — одно меню с переключением вкладок между Шлемом / Нагрудником / Поножами / Ботинками.
  3. **`UNIFIED`** — одно большое меню со **всеми 4 разделами и всеми 16 слотами сразу** в одном окне.
- **Полная визуальная кастомизация каждого меню в `plugins/MythicWardrobe/menus.yml`:**
  - `title` (MiniMessage, hex, `&`-коды, шрифты ресурспака), `rows` (`1`..`6`), `filler` (фон с поддержкой `custom-model-data`, `item-model`, `hide-tooltip`), `slot-positions` (точные номера ячеек), `buttons` (любые кнопки и декор с `material`, `name`, `lore`, `custom-model-data`, `item-model`, `glint`, `color`, `active`).
- Клик по слоту **Шлема** → `Маска (Скин)` | `Маска (3D)` | `Шлем (Статы)` | `Шлем (Косметика)`
- Клик по слоту **Нагрудника** → `Футболка/Майка (Скин)` | `Куртка/Кофта (Скин)` | `Бронежилет (Статы)` | `Нагрудник (Косметика)` | `Рюкзак (3D)`
- Клик по слоту **Поножей** → `Штаны (Скин)` | `Поножи (Статы)` | `Поножи (Косметика)`
- Клик по слоту **Ботинок** → `Носки (Скин)` | `Ботинки (Скин)` | `Пояс (Статы / 3D MythicArmors)` | `Пояс (Косметика)`
- Реальная броня хранится напрямую в `PlayerInventory` (без дюпов, 100% нативные статы, чары, атрибуты и `~onEquip`).

### 3. Интеграция с MythicArmors и одновременные 3D-слоты
- Любые 3D-предметы **MythicArmors** (с компонентом `Equippable`) и ванильная броня работают в слотах «Статы» и «Косметика».
- Через **PacketEvents** подменяются `ENTITY_EQUIPMENT` (для других игроков) и `SET_SLOT`/`WINDOW_ITEMS` (для F5-вида у самого игрока, с автозащитой в Creative). Без PacketEvents работает fallback через Paper `sendEquipmentChange`.
- **`combo-overrides.yml`**: при одновременном ношении Шлема + 3D-маски можно подставлять совмещённую 3D-модель MythicArmors и автоматически скрывать `ItemDisplay` маски.
- **`DISPLAY_3D`**: одновременный рендер **Рюкзака** поверх Бронежилета и **3D-маски** поверх Шлема через плавные несохраняемые `ItemDisplay`-сущности.

### 4. Интеграция с MythicMobs 5.13.0+
- **Механики `wardrobe` (`~onEquip`) и `unwardrobe` (`~onUnequip`):**
  ```yaml
  Skills:
    - wardrobe{type=body_skin;texture=demo_light.png;bodytype=slim} @self
    - wardrobe{bodytype=wide} @self
    - wardrobe{type=jacket;texture=jackets/demo_jacket.png} @self ~onEquip
    # Снимет слой ТОЛЬКО если надета именно эта куртка (защита от гонки при быстрой замене вещей!):
    - unwardrobe{type=jacket;texture=jackets/demo_jacket.png} @self ~onUnequip
    # Также можно снимать несколько слоёв сразу, все слои или сбрасывать bodytype:
    # - unwardrobe{type=jacket,undershirt,gloves} @self ~onUnequip
    # - unwardrobe{type=all;resetbodytype=true} @self ~onUnequip
  ```
  Алиасы `wardrobe`: `skinlayer`, `wardrobeskin`. Алиасы `unwardrobe`: `removewardrobe`, `clearwardrobe`, `unwear`, `unskinlayer`.
- **Условия `haswardrobelayer` и `hasbodytype`:**
  ```yaml
  Conditions:
    - hasbodytype{type=slim} true
    - haswardrobelayer{type=body_skin;texture=demo_light.png} true
    - haswardrobelayer{type=mask_3d} true
  ```
- Прямое чтение секции `Wardrobe:` из YAML-файлов предметов MythicMobs (см. `examples/MythicMobs_Clothes_Example.yml`).

### 5. Стартовый набор тестовых скинов и оверлеев (все 14 категорий, 61 PNG 64×64)
При старте (при `skin.create-example-overlays: true`) автоматически заполняются **все пустые категории** в `plugins/MythicWardrobe/overlays/` (уже существующие ваши файлы не перезаписываются; дубликат также лежит в `MythicWardrobe/sample-overlays/`):
- `body_skin/(wide|slim)/` — 5 оттенков кожи (`light`, `tan`, `olive`, `dark`, `pale_elf`) отдельно под 4px и 3px руки (10 PNG)
- `eyes/` — 5 цветов глаз (`blue`, `green`, `brown`, `amber`, `crimson`)
- `eyebrows/` — 2 варианта бровей (`dark_thick`, `light_arched`)
- `hairs/` — 5 причёсок (`brown_short`, `black_messy`, `blonde_swept`, `red_bob`, `silver_long`)
- `beards/` — 2 варианта бороды (`full_beard`, `goatee_dark`)
- `scars/` — 2 варианта шрамов/раскраски (`eye_scar`, `warpaint_blue`)
- `undershirts/(wide|slim)/` — 4 футболки/майки (`white_tshirt`, `black_tshirt`, `striped_navy`, `olive_tank`, 8 PNG)
- `jackets/(wide|slim)/` — 4 куртки/худи (`leather_brown`, `black_hoodie`, `military_parka`, `red_bomber`, 8 PNG)
- `pants/` — 4 вида штанов (`blue_jeans`, `cargo_olive`, `tactical_black`, `grey_joggers`)
- `socks/` — 3 вида носков (`white_socks`, `black_socks`, `striped_socks`)
- `boots/` — 3 пары обуви (`combat_boots`, `red_sneakers`, `leather_boots`)
- `gloves/(wide|slim)/` — 2 вида перчаток (`leather_fingerless`, `tactical_black`, 4 PNG)
- `masks/` — 3 маски (`bandana_red`, `balaclava_black`, `respirator`)
- `hats/` — 2 шапки (`beanie_grey`, `ushanka_brown`)

### 6. Ускорение загрузки скинов (Оптимизации v1.2.1)
- **Двухуровневый кэш (L1 RAM + L2 SQLite):** повторные образы применяются мгновенно (`< 1 мс`, 0 запросов к MineSkin).
- **Параллельный пул MineSkin v2 (`max-concurrency: 4`) + HTTP/2 + адаптивный быстрый поллинг (`350 → 550 → 800 мс`):** ускоряет генерацию новых скинов и не ставит других игроков в общую однопоточную очередь.
- **Дедупликация по SHA-256 + защита от устаревших ответов:** одинаковые одновременные запросы делят один вызов MineSkin; если игрок быстро сменил образ, старый ответ кэшируется без «мигания».
- **Прогрев базовых скинов (`/wardrobe prewarm`):** фоново подписывает все `body_skin` (`wide` + `slim`) в кэш заранее.
- **Кастомный `mineskin.base-url`:** позволяет подключить локальный прокси-подписчик на базе собственных аккаунтов Mojang.

### 7. Временный 3D веб-редактор для тестирования (`/wardrobe web`)
- Встроенный HTTP-сервер на стандартном JDK 21 (`com.sun.net.httpserver.HttpServer`, без сторонних библиотек и CDN).
- Игрок вводит `/wardrobe web`, получает одноразовую ссылку в чат → открывает в браузере живой **3D WebGL-превью** своего персонажа слева (`Wide 4px` / `Slim 3px`, вращение, анимация ходьбы, 2-й слой, скачивание PNG) и **прокручиваемый HUD всех категорий из `layers.yml`** справа.
- Подбор слоёв в браузере происходит **локально на Canvas (0 запросов в MineSkin)**; при нажатии **«Применить в игре»** все слои и `bodytype` отправляются в плагин **одним пакетом** → 1 запрос в MineSkin + применение через SkinsRestorer.
- **Лёгкое удаление потом:** вся логика изолирована в `dev.mythicwardrobe.web.WebEditorServer` и `resources/web/editor.html`. Когда сделаете внутриигровое меню, достаточно поставить `web-editor.enabled: false` в `config.yml` (или удалить пакет `dev.mythicwardrobe.web`).

---

## Установка

1. Поместите `releases/MythicWardrobe-1.2.1.jar` в папку `plugins/` сервера **Paper / Purpur 1.21.8 – 1.21.11** (Java 21).
2. Рекомендуемые плагины в `plugins/`:
   - **SkinsRestorer** v15.x
   - **MythicMobs** 5.13.0+ (+ **MythicArmors** для 3D-брони)
   - **PacketEvents** 2.x (для полной пакетной подмены косметической брони и F5-превью)
3. В `plugins/MythicWardrobe/config.yml` впишите свой бесплатный **MineSkin API-ключ** (`https://account.mineskin.org/keys`) и (при желании использовать веб-редактор не локально) укажите внешний IP/домен в `web-editor.public-url`.
4. Настройте слои в `plugins/MythicWardrobe/layers.yml`, разложите `64×64` PNG по папкам в `overlays/` и выполните `/wardrobe reload`.

---

## Команды и права

| Команда | Описание | Право |
|---|---|---|
| `/wardrobe` (`/wd`, `/clothes`) | Открыть главное меню гардероба | `mythicwardrobe.use` |
| `/wardrobe open <head\|chest\|legs\|feet> [игрок]` | Открыть конкретный раздел | `mythicwardrobe.use` / `admin` |
| `/wardrobe web [игрок]` (`/wardrobe editor`) | Получить ссылку на временный 3D веб-редактор | `mythicwardrobe.web` / `admin` |
| `/wardrobe bodytype [игрок] <slim\|wide\|auto>` | Сменить ширину рук (`slim` 3px / `wide` 4px / `auto`) | `mythicwardrobe.use` / `admin` |
| `/wardrobe set <игрок> <тип> <файл.png>` | Наложить любой слой из `layers.yml` (`body_skin`, `eyes`, `jacket` и др.) | `mythicwardrobe.admin` |
| `/wardrobe clear <игрок> <тип\|all>` | Снять слой скина | `mythicwardrobe.admin` |
| `/wardrobe reset [игрок]` | Сбросить все слои скиллов/команд | `mythicwardrobe.admin` |
| `/wardrobe layers` | Показать весь стек слоёв из `layers.yml` в Z-порядке | `mythicwardrobe.admin` |
| `/wardrobe tag <slotId> [файл.png]` | Привязать предмет в руке к GUI-слоту | `mythicwardrobe.admin` |
| `/wardrobe list` | Показать все загруженные PNG в `overlays/` | `mythicwardrobe.admin` |
| `/wardrobe prewarm` | Прогреть кэш MineSkin для всех базовых `body_skin` (`wide`+`slim`) | `mythicwardrobe.admin` |
| `/wardrobe reload` | Перезагрузить конфиги, создать новые папки и пересканировать PNG | `mythicwardrobe.admin` |
| `/wardrobe debug [игрок]` | Диагностика слоёв, `bodytype`, кэша и 3D-дисплеев | `mythicwardrobe.admin` |

---

## Сборка из исходников

```bash
./gradlew clean test jar
```
Готовый файл появится в `build/libs/MythicWardrobe-1.2.1.jar`.
