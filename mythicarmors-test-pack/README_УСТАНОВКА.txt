================================================================================
  MythicWardrobe v1.9.0 + MythicArmors — Готовый тестовый 3D-пак (v1.8)
================================================================================

ЧТО ВНУТРИ АРХИВА (17 готовых 3D-моделей .bbmodel):
 1. tactical_warden.bbmodel           — Боевая 3D-броня «Стража» (4 части)
 2. gold_warden_cosmetic.bbmodel      — Косметическая Золотая 3D-броня «Стража»
 3. tactical_gasmask.bbmodel          — Самостоятельный 3D-противогаз (носится БЕЗ шлема!)
 4. tactical_backpack.bbmodel         — Самостоятельный 3D-рюкзак (носится БЕЗ нагрудника!)
 5. tactical_warden_masked.bbmodel    — КОМБО: Тактический шлем + противогаз (0 лага!)
 6. gold_warden_masked.bbmodel        — КОМБО: Золотой шлем + противогаз (0 лага!)
 7. tactical_warden_backpack.bbmodel  — КОМБО: Тактический бронежилет + рюкзак (0 лага!)
 8. gold_warden_backpack.bbmodel      — КОМБО: Золотой бронежилет + рюкзак (0 лага!)
 9. hair_spiky_3d.bbmodel             — 3D Причёска «Взъерошенная» (перекрашиваемая _t.png!)
10. hair_ponytail_3d.bbmodel          — 3D Причёска «Хвост» с резинкой (перекрашиваемая!)
11. hair_ponytail_3d_under_helmet.bbmodel — ЧАСТИЧНЫЙ вариант под шлем (хвост скрыт, передние пряди видны!)
12. hair_bob_3d.bbmodel               — 3D Причёска «Каре» с заколкой (перекрашиваемая!)
13. belt_tactical_3d.bbmodel          — 3D Тактический пояс с подсумками и кобурой на бедро!
14. belt_adventurer_3d.bbmodel        — 3D Пояс искателя (сумка, светящаяся колба маны, свиток, кинжал!)
15. belt_chains_3d.bbmodel            — 3D Королевский кушак (_t.png перекрашиваемый) + золотые цепи + рубин!
16. tactical_warden_belt.bbmodel      — КОМБО: Тактические поножи + Тактический 3D-пояс (0 лага!)
17. gold_warden_belt.bbmodel          — КОМБО: Золотые поножи + Королевский пояс с цепями (0 лага!)

Плюс конфиги:
  • plugins/MythicMobs/Items/MythicWardrobe_TestArmorPack.yml
  • plugins/MythicWardrobe/items.yml
  • plugins/MythicWardrobe/combo-overrides.yml
  • plugins/MythicWardrobe/hair-3d.yml  (автопривязка 2D -> 3D волос + частичное отключение под шлемом!)

--------------------------------------------------------------------------------
БЫСТРАЯ УСТАНОВКА (4 шага):
--------------------------------------------------------------------------------
1. Обновите плагин до MythicWardrobe-1.7.0.jar и распакуйте архив в корень сервера.
2. Перезагрузите плагины:
     /ma reload
     /mm reload
     /wardrobe reload
3. Соберите БЕЗЛИМИТНЫЙ 3D-пак головы (Minecraft 1.21.4+ minecraft:composite):
     /wardrobe buildheadpack
   Подключите в клиент/сервер готовый объединённый ресурспак:
     plugins/MythicWardrobe/merged_pack.zip
4. Выдайте тестовые предметы (включая новые пояса и декор на талию!) и откройте /wardrobe open:
     /mm i give @s TacticalUtilityBelt3D
     /mm i give @s AdventurerPouchBelt3D
     /mm i give @s RoyalChainBelt3D
     /mm i give @s WardrobeLeatherBelt
     /mm i give @s WardrobeTacticalBelt
     /mm i give @s WardrobeGoldChainBelt
     /mm i give @s WardrobeHolsterBelt
     /mm i give @s WardrobeCrimsonSash
     /mm i give @s TacticalWardenLeggings
     /mm i give @s GoldWardenLeggingsCosmetic
     /mm i give @s WardrobeHazmatSuit

НОВЫЕ СЦЕНАРИИ ТЕСТА (v1.8.0):
  • Блокировка слоёв/слотов для цельных комплектов (Костюм химзащиты):
    Наденьте штаны (WardrobeBlueJeans) и ремень (WardrobeLeatherBelt), а затем
    наденьте WardrobeHazmatSuit (слой `jacket`, `Blocks: [undershirt, pants, belt_skin, gloves, socks, boots_skin, mask_skin, hat]`):
    — штаны и ремень автоматически снимутся и вернутся в инвентарь (`BlockMode: UNEQUIP`);
    — в меню гардероба заблокированные слоты отобразятся с красной иконкой-замком (BARRIER);
    — пока надет костюм, в них нельзя положить другую одежду (ни в GUI, ни ПКМ, ни Shift-кликом);
    — снимите костюм — все слоты мгновенно разблокируются!
    На любой предмет в руке можно повесить блокировку командой:
      /wardrobe block pants,undershirt,belt_skin,gloves,socks,boots_skin,mask_skin,hat UNEQUIP
  • Инвентарь больше НЕ закрывается при смене скина!
    Меняйте 2D-одежду прямо в меню гардероба сколько угодно раз — меню остаётся
    открытым, а новый скин мгновенно применится сразу после закрытия инвентаря
    (настраивается в config.yml -> skin.defer-apply-while-gui-open).
  • Кнопка «◀ Назад» во всех режимах меню:
    Возвращает в предыдущий раздел / хаб или выполняет команду back-command из menus.yml.
  • 2 слота одежды в разделе Штаны/Поножи (LEGS):
    Штаны (2D) + Пояс/Ремень (2D) + Пояс/Декор (3D) + Поножи (Статы + Косметика)!
    Положите TacticalUtilityBelt3D в «Пояс / Декор (3D)» сам по себе или вместе с
    TacticalWardenLeggings -> автоматическое 0-lag комбо tactical_warden_belt!
