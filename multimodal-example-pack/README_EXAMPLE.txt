================================================================================
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
