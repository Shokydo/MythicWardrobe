#!/usr/bin/env python3
"""
Generates a complete, ready-to-drop-in server example for MythicWardrobe v1.10.0 Multimodal Cosmetics:
  - 4 distinct MythicArmors .bbmodel 3D models (valid canonical bones, main + _e + _layer_1 + _layer_2 + icon):
      1) light_military_vest.bbmodel        (Olive-drab slim JPC plate carrier with radio & 3 mag pouches)
      2) light_military_vest_white.bbmodel  (Same slim JPC geometry in arctic white camo)
      3) heavy_military_vest.bbmodel        (Bulky dark-olive juggernaut vest: collar, heavy pauldrons, groin guard)
      4) heavy_military_vest_white.bbmodel  (Same bulky juggernaut geometry in arctic white camo)
  - plugins/MythicMobs/Items/MilitaryWhitePaint_Example.yml (LightMilitaryVest, HeavyMilitaryVest,
      LightMilitaryVest_White, HeavyMilitaryVest_White, and the SINGLE cosmetic item WhiteVestPaint)
  - plugins/MythicWardrobe/cosmetic-variants.yml (ready-to-use config)
  - README_EXAMPLE.txt (Russian quickstart & test commands)
  - Packages everything cleanly into MythicWardrobe/releases/MilitaryWhitePaint_Example.zip
  - Renders a side-by-side 3D comparison preview PNG to inspect silhouettes & colors!
"""
import math
import os
import sys
import json
import zipfile
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_mythicarmors_pack import (
    BBModelBuilder,
    paint_armor_atlas,
    paint_fallback_layers,
    paint_icons,
    render_model_preview,
    UV_PLATE,
    UV_WEAVE,
    UV_TRIM,
    UV_GLOW,
    UV_POUCH,
    UV_MECH,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXAMPLE_DIR = os.path.join(ROOT, "multimodal-example-pack")
ZIP_PATH = os.path.join(ROOT, "releases", "MilitaryWhitePaint_Example.zip")
PREVIEW_PNG = os.path.join(EXAMPLE_DIR, "_multimodal_preview.png")


def build_light_military_vest(model_id: str, primary, dark, trim, glow, pouch, boot):
    """Compact JPC plate carrier: slim torso plates, 3 mag pouches, shoulder straps, radio + antenna, light arm patches."""
    main_tex, emis_tex = paint_armor_atlas(primary, dark, trim, glow, pouch, boot)
    l1, _ = paint_fallback_layers(primary, dark, trim, glow)
    icons = paint_icons(primary, dark, trim, glow)

    b = BBModelBuilder(model_id, model_id)
    b.add_texture(f"{model_id}_main.png", main_tex, 64, 64)
    b.add_texture(f"{model_id}_main_e.png", emis_tex, 64, 64)
    b.add_texture(f"{model_id}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_id}_chestplate.png", icons["chestplate"], 16, 16)

    # Canonical CHEST bones
    body = b.add_bone("body", (0, 24, 0))
    r_arm = b.add_bone("right_arm", (5, 22, 0))
    l_arm = b.add_bone("left_arm", (-5, 22, 0))

    # Slim ballistic inner carrier (stays tight to torso, leaves sides ventilated!)
    b.add_cube(body, "carrier_front_plate", (-3.4, 14.2, -3.05), (3.4, 22.8, -2.0), UV_PLATE)
    b.add_cube(body, "carrier_back_plate",  (-3.4, 14.5,  2.0),  (3.4, 22.6,  3.05), UV_PLATE)
    # Thin elastic cummerbund side straps (lightweight look)
    b.add_cube(body, "cummerbund_right", ( 3.4, 14.6, -2.55), ( 4.45, 18.2, 2.55), UV_WEAVE)
    b.add_cube(body, "cummerbund_left",  (-4.45, 14.6, -2.55), (-3.4, 18.2, 2.55), UV_WEAVE)
    # Shoulder straps (slim over-shoulder webbing)
    b.add_cube(body, "strap_right_top", ( 1.8, 22.8, -2.7), ( 3.4, 24.45, 2.7), UV_TRIM)
    b.add_cube(body, "strap_left_top",  (-3.4, 22.8, -2.7), (-1.8, 24.45, 2.7), UV_TRIM)
    # Upper Velcro chest admin panel + status LED
    b.add_cube(body, "admin_velcro", (-2.6, 19.6, -3.35), (2.6, 21.8, -2.95), UV_WEAVE)
    b.add_cube(body, "chest_beacon", (-0.6, 20.3, -3.55), (0.6, 21.1, -3.25), UV_GLOW)
    # 3 slim front rifle magazine pouches
    b.add_cube(body, "mag_pouch_r", ( 1.25, 14.5, -3.95), ( 3.15, 18.4, -2.95), UV_POUCH)
    b.add_cube(body, "mag_pouch_c", (-0.95, 14.5, -3.95), ( 0.95, 18.4, -2.95), UV_POUCH)
    b.add_cube(body, "mag_pouch_l", (-3.15, 14.5, -3.95), (-1.25, 18.4, -2.95), UV_POUCH)
    # Compact shoulder tactical radio + whip antenna on right upper chest/back
    b.add_cube(body, "radio_body",    ( 2.35, 19.2, 2.85), ( 3.95, 22.4, 4.15), UV_MECH)
    b.add_cube(body, "radio_antenna", ( 3.15, 22.4, 3.25), ( 3.65, 27.2, 3.75), UV_TRIM)
    # Rear hydration flat pack
    b.add_cube(body, "rear_hydro", (-2.3, 15.6, 2.95), (2.3, 21.4, 3.85), UV_POUCH)

    # Light shoulder patches / elbow bands (no bulky pauldrons!)
    b.add_cube(r_arm, "r_arm_patch", ( 7.7, 19.4, -1.6), ( 8.55, 21.8, 1.6), UV_TRIM)
    b.add_cube(r_arm, "r_wrist_band",( 3.7, 13.8, -2.35), ( 8.3, 15.1, 2.35), UV_WEAVE)
    b.add_cube(l_arm, "l_arm_patch", (-8.55, 19.4, -1.6), (-7.7, 21.8, 1.6), UV_TRIM)
    b.add_cube(l_arm, "l_wrist_band",(-8.3, 13.8, -2.35), (-3.7, 15.1, 2.35), UV_WEAVE)

    return b.to_dict(), main_tex


def build_heavy_military_vest(model_id: str, primary, dark, trim, glow, pouch, boot):
    """Heavy Juggernaut Assault Vest: high armored blast collar, thick multi-layer chest plates,
    groin/lower-back armor flaps, double pouches, and massive segmented pauldrons on arms!"""
    main_tex, emis_tex = paint_armor_atlas(primary, dark, trim, glow, pouch, boot)
    l1, _ = paint_fallback_layers(primary, dark, trim, glow)
    icons = paint_icons(primary, dark, trim, glow)

    b = BBModelBuilder(model_id, model_id)
    b.add_texture(f"{model_id}_main.png", main_tex, 64, 64)
    b.add_texture(f"{model_id}_main_e.png", emis_tex, 64, 64)
    b.add_texture(f"{model_id}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_id}_chestplate.png", icons["chestplate"], 16, 16)

    body = b.add_bone("body", (0, 24, 0))
    r_arm = b.add_bone("right_arm", (5, 22, 0))
    l_arm = b.add_bone("left_arm", (-5, 22, 0))

    # Full wrap-around armored torso core (heavier & thicker than light vest!)
    b.add_cube(body, "heavy_core",        (-4.65, 12.2, -3.15), ( 4.65, 23.8,  3.15), UV_WEAVE)
    # Thick angled front & back ceramic trauma plates
    b.add_cube(body, "front_heavy_plate", (-4.25, 13.6, -4.15), ( 4.25, 22.9, -2.85), UV_PLATE)
    b.add_cube(body, "front_upper_bevel", (-3.75, 21.2, -4.45), ( 3.75, 23.3, -3.1),  UV_TRIM)
    b.add_cube(body, "back_heavy_plate",  (-4.25, 13.4,  2.85), ( 4.25, 23.0,  4.25), UV_PLATE)
    b.add_cube(body, "back_spine_rig",    (-1.6,  14.2,  4.05), ( 1.6,  22.6,  4.95), UV_MECH)
    # Glowing chest tactical core sensor
    b.add_cube(body, "chest_sensor_box",  (-1.35, 19.6, -4.55), ( 1.35, 21.0, -3.95), UV_MECH)
    b.add_cube(body, "chest_sensor_led",  (-0.85, 19.95,-4.72), ( 0.85, 20.65,-4.42), UV_GLOW)
    # High wrap-around juggernaut blast collar!
    b.add_cube(body, "collar_back",  (-4.4, 23.5,  2.45), ( 4.4, 26.5,  4.15), UV_PLATE)
    b.add_cube(body, "collar_right", ( 3.1, 23.5, -3.55), ( 4.65, 26.2,  3.35), UV_PLATE)
    b.add_cube(body, "collar_left",  (-4.65, 23.5,-3.55), (-3.1, 26.2,  3.35), UV_PLATE)
    b.add_cube(body, "collar_front", (-3.4, 23.2, -4.05), ( 3.4, 25.1, -2.75), UV_TRIM)
    # Heavy side armor plates
    b.add_cube(body, "side_plate_r", ( 4.2, 13.6, -3.25), ( 5.15, 21.6, 3.25), UV_PLATE)
    b.add_cube(body, "side_plate_l", (-5.15,13.6, -3.25), (-4.2,  21.6, 3.25), UV_PLATE)
    # Double-row heavy assault pouches
    b.add_cube(body, "heavy_pouch_r", ( 1.35, 14.0, -5.05), ( 3.95, 18.6, -3.95), UV_POUCH)
    b.add_cube(body, "heavy_pouch_l", (-3.95, 14.0, -5.05), (-1.35, 18.6, -3.95), UV_POUCH)
    b.add_cube(body, "heavy_pouch_c", (-1.25, 14.2, -4.95), ( 1.25, 18.4, -3.95), UV_POUCH)
    # Hanging ballistic groin & lower-back flaps!
    b.add_cube(body, "groin_flap_front", (-3.0, 9.6, -4.05), ( 3.0, 13.8, -2.95), UV_PLATE)
    b.add_cube(body, "groin_trim_front", (-2.6, 9.4, -4.2),  ( 2.6, 10.4, -3.1),  UV_TRIM)
    b.add_cube(body, "rear_skirt_plate", (-3.2, 10.2, 2.95), ( 3.2, 13.8,  4.05), UV_PLATE)

    # MASSIVE segmented armored pauldrons + heavy forearm gauntlets on right_arm & left_arm!
    b.add_cube(r_arm, "r_pauldron_top",   ( 3.6, 21.4, -3.15), ( 9.55, 25.0,  3.15), UV_PLATE)
    b.add_cube(r_arm, "r_pauldron_ridge", ( 4.2, 24.4, -2.85), ( 9.0,  25.65, 2.85), UV_TRIM)
    b.add_cube(r_arm, "r_pauldron_lower", ( 4.0, 18.2, -2.95), ( 9.25, 21.6,  2.95), UV_PLATE)
    b.add_cube(r_arm, "r_pauldron_led",   ( 9.2, 21.8, -0.7),  ( 9.7,  23.2,  0.7),  UV_GLOW)
    b.add_cube(r_arm, "r_gauntlet",       ( 3.6, 12.6, -2.65), ( 8.55, 17.6,  2.65), UV_WEAVE)
    b.add_cube(r_arm, "r_gauntlet_plate", ( 7.8, 13.0, -2.25), ( 8.9,  17.2,  2.25), UV_TRIM)

    b.add_cube(l_arm, "l_pauldron_top",   (-9.55, 21.4, -3.15), (-3.6, 25.0,  3.15), UV_PLATE)
    b.add_cube(l_arm, "l_pauldron_ridge", (-9.0,  24.4, -2.85), (-4.2, 25.65, 2.85), UV_TRIM)
    b.add_cube(l_arm, "l_pauldron_lower", (-9.25, 18.2, -2.95), (-4.0, 21.6,  2.95), UV_PLATE)
    b.add_cube(l_arm, "l_pauldron_led",   (-9.7,  21.8, -0.7),  (-9.2, 23.2,  0.7),  UV_GLOW)
    b.add_cube(l_arm, "l_gauntlet",       (-8.55, 12.6, -2.65), (-3.6, 17.6,  2.65), UV_WEAVE)
    b.add_cube(l_arm, "l_gauntlet_plate", (-8.9,  13.0, -2.25), (-7.8, 17.2,  2.25), UV_TRIM)

    return b.to_dict(), main_tex


MYTHICMOBS_EXAMPLE_YML = """# ==============================================================================
# MythicWardrobe v1.10.0 — Готовый пример МУЛЬТИМОДАЛЬНОЙ КОСМЕТИКИ
# ==============================================================================
# Сценарий:
#   • Есть 2 разные реальные брони:
#       1) LightMilitaryVest  — Лёгкий военный бронежилет (компактный плитник)
#       2) HeavyMilitaryVest  — Тяжёлый военный бронежилет (массивный джаггернаут)
#   • Есть ОДИН косметический предмет:
#       3) WhiteVestPaint     — Белая краска для бронежилета (WHITE_DYE!)
#   • Игрок кладёт ОДНУ краску WhiteVestPaint в слот косметики (`chest_cosmetic`):
#       - Если надет LightMilitaryVest -> отображается LightMilitaryVest_White!
#       - Если надет HeavyMilitaryVest -> отображается HeavyMilitaryVest_White!
#       - Если броня снята             -> ничего лишнего не надевается!
#
# Положите этот файл в: plugins/MythicMobs/Items/MilitaryWhitePaint_Example.yml
# 4 файла .bbmodel в:   plugins/MythicArmors/models/
# cosmetic-variants.yml в: plugins/MythicWardrobe/cosmetic-variants.yml
# Затем выполните: /ma reload -> /mm reload -> /wardrobe reload
# ==============================================================================

# 1. РЕАЛЬНАЯ БРОНЯ #1 — ЛЁГКИЙ ВОЕННЫЙ БРОНЕЖИЛЕТ
LightMilitaryVest:
  Material: LEATHER_CHESTPLATE
  Display: '&aЛёгкий военный бронежилет &7[Класс III]'
  Lore:
    - '&7Компактный тактический плитник с рацией'
    - '&8Защита: &f+6 брони &8· &aЛёгкий и манёвренный'
    - '&fСовместим с «Белой краской для бронежилета»!'
  Equippable:
    Model: mythicarmor:light_military_vest_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_iron'
  Options:
    ItemModel: mythicarmor:light_military_vest_chest_piece
    Unbreakable: true
    HideFlags: true
  Attributes:
    Chest:
      Armor: 6
      ArmorToughness: 1
      MovementSpeed: 0.02

# 2. РЕАЛЬНАЯ БРОНЯ #2 — ТЯЖЁЛЫЙ ВОЕННЫЙ БРОНЕЖИЛЕТ
HeavyMilitaryVest:
  Material: LEATHER_CHESTPLATE
  Display: '&6Тяжёлый военный бронежилет &7[Класс VI]'
  Lore:
    - '&7Штурмовой бронежилет с воротником и наплечниками'
    - '&8Защита: &f+10 брони, +4 прочности, +20% стойкости'
    - '&fСовместим с «Белой краской для бронежилета»!'
  Equippable:
    Model: mythicarmor:heavy_military_vest_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_netherite'
  Options:
    ItemModel: mythicarmor:heavy_military_vest_chest_piece
    Unbreakable: true
    HideFlags: true
  Attributes:
    Chest:
      Armor: 10
      ArmorToughness: 4
      KnockbackResistance: 0.2

# 3. ОДИН КОСМЕТИЧЕСКИЙ ПРЕДМЕТ — БЕЛАЯ КРАСКА ДЛЯ БРОНЕЖИЛЕТА
#    Обратите внимание: это обычный WHITE_DYE (НЕ броня!), который свободно
#    кладётся в слот `chest_cosmetic` и меняет модель под надетый бронежилет!
WhiteVestPaint:
  Material: WHITE_DYE
  Display: '&f&lБелая краска для бронежилета &7[Мульти-скин]'
  Lore:
    - '&7Универсальный арктический камуфляж (1 предмет на всё!)'
    - '&8Положите в слот &fКосметики Нагрудника &8(или ПКМ в руке):'
    - '&f• На Лёгком бронежилете  &7→ &fБелый лёгкий плитник'
    - '&f• На Тяжёлом бронежилете &7→ &fБелый тяжёлый джаггернаут'
    - '&f• На Кожаной броне       &7→ &fПерекраска в белый'
    - '&8Если жилет снят — краска не отображается.'
  Options:
    HideFlags: true
  Wardrobe:
    Slot: chest_cosmetic
    UnmatchedMode: KEEP_REAL
    EmptyArmorMode: KEEP_EMPTY
    Variants:
      LightMilitaryVest: LightMilitaryVest_White
      HeavyMilitaryVest: HeavyMilitaryVest_White

# 4. ВИЗУАЛЬНАЯ МОДЕЛЬ #1 — ЛЁГКИЙ ВОЕННЫЙ БРОНЕЖИЛЕТ (БЕЛЫЙ)
LightMilitaryVest_White:
  Material: LEATHER_CHESTPLATE
  Display: '&fЛёгкий военный бронежилет &b[Арктика]'
  Lore:
    - '&73D-вариант для мультимодальной косметики WhiteVestPaint'
  Equippable:
    Model: mythicarmor:light_military_vest_white_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_iron'
  Options:
    ItemModel: mythicarmor:light_military_vest_white_chest_piece
    Unbreakable: true
    HideFlags: true

# 5. ВИЗУАЛЬНАЯ МОДЕЛЬ #2 — ТЯЖЁЛЫЙ ВОЕННЫЙ БРОНЕЖИЛЕТ (БЕЛЫЙ)
HeavyMilitaryVest_White:
  Material: LEATHER_CHESTPLATE
  Display: '&fТяжёлый военный бронежилет &b[Арктика]'
  Lore:
    - '&73D-вариант для мультимодальной косметики WhiteVestPaint'
  Equippable:
    Model: mythicarmor:heavy_military_vest_white_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_netherite'
  Options:
    ItemModel: mythicarmor:heavy_military_vest_white_chest_piece
    Unbreakable: true
    HideFlags: true
"""

COSMETIC_VARIANTS_EXAMPLE_YML = """# ==============================================================================
# MythicWardrobe v1.10.0 — Пример cosmetic-variants.yml (Белая краска на жилеты)
# Положите в: plugins/MythicWardrobe/cosmetic-variants.yml
# ==============================================================================
enabled: true

skins:
  white_vest_paint:
    cosmetic-item: "WhiteVestPaint"
    allowed-slots:
      - "chest_cosmetic"
    unmatched-mode: "KEEP_REAL"
    empty-armor-mode: "KEEP_EMPTY"
    variants:
      light_military_vest:
        match-armor:
          - "LightMilitaryVest"
        result-mythic-item: "LightMilitaryVest_White"

      heavy_military_vest:
        match-armor:
          - "HeavyMilitaryVest"
        result-mythic-item: "HeavyMilitaryVest_White"

      # Бонус: на ванильной кожаной броне просто перекрашивает в арктический белый!
      vanilla_leather_chest:
        match-materials:
          - "LEATHER_CHESTPLATE"
        dye-color: "#F2F5FA"
"""

README_EXAMPLE = """================================================================================
MythicWardrobe v1.10.0 — Готовый пример мультимодальной косметики
«Белая краска для бронежилета» (1 предмет -> разные 3D-модели!)
================================================================================

ЧТО ВНУТРИ АРХИВА:
  1. plugins/MythicArmors/models/
       • light_military_vest.bbmodel        — Лёгкий военный бронежилет (оливковый)
       • light_military_vest_white.bbmodel  — Лёгкий военный бронежилет (белый)
       • heavy_military_vest.bbmodel        — Тяжёлый военный бронежилет (графит/олива)
       • heavy_military_vest_white.bbmodel  — Тяжёлый военный бронежилет (белый)
  2. plugins/MythicMobs/Items/MilitaryWhitePaint_Example.yml
       • LightMilitaryVest        (реальная броня №1)
       • HeavyMilitaryVest        (реальная броня №2)
       • WhiteVestPaint           (ОДИН косметический предмет — Белая краска!)
       • LightMilitaryVest_White  (3D-вариант №1)
       • HeavyMilitaryVest_White  (3D-вариант №2)
  3. plugins/MythicWardrobe/cosmetic-variants.yml
       • Привязка 1 предмета WhiteVestPaint ко всем вариантам брони

УСТАНОВКА (3 ШАГА):
  1. Убедитесь, что стоит MythicWardrobe-1.10.0.jar (и MythicMobs + MythicArmors).
  2. Распакуйте папку `plugins/` из этого архива в корень сервера.
  3. Выполните в игре / консоли:
       /ma reload
       /mm reload
       /wardrobe reload
     (И обновите ресурспак MythicArmors, если не включён авто-отправщик ресурспака!)

КАК ПРОВЕРИТЬ В ИГРЕ (3 КОМАНДЫ):
  /mm i give @s LightMilitaryVest
  /mm i give @s HeavyMilitaryVest
  /mm i give @s WhiteVestPaint

  1) Откройте гардероб (/wardrobe) и положите `WhiteVestPaint` в слот «Косметика Нагрудника»
     (или просто нажмите ПКМ с краской в руке!).
     -> Пока реальный жилет не надет — ничего лишнего на персонаже не появляется!
  2) Наденьте `LightMilitaryVest` -> персонаж мгновенно в БЕЛОМ Лёгком плитнике!
  3) Смените реальный жилет на `HeavyMilitaryVest` (краску НЕ трогайте!) ->
     персонаж мгновенно в БЕЛОМ Тяжёлом штурмовом бронежилете с воротником и наплечниками!
  4) Снимите `WhiteVestPaint` -> вернётся обычный оливковый цвет текущего бронежилета!
"""


def main():
    os.makedirs(os.path.join(EXAMPLE_DIR, "plugins", "MythicArmors", "models"), exist_ok=True)
    os.makedirs(os.path.join(EXAMPLE_DIR, "plugins", "MythicMobs", "Items"), exist_ok=True)
    os.makedirs(os.path.join(EXAMPLE_DIR, "plugins", "MythicWardrobe"), exist_ok=True)

    # Palettes
    OLIVE_LIGHT = dict(
        primary=(88, 102, 72, 255),
        dark=(46, 52, 42, 255),
        trim=(122, 114, 86, 255),
        glow=(86, 238, 146, 255),
        pouch=(74, 86, 62, 255),
        boot=(42, 44, 40, 255),
    )
    WHITE_LIGHT = dict(
        primary=(232, 238, 244, 255),
        dark=(162, 174, 188, 255),
        trim=(204, 214, 224, 255),
        glow=(72, 212, 255, 255),
        pouch=(216, 224, 232, 255),
        boot=(136, 146, 158, 255),
    )
    OLIVE_HEAVY = dict(
        primary=(62, 72, 58, 255),
        dark=(34, 38, 36, 255),
        trim=(98, 102, 82, 255),
        glow=(255, 168, 48, 255),
        pouch=(54, 62, 50, 255),
        boot=(30, 32, 32, 255),
    )
    WHITE_HEAVY = dict(
        primary=(238, 243, 248, 255),
        dark=(122, 134, 148, 255),
        trim=(192, 204, 218, 255),
        glow=(64, 200, 255, 255),
        pouch=(220, 228, 236, 255),
        boot=(116, 126, 138, 255),
    )

    models = [
        ("light_military_vest",       *build_light_military_vest("light_military_vest", **OLIVE_LIGHT)),
        ("light_military_vest_white", *build_light_military_vest("light_military_vest_white", **WHITE_LIGHT)),
        ("heavy_military_vest",       *build_heavy_military_vest("heavy_military_vest", **OLIVE_HEAVY)),
        ("heavy_military_vest_white", *build_heavy_military_vest("heavy_military_vest_white", **WHITE_HEAVY)),
    ]

    models_dir = os.path.join(EXAMPLE_DIR, "plugins", "MythicArmors", "models")
    for mid, bb, _ in models:
        p = os.path.join(models_dir, f"{mid}.bbmodel")
        with open(p, "w", encoding="utf-8") as f:
            json.dump(bb, f, indent=2)
        print(f"Wrote {mid}.bbmodel ({len(bb['elements'])} cubes, {len(bb['textures'])} textures)")

    with open(os.path.join(EXAMPLE_DIR, "plugins", "MythicMobs", "Items", "MilitaryWhitePaint_Example.yml"), "w", encoding="utf-8") as f:
        f.write(MYTHICMOBS_EXAMPLE_YML)
    with open(os.path.join(EXAMPLE_DIR, "plugins", "MythicWardrobe", "cosmetic-variants.yml"), "w", encoding="utf-8") as f:
        f.write(COSMETIC_VARIANTS_EXAMPLE_YML)
    with open(os.path.join(EXAMPLE_DIR, "README_EXAMPLE.txt"), "w", encoding="utf-8") as f:
        f.write(README_EXAMPLE)

    # Render clean 4-panel visual comparison PNG!
    pw, ph = 290, 320
    canvas = Image.new("RGBA", (pw * 4 + 40, ph + 78), (15, 17, 22, 255))
    draw = ImageDraw.Draw(canvas)
    titles = [
        "1. LightMilitaryVest\n(Real Armor #1)",
        "1b. + WhiteVestPaint\n-> LightMilitaryVest_White",
        "2. HeavyMilitaryVest\n(Real Armor #2)",
        "2b. + WhiteVestPaint\n-> HeavyMilitaryVest_White",
    ]
    for i, ((mid, bb, tex), title) in enumerate(zip(models, titles)):
        shot = render_model_preview(bb, tex, canvas_size=(pw, ph), yaw_deg=32, pitch_deg=14, show_mannequin=True)
        x0 = 10 + i * (pw + 6)
        canvas.paste(shot, (x0, 10))
        draw.rectangle([x0, 10, x0 + pw - 1, 10 + ph - 1], outline=(55, 62, 76, 255), width=2)
        draw.text((x0 + 10, ph + 20), title, fill=(238, 242, 248, 255))
    # Visual separator between pair 1 and pair 2
    mid_x = 10 + 2 * (pw + 6) - 3
    draw.line([(mid_x, 10), (mid_x, ph + 66)], fill=(110, 120, 140, 255), width=2)
    canvas.save(PREVIEW_PNG)
    print(f"Wrote preview image: {PREVIEW_PNG}")

    # Build clean standalone example ZIP (delete old first so no duplicates)
    if os.path.exists(ZIP_PATH):
        os.remove(ZIP_PATH)
    with zipfile.ZipFile(ZIP_PATH, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for base, _, files in os.walk(EXAMPLE_DIR):
            for fn in sorted(files):
                if fn.startswith("_"):
                    continue
                full = os.path.join(base, fn)
                rel = os.path.relpath(full, EXAMPLE_DIR)
                zf.write(full, rel)
    print(f"Wrote ZIP: {ZIP_PATH} ({os.path.getsize(ZIP_PATH)/1024:.1f} KB)")

    # ALSO include all 4 models, MilitaryWhitePaint_Example.yml, and cosmetic-variants.yml
    # directly inside the main MythicWardrobe_MythicArmors_TestPack.zip so users who install
    # the main Test Pack immediately have LightMilitaryVest, HeavyMilitaryVest & WhiteVestPaint!
    test_pack_dir = os.path.join(ROOT, "mythicarmors-test-pack")
    test_pack_zip = os.path.join(ROOT, "releases", "MythicWardrobe_MythicArmors_TestPack.zip")
    tp_models = os.path.join(test_pack_dir, "plugins", "MythicArmors", "models")
    tp_items = os.path.join(test_pack_dir, "plugins", "MythicMobs", "Items")
    tp_mw = os.path.join(test_pack_dir, "plugins", "MythicWardrobe")
    os.makedirs(tp_models, exist_ok=True)
    os.makedirs(tp_items, exist_ok=True)
    os.makedirs(tp_mw, exist_ok=True)

    for mid, bb, _ in models:
        with open(os.path.join(tp_models, f"{mid}.bbmodel"), "w", encoding="utf-8") as f:
            json.dump(bb, f, indent=2)
    with open(os.path.join(tp_items, "MilitaryWhitePaint_Example.yml"), "w", encoding="utf-8") as f:
        f.write(MYTHICMOBS_EXAMPLE_YML)
    with open(os.path.join(tp_mw, "cosmetic-variants.yml"), "w", encoding="utf-8") as f:
        f.write(COSMETIC_VARIANTS_EXAMPLE_YML)

    if os.path.exists(test_pack_zip):
        os.remove(test_pack_zip)
    with zipfile.ZipFile(test_pack_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for base, _, files in os.walk(test_pack_dir):
            for fn in sorted(files):
                if fn.startswith("_"):
                    continue
                full = os.path.join(base, fn)
                rel = os.path.relpath(full, test_pack_dir)
                zf.write(full, rel)
    print(f"Updated Main TestPack ZIP: {test_pack_zip} ({os.path.getsize(test_pack_zip)/1024:.1f} KB)")


if __name__ == "__main__":
    main()
