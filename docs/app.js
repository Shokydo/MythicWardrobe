/**
 * MythicWardrobe Studio v2.5 - 100% Client-Side Application Controller
 * Full 2D + 3D Character Inspector, .bbmodel Combiner, 2D Skins & GUI Editor
 * Zero server / Zero Python / Zero external network requests!
 */
(() => {
  "use strict";

  const SE = window.StudioEngine;

  function ico(name, cls = "") {
    return `<svg class="ico${cls ? " " + cls : ""}" aria-hidden="true"><use href="#ico-${name}"></use></svg>`;
  }

  const CATEGORY_META = {
    helmets:     { label: "Шлемы",      slot: "HEAD",  tagClass: "tag-head",  defaultWardrobe: "helmet_cosmetic" },
    mask_3d:     { label: "3D-Маски",   slot: "HEAD",  tagClass: "tag-head",  defaultWardrobe: "mask_3d" },
    hair_3d:     { label: "3D-Причёски",slot: "HEAD",  tagClass: "tag-head",  defaultWardrobe: "hair_3d" },
    armor_chest: { label: "Нагрудники", slot: "CHEST", tagClass: "tag-chest", defaultWardrobe: "chest_cosmetic" },
    backpack_3d: { label: "3D-Рюкзаки", slot: "CHEST", tagClass: "tag-chest", defaultWardrobe: "backpack_3d" },
    armor_legs:  { label: "Поножи",     slot: "LEGS",  tagClass: "tag-legs",  defaultWardrobe: "legs_cosmetic" },
    belt_3d:     { label: "3D-Пояса",   slot: "LEGS",  tagClass: "tag-legs",  defaultWardrobe: "belt_3d" },
    armor_feet:  { label: "Ботинки",    slot: "FEET",  tagClass: "tag-feet",  defaultWardrobe: "boots_cosmetic" },
  };

  const CANONICAL_BONES = [
    ["head",       "head (Голова)"],
    ["body",       "body (Торс)"],
    ["right_arm",  "right_arm (Правая рука)"],
    ["left_arm",   "left_arm (Левая рука)"],
    ["waist",      "waist (Пояс)"],
    ["right_leg",  "right_leg (Правая нога)"],
    ["left_leg",   "left_leg (Левая нога)"],
    ["right_foot", "right_foot (Правая ступня)"],
    ["left_foot",  "left_foot (Левая ступня)"],
  ];

  const HAZMAT_DEFAULT_BLOCKS = [
    "undershirt", "pants", "belt_skin", "gloves", "socks", "boots_skin", "mask_skin", "hat",
  ];

  const state = {
    activeTab: "preview",
    models: [],          // { id, exportId, mythicId, filename, category, slot, wardrobeSlot, displayName, lore, colorRgb, offset, scale, boneMap, analysis, bbmodel, blocks, blockMode }
    selectedModelId: null,
    equippedIds: new Set(),
    activeComboPreview: null,
    poseMode: "idle",    // "idle" | "walk" | "run" | "sneak" | "apose"
    lightMode: "studio", // "studio" | "day" | "night"
    camPreset: "default",// "default" | "front" | "back" | "left" | "right" | "head" | "torso" | "feet"
    show2dSkin: true,
    show2dOuter: true,
    show3dArmor: true,
    showGrid: true,
    showBones: false,
    autoRotate: false,
    links: [
      { id: "link1", name: "Шлемы × 3D-Маски",       slot: "HEAD",  categories: ["helmets", "mask_3d"],     secondarySlot: "mask_3d",     stripFrontStraps: false, enabled: true },
      { id: "link2", name: "Нагрудники × 3D-Рюкзаки", slot: "CHEST", categories: ["armor_chest", "backpack_3d"], secondarySlot: "backpack_3d", stripFrontStraps: true,  enabled: true },
      { id: "link3", name: "Поножи × 3D-Пояса (v1.7)", slot: "LEGS", categories: ["armor_legs", "belt_3d"],   secondarySlot: "belt_3d",     stripFrontStraps: false, enabled: true },
    ],

    // 2D Skin Editor State (15 layers, top-to-bottom priority.yml order)
    skinBodyType: "wide",
    skinLayers: SE.DEFAULT_SKIN_LAYERS.map(l => ({ ...l })),
    activeSkinLayerId: "jacket",
    skinLibrary: {},     // { folder: { wide: [{name, dataUri, autoBlocks?}], slim: [...] } }
    skinSelected: {},    // { layerId: { name, dataUri, isCustom } }
    skinLayerBlocks: {}, // { layerId: { blocks: [...], blockMode: "UNEQUIP" } }
    skinItemMeta: {},    // { layerId: { mythicId, displayName, material, customModelData, lore: [...] } }
    rawUploadSkin: null,
    skinCompositeDataUri: null,

    // v2.2: Live helmet preview toggle (tests HIDE_ALL & PARTIAL_MODEL hair hiding!)
    testHelmetOn: false,

    // v2.2: Visual Inventory GUI Editor (menus.yml)
    menus: SE.createDefaultMenusState(),
    guiActiveScreen: "tabbed",
    guiSelectedSlot: 11,
  };

  let viewer = null;
  const $ = (id) => document.getElementById(id);

  function showToast(msg, ms = 3200) {
    const el = $("toast");
    el.textContent = msg;
    el.classList.add("show");
    clearTimeout(el._timer);
    el._timer = setTimeout(() => el.classList.remove("show"), ms);
  }

  function downloadBlob(blob, filename) {
    const a = document.createElement("a");
    const url = URL.createObjectURL(blob);
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 4000);
  }

  function hexToRgbStr(hex) {
    const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex || "");
    if (!m) return "";
    return `${parseInt(m[1], 16)},${parseInt(m[2], 16)},${parseInt(m[3], 16)}`;
  }

  function rgbStrToHex(rgb) {
    const p = String(rgb || "").split(",").map(v => Math.max(0, Math.min(255, parseInt(v.trim(), 10))));
    if (p.length !== 3 || p.some(Number.isNaN)) return "#ffffff";
    return "#" + p.map(n => n.toString(16).padStart(2, "0")).join("");
  }

  function escapeHtml(s) {
    return String(s || "").replace(/[&<>"']/g, ch => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[ch]));
  }

  // ==================== SHARED 3D VIEWER MOUNTING ====================
  function mountSharedViewer(targetId) {
    const root = $("sharedViewerRoot");
    const mount = $(targetId);
    if (!mount) return;
    mount.appendChild(root);
    root.style.display = "flex";
    root.style.flexDirection = "column";
    root.style.height = "100%";
    if (viewer) viewer.resize();
  }

  function switchTab(tab) {
    state.activeTab = tab;
    document.querySelectorAll(".tab-btn").forEach(b => b.classList.toggle("active", b.dataset.tab === tab));
    document.querySelectorAll(".workspace-tab").forEach(p => p.classList.toggle("active", p.dataset.tabPanel === tab));

    const ws = $("mainWorkspace");
    ws.classList.toggle("preview-mode", tab === "preview");
    ws.classList.toggle("config-mode", tab === "configs");
    ws.classList.toggle("gui-mode", tab === "gui");

    if (tab === "preview") {
      mountSharedViewer("viewerMountPreview");
      renderFullPreviewPanel();
      syncViewerUiState();
    } else if (tab === "bbmodel") {
      mountSharedViewer("viewerMount3d");
    } else if (tab === "skins") {
      mountSharedViewer("viewerMount2d");
      renderSkinItemMetaEditor();
      renderSkinComposite();
    } else if (tab === "gui") {
      renderGuiEditorAll();
    } else if (tab === "configs") {
      renderConfigOutputs();
    }
    if (viewer && tab !== "configs" && tab !== "gui") setTimeout(() => viewer.resize(), 40);
  }

  function autoDetectHairHideCubeUuids(bb) {
    const kws = ["tail", "ponytail", "bun", "scrunchie", "tie", "crown", "top", "spike", "back", "side", "cap", "horn", "ear", "ahoge"];
    const keepKws = ["strand", "bang", "front", "fringe", "lock_front", "cheek"];
    const out = [];
    for (const el of (bb && bb.elements) || []) {
      if (!el || !el.uuid) continue;
      const nm = String(el.name || "").toLowerCase();
      if (keepKws.some(k => nm.includes(k))) continue;
      if (kws.some(k => nm.includes(k))) out.push(el.uuid);
    }
    return out;
  }

  // ==================== 3D MODEL MANAGEMENT ====================
  function addAnalyzedModel(bb, filename, overrides = {}) {
    const analysis = SE.analyzeBBModel(bb, filename);
    const idBase = analysis.id;
    let uid = idBase;
    let idx = 2;
    while (state.models.some(m => m.id === uid)) {
      uid = `${idBase}_${idx++}`;
    }
    const cat = overrides.category || analysis.suggestedCategory || "armor_chest";
    const cmeta = CATEGORY_META[cat] || CATEGORY_META.armor_chest;

    const slot = overrides.slot || cmeta.slot;
    const mythicId = overrides.mythicId || SE.toPascalId(uid);
    const ovHair = overrides.hair3d || {};
    let defaultHideUuids = [];
    if (Array.isArray(ovHair.helmetHideCubeNames) && ovHair.helmetHideCubeNames.length) {
      const nameSet = new Set(ovHair.helmetHideCubeNames.map(s => String(s).toLowerCase()));
      defaultHideUuids = (bb.elements || []).filter(el => el && nameSet.has(String(el.name || "").toLowerCase())).map(el => el.uuid);
    } else if (Array.isArray(ovHair.helmetHideCubes)) {
      defaultHideUuids = [...ovHair.helmetHideCubes];
    }

    const model = {
      id: uid,
      exportId: overrides.exportId || uid,
      mythicId,
      filename,
      category: cat,
      slot,
      material: overrides.material || SE.SLOT_DEFAULT_MATERIAL[slot] || "LEATHER_CHESTPLATE",
      customModelData: overrides.customModelData || "",
      wardrobeSlot: overrides.wardrobeSlot !== undefined ? overrides.wardrobeSlot : cmeta.defaultWardrobe,
      displayName: overrides.displayName || `&f${analysis.name}`,
      lore: overrides.lore || [
        `&73D модель MythicArmors: &f${uid}.bbmodel`,
        "&aСобрано в MythicWardrobe Studio",
      ],
      colorRgb: overrides.colorRgb || (analysis.hasTint ? "196,132,68" : ""),
      offset: overrides.offset || [0, 0, 0],
      scale: overrides.scale || 1.0,
      boneMap: { ...analysis.suggestedBoneMap },
      autoLayers: true,
      autoTintMode: "keep",
      autoEmissiveMode: "keep",
      customIconDataUri: null,
      blocks: overrides.blocks ? [...overrides.blocks] : [],
      blockMode: overrides.blockMode || "UNEQUIP",
      hair3d: {
        bind2dHair: ovHair.bind2dHair || "",
        helmetMode: ovHair.helmetMode || "HIDE_ALL",
        helmetListMode: ovHair.helmetListMode || "ALL",
        helmets: ovHair.helmets || "",
        exceptions: ovHair.exceptions || "",
        partialMythicId: ovHair.partialMythicId || `${mythicId}HelmetSafe`,
        helmetHideCubes: defaultHideUuids,
      },
      analysis,
      bbmodel: bb,
    };
    state.models.push(model);
    return model;
  }

  async function uploadBBModelFiles(files) {
    let added = 0;
    for (const file of files) {
      try {
        const text = await file.text();
        const bb = JSON.parse(text);
        if (!bb || typeof bb !== "object") throw new Error("Некорректный JSON .bbmodel");
        const m = addAnalyzedModel(bb, file.name);
        state.selectedModelId = m.id;
        state.equippedIds.add(m.id);
        added++;
      } catch (err) {
        showToast(`Ошибка ${file.name}: ${err.message}`, 4500);
      }
    }
    if (added > 0) {
      state.activeComboPreview = null;
      renderAll3d();
      refresh3dViewport();
      showToast(`Загружено моделей: ${added}`);
    }
  }

  function loadDemo3dModels() {
    const demo = window.STUDIO_DEMO_PACK;
    if (!demo || !Array.isArray(demo.models) || !demo.models.length) {
      showToast("Встроенный демо-пак не найден");
      return;
    }
    state.models = [];
    state.equippedIds.clear();
    state.activeComboPreview = null;

    for (const item of demo.models) {
      const m = addAnalyzedModel(item.bbmodel, item.filename, {
        category: item.category,
        wardrobeSlot: item.wardrobeSlot,
        displayName: item.displayName,
        mythicId: item.mythicId,
        colorRgb: item.colorRgb || "",
        hair3d: item.hair3d || null,
      });
      // Equip showcase set right away including hair_ponytail_3d (demonstrates PARTIAL_MODEL under helmet!)
      if (["gold_warden_chest", "tactical_backpack", "belt_tactical_3d", "gold_warden_legs", "gold_warden_boots", "hair_ponytail_3d"].includes(m.exportId)) {
        state.equippedIds.add(m.id);
      }
    }
    const ponytail = state.models.find(m => m.exportId === "hair_ponytail_3d");
    if (ponytail) state.selectedModelId = ponytail.id;
    else if (state.models.length) state.selectedModelId = state.models[0].id;
    renderAll3d();
    refresh3dViewport();
    showToast(`Загружено ${state.models.length} демо-моделей (включая 3D-причёску с частичным скрытием под шлемом)!`);
  }

  function getSelectedModel() {
    return state.models.find(m => m.id === state.selectedModelId) || null;
  }

  function renderModelList() {
    const filter = $("filterCategory").value;
    const container = $("modelList");
    $("modelCountBadge").textContent = String(state.models.length);

    const list = state.models.filter(m => filter === "all" || m.category === filter);
    if (!list.length) {
      container.innerHTML = `<div class="hint">Нет моделей в выбранной категории. Перетащите .bbmodel выше или нажмите «Демо (10 моделей)».</div>`;
      return;
    }

    container.innerHTML = "";
    for (const m of list) {
      const cmeta = CATEGORY_META[m.category] || CATEGORY_META.armor_chest;
      const equipped = state.equippedIds.has(m.id);
      const div = document.createElement("div");
      div.className = "model-item" + (m.id === state.selectedModelId ? " selected" : "");
      div.innerHTML = `
        <div class="model-item-top">
          <div class="model-item-name" title="${escapeHtml(m.filename)}">${escapeHtml(m.exportId)}</div>
          <div class="row" style="gap:4px">
            <button class="btn btn-sm ${equipped ? "btn-green" : ""}" data-act="equip">${equipped ? `${ico("check", "ico-sm")}<span>Надето</span>` : "Надеть"}</button>
            <button class="btn btn-danger btn-icon" data-act="del" title="Удалить модель" aria-label="Удалить модель">${ico("x", "ico-sm")}</button>
          </div>
        </div>
        <div class="model-meta">
          <span class="tag ${cmeta.tagClass}">${cmeta.label}</span>
          <span class="tag">${m.analysis.cubeCount} кубов</span>
          ${m.analysis.hasTint ? `<span class="tag tag-tint">${ico("palette", "ico-sm")} _t</span>` : ""}
          ${m.analysis.hasEmissive ? `<span class="tag tag-emis">${ico("sparkles", "ico-sm")} _e</span>` : ""}
          ${(m.blocks && m.blocks.length) ? `<span class="tag tag-lock">${ico("lock", "ico-sm")} ${m.blocks.length}</span>` : ""}
        </div>
      `;
      div.addEventListener("click", (e) => {
        const actEl = e.target.closest("[data-act]");
        const act = actEl ? actEl.dataset.act : null;
        if (act === "equip") {
          e.stopPropagation();
          state.activeComboPreview = null;
          if (state.equippedIds.has(m.id)) state.equippedIds.delete(m.id);
          else state.equippedIds.add(m.id);
          renderModelList();
          refresh3dViewport();
          return;
        }
        if (act === "del") {
          e.stopPropagation();
          state.models = state.models.filter(x => x.id !== m.id);
          state.equippedIds.delete(m.id);
          if (state.selectedModelId === m.id) state.selectedModelId = state.models[0]?.id || null;
          renderAll3d();
          refresh3dViewport();
          return;
        }
        state.selectedModelId = m.id;
        renderModelList();
        renderInspector();
      });
      container.appendChild(div);
    }
  }

  function renderBlockChips(shortcutsContainer, targetsContainer, currentBlocks, onToggle) {
    const activeSet = new Set(currentBlocks || []);
    shortcutsContainer.innerHTML = "";
    for (const sc of SE.BLOCK_SHORTCUTS) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "block-chip shortcut" + (activeSet.has(sc.id) ? " active" : "");
      btn.textContent = sc.id;
      btn.title = sc.label;
      btn.addEventListener("click", () => onToggle(sc.id));
      shortcutsContainer.appendChild(btn);
    }
    targetsContainer.innerHTML = "";
    for (const ch of SE.BLOCKABLE_CHOICES) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "block-chip" + (activeSet.has(ch.id) ? " active" : "");
      btn.textContent = ch.label;
      btn.title = `${ch.id} (${ch.group})`;
      btn.addEventListener("click", () => onToggle(ch.id));
      targetsContainer.appendChild(btn);
    }
  }

  function updateMaterialHint(m) {
    const box = $("materialHintBox");
    if (!box || !m) return;
    const mat = String(m.material || "").trim().toUpperCase();
    const cmd = parseInt(String(m.customModelData || "").trim(), 10);
    const hasCmd = Number.isFinite(cmd) && cmd > 0;
    const isDyeable = SE.DYEABLE_MATERIALS.has(mat);
    const notes = [];

    if (m.slot === "HEAD" && hasCmd) {
      notes.push(`${ico("check", "ico-sm")} <b>${escapeHtml(mat)} + CMD ${cmd} (HEAD):</b> при экспорте ZIP создастся <code>resourcepack_snippet/assets/minecraft/items/${escapeHtml(mat.toLowerCase())}.json</code> с <code>display_context</code> (на голове 3D, в инвентаре ваша иконка).`);
    } else if (m.slot === "HEAD" && !hasCmd) {
      notes.push(`${ico("check", "ico-sm")} <b>${escapeHtml(mat)} (HEAD):</b> работает через <code>ItemModel: mythicarmor:${escapeHtml(m.exportId)}_head_piece</code>. Добавьте PNG-иконку ниже, и MythicArmors покажет её в инвентаре.`);
    } else if (hasCmd) {
      notes.push(`${ico("check", "ico-sm")} <b>${escapeHtml(mat)} + CMD ${cmd} (${m.slot}):</b> в инвентаре ваша иконка по CustomModelData, а на теле 3D-броня через <code>Equippable.Model</code>.`);
    } else {
      notes.push(`${ico("check", "ico-sm")} <b>${escapeHtml(mat)} (${m.slot}):</b> 3D-броня через <code>Equippable.Model</code>.`);
    }
    if (!isDyeable && (m.colorRgb || m.analysis.hasTint || m.autoTintMode === "full" || m.autoTintMode === "light_only")) {
      notes.push(`${ico("alert", "ico-sm")} Материал <code>${escapeHtml(mat)}</code> не красится через <code>Options.Color</code>: для покраски <code>_t.png</code> используйте <code>LEATHER_*</code>.`);
    }
    box.innerHTML = notes.join("<br>");
  }

  function renderTextureAutoFixer(m) {
    const a = m.analysis;
    const grid = $("texStatusGrid");
    const chips = [];
    const autoL = m.autoLayers !== false;

    chips.push(a.hasLayer1
      ? `<span class="tag">${ico("check", "ico-sm")} layer_1</span>`
      : autoL ? `<span class="tag tag-tint">${ico("plus", "ico-sm")} layer_1 (авто)</span>` : `<span class="tag tag-missing">нет layer_1</span>`);
    chips.push(a.hasLayer2
      ? `<span class="tag">${ico("check", "ico-sm")} layer_2</span>`
      : autoL ? `<span class="tag tag-tint">${ico("plus", "ico-sm")} layer_2 (авто)</span>` : `<span class="tag tag-missing">нет layer_2</span>`);

    if (m.autoTintMode === "full" || m.autoTintMode === "light_only") {
      chips.push(`<span class="tag tag-tint">${ico("plus", "ico-sm")} _t.png (авто)</span>`);
    } else if (a.hasTint && m.autoTintMode !== "none") {
      chips.push(`<span class="tag">${ico("check", "ico-sm")} _t.png</span>`);
    } else {
      chips.push(`<span class="tag tag-missing">без _t.png</span>`);
    }

    if (m.autoEmissiveMode === "bright_neon") {
      chips.push(`<span class="tag tag-emis">${ico("plus", "ico-sm")} _e.png (авто)</span>`);
    } else if (a.hasEmissive && m.autoEmissiveMode !== "none") {
      chips.push(`<span class="tag">${ico("check", "ico-sm")} _e.png</span>`);
    } else {
      chips.push(`<span class="tag tag-missing">без _e.png</span>`);
    }

    if (m.customIconDataUri) {
      chips.push(`<span class="tag tag-tint">${ico("plus", "ico-sm")} 2D-иконка</span>`);
    } else if (a.hasIcons && a.hasIcons.length) {
      chips.push(`<span class="tag">${ico("check", "ico-sm")} иконка (${a.hasIcons.join(",")})</span>`);
    } else {
      chips.push(`<span class="tag tag-missing">без 2D-иконки</span>`);
    }
    grid.innerHTML = chips.join("");

    const missingLayers = !a.hasLayer1 || !a.hasLayer2;
    $("texAutoBadge").textContent = missingLayers ? "Доделает layer_1/2" : "Все слои OK";

    const pbox = $("modelIconPreviewBox");
    if (m.customIconDataUri) {
      pbox.innerHTML = `<img src="${m.customIconDataUri}" alt="icon">`;
    } else {
      pbox.innerHTML = `<span>2D</span>`;
    }
  }

  function renderInspector() {
    const m = getSelectedModel();
    if (!m) {
      $("inspectorEmpty").style.display = "block";
      $("inspectorBody").style.display = "none";
      $("inspectorModelTag").textContent = "Не выбрано";
      return;
    }
    $("inspectorEmpty").style.display = "none";
    $("inspectorBody").style.display = "block";
    $("inspectorModelTag").textContent = `${m.analysis.cubeCount} кубов`;

    $("inpExportId").value = m.exportId;
    $("inpMythicId").value = m.mythicId;
    $("inpCategory").value = m.category;
    $("inpWardrobeSlot").value = m.wardrobeSlot || "";
    $("inpMaterial").value = m.material || SE.SLOT_DEFAULT_MATERIAL[m.slot] || "LEATHER_CHESTPLATE";
    $("inpCustomModelData").value = m.customModelData || "";
    $("inpDisplayName").value = m.displayName;
    $("inpColorRgb").value = m.colorRgb || "";
    $("inpColorPicker").value = rgbStrToHex(m.colorRgb || "255,255,255");
    $("inpLore").value = (m.lore || []).join("\n");
    $("inpOffX").value = m.offset[0];
    $("inpOffY").value = m.offset[1];
    $("inpOffZ").value = m.offset[2];
    $("inpScale").value = m.scale;
    $("inp3dBlockMode").value = m.blockMode || "UNEQUIP";
    $("chkAutoLayers").checked = m.autoLayers !== false;
    $("selAutoTint").value = m.autoTintMode || "keep";
    $("selAutoEmissive").value = m.autoEmissiveMode || "keep";

    updateMaterialHint(m);
    renderTextureAutoFixer(m);

    const bt = $("boneMappingTable");
    bt.innerHTML = "";
    for (const b of m.analysis.bones) {
      const row = document.createElement("div");
      row.className = "bone-row";
      const cur = m.boneMap[b.original] || b.mapped;
      const opts = CANONICAL_BONES.map(([id, lbl]) =>
        `<option value="${id}" ${cur === id ? "selected" : ""}>${lbl}</option>`
      ).join("");
      row.innerHTML = `
        <span title="${escapeHtml(b.original)}" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">
          <b>${escapeHtml(b.original)}</b>
        </span>
        <span style="color:var(--muted);text-align:center">→</span>
        <select data-bone="${escapeHtml(b.original)}">${opts}</select>
        <span class="tag">${b.cubeCount} шт</span>
      `;
      row.querySelector("select").addEventListener("change", (e) => {
        m.boneMap[b.original] = e.target.value;
        refresh3dViewport();
      });
      bt.appendChild(row);
    }

    renderBlockChips(
      $("blockChips3dShortcuts"),
      $("blockChips3dTargets"),
      m.blocks,
      (targetId) => {
        if (!m.blocks) m.blocks = [];
        const idx = m.blocks.indexOf(targetId);
        if (idx >= 0) m.blocks.splice(idx, 1);
        else m.blocks.push(targetId);
        renderInspector();
        renderModelList();
      }
    );

    renderHair3dPanel(m);
  }

  function renderHair3dPanel(m) {
    const panel = $("hair3dPanel");
    if (!panel) return;
    const isHeadOrHair = m && (m.category === "hair_3d" || m.wardrobeSlot === "hair_3d" || m.slot === "HEAD");
    panel.style.display = isHeadOrHair ? "block" : "none";
    if (!isHeadOrHair) return;

    if (!m.hair3d) {
      m.hair3d = {
        bind2dHair: "", helmetMode: "HIDE_ALL", helmetListMode: "ALL",
        helmets: "", exceptions: "", partialMythicId: `${m.mythicId}HelmetSafe`, helmetHideCubes: [],
      };
    }
    const h = m.hair3d;
    $("inpHairBind2d").value = h.bind2dHair || "";
    $("selHairHelmetMode").value = h.helmetMode || "HIDE_ALL";
    $("selHairListMode").value = h.helmetListMode || "ALL";
    $("inpHairPartialId").value = h.partialMythicId || `${m.mythicId}HelmetSafe`;
    $("inpHairHelmets").value = h.helmets || "";
    $("inpHairExceptions").value = h.exceptions || "";

    // Populate datalist with all available 2D hair PNGs from skinLibrary
    const dl = $("hair2dPresets");
    if (dl) {
      const names = new Set(["hair_brown.png", "hair_blonde.png", "hair_black.png"]);
      const hairsLib = state.skinLibrary && state.skinLibrary.hairs;
      if (hairsLib) {
        for (const arr of Object.values(hairsLib)) {
          for (const it of (arr || [])) if (it && it.name) names.add(it.name);
        }
      }
      if (state.skinSelected.hair && state.skinSelected.hair.name) names.add(state.skinSelected.hair.name);
      dl.innerHTML = [...names].map(n => `<option value="${escapeHtml(n)}"></option>`).join("");
    }

    const showSwap = h.helmetMode === "SWAP_MODEL";
    const showParts = h.helmetMode === "PARTIAL_MODEL";
    if ($("hairSwapModelWrap")) $("hairSwapModelWrap").style.display = showSwap ? "block" : "none";
    $("hairPartialPartsWrap").style.display = showParts ? "block" : "none";

    // Populate swap target dropdown with all other loaded 3D models
    const swapSel = $("selHairSwapModel");
    if (swapSel) {
      const opts = [`<option value="">Указать вручную по MythicMobs ID</option>`];
      for (const other of state.models) {
        if (other.id === m.id) continue;
        const selected = h.swapModelInternalId === other.id ? "selected" : "";
        opts.push(`<option value="${escapeHtml(other.id)}" ${selected}>${escapeHtml(other.exportId)} (${escapeHtml(other.mythicId)})</option>`);
      }
      swapSel.innerHTML = opts.join("");
    }

    const hiddenSet = new Set(h.helmetHideCubes || []);
    const elements = (m.bbmodel && Array.isArray(m.bbmodel.elements)) ? m.bbmodel.elements : [];
    const total = elements.length;
    const hiddenCount = elements.filter(el => el && hiddenSet.has(el.uuid)).length;
    $("hair3dPartBadge").textContent = h.helmetMode === "HIDE_ALL"
      ? "Скрыть всё"
      : (h.helmetMode === "KEEP" ? "Всегда видна"
        : (h.helmetMode === "SWAP_MODEL"
          ? `Замена → ${h.swapMythicId || h.partialMythicId || "..."}`
          : `Скрыто ${hiddenCount}/${total}`));

    const listEl = $("hairPartsList");
    listEl.innerHTML = "";
    for (const el of elements) {
      if (!el || !el.uuid) continue;
      const isHidden = hiddenSet.has(el.uuid);
      const row = document.createElement("label");
      row.className = "hair-part-row" + (isHidden ? " is-hidden" : "");
      const fr = el.from || [0, 0, 0], to = el.to || [0, 0, 0];
      const yMid = ((fr[1] + to[1]) * 0.5).toFixed(1);
      const zMid = ((fr[2] + to[2]) * 0.5).toFixed(1);
      row.innerHTML = `
        <span class="row" style="gap:6px">
          <input type="checkbox" ${isHidden ? "checked" : ""}>
          <code>${escapeHtml(el.name || "cube")}</code>
        </span>
        <span style="font-size:10px;color:var(--muted)">${isHidden ? "СКРЫТЬ под шлемом" : `Видна (Y:${yMid} Z:${zMid})`}</span>
      `;
      row.querySelector("input").addEventListener("change", (ev) => {
        const set = new Set(h.helmetHideCubes || []);
        if (ev.target.checked) set.add(el.uuid);
        else set.delete(el.uuid);
        h.helmetHideCubes = [...set];
        renderHair3dPanel(m);
        refresh3dViewport();
        if (state.activeTab === "configs") renderConfigOutputs();
      });
      listEl.appendChild(row);
    }
  }

  function bindInspectorEvents() {
    const syncField = (fn) => {
      const m = getSelectedModel();
      if (!m) return;
      fn(m);
      updateMaterialHint(m);
      renderTextureAutoFixer(m);
      renderModelList();
      renderLinksAndCombos();
      refresh3dViewport();
    };
    $("inpExportId").addEventListener("input", e => syncField(m => { m.exportId = e.target.value.trim() || m.id; }));
    $("inpMythicId").addEventListener("input", e => syncField(m => { m.mythicId = e.target.value.trim() || SE.toPascalId(m.exportId); }));
    $("inpCategory").addEventListener("change", e => syncField(m => {
      m.category = e.target.value;
      const cm = CATEGORY_META[m.category];
      if (cm) {
        m.slot = cm.slot;
        m.wardrobeSlot = cm.defaultWardrobe;
        $("inpWardrobeSlot").value = m.wardrobeSlot;
        if (SE.DYEABLE_MATERIALS.has(String(m.material || "").toUpperCase())) {
          m.material = SE.SLOT_DEFAULT_MATERIAL[m.slot];
          $("inpMaterial").value = m.material;
        }
      }
    }));
    $("inpWardrobeSlot").addEventListener("change", e => syncField(m => { m.wardrobeSlot = e.target.value; }));
    $("inpMaterial").addEventListener("input", e => syncField(m => {
      m.material = e.target.value.trim().toUpperCase() || SE.SLOT_DEFAULT_MATERIAL[m.slot];
    }));
    $("inpCustomModelData").addEventListener("input", e => syncField(m => {
      m.customModelData = e.target.value.trim();
    }));
    $("inpDisplayName").addEventListener("input", e => syncField(m => { m.displayName = e.target.value; }));
    $("inpColorRgb").addEventListener("input", e => syncField(m => {
      m.colorRgb = e.target.value.trim();
      if (m.colorRgb) $("inpColorPicker").value = rgbStrToHex(m.colorRgb);
    }));
    $("inpColorPicker").addEventListener("input", e => syncField(m => {
      m.colorRgb = hexToRgbStr(e.target.value);
      $("inpColorRgb").value = m.colorRgb;
    }));
    $("inpLore").addEventListener("input", e => syncField(m => {
      m.lore = e.target.value.split("\n").filter(l => l.trim().length > 0);
    }));
    $("inp3dBlockMode").addEventListener("change", e => syncField(m => {
      m.blockMode = e.target.value;
    }));

    // Auto-texture completion controls
    $("chkAutoLayers").addEventListener("change", e => syncField(m => { m.autoLayers = e.target.checked; }));
    $("selAutoTint").addEventListener("change", e => syncField(m => { m.autoTintMode = e.target.value; }));
    $("selAutoEmissive").addEventListener("change", e => syncField(m => { m.autoEmissiveMode = e.target.value; }));

    $("btnUploadIconPng").addEventListener("click", () => $("inpIconPngFile").click());
    $("inpIconPngFile").addEventListener("change", (e) => {
      const file = e.target.files && e.target.files[0];
      const m = getSelectedModel();
      if (!file || !m) return;
      const reader = new FileReader();
      reader.onload = () => {
        m.customIconDataUri = reader.result;
        renderTextureAutoFixer(m);
        showToast("2D-иконка прикреплена и вошьётся внутрь .bbmodel!");
      };
      reader.readAsDataURL(file);
      e.target.value = "";
    });

    $("btnCapture3dIcon").addEventListener("click", async () => {
      const m = getSelectedModel();
      if (!m || !viewer) return;
      // Temporarily render ONLY this model cleanly so snapshot has just this item
      const prevCombo = state.activeComboPreview;
      await viewer.setModels([{
        bbmodel: m.bbmodel,
        boneMap: m.boneMap,
        offset: m.offset,
        scale: m.scale,
        colorRgb: m.colorRgb || null,
      }]);
      const dataUri = viewer.captureIconDataUri(32);
      state.activeComboPreview = prevCombo;
      await refresh3dViewport();
      if (dataUri) {
        m.customIconDataUri = dataUri;
        renderTextureAutoFixer(m);
        showToast("Снята 32×32 PNG-иконка прямо с 3D-модели!");
      } else {
        showToast("Не удалось снять иконку: убедитесь, что модель видна в 3D");
      }
    });

    $("btnClearIconPng").addEventListener("click", () => {
      const m = getSelectedModel();
      if (!m) return;
      m.customIconDataUri = null;
      renderTextureAutoFixer(m);
    });

    // v2.2: Upload custom 3D model textures (main / _t / _e) directly onto selected .bbmodel!
    const bindTexUpload = (btnId, inpId, role, toastLabel) => {
      $(btnId).addEventListener("click", () => $(inpId).click());
      $(inpId).addEventListener("change", (e) => {
        const file = e.target.files && e.target.files[0];
        const m = getSelectedModel();
        if (!file || !m) return;
        const reader = new FileReader();
        reader.onload = () => {
          m.bbmodel = SE.replaceBBModelTexture(m.bbmodel, role, reader.result, file.name);
          m.analysis = SE.analyzeBBModel(m.bbmodel, m.filename);
          renderTextureAutoFixer(m);
          renderModelList();
          refresh3dViewport();
          showToast(`${toastLabel} (${file.name}) применена к ${m.exportId}!`);
        };
        reader.readAsDataURL(file);
        e.target.value = "";
      });
    };
    bindTexUpload("btnUpload3dMainTex", "inp3dMainTexFile", "main", "Основная 3D-текстура");
    bindTexUpload("btnUpload3dTintTex", "inp3dTintTexFile", "tint", "Маска покраски _t.png");
    bindTexUpload("btnUpload3dEmisTex", "inp3dEmisTexFile", "emissive", "Маска свечения _e.png");

    // v2.2: 3D Hair Binding & Partial Helmet Hiding events
    const syncHair = (fn) => {
      const m = getSelectedModel();
      if (!m) return;
      if (!m.hair3d) m.hair3d = { bind2dHair: "", helmetMode: "HIDE_ALL", helmetListMode: "ALL", helmets: "", exceptions: "", partialMythicId: `${m.mythicId}HelmetSafe`, helmetHideCubes: [] };
      fn(m, m.hair3d);
      renderHair3dPanel(m);
      refresh3dViewport();
      if (state.activeTab === "configs") renderConfigOutputs();
    };
    $("inpHairBind2d").addEventListener("input", e => syncHair((m, h) => { h.bind2dHair = e.target.value.trim(); }));
    $("selHairHelmetMode").addEventListener("change", e => syncHair((m, h) => {
      h.helmetMode = e.target.value;
      if (h.helmetMode === "PARTIAL_MODEL" && (!h.helmetHideCubes || !h.helmetHideCubes.length)) {
        h.helmetHideCubes = autoDetectHairHideCubeUuids(m.bbmodel);
      }
    }));
    $("selHairListMode").addEventListener("change", e => syncHair((m, h) => { h.helmetListMode = e.target.value; }));
    $("selHairSwapModel").addEventListener("change", e => syncHair((m, h) => {
      h.swapModelInternalId = e.target.value;
      const target = state.models.find(x => x.id === h.swapModelInternalId);
      if (target) {
        h.swapMythicId = target.mythicId;
        h.partialMythicId = target.mythicId;
        $("inpHairPartialId").value = target.mythicId;
      }
    }));
    $("inpHairPartialId").addEventListener("input", e => syncHair((m, h) => {
      const val = e.target.value.trim() || `${m.mythicId}HelmetSafe`;
      h.partialMythicId = val;
      h.swapMythicId = val;
    }));
    $("inpHairHelmets").addEventListener("input", e => syncHair((m, h) => { h.helmets = e.target.value; }));
    $("inpHairExceptions").addEventListener("input", e => syncHair((m, h) => { h.exceptions = e.target.value; }));
    $("btnHairAutoMarkTail").addEventListener("click", () => syncHair((m, h) => {
      h.helmetMode = "PARTIAL_MODEL";
      h.helmetHideCubes = autoDetectHairHideCubeUuids(m.bbmodel);
      showToast(`Отмечено частей для скрытия под шлемом: ${h.helmetHideCubes.length}`);
    }));
    $("btnHairClearParts").addEventListener("click", () => syncHair((m, h) => {
      h.helmetHideCubes = [];
    }));
    $("btnDownloadPartialHair").addEventListener("click", async () => {
      const m = getSelectedModel();
      if (!m || !m.hair3d || !m.hair3d.helmetHideCubes || !m.hair3d.helmetHideCubes.length) {
        showToast("Отметьте хотя бы 1 часть волос для скрытия под шлемом!");
        return;
      }
      try {
        const cleanId = SE.sanitizeId(`${m.exportId || m.id}_under_helmet`);
        const partialBb = await SE.combineBBModels([{
          bbmodel: m.bbmodel,
          boneMap: m.boneMap || {},
          offset: m.offset || [0, 0, 0],
          scale: m.scale || 1.0,
          stripKeywords: [],
          excludeCubeUuids: m.hair3d.helmetHideCubes,
          slotFilter: "HEAD",
        }], cleanId, "HEAD", {
          autoLayers: m.autoLayers !== false,
          autoTintMode: m.autoTintMode || "keep",
          autoEmissiveMode: m.autoEmissiveMode || "keep",
          customIconDataUri: m.customIconDataUri || null,
          customIconSlot: "HEAD",
        });
        downloadBlob(new Blob([JSON.stringify(partialBb)], { type: "application/json" }), `${cleanId}.bbmodel`);
        showToast(`Скачана частичная модель под шлем: ${cleanId}.bbmodel (${partialBb.elements.length} кубов)!`, 4500);
      } catch (err) {
        showToast(`Ошибка: ${err.message}`, 4500);
      }
    });

    $("btnDownloadSingleBbmodel").addEventListener("click", async () => {
      const m = getSelectedModel();
      if (!m) return;
      try {
        const cleanId = SE.sanitizeId(m.exportId || m.id);
        const fixed = await SE.combineBBModels([{
          bbmodel: m.bbmodel,
          boneMap: m.boneMap || {},
          offset: m.offset || [0, 0, 0],
          scale: m.scale || 1.0,
          stripKeywords: [],
          slotFilter: null,
        }], cleanId, null, {
          autoLayers: m.autoLayers !== false,
          autoTintMode: m.autoTintMode || "keep",
          autoEmissiveMode: m.autoEmissiveMode || "keep",
          customIconDataUri: m.customIconDataUri || null,
          customIconSlot: m.slot || "HEAD",
        });
        const blob = new Blob([JSON.stringify(fixed)], { type: "application/json" });
        downloadBlob(blob, `${cleanId}.bbmodel`);
        const tnames = (fixed.textures || []).map(t => t.name).join(", ");
        showToast(`Скачан ${cleanId}.bbmodel! Текстуры: ${tnames}`, 4500);
      } catch (err) {
        showToast(`Ошибка сборки .bbmodel: ${err.message}`, 4500);
      }
    });

    const syncTransform = () => {
      const m = getSelectedModel();
      if (!m) return;
      m.offset = [
        parseFloat($("inpOffX").value) || 0,
        parseFloat($("inpOffY").value) || 0,
        parseFloat($("inpOffZ").value) || 0,
      ];
      m.scale = Math.max(0.2, Math.min(3.0, parseFloat($("inpScale").value) || 1.0));
      refresh3dViewport();
    };
    ["inpOffX", "inpOffY", "inpOffZ", "inpScale"].forEach(id => $(id).addEventListener("input", syncTransform));
  }

  // ==================== CATEGORY LINKS (COMBINATION MATRIX) ====================
  function computeLinkCombos(link) {
    const groups = link.categories.map(cat => state.models.filter(m => m.category === cat));
    if (groups.some(g => g.length === 0)) return [];
    return groups.reduce((acc, cur) => acc.flatMap(a => cur.map(b => [...a, b])), [[]]);
  }

  function renderLinksAndCombos() {
    const lc = $("linksContainer");
    lc.innerHTML = "";
    const catOptions = Object.entries(CATEGORY_META)
      .map(([k, v]) => `<option value="${k}">${v.label} (${v.slot})</option>`).join("");

    for (const link of state.links) {
      const combos = computeLinkCombos(link);
      const card = document.createElement("div");
      card.className = "link-card";
      card.innerHTML = `
        <div class="row-between">
          <label class="row" style="font-weight:600;font-size:12.5px;cursor:pointer">
            <input type="checkbox" data-f="enabled" ${link.enabled ? "checked" : ""}>
            <span>${escapeHtml(link.name)}</span>
          </label>
          <div class="row" style="gap:4px">
            <span class="tag">${combos.length} комбо</span>
            <button class="btn btn-danger btn-icon" data-act="rm" title="Удалить связку" aria-label="Удалить связку">${ico("x", "ico-sm")}</button>
          </div>
        </div>
        <div class="grid-3">
          <select class="select-sm" data-f="cat0">${catOptions}</select>
          <select class="select-sm" data-f="cat1">${catOptions}</select>
          <select class="select-sm" data-f="slot">
            <option value="HEAD">Слот HEAD</option>
            <option value="CHEST">Слот CHEST</option>
            <option value="LEGS">Слот LEGS</option>
            <option value="FEET">Слот FEET</option>
          </select>
        </div>
        <div class="row-between">
          <label class="toggle-chip">
            <input type="checkbox" data-f="strip" ${link.stripFrontStraps ? "checked" : ""}>
            <span>Убирать передние лямки</span>
          </label>
          <select class="select-sm" data-f="secSlot" style="width:130px">
            <option value="mask_3d">2-й: mask_3d</option>
            <option value="hair_3d">2-й: hair_3d</option>
            <option value="backpack_3d">2-й: backpack_3d</option>
            <option value="belt_3d">2-й: belt_3d</option>
          </select>
        </div>
      `;
      card.querySelector('[data-f="cat0"]').value = link.categories[0];
      card.querySelector('[data-f="cat1"]').value = link.categories[1];
      card.querySelector('[data-f="slot"]').value = link.slot;
      card.querySelector('[data-f="secSlot"]').value = link.secondarySlot || "backpack_3d";

      card.querySelector('[data-f="enabled"]').addEventListener("change", e => { link.enabled = e.target.checked; renderLinksAndCombos(); });
      card.querySelector('[data-f="cat0"]').addEventListener("change", e => {
        link.categories[0] = e.target.value;
        link.slot = CATEGORY_META[e.target.value]?.slot || link.slot;
        link.name = `${CATEGORY_META[link.categories[0]].label} × ${CATEGORY_META[link.categories[1]].label}`;
        renderLinksAndCombos();
      });
      card.querySelector('[data-f="cat1"]').addEventListener("change", e => {
        link.categories[1] = e.target.value;
        link.secondarySlot = CATEGORY_META[e.target.value]?.defaultWardrobe || link.secondarySlot;
        link.name = `${CATEGORY_META[link.categories[0]].label} × ${CATEGORY_META[link.categories[1]].label}`;
        renderLinksAndCombos();
      });
      card.querySelector('[data-f="slot"]').addEventListener("change", e => { link.slot = e.target.value; renderLinksAndCombos(); });
      card.querySelector('[data-f="secSlot"]').addEventListener("change", e => { link.secondarySlot = e.target.value; });
      card.querySelector('[data-f="strip"]').addEventListener("change", e => { link.stripFrontStraps = e.target.checked; });
      card.querySelector('[data-act="rm"]').addEventListener("click", () => {
        state.links = state.links.filter(x => x.id !== link.id);
        renderLinksAndCombos();
      });
      lc.appendChild(card);
    }

    // Live combo preview list
    const previewEl = $("comboPreviewList");
    previewEl.innerHTML = "";
    const allCombos = [];
    for (const link of state.links) {
      if (!link.enabled) continue;
      for (const pair of computeLinkCombos(link)) allCombos.push({ link, pair });
    }
    if (!allCombos.length) {
      previewEl.innerHTML = `<div class="hint">Загрузите модели в связанные категории (например «Поножи» и «3D-Пояса»), чтобы увидеть комбо-модели.</div>`;
      return;
    }

    const head = document.createElement("div");
    head.className = "hint";
    head.innerHTML = `Будет сгенерировано комбо-моделей: <b style="color:#ffffff">${allCombos.length}</b> (нажмите «Примерить», чтобы сшить прямо в браузере):`;
    previewEl.appendChild(head);

    for (const { link, pair } of allCombos.slice(0, 24)) {
      const label = pair.map(m => m.exportId).join(" + ");
      const key = `${link.slot}:${pair.map(m => m.id).join("+")}`;
      const isActive = state.activeComboPreview && state.activeComboPreview.key === key;
      const row = document.createElement("div");
      row.className = "combo-preview-row" + (isActive ? " active-preview" : "");
      row.innerHTML = `
        <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${escapeHtml(label)}">
          <span class="tag">${link.slot}</span> ${escapeHtml(label)}
        </span>
        <button class="btn btn-sm ${isActive ? "btn-green" : ""}">${isActive ? `${ico("check", "ico-sm")}<span>В 3D</span>` : "Примерить"}</button>
      `;
      row.querySelector("button").addEventListener("click", () => previewCombinedPair(key, link, pair));
      previewEl.appendChild(row);
    }
  }

  async function previewCombinedPair(key, link, pair) {
    try {
      const inputs = pair.map((m, idx) => ({
        bbmodel: m.bbmodel,
        boneMap: m.boneMap,
        offset: m.offset,
        scale: m.scale,
        stripKeywords: (idx > 0 && link.stripFrontStraps) ? ["strap"] : [],
        slotFilter: link.slot,
      }));
      const merged = await SE.combineBBModels(inputs, pair.map(m => m.exportId).join("_plus_"), link.slot);
      const colorRgb = (pair.find(m => m.colorRgb) || {}).colorRgb || null;
      state.activeComboPreview = {
        key,
        name: pair.map(m => m.exportId).join(" + "),
        bbmodel: merged,
        colorRgb,
      };
      renderLinksAndCombos();
      refresh3dViewport();
      showToast(`Сшито в браузере: ${merged.elements.length} кубов · атлас ${merged.resolution.width}×${merged.resolution.height}`);
    } catch (err) {
      showToast(`Ошибка сшивки: ${err.message}`, 4500);
    }
  }

  // ==================== 3D VIEWPORT REFRESH ====================
  async function refresh3dViewport() {
    if (!viewer) return;
    const chips = $("equippedChips");

    const active2dCount = Object.keys(state.skinSelected).filter(k => state.skinSelected[k]?.dataUri).length;
    const bodyLbl = state.skinBodyType === "slim" ? "Slim" : "Wide";
    const skinBadgeHtml = `<span class="tag" title="Активный 2D-скин на персонаже">${ico("palette", "ico-sm")} 2D ${bodyLbl} (${active2dCount} сл.)</span>`;

    if (state.activeComboPreview) {
      await viewer.setModels([{
        bbmodel: state.activeComboPreview.bbmodel,
        colorRgb: state.activeComboPreview.colorRgb,
      }]);
      chips.innerHTML = `${skinBadgeHtml}<span class="tag tag-emis">${ico("zap", "ico-sm")} Комбо 3D: ${escapeHtml(state.activeComboPreview.name)}</span>`;
      $("viewerBadge").innerHTML = `<span>2D (${active2dCount} сл.) + Комбо 3D: ${escapeHtml(state.activeComboPreview.name)}</span>`;
      renderFullPreviewPanel();
      return;
    }

    const eqModels = state.models.filter(m => state.equippedIds.has(m.id));
    const hasEquippedHelmet = eqModels.some(m => m.category === "helmets" || m.wardrobeSlot === "helmet_cosmetic");
    const helmetActive = Boolean(state.testHelmetOn || hasEquippedHelmet);

    const renderList = [];
    const chipHtml = [skinBadgeHtml];

    for (const m of eqModels) {
      const isHair = m.category === "hair_3d" || m.wardrobeSlot === "hair_3d";
      if (isHair && helmetActive && m.hair3d) {
        const mode = m.hair3d.helmetMode || "HIDE_ALL";
        if (mode === "HIDE_ALL") {
          chipHtml.push(`<span class="tag tag-missing" title="Скрыта шлемом (HIDE_ALL)">${escapeHtml(m.exportId)} [скрыто]</span>`);
          continue;
        }
        if (mode === "SWAP_MODEL") {
          const swapTarget = state.models.find(x =>
            (m.hair3d.swapModelInternalId && x.id === m.hair3d.swapModelInternalId) ||
            (m.hair3d.swapMythicId && x.mythicId.toLowerCase() === m.hair3d.swapMythicId.toLowerCase())
          );
          if (swapTarget) {
            renderList.push({
              bbmodel: swapTarget.bbmodel,
              boneMap: swapTarget.boneMap,
              offset: swapTarget.offset,
              scale: swapTarget.scale,
              colorRgb: swapTarget.colorRgb || m.colorRgb || null,
            });
            chipHtml.push(`<span class="tag tag-emis" title="Подмена модели при шлеме (SWAP_MODEL)">${ico("refresh", "ico-sm")} ${escapeHtml(swapTarget.exportId)}</span>`);
            continue;
          }
        }
        if (mode === "PARTIAL_MODEL" && Array.isArray(m.hair3d.helmetHideCubes) && m.hair3d.helmetHideCubes.length > 0) {
          renderList.push({
            bbmodel: m.bbmodel,
            boneMap: m.boneMap,
            offset: m.offset,
            scale: m.scale,
            colorRgb: m.colorRgb || null,
            excludeCubeUuids: m.hair3d.helmetHideCubes,
          });
          chipHtml.push(`<span class="tag tag-emis" title="Частичное скрытие под шлемом">${escapeHtml(m.exportId)} [-${m.hair3d.helmetHideCubes.length} ч.]</span>`);
          continue;
        }
      }
      renderList.push({
        bbmodel: m.bbmodel,
        boneMap: m.boneMap,
        offset: m.offset,
        scale: m.scale,
        colorRgb: m.colorRgb || null,
      });
      chipHtml.push(`<span class="tag tag-chest">${escapeHtml(m.exportId)}</span>`);
    }

    if (state.testHelmetOn && !hasEquippedHelmet) {
      const demoHelmet = state.models.find(m => m.category === "helmets" || m.wardrobeSlot === "helmet_cosmetic");
      if (demoHelmet) {
        renderList.push({
          bbmodel: demoHelmet.bbmodel,
          boneMap: demoHelmet.boneMap,
          offset: demoHelmet.offset,
          scale: demoHelmet.scale,
          colorRgb: demoHelmet.colorRgb || null,
        });
        chipHtml.push(`<span class="tag tag-lock">${ico("hard-hat", "ico-sm")} ${escapeHtml(demoHelmet.exportId)} (тест)</span>`);
      }
    }

    await viewer.setModels(renderList);

    if (renderList.length === 0) {
      chips.innerHTML = `${skinBadgeHtml}<span class="hint">3D-модели сняты — показан 2D-скин</span>`;
      $("viewerBadge").textContent = `2D-скин (${bodyLbl} · ${active2dCount} сл.) · ЛКМ: вращение · Колесо: зум`;
    } else {
      chips.innerHTML = chipHtml.join("");
      $("viewerBadge").innerHTML = helmetActive
        ? `${ico("hard-hat", "ico-sm")}<span>2D (${active2dCount} сл.) + 3D (${renderList.length}) · Шлем надет (проверка 3D-волос)</span>`
        : `<span>2D (${active2dCount} сл.) + 3D (${renderList.length}) · ЛКМ: вращение · Колесо: зум</span>`;
    }
    renderFullPreviewPanel();
  }

  function renderAll3d() {
    renderModelList();
    renderInspector();
    renderLinksAndCombos();
    renderFullPreviewPanel();
  }

  function syncAutoBound3dHairFrom2d() {
    const selHair = state.skinSelected.hair;
    if (!selHair || !selHair.name) return;
    const norm = selHair.name.trim().toLowerCase().replace(/\.png$/i, "");
    const match = state.models.find(m => {
      if (!m.hair3d || !m.hair3d.bind2dHair) return false;
      const b = m.hair3d.bind2dHair.trim().toLowerCase().replace(/\.png$/i, "");
      return b === norm;
    });
    if (!match) return;
    for (const m of state.models) {
      if (m.category === "hair_3d" || m.wardrobeSlot === "hair_3d") {
        state.equippedIds.delete(m.id);
      }
    }
    state.equippedIds.add(match.id);
    renderModelList();
    refresh3dViewport();
  }

  // ==================== FULL SERVER ZIP EXPORT (CLIENT-SIDE!) ====================
  async function exportFullZip() {
    const include2d = $("chkInclude2dInZip").checked;
    const includeCfg = $("chkIncludeConfigInZip").checked;
    const includeMenus = $("chkIncludeMenusInZip") ? $("chkIncludeMenusInZip").checked : true;
    if (!state.models.length && !include2d && !includeMenus) {
      showToast("Сначала загрузите .bbmodel или включите 2D-скины / menus.yml!");
      return;
    }
    const btn = $("btnExportFullZip");
    btn.disabled = true;
    btn.innerHTML = `${ico("loader")}<span>Сборка ZIP...</span>`;

    try {
      const packName = $("inpPackName").value.trim() || "my_wardrobe_pack";
      const linksPayload = state.links.map(l => ({
        name: l.name,
        slot: l.slot,
        secondarySlot: l.secondarySlot,
        stripFrontStraps: l.stripFrontStraps,
        enabled: l.enabled,
        groups: l.categories.map(cat => state.models.filter(m => m.category === cat).map(m => m.id)),
      }));

      let skinBundle = null;
      if (include2d) {
        const overlays = [];
        for (const layer of state.skinLayers) {
          const sel = state.skinSelected[layer.id];
          if (!sel || !sel.dataUri) continue;
          const bInfo = state.skinLayerBlocks[layer.id] || {};
          const meta = state.skinItemMeta[layer.id] || {};
          overlays.push({
            layerId: layer.id,
            folder: layer.folder,
            filename: sel.name,
            pngBase64: sel.dataUri,
            mythicId: meta.mythicId || "",
            displayName: meta.displayName || `&b${sel.name.replace(/\.png$/i, "")} &7[${layer.label}]`,
            material: meta.material || "PAPER",
            customModelData: meta.customModelData || "",
            lore: meta.lore || [],
            blocks: bInfo.blocks || [],
            blockMode: bInfo.blockMode || "UNEQUIP",
          });
        }
        skinBundle = {
          include: true,
          priorityYaml: SE.generatePriorityYml(state.skinLayers.map(l => l.alias)),
          previewPngBase64: state.skinCompositeDataUri,
          overlays,
        };
      }

      const configSnippetYaml = includeCfg ? buildConfigSnippetBundle() : null;
      const menusYaml = includeMenus ? SE.generateMenusYml(state.menus) : null;

      const { blob, summary } = await SE.buildStudioPackZip({
        packName,
        includeStandalone: $("chkIncludeStandalone").checked,
        models: state.models,
        links: linksPayload,
        skinBundle,
        configSnippetYaml,
        menusYaml,
      });

      downloadBlob(blob, `${SE.sanitizeId(packName)}.zip`);
      $("exportReport").innerHTML = `<span style="color:var(--text)">${ico("check", "ico-sm")} Готово! В архиве: <b>${summary.modelFiles.length}</b> .bbmodel (из них <b>${summary.comboCount}</b> комбо), <b>${summary.itemCount}</b> команд выдачи + hair-3d.yml + menus.yml + priority.yml.</span>`;
      showToast("ZIP-архив собран локально и скачан!");
    } catch (err) {
      showToast(`Ошибка экспорта: ${err.message}`, 5000);
    } finally {
      btn.disabled = false;
      btn.innerHTML = `${ico("download")}<span>Собрать и скачать ZIP</span>`;
    }
  }

  // ==================== TAB 2: 2D SKIN FITTER & LIVE BLOCKING (v1.8.0) ====================
  function computeActive2dBlockMap() {
    // Returns Map<blockedLayerId, { byLayerId, byLabel, mode }>
    const blocked = new Map();
    for (const layer of state.skinLayers) {
      const sel = state.skinSelected[layer.id];
      if (!sel || !sel.dataUri) continue;
      const bInfo = state.skinLayerBlocks[layer.id];
      if (!bInfo || !bInfo.blocks || !bInfo.blocks.length) continue;
      const expanded = SE.expandBlockedTargets(layer.id, bInfo.blocks);
      for (const targetId of expanded) {
        if (!blocked.has(targetId)) {
          blocked.set(targetId, {
            byLayerId: layer.id,
            byLabel: layer.label,
            mode: bInfo.blockMode || "UNEQUIP",
          });
        }
      }
    }
    return blocked;
  }

  function loadDemo2dSkins(autoEquip = true) {
    const demo = window.STUDIO_DEMO_PACK;
    if (!demo || !demo.overlays) {
      showToast("Встроенные 2D-скины не найдены");
      return;
    }
    state.skinLibrary = demo.overlays;
    if (autoEquip) {
      const bodyKey = state.skinBodyType;
      state.skinSelected = {};
      state.skinLayerBlocks = {};
      const defaults = {
        body_skin:  "light.png",
        eyes:       "blue.png",
        eyebrows:   "dark_thick.png",
        hair:       "blonde_swept.png",
        undershirt: "white_tshirt.png",
        pants:      "blue_jeans.png",
        belt_skin:  "leather_belt.png",
        boots_skin: "combat_boots.png",
        jacket:     "black_hoodie.png",
      };
      for (const layer of state.skinLayers) {
        const list = (state.skinLibrary[layer.folder] && state.skinLibrary[layer.folder][bodyKey]) || [];
        const want = defaults[layer.id];
        if (!want) continue;
        const found = list.find(x => x.name === want) || list[0] || null;
        if (found) state.skinSelected[layer.id] = { name: found.name, dataUri: found.dataUri, isCustom: false };
      }
    }
    renderSkinLayersList();
    renderSkinBlockingEditor();
    renderSkinComposite();
    showToast("Загружены все 68 стартовых 2D-скинов (15 категорий + пояса + химзащита)!");
  }

  function tryHazmatSuitDemo() {
    if (!Object.keys(state.skinLibrary).length) {
      loadDemo2dSkins(true);
    }
    const bodyKey = state.skinBodyType;
    const jackets = (state.skinLibrary.jackets && state.skinLibrary.jackets[bodyKey]) || [];
    const hazmat = jackets.find(x => x.name === "hazmat_suit.png");
    if (!hazmat) {
      showToast("hazmat_suit.png не найден в демо-библиотеке");
      return;
    }
    // Equip a full set under it so user immediately sees all conflicting layers getting locked!
    const pick = (layerId, folder, fname) => {
      const arr = (state.skinLibrary[folder] && state.skinLibrary[folder][bodyKey]) || [];
      const f = arr.find(x => x.name === fname) || arr[0];
      if (f) state.skinSelected[layerId] = { name: f.name, dataUri: f.dataUri, isCustom: false };
    };
    pick("body_skin", "body_skin", "tone_light.png");
    pick("eyes", "eyes", "eyes_blue.png");
    pick("hair", "hairs", "hair_brown.png");
    pick("hat", "hats", "beanie_red.png");
    pick("undershirt", "undershirts", "shirt_white.png");
    pick("pants", "pants", "jeans_blue.png");
    pick("belt_skin", "belts", "utility_tactical.png");
    pick("boots_skin", "boots", "boots_combat.png");

    // Equip Hazmat Suit on jacket layer + attach v1.8.0 blocking rules!
    state.skinSelected.jacket = { name: hazmat.name, dataUri: hazmat.dataUri, isCustom: false };
    state.skinLayerBlocks.jacket = {
      blocks: [...HAZMAT_DEFAULT_BLOCKS],
      blockMode: "UNEQUIP",
    };
    state.activeSkinLayerId = "jacket";

    renderSkinLayersList();
    renderSkinBlockingEditor();
    renderSkinComposite();
    showToast("Надет Костюм химзащиты: 8 конфликтующих слоёв заблокированы и скрыты на 3D-манекене!", 4200);
  }

  function renderSkinLayersList() {
    const container = $("skinLayersList");
    container.innerHTML = "";
    const bodyKey = state.skinBodyType;
    const blockMap = computeActive2dBlockMap();

    const lockBadge = $("skinBlockedCountBadge");
    if (blockMap.size > 0) {
      lockBadge.style.display = "inline-flex";
      lockBadge.innerHTML = `${ico("lock", "ico-sm")}<span>Заблок.: ${blockMap.size}</span>`;
    } else {
      lockBadge.style.display = "none";
    }

    state.skinLayers.forEach((layer, idx) => {
      const libList = (state.skinLibrary[layer.folder] && state.skinLibrary[layer.folder][bodyKey]) || [];
      const sel = state.skinSelected[layer.id];
      const lockedBy = blockMap.get(layer.id);
      const ownBlocks = (state.skinLayerBlocks[layer.id]?.blocks || []).length;

      const row = document.createElement("div");
      row.className = "skin-layer-row"
        + (layer.id === state.activeSkinLayerId ? " active-layer" : "")
        + (lockedBy ? " locked-layer" : "");

      const options = [`<option value="">нет</option>`];
      if (sel && sel.isCustom) {
        options.push(`<option value="__custom__" selected>* ${escapeHtml(sel.name)}</option>`);
      }
      for (const item of libList) {
        const isSel = sel && !sel.isCustom && sel.name === item.name;
        options.push(`<option value="${escapeHtml(item.name)}" ${isSel ? "selected" : ""}>${escapeHtml(item.name.replace(/\.png$/i, ""))}</option>`);
      }

      const subLine = lockedBy
        ? `<span class="skin-layer-status">${ico("lock", "ico-sm")}<span>Заблок. (${escapeHtml(lockedBy.byLabel)})</span></span>`
        : ownBlocks > 0
          ? `<span class="skin-layer-status">${ico("shield", "ico-sm")}<span>Блокирует ${ownBlocks} сл.</span></span>`
          : `<span style="font-size:11px;color:var(--muted)">${escapeHtml(layer.alias)}</span>`;

      row.innerHTML = `
        <div class="skin-rank">${idx + 1}</div>
        <div class="skin-layer-meta" style="cursor:pointer" data-act="select">
          <div class="skin-layer-title">${escapeHtml(layer.label)}</div>
          ${subLine}
        </div>
        <select data-act="pick">${options.join("")}</select>
        <div class="row" style="gap:3px;justify-content:flex-end">
          <button class="btn btn-icon-xs" data-act="up" ${idx === 0 ? "disabled" : ""} title="Поднять выше" aria-label="Поднять слой выше">${ico("chevron-up", "ico-sm")}</button>
          <button class="btn btn-icon-xs" data-act="dn" ${idx === state.skinLayers.length - 1 ? "disabled" : ""} title="Опустить ниже" aria-label="Опустить слой ниже">${ico("chevron-down", "ico-sm")}</button>
        </div>
      `;

      row.querySelector('[data-act="select"]').addEventListener("click", () => {
        state.activeSkinLayerId = layer.id;
        $("activeSkinLayerTag").textContent = layer.label;
        renderSkinLayersList();
        renderSkinItemMetaEditor();
        renderSkinBlockingEditor();
      });

      row.querySelector('[data-act="pick"]').addEventListener("change", (e) => {
        const val = e.target.value;
        if (!val) {
          delete state.skinSelected[layer.id];
          if (layer.id === "jacket" && state.skinLayerBlocks.jacket?._autoHazmat) {
            delete state.skinLayerBlocks.jacket;
          }
        } else if (val !== "__custom__") {
          const found = libList.find(x => x.name === val);
          if (found) {
            state.skinSelected[layer.id] = { name: found.name, dataUri: found.dataUri, isCustom: false };
            // Auto-apply Hazmat blocking preset when selecting hazmat_suit.png!
            if (layer.id === "jacket" && found.name === "hazmat_suit.png") {
              state.skinLayerBlocks.jacket = { blocks: [...HAZMAT_DEFAULT_BLOCKS], blockMode: "UNEQUIP", _autoHazmat: true };
            } else if (layer.id === "jacket" && state.skinLayerBlocks.jacket?._autoHazmat) {
              delete state.skinLayerBlocks.jacket;
            }
          }
        }
        state.activeSkinLayerId = layer.id;
        $("activeSkinLayerTag").textContent = layer.label;
        renderSkinLayersList();
        renderSkinItemMetaEditor();
        renderSkinBlockingEditor();
        renderSkinComposite();
        if (layer.id === "hair") syncAutoBound3dHairFrom2d();
      });

      row.querySelector('[data-act="up"]').addEventListener("click", () => {
        if (idx <= 0) return;
        const t = state.skinLayers[idx - 1];
        state.skinLayers[idx - 1] = state.skinLayers[idx];
        state.skinLayers[idx] = t;
        renderSkinLayersList();
        renderSkinComposite();
      });
      row.querySelector('[data-act="dn"]').addEventListener("click", () => {
        if (idx >= state.skinLayers.length - 1) return;
        const t = state.skinLayers[idx + 1];
        state.skinLayers[idx + 1] = state.skinLayers[idx];
        state.skinLayers[idx] = t;
        renderSkinLayersList();
        renderSkinComposite();
      });
      container.appendChild(row);
    });

    const actLayer = state.skinLayers.find(l => l.id === state.activeSkinLayerId);
    if (actLayer) $("activeSkinLayerTag").textContent = actLayer.label;
  }

  function renderSkinItemMetaEditor() {
    const lid = state.activeSkinLayerId;
    const layer = state.skinLayers.find(l => l.id === lid);
    if (!layer || !$("skinMetaLayerTag")) return;
    $("skinMetaLayerTag").textContent = `${layer.label} (${layer.id})`;
    const sel = state.skinSelected[lid];
    const meta = state.skinItemMeta[lid] || {};
    const stem = sel && sel.name ? sel.name.replace(/\.png$/i, "") : layer.id;
    $("skinItemMythicId").value = meta.mythicId || "";
    $("skinItemMythicId").placeholder = SE.toPascalId(`wardrobe_${layer.id}_${stem}`);
    $("skinItemDisplay").value = meta.displayName || "";
    $("skinItemDisplay").placeholder = `&f${stem} &7[${layer.label}]`;
    $("skinItemMaterial").value = meta.material || "PAPER";
    $("skinItemCmd").value = meta.customModelData || "";
    $("skinItemLore").value = Array.isArray(meta.lore) ? meta.lore.join("\n") : "";
  }

  function renderSkinBlockingEditor() {
    const layer = state.skinLayers.find(l => l.id === state.activeSkinLayerId);
    if (!layer) return;
    $("skinBlockLayerLabel").textContent = `${layer.label} (${layer.id})`;
    if (!state.skinLayerBlocks[layer.id]) {
      state.skinLayerBlocks[layer.id] = { blocks: [], blockMode: "UNEQUIP" };
    }
    const cfg = state.skinLayerBlocks[layer.id];
    $("skinBlockMode").value = cfg.blockMode || "UNEQUIP";

    renderBlockChips(
      $("blockChips2dShortcuts"),
      $("blockChips2dTargets"),
      cfg.blocks,
      (targetId) => {
        cfg._autoHazmat = false;
        const idx = cfg.blocks.indexOf(targetId);
        if (idx >= 0) cfg.blocks.splice(idx, 1);
        else cfg.blocks.push(targetId);
        renderSkinLayersList();
        renderSkinBlockingEditor();
        renderSkinComposite();
      }
    );
  }

  async function renderSkinComposite() {
    const canvas = $("skin2dCanvas");
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, 64, 64);

    const blockMap = computeActive2dBlockMap();

    // Determine bottom-to-top draw order, skipping blocked layers & applying clear-outer-regions
    // (including inherited clear-outer-regions from blocked layers, matching v1.8.0 Java logic!)
    const activeLayers = [];
    const inheritedClearOuter = new Set();

    for (const layer of state.skinLayers) {
      if (blockMap.has(layer.id)) {
        // Blocked layers don't draw; their clearOuter regions are inherited by the blocker!
        (layer.clearOuter || []).forEach(r => inheritedClearOuter.add(r));
        continue;
      }
      const sel = state.skinSelected[layer.id];
      if (!sel || !sel.dataUri) continue;
      activeLayers.push({ layer, sel });
    }

    const drawOrder = [...activeLayers].reverse();

    if (!drawOrder.length) {
      ctx.fillStyle = "#e5b89b";
      ctx.fillRect(0, 0, 64, 64);
      state.skinCompositeDataUri = canvas.toDataURL("image/png");
      if (viewer) viewer.setSkinDataUri(state.skinCompositeDataUri, state.skinBodyType === "slim");
      renderFullPreviewPanel();
      return;
    }

    const loadImg = (src) => new Promise(res => {
      const im = new Image();
      im.onload = () => res(im);
      im.onerror = () => res(null);
      im.src = src;
    });

    for (const { layer, sel } of drawOrder) {
      const im = await loadImg(sel.dataUri);
      if (!im) continue;

      // Clear outer regions before drawing non-base layers
      if (!layer.replacesBase) {
        const toClear = new Set(layer.clearOuter || []);
        const ownBlocks = state.skinLayerBlocks[layer.id]?.blocks || [];
        if (ownBlocks.length) inheritedClearOuter.forEach(r => toClear.add(r));
        for (const regionKey of toClear) {
          const rect = SE.OUTER_UV_RECTS[regionKey];
          if (rect) ctx.clearRect(rect[0], rect[1], rect[2], rect[3]);
        }
      }
      ctx.drawImage(im, 0, 0, 64, 64);
    }

    state.skinCompositeDataUri = canvas.toDataURL("image/png");
    if (viewer) viewer.setSkinDataUri(state.skinCompositeDataUri, state.skinBodyType === "slim");
    renderFullPreviewPanel();
    const firstChip = $("equippedChips")?.querySelector(".tag");
    if (firstChip && firstChip.title === "Активный 2D-скин на персонаже") {
      const active2dCount = Object.keys(state.skinSelected).filter(k => state.skinSelected[k]?.dataUri).length;
      const bodyLbl = state.skinBodyType === "slim" ? "Slim" : "Wide";
      firstChip.innerHTML = `${ico("palette", "ico-sm")} 2D ${bodyLbl} (${active2dCount} сл.)`;
    }
  }

  async function processAndApplyUploadedSkin() {
    if (!state.rawUploadSkin) {
      const cur = state.skinSelected[state.activeSkinLayerId];
      if (!cur || !cur.dataUri) {
        showToast("Сначала загрузите PNG-файл или выберите слой из библиотеки");
        return;
      }
      state.rawUploadSkin = { name: cur.name, dataUri: cur.dataUri };
    }
    try {
      const res = await SE.processSkinLayer(state.rawUploadSkin.dataUri, {
        region: $("skinRegion").value,
        armConvert: $("skinArmConvert").value,
        hueShift: parseFloat($("skinHue").value) || 0,
        satMult: (parseFloat($("skinSat").value) || 100) / 100,
        valMult: (parseFloat($("skinVal").value) || 100) / 100,
        tintHex: $("skinTintColor").value,
        tintStrength: (parseFloat($("skinTintStrength").value) || 0) / 100,
      });
      const customName = ($("skinCustomName").value.trim() || state.rawUploadSkin.name || "custom_layer")
        .replace(/\.png$/i, "") + ".png";
      state.skinSelected[state.activeSkinLayerId] = {
        name: customName,
        dataUri: res.pngBase64,
        isCustom: true,
      };
      renderSkinLayersList();
      await renderSkinComposite();
      showToast(`Подгонка готова: ${res.originalSize} → ${res.note}`);
    } catch (err) {
      showToast(`Ошибка 2D-подгонки: ${err.message}`, 4500);
    }
  }

  function handleSkinFileUpload(file) {
    const reader = new FileReader();
    reader.onload = () => {
      state.rawUploadSkin = { name: file.name, dataUri: reader.result };
      $("skinCustomName").value = file.name.replace(/\.[^.]+$/, "");
      processAndApplyUploadedSkin();
    };
    reader.readAsDataURL(file);
  }

  async function export2dZip() {
    const overlays = [];
    for (const layer of state.skinLayers) {
      const sel = state.skinSelected[layer.id];
      if (!sel || !sel.dataUri) continue;
      const bInfo = state.skinLayerBlocks[layer.id] || {};
      const meta = state.skinItemMeta[layer.id] || {};
      overlays.push({
        layerId: layer.id,
        folder: layer.folder,
        filename: sel.name,
        pngBase64: sel.dataUri,
        mythicId: meta.mythicId || "",
        displayName: meta.displayName || `&b${sel.name.replace(/\.png$/i, "")} &7[${layer.label}]`,
        material: meta.material || "PAPER",
        customModelData: meta.customModelData || "",
        lore: meta.lore || [],
        blocks: bInfo.blocks || [],
        blockMode: bInfo.blockMode || "UNEQUIP",
      });
    }
    try {
      const { blob } = await SE.buildSkinPackZip({
        packName: "mythicwardrobe_2d_pack",
        order: state.skinLayers.map(l => l.alias),
        previewPngBase64: state.skinCompositeDataUri,
        overlays,
      });
      downloadBlob(blob, "mythicwardrobe_2d_pack.zip");
      showToast("2D ZIP-пак (скины + Lore + Blocks + priority.yml) скачан!");
    } catch (err) {
      showToast(`Ошибка 2D ZIP: ${err.message}`, 4500);
    }
  }

  // ==================== TAB 4: VISUAL GUI INVENTORY EDITOR (menus.yml + MC ASSETS) ====================
  const MC_COLOR_MAP = {
    "0": "#111114", "1": "#0000aa", "2": "#00aa00", "3": "#00aaaa",
    "4": "#aa0000", "5": "#aa00aa", "6": "#ffaa00", "7": "#aaaaaa",
    "8": "#555555", "9": "#5555ff", "a": "#55ff55", "b": "#55ffff",
    "c": "#ff5555", "d": "#ff55ff", "e": "#ffff55", "f": "#ffffff",
  };

  function getMcAssetUrl(material) {
    const assets = window.MC_ITEM_ASSETS || {};
    const key = String(material || "").trim().toUpperCase();
    return assets[key] || null;
  }

  function getSlotHintSprite(slotId) {
    const sprites = window.MC_SLOT_SPRITES || {};
    const s = String(slotId || "").toLowerCase();
    if (s.includes("helmet") || s.includes("head") || s.includes("hat") || s.includes("mask") || s.includes("hair")) return sprites.SLOT_HELMET || null;
    if (s.includes("chest") || s.includes("jacket") || s.includes("backpack") || s.includes("shirt") || s.includes("gloves")) return sprites.SLOT_CHESTPLATE || null;
    if (s.includes("legs") || s.includes("pants") || s.includes("belt")) return sprites.SLOT_LEGGINGS || null;
    if (s.includes("boots") || s.includes("feet") || s.includes("socks")) return sprites.SLOT_BOOTS || null;
    return null;
  }

  function renderMcFormattedHtml(text) {
    const raw = String(text || "");
    let html = "";
    let curColor = "#f5f5f5";
    let bold = false, italic = false;
    const openSpan = (txt) => {
      if (!txt) return "";
      let st = `color:${curColor};`;
      if (bold) st += "font-weight:700;";
      if (italic) st += "font-style:italic;";
      return `<span style="${st}">${escapeHtml(txt)}</span>`;
    };
    const tokens = raw.split(/(<#[0-9a-fA-F]{6}>|<\/?[a-z_]+>|&[0-9a-fk-orA-FK-OR])/g);
    for (const tok of tokens) {
      if (!tok) continue;
      if (/^<#[0-9a-fA-F]{6}>$/.test(tok)) {
        curColor = tok.slice(1, -1);
      } else if (/^&[0-9a-fA-F]$/.test(tok)) {
        curColor = MC_COLOR_MAP[tok[1].toLowerCase()] || "#f5f5f5";
        bold = false; italic = false;
      } else if (/^&[lL]$/.test(tok)) {
        bold = true;
      } else if (/^&[oO]$/.test(tok)) {
        italic = true;
      } else if (/^&[rR]$/.test(tok)) {
        curColor = "#f5f5f5"; bold = false; italic = false;
      } else if (/^<\/?[a-z_]+>$/i.test(tok)) {
        const tag = tok.slice(1, -1).toLowerCase();
        const mmColors = {
          white: "#ffffff", gray: "#aaaaaa", dark_gray: "#555555", black: "#111114",
          red: "#ff5555", green: "#55ff55", yellow: "#ffff55", gold: "#ffaa00",
          aqua: "#55ffff", blue: "#5555ff", light_purple: "#ff55ff",
        };
        if (mmColors[tag]) curColor = mmColors[tag];
      } else {
        html += openSpan(tok);
      }
    }
    return html || escapeHtml(raw);
  }

  function stripMcFormat(s) {
    return String(s || "")
      .replace(/<[^>]+>/g, "")
      .replace(/&[0-9a-fk-or]/gi, "")
      .trim();
  }

  function getGuiScreen() {
    return state.menus.screens[state.guiActiveScreen] || state.menus.screens.tabbed;
  }

  function renderGuiScreenList() {
    const container = $("guiScreenList");
    const pills = $("guiScreenPills");
    if (container) container.innerHTML = "";
    if (pills) pills.innerHTML = "";

    const shortLabels = {
      tabbed: "TABBED", unified: "UNIFIED", compact_hub: "HUB",
      compact_HEAD: "HEAD", compact_CHEST: "CHEST", compact_LEGS: "LEGS", compact_FEET: "FEET",
    };

    for (const [key, sc] of Object.entries(state.menus.screens)) {
      const onPick = () => {
        state.guiActiveScreen = key;
        const maxS = (sc.rows || 1) * 9 - 1;
        if (state.guiSelectedSlot > maxS) state.guiSelectedSlot = 0;
        renderGuiEditorAll();
      };
      if (container) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "gui-screen-btn" + (key === state.guiActiveScreen ? " active" : "");
        btn.innerHTML = `<span>${escapeHtml(sc.label || key)}</span><span class="tag">${sc.rows}×9</span>`;
        btn.addEventListener("click", onPick);
        container.appendChild(btn);
      }
      if (pills) {
        const p = document.createElement("button");
        p.type = "button";
        p.className = "gui-screen-pill" + (key === state.guiActiveScreen ? " active" : "");
        p.textContent = `${shortLabels[key] || key} (${sc.rows}×9)`;
        p.addEventListener("click", onPick);
        pills.appendChild(p);
      }
    }
  }

  function renderGuiScreenSettings() {
    const sc = getGuiScreen();
    $("guiDefaultMode").value = state.menus.defaultMode || "TABBED";
    if ($("guiBackFallback")) $("guiBackFallback").value = state.menus.backFallback || "INVENTORY";
    $("guiBackCmd").value = state.menus.backCommand || "";
    $("guiScreenTitle").value = sc.title || "";
    $("guiScreenRows").value = String(sc.rows || 3);
    $("guiFillerEnabled").checked = sc.fillerEnabled !== false;
    const f = sc.filler || {};
    $("guiFillerMat").value = f.material || "BLACK_STAINED_GLASS_PANE";
    $("guiFillerCmd").value = f.customModelData || "";
    $("guiFillerItemModel").value = f.itemModel || "";
    $("guiFillerName").value = f.name !== undefined ? f.name : " ";
    $("guiShowIndicators").checked = Boolean(sc.showIndicators);
  }

  function renderGuiTooltip(slotIdx) {
    const box = $("guiTooltipBox");
    if (!box) return;
    const sc = getGuiScreen();
    const cell = sc.cells && sc.cells[slotIdx];
    if (!cell) {
      const f = sc.filler || {};
      box.innerHTML = sc.fillerEnabled !== false
        ? `<div style="color:var(--text-2)">Слот #${slotIdx}: Заполнитель (<b>${escapeHtml(f.material || "BLACK_STAINED_GLASS_PANE")}</b>)</div><div class="mc-tooltip-meta">Кликните по слоту, чтобы назначить слот гардероба, кнопку или декор</div>`
        : `<div style="color:var(--muted)">Слот #${slotIdx}: Пустая ячейка (без заполнителя)</div>`;
      return;
    }
    const spec = cell.kind === "slot" ? (cell.emptyIcon || {}) : cell;
    const titleHtml = renderMcFormattedHtml(spec.name || (cell.kind === "slot" ? `&fСлот: ${cell.slotId}` : `&f${spec.material || "Предмет"}`));
    const loreLines = Array.isArray(spec.lore) ? spec.lore : [];
    const loreHtml = loreLines.map(l => `<div>${renderMcFormattedHtml(l)}</div>`).join("");
    const metaParts = [
      `Слот #${slotIdx}`,
      cell.kind === "slot" ? `Wardrobe: ${cell.slotId}` : `Action: ${cell.action || "DECOR"}`,
      `Material: ${spec.material || "PAPER"}`,
    ];
    if (spec.customModelData) metaParts.push(`CMD: ${spec.customModelData}`);
    if (spec.itemModel) metaParts.push(`ItemModel: ${spec.itemModel}`);
    box.innerHTML = `<div>${titleHtml}</div>${loreHtml}<div class="mc-tooltip-meta">${escapeHtml(metaParts.join(" · "))}</div>`;
  }

  function renderGuiGrid() {
    const sc = getGuiScreen();
    const grid = $("guiInvGrid");
    if (!grid) return;
    const rows = Math.max(1, Math.min(6, parseInt(sc.rows || 3, 10) || 3));
    const total = rows * 9;
    $("guiPreviewTitle").innerHTML = renderMcFormattedHtml(sc.title || "&fГардероб");
    $("guiGridSizeTag").textContent = `${rows}×9 (${total})`;

    const fillerUrl = sc.fillerEnabled !== false ? getMcAssetUrl(sc.filler && sc.filler.material) : null;

    grid.innerHTML = "";
    for (let i = 0; i < total; i++) {
      const cell = sc.cells && sc.cells[i];
      const el = document.createElement("div");
      const kind = cell ? cell.kind : "empty";
      el.className = `mc-slot kind-${kind}` + (state.guiSelectedSlot === i ? " selected" : "");

      let cmdVal = "";
      let iconHtml = "";
      let labelText = "";

      if (kind === "slot") {
        const iconSpec = cell.emptyIcon || {};
        cmdVal = iconSpec.customModelData || "";
        const imgUrl = iconSpec.previewDataUri || getMcAssetUrl(iconSpec.material);
        const hintSprite = getSlotHintSprite(cell.slotId);
        const glintCls = iconSpec.glint ? " is-glint" : "";
        iconHtml = (hintSprite ? `<img class="mc-slot-sprite-bg" src="${hintSprite}" alt="">` : "")
          + (imgUrl
            ? `<img class="mc-slot-img${glintCls}" src="${imgUrl}" alt="">`
            : `<span style="color:var(--muted)">${ico("box", "ico-md")}</span>`);
        labelText = cell.slotId || "slot";
      } else if (kind === "button" || kind === "decor") {
        cmdVal = cell.customModelData || "";
        const imgUrl = cell.previewDataUri || getMcAssetUrl(cell.material);
        const glintCls = cell.glint ? " is-glint" : "";
        iconHtml = imgUrl
          ? `<img class="mc-slot-img${glintCls}" src="${imgUrl}" alt="">`
          : `<span style="color:var(--muted)">${ico("cube", "ico-md")}</span>`;
        labelText = stripMcFormat(cell.name) || cell.action || "";
      } else {
        if (fillerUrl) {
          iconHtml = `<img class="mc-slot-img is-filler" src="${fillerUrl}" alt="">`;
        } else if (sc.fillerEnabled !== false) {
          iconHtml = `<span style="opacity:0.25;width:6px;height:6px;border-radius:1px;background:var(--muted)"></span>`;
        }
      }

      el.innerHTML = `
        <span class="mc-slot-num">${i}</span>
        ${cmdVal ? `<span class="mc-slot-cmd">#${escapeHtml(cmdVal)}</span>` : ""}
        ${iconHtml}
        ${labelText ? `<div class="mc-slot-label">${escapeHtml(labelText)}</div>` : ""}
        ${kind !== "empty" ? `<span class="mc-slot-kind-dot"></span>` : ""}
      `;
      el.addEventListener("mouseenter", () => renderGuiTooltip(i));
      el.addEventListener("click", () => {
        state.guiSelectedSlot = i;
        renderGuiGrid();
        renderGuiCellInspector();
        renderGuiTooltip(i);
      });
      grid.appendChild(el);
    }

    renderGuiTooltip(state.guiSelectedSlot);
    const yml = SE.generateMenusYml(state.menus);
    const livePre = $("guiLiveMenusYml");
    if (livePre) livePre.textContent = yml;
  }

  function renderGuiMcAssetPicker() {
    const grid = $("guiMcAssetGrid");
    if (!grid) return;
    const assets = window.MC_ITEM_ASSETS || {};
    const q = String(($("guiMcAssetSearch") && $("guiMcAssetSearch").value) || "").trim().toLowerCase();
    const curMat = String(($("guiCellMat") && $("guiCellMat").value) || "").trim().toUpperCase();

    const entries = Object.entries(assets).filter(([mat]) => !q || mat.toLowerCase().includes(q));
    if ($("guiMcAssetCount")) $("guiMcAssetCount").textContent = String(entries.length);

    grid.innerHTML = "";
    for (const [mat, url] of entries) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "mc-asset-btn" + (mat === curMat ? " active" : "");
      btn.title = mat;
      btn.innerHTML = `<img src="${url}" alt="${escapeHtml(mat)}">`;
      btn.addEventListener("click", () => {
        $("guiCellMat").value = mat;
        $("guiCellMat").dispatchEvent(new Event("input"));
        renderGuiMcAssetPicker();
      });
      grid.appendChild(btn);
    }
  }

  function renderGuiCellInspector() {
    const sc = getGuiScreen();
    const idx = state.guiSelectedSlot;
    $("guiSelectedSlotBadge").textContent = `Слот #${idx} (ряд ${Math.floor(idx / 9) + 1}, кол ${idx % 9 + 1})`;
    const cell = sc.cells && sc.cells[idx];
    const kind = cell ? cell.kind : "empty";
    $("guiCellKind").value = kind;

    $("guiCellSlotGroup").style.display = kind === "slot" ? "block" : "none";
    $("guiCellButtonGroup").style.display = kind === "button" ? "block" : "none";
    $("guiCellIconGroup").style.display = kind !== "empty" ? "block" : "none";

    if (kind === "slot") {
      $("guiCellWardrobeSlot").value = cell.slotId || "helmet_cosmetic";
      const ic = cell.emptyIcon || {};
      $("guiCellMat").value = ic.material || "PAPER";
      $("guiCellCmd").value = ic.customModelData || "";
      $("guiCellItemModel").value = ic.itemModel || "";
      $("guiCellColor").value = ic.colorHex || "";
      $("guiCellName").value = ic.name || "";
      $("guiCellLore").value = Array.isArray(ic.lore) ? ic.lore.join("\n") : "";
      $("guiCellGlint").checked = Boolean(ic.glint);
      $("guiCellHideTip").checked = Boolean(ic.hideTooltip);
      const previewUrl = ic.previewDataUri || getMcAssetUrl(ic.material);
      $("guiCellPreviewImgBox").innerHTML = previewUrl
        ? `<img src="${previewUrl}" alt="icon">`
        : `<span style="font-size:10px;color:var(--muted)">PNG</span>`;
    } else if (kind === "button" || kind === "decor") {
      if (kind === "button") {
        const act = cell.action || "BACK";
        $("guiCellAction").value = act;
        $("guiCellSectionRow").style.display = (act === "SWITCH_TAB" || act === "OPEN_SECTION") ? "block" : "none";
        $("guiCellCommandRow").style.display = act === "RUN_COMMAND" ? "block" : "none";
        $("guiCellSection").value = cell.section || "HEAD";
        $("guiCellCommand").value = cell.command || "";
      }
      $("guiCellMat").value = cell.material || "PAPER";
      $("guiCellCmd").value = cell.customModelData || "";
      $("guiCellItemModel").value = cell.itemModel || "";
      $("guiCellColor").value = cell.colorHex || "";
      $("guiCellName").value = cell.name || "";
      $("guiCellLore").value = Array.isArray(cell.lore) ? cell.lore.join("\n") : "";
      $("guiCellGlint").checked = Boolean(cell.glint);
      $("guiCellHideTip").checked = Boolean(cell.hideTooltip);
      const previewUrl = cell.previewDataUri || getMcAssetUrl(cell.material);
      $("guiCellPreviewImgBox").innerHTML = previewUrl
        ? `<img src="${previewUrl}" alt="icon">`
        : `<span style="font-size:10px;color:var(--muted)">PNG</span>`;
    }
    renderGuiMcAssetPicker();
  }

  function renderGuiEditorAll() {
    renderGuiScreenList();
    renderGuiScreenSettings();
    renderGuiGrid();
    renderGuiCellInspector();
  }

  function bindGuiEditorEvents() {
    // Populate dropdown catalogs & datalist with all 149 Minecraft materials
    $("guiCellWardrobeSlot").innerHTML = SE.GUI_SLOT_CATALOG.map(s =>
      `<option value="${s.id}">${escapeHtml(s.label)} (${s.id})</option>`
    ).join("");
    $("guiCellAction").innerHTML = SE.GUI_BUTTON_ACTIONS.map(a =>
      `<option value="${a.id}">${escapeHtml(a.label)}</option>`
    ).join("");
    if ($("guiMaterialPresets") && window.MC_ITEM_ASSETS) {
      $("guiMaterialPresets").innerHTML = Object.keys(window.MC_ITEM_ASSETS)
        .map(m => `<option value="${escapeHtml(m)}"></option>`).join("");
    }
    if ($("guiMcAssetSearch")) {
      $("guiMcAssetSearch").addEventListener("input", renderGuiMcAssetPicker);
    }

    const syncScreen = (fn) => {
      const sc = getGuiScreen();
      fn(sc);
      renderGuiScreenList();
      renderGuiGrid();
      if (state.activeTab === "configs") renderConfigOutputs();
    };
    $("guiDefaultMode").addEventListener("change", e => { state.menus.defaultMode = e.target.value; renderGuiGrid(); });
    if ($("guiBackFallback")) {
      $("guiBackFallback").addEventListener("change", e => { state.menus.backFallback = e.target.value; renderGuiGrid(); });
    }
    $("guiBackCmd").addEventListener("input", e => { state.menus.backCommand = e.target.value.trim(); renderGuiGrid(); });
    $("guiScreenTitle").addEventListener("input", e => syncScreen(sc => { sc.title = e.target.value; }));
    $("guiScreenRows").addEventListener("change", e => syncScreen(sc => {
      sc.rows = Math.max(1, Math.min(6, parseInt(e.target.value, 10) || 3));
      if (state.guiSelectedSlot >= sc.rows * 9) state.guiSelectedSlot = 0;
      renderGuiCellInspector();
    }));
    $("guiFillerEnabled").addEventListener("change", e => syncScreen(sc => { sc.fillerEnabled = e.target.checked; }));
    $("guiFillerMat").addEventListener("input", e => syncScreen(sc => { sc.filler.material = e.target.value.trim().toUpperCase() || "BLACK_STAINED_GLASS_PANE"; }));
    $("guiFillerCmd").addEventListener("input", e => syncScreen(sc => { sc.filler.customModelData = e.target.value.trim(); }));
    $("guiFillerItemModel").addEventListener("input", e => syncScreen(sc => { sc.filler.itemModel = e.target.value.trim(); }));
    $("guiFillerName").addEventListener("input", e => syncScreen(sc => { sc.filler.name = e.target.value; }));
    $("guiShowIndicators").addEventListener("change", e => syncScreen(sc => { sc.showIndicators = e.target.checked; }));

    const getOrCreateCellIconTarget = (sc, idx) => {
      const cell = sc.cells[idx];
      if (!cell) return null;
      if (cell.kind === "slot") {
        if (!cell.emptyIcon) cell.emptyIcon = { material: "PAPER", name: "", lore: [], customModelData: "", itemModel: "" };
        return cell.emptyIcon;
      }
      return cell;
    };

    $("guiCellKind").addEventListener("change", (e) => {
      const sc = getGuiScreen();
      const idx = state.guiSelectedSlot;
      const k = e.target.value;
      if (k === "empty") {
        delete sc.cells[idx];
      } else if (k === "slot") {
        const defSlot = SE.GUI_SLOT_CATALOG[0];
        sc.cells[idx] = {
          kind: "slot",
          slotId: defSlot.id,
          emptyIcon: {
            material: "PAPER",
            name: `&fПустой слот: ${defSlot.label}`,
            lore: ["&7Перетащите предмет сюда"],
            customModelData: "",
            itemModel: "",
            glint: false,
          },
        };
      } else if (k === "button") {
        sc.cells[idx] = {
          kind: "button",
          action: "RUN_COMMAND",
          command: "menu",
          section: "HEAD",
          material: "ARROW",
          name: "&fКнопка",
          lore: ["&7Нажмите, чтобы выполнить действие"],
          customModelData: "",
          itemModel: "",
          glint: false,
        };
      } else if (k === "decor") {
        sc.cells[idx] = {
          kind: "decor",
          action: "DECOR",
          material: "WHITE_STAINED_GLASS_PANE",
          name: " ",
          lore: [],
          customModelData: "",
          itemModel: "",
          hideTooltip: true,
          glint: false,
        };
      }
      renderGuiGrid();
      renderGuiCellInspector();
    });

    $("guiCellWardrobeSlot").addEventListener("change", e => {
      const sc = getGuiScreen();
      const cell = sc.cells[state.guiSelectedSlot];
      if (!cell || cell.kind !== "slot") return;
      cell.slotId = e.target.value;
      renderGuiGrid();
    });
    $("guiCellAction").addEventListener("change", e => {
      const sc = getGuiScreen();
      const cell = sc.cells[state.guiSelectedSlot];
      if (!cell || cell.kind !== "button") return;
      cell.action = e.target.value;
      renderGuiGrid();
      renderGuiCellInspector();
    });
    $("guiCellSection").addEventListener("change", e => {
      const sc = getGuiScreen();
      const cell = sc.cells[state.guiSelectedSlot];
      if (cell) cell.section = e.target.value;
      renderGuiGrid();
    });
    $("guiCellCommand").addEventListener("input", e => {
      const sc = getGuiScreen();
      const cell = sc.cells[state.guiSelectedSlot];
      if (cell) cell.command = e.target.value;
      renderGuiGrid();
    });

    const syncIconProp = (fn) => {
      const sc = getGuiScreen();
      const target = getOrCreateCellIconTarget(sc, state.guiSelectedSlot);
      if (!target) return;
      fn(target);
      renderGuiGrid();
    };
    $("guiCellMat").addEventListener("input", e => syncIconProp(t => {
      t.material = e.target.value.trim().toUpperCase() || "PAPER";
      const previewUrl = t.previewDataUri || getMcAssetUrl(t.material);
      if ($("guiCellPreviewImgBox")) {
        $("guiCellPreviewImgBox").innerHTML = previewUrl ? `<img src="${previewUrl}" alt="icon">` : `<span style="font-size:10px;color:var(--muted)">PNG</span>`;
      }
      renderGuiMcAssetPicker();
    }));
    $("guiCellCmd").addEventListener("input", e => syncIconProp(t => { t.customModelData = e.target.value.trim(); }));
    $("guiCellItemModel").addEventListener("input", e => syncIconProp(t => { t.itemModel = e.target.value.trim(); }));
    $("guiCellColor").addEventListener("input", e => syncIconProp(t => { t.colorHex = e.target.value.trim(); }));
    $("guiCellName").addEventListener("input", e => syncIconProp(t => { t.name = e.target.value; }));
    $("guiCellLore").addEventListener("input", e => syncIconProp(t => {
      t.lore = e.target.value.split("\n").filter(l => l.length > 0);
    }));
    $("guiCellGlint").addEventListener("change", e => syncIconProp(t => { t.glint = e.target.checked; }));
    $("guiCellHideTip").addEventListener("change", e => syncIconProp(t => { t.hideTooltip = e.target.checked; }));

    $("btnGuiUploadCellImg").addEventListener("click", () => $("inpGuiCellImgFile").click());
    $("inpGuiCellImgFile").addEventListener("change", (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        syncIconProp(t => { t.previewDataUri = reader.result; });
        renderGuiCellInspector();
      };
      reader.readAsDataURL(file);
      e.target.value = "";
    });
    $("btnGuiClearCellImg").addEventListener("click", () => {
      syncIconProp(t => { delete t.previewDataUri; });
      renderGuiCellInspector();
    });

    $("btnGuiClearCell").addEventListener("click", () => {
      const sc = getGuiScreen();
      delete sc.cells[state.guiSelectedSlot];
      renderGuiGrid();
      renderGuiCellInspector();
    });
    $("btnGuiResetAll").addEventListener("click", () => {
      state.menus = SE.createDefaultMenusState();
      state.guiActiveScreen = "tabbed";
      state.guiSelectedSlot = 11;
      renderGuiEditorAll();
      showToast("GUI меню сброшено к стандартному шаблону");
    });
    $("btnGuiCopyYml").addEventListener("click", () => copyTextToClipboard(SE.generateMenusYml(state.menus)));
    $("btnGuiDownloadYml").addEventListener("click", () => {
      const yml = SE.generateMenusYml(state.menus);
      downloadBlob(new Blob([yml], { type: "text/yaml;charset=utf-8" }), "menus.yml");
      showToast("Скачан готовый plugins/MythicWardrobe/menus.yml!");
    });
  }

  // ==================== TAB 3: v1.7 / v1.8 CONFIG GENERATOR ====================
  function buildConfigSnippetBundle() {
    const defer = $("cfgDeferGui").checked;
    const reopen = $("cfgReopenGui").checked;
    const debounce = parseInt($("cfgDebounceTicks").value, 10) || 8;
    const reopenTicks = parseInt($("cfgReopenTicks").value, 10) || 2;
    const menuMode = $("cfgMenuMode").value;
    const backCmd = $("cfgBackCommand").value.trim();
    const defBlockMode = $("cfgDefaultBlockMode").value;
    const lockMat = $("cfgLockMaterial").value.trim() || "RED_STAINED_GLASS_PANE";
    const inheritClear = $("cfgInheritClearOuter").checked;

    return [
      "# MythicWardrobe v1.8.0 - Фрагмент config.yml",
      "skin:",
      `  debounce-ticks: ${debounce}`,
      `  defer-apply-while-gui-open: ${defer}`,
      `  auto-reopen-gui-after-skin-apply: ${reopen}`,
      `  gui-reopen-delay-ticks: ${reopenTicks}`,
      "",
      "menu:",
      `  mode: ${menuMode}`,
      `  back-command: "${backCmd}"`,
      "",
      "blocking:",
      "  enabled: true",
      `  default-mode: ${defBlockMode}`,
      `  locked-slot-material: ${lockMat}`,
      `  inherit-clear-outer-regions: ${inheritClear}`,
      "",
    ].join("\n");
  }

  function renderConfigOutputs() {
    $("outConfigYml").textContent = buildConfigSnippetBundle();
    $("outMenusYml").textContent = SE.generateMenusYml(state.menus);
    if ($("outHair3dYml")) {
      $("outHair3dYml").textContent = SE.generateHair3DYml(state.models);
    }

    const suitId = $("cfgSuitId").value.trim() || "WardrobeHazmatSuit";
    const suitSlot = $("cfgSuitSlot").value;
    const suitDisp = $("cfgSuitDisplay").value.trim() || "&eКостюм химзащиты";
    const suitTex = $("cfgSuitTexture").value.trim() || "hazmat_suit.png";
    const suitMode = $("cfgSuitMode").value;
    const rawBlocks = $("cfgSuitBlocks").value.split(/[,;\s]+/).map(s => s.trim()).filter(Boolean);
    const is3d = suitSlot.endsWith("_cosmetic") || suitSlot.endsWith("_3d");

    const mmLines = [
      `# 1) MythicMobs YAML (plugins/MythicMobs/Items/WardrobeSuits.yml):`,
      `${suitId}:`,
      `  Material: ${is3d ? "LEATHER_CHESTPLATE" : "YELLOW_DYE"}`,
      `  Display: '${suitDisp.replace(/'/g, "''")}'`,
      `  Lore:`,
      `    - '&7Надевается в слот: &f${suitSlot}'`,
      `    - '&cБлокирует: &7${rawBlocks.join(", ")}'`,
      `  Wardrobe:`,
      `    Slot: ${suitSlot}`,
      ...(is3d ? [] : [`    Texture: ${suitTex}`]),
      `    Blocks:`,
      ...rawBlocks.map(b => `      - ${b}`),
      `    BlockMode: ${suitMode}`,
      "",
      `# 2) Или в plugins/MythicWardrobe/items.yml:`,
      `items:`,
      `  ${suitId}:`,
      `    slot: ${suitSlot}`,
      ...(is3d ? [] : [`    texture: "${suitTex}"`]),
      `    blocks: [${rawBlocks.join(", ")}]`,
      `    block-mode: ${suitMode}`,
      "",
      `# 3) Или прямо в игре на предмете в руке (PDC-тег, без перезагрузки!):`,
      `/wardrobe tag ${suitSlot}${is3d ? "" : " " + suitTex}`,
      `/wardrobe block ${suitMode} ${rawBlocks.join(" ")}`,
    ];
    $("outSuitYml").textContent = mmLines.join("\n");
  }

  function copyTextToClipboard(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        () => showToast("YAML скопирован в буфер обмена!"),
        () => fallbackCopy(text)
      );
    } else {
      fallbackCopy(text);
    }
  }

  function fallbackCopy(text) {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand("copy"); showToast("YAML скопирован в буфер обмена!"); }
    catch { showToast("Выделите текст и нажмите Ctrl+C"); }
    ta.remove();
  }

  // ==================== TAB 0: FULL 2D + 3D MODEL INSPECTOR ====================
  const PREVIEW_SLOT_ZONES = [
    { key: "HEAD",  title: "Голова · Шлемы · 3D-Маски · 3D-Причёски", cats: ["helmets", "mask_3d", "hair_3d"] },
    { key: "CHEST", title: "Торс · Нагрудники · 3D-Рюкзаки",          cats: ["armor_chest", "backpack_3d"] },
    { key: "LEGS",  title: "Ноги · Поножи · 3D-Пояса",                cats: ["armor_legs", "belt_3d"] },
    { key: "FEET",  title: "Обувь · Ботинки",                         cats: ["armor_feet"] },
  ];

  function syncViewerUiState() {
    if (viewer) {
      viewer.showMannequin = state.show2dSkin;
      viewer.setShowOuterSkin(state.show2dOuter);
      viewer.showArmor = state.show3dArmor;
      viewer.showGrid = state.showGrid;
      viewer.showBones = state.showBones;
      viewer.autoRotate = state.autoRotate;
      viewer.setPoseMode(state.poseMode);
      viewer.setLightMode(state.lightMode);
    }
    const setChk = (id, val) => { const el = $(id); if (el) el.checked = !!val; };
    setChk("chkShowMannequin", state.show2dSkin);
    setChk("pvShow2dSkin", state.show2dSkin);
    setChk("chkShowOuterSkin", state.show2dOuter);
    setChk("pvShow2dOuter", state.show2dOuter);
    setChk("chkShowArmor", state.show3dArmor);
    setChk("pvShow3dArmor", state.show3dArmor);
    setChk("chkWalkAnim", state.poseMode === "walk");
    setChk("chkAutoRotate", state.autoRotate);
    setChk("pvAutoRotate", state.autoRotate);
    setChk("pvShowGrid", state.showGrid);
    setChk("pvShowBones", state.showBones);

    document.querySelectorAll("#previewPoseGroup [data-pose]").forEach(btn => {
      btn.classList.toggle("active", btn.dataset.pose === state.poseMode);
    });
    document.querySelectorAll("#previewCamGroup [data-cam]").forEach(btn => {
      btn.classList.toggle("active", btn.dataset.cam === state.camPreset);
    });
    document.querySelectorAll("#previewLightGroup [data-light]").forEach(btn => {
      btn.classList.toggle("active", btn.dataset.light === state.lightMode);
    });

    const syncHelmetBtn = (id, smallIco) => {
      const btn = $(id);
      if (!btn) return;
      btn.classList.toggle("active", state.testHelmetOn);
      btn.innerHTML = state.testHelmetOn
        ? `${ico("hard-hat", smallIco ? "ico-sm" : "")}<span>Шлем надет</span>`
        : `${ico("hard-hat", smallIco ? "ico-sm" : "")}<span>Тест шлема</span>`;
    };
    syncHelmetBtn("btnToggleTestHelmet", false);
    syncHelmetBtn("btnPreviewTestHelmet", true);

    if ($("pvBodyType")) $("pvBodyType").value = state.skinBodyType;
    if ($("skinBodyType")) $("skinBodyType").value = state.skinBodyType;
  }

  function setViewerPose(pose) {
    state.poseMode = pose || "idle";
    syncViewerUiState();
  }

  function setViewerCam(cam) {
    state.camPreset = cam || "default";
    if (viewer) viewer.setViewPreset(state.camPreset);
    syncViewerUiState();
  }

  function setViewerLight(light) {
    state.lightMode = light || "studio";
    syncViewerUiState();
  }

  function setTestHelmetState(on) {
    state.testHelmetOn = Boolean(on);
    syncViewerUiState();
    refresh3dViewport();
  }

  function setBodyTypeEverywhere(bodyType) {
    state.skinBodyType = bodyType === "slim" ? "slim" : "wide";
    const bodyKey = state.skinBodyType;
    for (const layer of state.skinLayers) {
      const sel = state.skinSelected[layer.id];
      if (!sel || sel.isCustom) continue;
      const list = (state.skinLibrary[layer.folder] && state.skinLibrary[layer.folder][bodyKey]) || [];
      const match = list.find(x => x.name === sel.name);
      if (match) state.skinSelected[layer.id] = { name: match.name, dataUri: match.dataUri, isCustom: false };
    }
    syncViewerUiState();
    renderSkinLayersList();
    renderFullPreviewPanel();
    renderSkinComposite();
  }

  function equipBalanced3dSet() {
    if (!state.models.length) {
      showToast("Сначала загрузите .bbmodel или нажмите «Загрузить демо»");
      return;
    }
    state.activeComboPreview = null;
    state.equippedIds.clear();
    const demoSet = ["gold_warden_chest", "tactical_backpack", "belt_tactical_3d", "gold_warden_legs", "hair_ponytail_3d", "gold_warden_boots"];
    let matched = 0;
    for (const expId of demoSet) {
      const found = state.models.find(m => m.exportId === expId);
      if (found) { state.equippedIds.add(found.id); matched++; }
    }
    if (matched === 0) {
      const seenCats = new Set();
      for (const m of state.models) {
        if (!seenCats.has(m.category)) {
          seenCats.add(m.category);
          state.equippedIds.add(m.id);
        }
      }
    }
    renderModelList();
    renderLinksAndCombos();
    refresh3dViewport();
    showToast(`Надет полный 3D-комплект (${state.equippedIds.size} моделей)`);
  }

  function randomize2dSkin() {
    const bodyKey = state.skinBodyType;
    state.skinSelected = {};
    state.skinLayerBlocks = {};
    const mustPick = ["body_skin", "eyes", "eyebrows", "hair", "undershirt", "pants", "boots_skin"];
    const optionalPick = ["jacket", "belt_skin", "gloves", "hat", "mask_skin", "beard", "scars", "socks"];
    const pickRandom = (layer, allowHazmat = false) => {
      const lib = (state.skinLibrary[layer.folder] && state.skinLibrary[layer.folder][bodyKey]) || [];
      const pool = allowHazmat ? lib : lib.filter(x => x.name !== "hazmat_suit.png");
      if (!pool.length) return;
      const item = pool[Math.floor(Math.random() * pool.length)];
      state.skinSelected[layer.id] = { name: item.name, dataUri: item.dataUri, isCustom: false };
    };
    for (const lid of mustPick) {
      const layer = state.skinLayers.find(l => l.id === lid);
      if (layer) pickRandom(layer, false);
    }
    for (const lid of optionalPick) {
      if (Math.random() < 0.38) {
        const layer = state.skinLayers.find(l => l.id === lid);
        if (layer) pickRandom(layer, false);
      }
    }
    renderSkinLayersList();
    renderSkinItemMetaEditor();
    renderSkinBlockingEditor();
    renderSkinComposite();
    syncAutoBound3dHairFrom2d();
    showToast("Собрана случайная комбинация 2D-слоёв");
  }

  function renderFullPreviewPanel() {
    const groupsEl = $("preview3dSlotGroups");
    const list2dEl = $("preview2dQuickList");
    if (!groupsEl || !list2dEl) return;

    // ---- 1. 3D Equipment grouped by body zone ----
    const eqCount = state.activeComboPreview ? 1 : state.equippedIds.size;
    $("pv3dCountTag").textContent = state.activeComboPreview ? "Комбо 3D" : `${eqCount} надето`;

    if (!state.models.length) {
      groupsEl.innerHTML = `<div class="hint">3D-модели не загружены. Перейдите в «3D Модели» или нажмите «Загрузить демо».</div>`;
    } else {
      groupsEl.innerHTML = "";
      for (const zone of PREVIEW_SLOT_ZONES) {
        const modelsInZone = state.models.filter(m => zone.cats.includes(m.category));
        if (!modelsInZone.length) continue;

        const grp = document.createElement("div");
        grp.className = "preview-slot-group";
        const zoneEqCount = modelsInZone.filter(m => state.equippedIds.has(m.id)).length;
        grp.innerHTML = `
          <div class="preview-slot-head">
            <span>${escapeHtml(zone.title)}</span>
            <span>${zoneEqCount}/${modelsInZone.length}</span>
          </div>
          <div class="preview-3d-list"></div>
        `;
        const listEl = grp.querySelector(".preview-3d-list");
        for (const m of modelsInZone) {
          const isEq = !state.activeComboPreview && state.equippedIds.has(m.id);
          const cmeta = CATEGORY_META[m.category] || CATEGORY_META.armor_chest;
          const btn = document.createElement("button");
          btn.type = "button";
          btn.className = "preview-3d-item" + (isEq ? " equipped" : "");
          btn.innerHTML = `
            <span class="preview-3d-item-main">
              ${isEq ? ico("check", "ico-sm") : ico("cube", "ico-sm")}
              <span class="preview-3d-item-name" title="${escapeHtml(m.filename)}">${escapeHtml(m.exportId)}</span>
            </span>
            <span class="preview-3d-item-meta">
              <span class="tag">${escapeHtml(cmeta.label)}</span>
              ${m.analysis.hasEmissive ? `<span class="tag">_e</span>` : ""}
            </span>
          `;
          btn.addEventListener("click", () => {
            state.activeComboPreview = null;
            if (state.equippedIds.has(m.id)) state.equippedIds.delete(m.id);
            else state.equippedIds.add(m.id);
            renderModelList();
            renderLinksAndCombos();
            refresh3dViewport();
          });
          listEl.appendChild(btn);
        }
        groupsEl.appendChild(grp);
      }
    }

    // ---- 1b. Live Combos inside Inspector ----
    const comboSec = $("previewComboSection");
    const comboList = $("previewComboList");
    if (comboSec && comboList) {
      const allCombos = [];
      for (const link of state.links) {
        if (!link.enabled) continue;
        for (const pair of computeLinkCombos(link)) allCombos.push({ link, pair });
      }
      if (!allCombos.length) {
        comboSec.style.display = "none";
      } else {
        comboSec.style.display = "flex";
        comboList.innerHTML = "";
        for (const { link, pair } of allCombos.slice(0, 6)) {
          const label = pair.map(m => m.exportId).join(" + ");
          const key = `${link.slot}:${pair.map(m => m.id).join("+")}`;
          const isActive = state.activeComboPreview && state.activeComboPreview.key === key;
          const row = document.createElement("div");
          row.className = "combo-preview-row" + (isActive ? " active-preview" : "");
          row.innerHTML = `
            <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${escapeHtml(label)}">
              <span class="tag">${escapeHtml(link.slot)}</span> ${escapeHtml(label)}
            </span>
            <button type="button" class="btn btn-sm ${isActive ? "btn-green" : ""}">${isActive ? "Сбросить" : "Сшить"}</button>
          `;
          row.querySelector("button").addEventListener("click", () => {
            if (isActive) {
              state.activeComboPreview = null;
              renderLinksAndCombos();
              refresh3dViewport();
            } else {
              previewCombinedPair(key, link, pair);
            }
          });
          comboList.appendChild(row);
        }
      }
    }

    // ---- 2. 2D Skin Layers (15 layers) ----
    const bodyKey = state.skinBodyType;
    const blockMap = computeActive2dBlockMap();
    let active2dVisible = 0;

    list2dEl.innerHTML = "";
    for (const layer of state.skinLayers) {
      const sel = state.skinSelected[layer.id];
      const lockedBy = blockMap.get(layer.id);
      const hasVal = Boolean(sel && sel.dataUri);
      if (hasVal && !lockedBy) active2dVisible++;

      const libList = (state.skinLibrary[layer.folder] && state.skinLibrary[layer.folder][bodyKey]) || [];
      const row = document.createElement("div");
      row.className = "preview-2d-row" + (lockedBy ? " is-locked" : (hasVal ? " has-value" : ""));

      const opts = [`<option value="">— нет —</option>`];
      if (sel && sel.isCustom) {
        opts.push(`<option value="__custom__" selected>${escapeHtml(sel.name)} (свой)</option>`);
      }
      for (const item of libList) {
        const selectedAttr = (sel && !sel.isCustom && sel.name === item.name) ? "selected" : "";
        opts.push(`<option value="${escapeHtml(item.name)}" ${selectedAttr}>${escapeHtml(item.name.replace(/\.png$/i, ""))}</option>`);
      }

      const subText = lockedBy
        ? `${ico("lock", "ico-sm")}<span>Заблок. (${escapeHtml(lockedBy.byLabel)})</span>`
        : hasVal
          ? `<span>${escapeHtml(sel.name.replace(/\.png$/i, ""))}</span>`
          : `<span>${escapeHtml(layer.alias)}</span>`;

      row.innerHTML = `
        <div class="preview-2d-label">
          <span class="preview-2d-name">${escapeHtml(layer.label)}</span>
          <span class="preview-2d-sub">${subText}</span>
        </div>
        <select ${lockedBy ? "disabled" : ""} aria-label="Слой ${escapeHtml(layer.label)}">${opts.join("")}</select>
      `;

      row.querySelector("select").addEventListener("change", (e) => {
        const val = e.target.value;
        if (!val) {
          delete state.skinSelected[layer.id];
          if (layer.id === "jacket" && state.skinLayerBlocks.jacket?._autoHazmat) {
            delete state.skinLayerBlocks.jacket;
          }
        } else if (val !== "__custom__") {
          const found = libList.find(x => x.name === val);
          if (found) {
            state.skinSelected[layer.id] = { name: found.name, dataUri: found.dataUri, isCustom: false };
            if (layer.id === "jacket" && found.name === "hazmat_suit.png") {
              state.skinLayerBlocks.jacket = { blocks: [...HAZMAT_DEFAULT_BLOCKS], blockMode: "UNEQUIP", _autoHazmat: true };
            } else if (layer.id === "jacket" && state.skinLayerBlocks.jacket?._autoHazmat) {
              delete state.skinLayerBlocks.jacket;
            }
          }
        }
        state.activeSkinLayerId = layer.id;
        renderSkinLayersList();
        renderSkinItemMetaEditor();
        renderSkinBlockingEditor();
        renderSkinComposite();
        if (layer.id === "hair") syncAutoBound3dHairFrom2d();
      });

      list2dEl.appendChild(row);
    }

    $("pv2dCountTag").textContent = `${active2dVisible} / ${state.skinLayers.length}`;
    if ($("pvBodyType")) $("pvBodyType").value = state.skinBodyType;

    // ---- 3. Full Combined 2D + 3D Summary ----
    const eqModels = state.models.filter(m => state.equippedIds.has(m.id));
    const activeBones = new Set();
    let totalCubes = 0;
    if (state.activeComboPreview) {
      totalCubes = (state.activeComboPreview.bbmodel.elements || []).length;
      for (const grp of (state.activeComboPreview.bbmodel.outliner || [])) {
        if (grp && typeof grp === "object" && grp.name) activeBones.add(grp.name);
      }
    } else {
      for (const m of eqModels) {
        totalCubes += m.analysis.cubeCount || 0;
        for (const b of Object.values(m.boneMap || {})) {
          if (b && b !== "ignore") activeBones.add(b);
        }
        for (const b of (m.analysis.bones || [])) activeBones.add(b);
      }
    }

    $("pvBodyBadge").textContent = state.skinBodyType === "slim" ? "Slim (3px)" : "Wide (4px)";
    $("pvMetric2d").textContent = String(active2dVisible);
    $("pvMetric3d").textContent = String(state.activeComboPreview ? 1 : eqModels.length);
    $("pvMetricCubes").textContent = String(totalCubes);
    $("pvMetricBlocked").textContent = String(blockMap.size);

    const detailsEl = $("previewSummaryDetails");
    if (detailsEl) {
      const detailParts = [];
      const hair3dEq = eqModels.find(m => m.category === "hair_3d" || m.wardrobeSlot === "hair_3d");
      if (hair3dEq && hair3dEq.hair3d) {
        const modeMap = { HIDE_ALL: "Скрыть всё", PARTIAL_MODEL: "Скрыть часть", SWAP_MODEL: "Подмена модели", KEEP: "Оставить" };
        detailParts.push(`<div>3D-причёска: <strong>${escapeHtml(hair3dEq.exportId)}</strong> · при шлеме: <span class="tag">${escapeHtml(modeMap[hair3dEq.hair3d.helmetMode] || hair3dEq.hair3d.helmetMode)}</span></div>`);
      }
      if (activeBones.size > 0) {
        const chips = [...activeBones].slice(0, 10).map(b => `<span class="tag">${escapeHtml(b)}</span>`).join("");
        detailParts.push(`<div>Задействованные кости 3D:</div><div class="preview-bone-chips">${chips}</div>`);
      } else {
        detailParts.push(`<div class="hint">Сейчас отображается только 2D-скин. Включите любую 3D-модель выше, чтобы совместить 2D + 3D.</div>`);
      }
      detailsEl.innerHTML = detailParts.join("");
    }
  }

  function bindFullPreviewControls() {
    document.querySelectorAll("#previewPoseGroup [data-pose]").forEach(btn => {
      btn.addEventListener("click", () => setViewerPose(btn.dataset.pose));
    });
    document.querySelectorAll("#previewCamGroup [data-cam]").forEach(btn => {
      btn.addEventListener("click", () => setViewerCam(btn.dataset.cam));
    });
    document.querySelectorAll("#previewLightGroup [data-light]").forEach(btn => {
      btn.addEventListener("click", () => setViewerLight(btn.dataset.light));
    });

    const wireToggle = (id, key) => {
      const el = $(id);
      if (!el) return;
      el.addEventListener("change", (e) => {
        state[key] = e.target.checked;
        syncViewerUiState();
      });
    };
    wireToggle("pvShow2dSkin", "show2dSkin");
    wireToggle("pvShow2dOuter", "show2dOuter");
    wireToggle("pvShow3dArmor", "show3dArmor");
    wireToggle("pvShowGrid", "showGrid");
    wireToggle("pvShowBones", "showBones");
    wireToggle("pvAutoRotate", "autoRotate");

    $("btnCaptureFullPng").addEventListener("click", () => {
      if (!viewer || !viewer.captureFullCharacterPng) {
        showToast("WebGL-сцена недоступна");
        return;
      }
      const dataUrl = viewer.captureFullCharacterPng(true);
      if (!dataUrl) {
        showToast("Не удалось снять скриншот");
        return;
      }
      const a = document.createElement("a");
      a.href = dataUrl;
      a.download = `mythicwardrobe_character_2d_3d_${state.skinBodyType}.png`;
      a.click();
      showToast("Прозрачный PNG-скриншот полной модели (2D + 3D) сохранён!");
    });

    $("btnPreviewTestHelmet").addEventListener("click", () => setTestHelmetState(!state.testHelmetOn));
    $("btnPreviewEquipSet").addEventListener("click", equipBalanced3dSet);
    $("btnPreviewClear3d").addEventListener("click", () => {
      state.equippedIds.clear();
      state.activeComboPreview = null;
      setTestHelmetState(false);
      renderModelList();
      renderLinksAndCombos();
      refresh3dViewport();
    });

    $("pvBodyType").addEventListener("change", (e) => setBodyTypeEverywhere(e.target.value));
    $("btnPreviewRand2d").addEventListener("click", randomize2dSkin);
    $("btnPreviewHazmat").addEventListener("click", tryHazmatSuitDemo);
    $("btnPreviewDl2dPng").addEventListener("click", () => {
      if (!state.skinCompositeDataUri) return;
      const a = document.createElement("a");
      a.href = state.skinCompositeDataUri;
      a.download = `wardrobe_skin_${state.skinBodyType}.png`;
      a.click();
    });
    $("btnPreviewDlZip").addEventListener("click", exportFullZip);
  }

  // ==================== DROPZONE & EVENT WIRING ====================
  function setupDropzone(zoneEl, inputEl, onFiles) {
    zoneEl.addEventListener("click", () => inputEl.click());
    inputEl.addEventListener("change", () => {
      if (inputEl.files.length) onFiles(Array.from(inputEl.files));
      inputEl.value = "";
    });
    zoneEl.addEventListener("dragover", e => { e.preventDefault(); zoneEl.classList.add("dragover"); });
    zoneEl.addEventListener("dragleave", () => zoneEl.classList.remove("dragover"));
    zoneEl.addEventListener("drop", e => {
      e.preventDefault();
      zoneEl.classList.remove("dragover");
      if (e.dataTransfer.files.length) onFiles(Array.from(e.dataTransfer.files));
    });
  }

  function init() {
    mountSharedViewer("viewerMountPreview");
    try {
      viewer = new window.StudioWebGLViewer($("viewport3d"));
    } catch (err) {
      $("viewerBadge").textContent = "WebGL недоступен в этом браузере - 2D превью и сборка ZIP работают полностью!";
    }

    // Tabs
    document.querySelectorAll(".tab-btn").forEach(b => b.addEventListener("click", () => switchTab(b.dataset.tab)));

    // Topbar actions
    $("btnLoadDemo").addEventListener("click", () => {
      loadDemo3dModels();
      loadDemo2dSkins(true);
    });
    $("btnQuickExport").addEventListener("click", () => {
      if (state.activeTab === "skins") export2dZip();
      else exportFullZip();
    });

    // 3D Tab controls
    setupDropzone($("bbDropzone"), $("bbFileInput"), uploadBBModelFiles);
    $("btnLoadDemo3d").addEventListener("click", loadDemo3dModels);
    $("btnClear3d").addEventListener("click", () => {
      state.models = [];
      state.selectedModelId = null;
      state.equippedIds.clear();
      state.activeComboPreview = null;
      renderAll3d();
      refresh3dViewport();
    });
    $("filterCategory").addEventListener("change", renderModelList);
    $("btnAddLink").addEventListener("click", () => {
      state.links.push({
        id: "link_" + Date.now(),
        name: "Новая связка",
        slot: "CHEST",
        categories: ["armor_chest", "backpack_3d"],
        secondarySlot: "backpack_3d",
        stripFrontStraps: false,
        enabled: true,
      });
      renderLinksAndCombos();
    });
    $("btnExportFullZip").addEventListener("click", exportFullZip);
    bindInspectorEvents();

    // Shared Viewer header controls (bidirectionally synced with Tab 0 Inspector)
    $("chkShowMannequin").addEventListener("change", e => { state.show2dSkin = e.target.checked; syncViewerUiState(); });
    $("chkShowOuterSkin").addEventListener("change", e => { state.show2dOuter = e.target.checked; syncViewerUiState(); });
    $("chkShowArmor").addEventListener("change", e => { state.show3dArmor = e.target.checked; syncViewerUiState(); });
    $("chkWalkAnim").addEventListener("change", e => { setViewerPose(e.target.checked ? "walk" : "idle"); });
    $("chkAutoRotate").addEventListener("change", e => { state.autoRotate = e.target.checked; syncViewerUiState(); });
    $("btnToggleTestHelmet").addEventListener("click", () => setTestHelmetState(!state.testHelmetOn));
    $("btnViewFront").addEventListener("click", () => setViewerCam("front"));
    $("btnViewBack").addEventListener("click", () => setViewerCam("back"));
    $("btnViewReset").addEventListener("click", () => setViewerCam("default"));
    $("btnClearEquipped").addEventListener("click", () => {
      state.equippedIds.clear();
      state.activeComboPreview = null;
      setTestHelmetState(false);
      renderModelList();
      renderLinksAndCombos();
      refresh3dViewport();
    });

    // Tab 0 Full 2D+3D Inspector controls
    bindFullPreviewControls();

    // 2D Skin Tab controls
    setupDropzone($("skinDropzone"), $("skinFileInput"), files => handleSkinFileUpload(files[0]));
    $("btnLoadDemo2d").addEventListener("click", () => loadDemo2dSkins(true));
    $("btnTryHazmat").addEventListener("click", tryHazmatSuitDemo);
    $("btnReset2dLayers").addEventListener("click", () => {
      state.skinSelected = {};
      state.skinLayerBlocks = {};
      renderSkinLayersList();
      renderSkinBlockingEditor();
      renderSkinComposite();
      showToast("Все 2D-слои и блокировки сброшены");
    });

    $("skinBodyType").addEventListener("change", e => setBodyTypeEverywhere(e.target.value));

    const updateSliderLabels = () => {
      $("lblHue").textContent = `${$("skinHue").value}°`;
      $("lblSat").textContent = `${$("skinSat").value}%`;
      $("lblVal").textContent = `${$("skinVal").value}%`;
      $("lblTint").textContent = `${$("skinTintStrength").value}%`;
    };
    ["skinHue", "skinSat", "skinVal", "skinTintStrength"].forEach(id => $(id).addEventListener("input", updateSliderLabels));
    $("btnApplySkinFit").addEventListener("click", processAndApplyUploadedSkin);

    // v2.2: 2D Item Metadata (Display Name, Material, CMD & Lore)
    const sync2dMeta = (fn) => {
      const lid = state.activeSkinLayerId;
      if (!state.skinItemMeta[lid]) state.skinItemMeta[lid] = { mythicId: "", displayName: "", material: "PAPER", customModelData: "", lore: [] };
      fn(state.skinItemMeta[lid]);
    };
    $("skinItemMythicId").addEventListener("input", e => sync2dMeta(m => { m.mythicId = e.target.value.trim(); }));
    $("skinItemDisplay").addEventListener("input", e => sync2dMeta(m => { m.displayName = e.target.value; }));
    $("skinItemMaterial").addEventListener("input", e => sync2dMeta(m => { m.material = e.target.value.trim().toUpperCase() || "PAPER"; }));
    $("skinItemCmd").addEventListener("input", e => sync2dMeta(m => { m.customModelData = e.target.value.trim(); }));
    $("skinItemLore").addEventListener("input", e => sync2dMeta(m => {
      m.lore = e.target.value.split("\n").filter(l => l.length > 0);
    }));

    // v2.2: Visual Inventory GUI Editor
    bindGuiEditorEvents();

    // 2D Blocking Editor controls
    $("skinBlockMode").addEventListener("change", e => {
      if (!state.skinLayerBlocks[state.activeSkinLayerId]) {
        state.skinLayerBlocks[state.activeSkinLayerId] = { blocks: [], blockMode: e.target.value };
      } else {
        state.skinLayerBlocks[state.activeSkinLayerId].blockMode = e.target.value;
      }
      renderSkinLayersList();
    });
    $("btnPresetHazmatBlocks").addEventListener("click", () => {
      state.skinLayerBlocks[state.activeSkinLayerId] = { blocks: [...HAZMAT_DEFAULT_BLOCKS], blockMode: "UNEQUIP" };
      renderSkinLayersList();
      renderSkinBlockingEditor();
      renderSkinComposite();
      showToast("Применён пресет «Костюм химзащиты» (8 слоёв)");
    });
    $("btnPresetSuitBlocks").addEventListener("click", () => {
      state.skinLayerBlocks[state.activeSkinLayerId] = { blocks: ["suit"], blockMode: "UNEQUIP" };
      renderSkinLayersList();
      renderSkinBlockingEditor();
      renderSkinComposite();
      showToast("Применён пресет «Комбинезон» (suit)");
    });
    $("btnClearSkinBlocks").addEventListener("click", () => {
      state.skinLayerBlocks[state.activeSkinLayerId] = { blocks: [], blockMode: "UNEQUIP" };
      renderSkinLayersList();
      renderSkinBlockingEditor();
      renderSkinComposite();
    });

    $("btnDownloadSkinPng").addEventListener("click", () => {
      if (!state.skinCompositeDataUri) return;
      const a = document.createElement("a");
      a.href = state.skinCompositeDataUri;
      a.download = `wardrobe_skin_${state.skinBodyType}.png`;
      a.click();
    });
    $("btnExport2dZip").addEventListener("click", export2dZip);

    // Tab 3 Config generator bindings
    [
      "cfgDeferGui", "cfgReopenGui", "cfgDebounceTicks", "cfgReopenTicks",
      "cfgMenuMode", "cfgBackCommand", "cfgDefaultBlockMode", "cfgLockMaterial",
      "cfgInheritClearOuter", "cfgSuitId", "cfgSuitSlot", "cfgSuitDisplay",
      "cfgSuitTexture", "cfgSuitMode", "cfgSuitBlocks",
    ].forEach(id => {
      const el = $(id);
      el.addEventListener("input", renderConfigOutputs);
      el.addEventListener("change", renderConfigOutputs);
    });
    document.querySelectorAll("[data-copy-target]").forEach(btn => {
      btn.addEventListener("click", () => {
        const target = $(btn.dataset.copyTarget);
        if (target) copyTextToClipboard(target.textContent);
      });
    });

    // Initial render + auto-load embedded demos right on startup so user immediately sees 2D + 3D together!
    renderAll3d();
    renderSkinLayersList();
    renderSkinItemMetaEditor();
    renderSkinBlockingEditor();
    renderGuiEditorAll();
    renderConfigOutputs();
    if (window.STUDIO_DEMO_PACK) {
      loadDemo2dSkins(true);
      loadDemo3dModels();
      renderSkinItemMetaEditor();
      renderConfigOutputs();
    }
    syncViewerUiState();
    renderFullPreviewPanel();
    if (viewer) setTimeout(() => viewer.resize(), 60);
  }

  window.addEventListener("DOMContentLoaded", init);
})();
