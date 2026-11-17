/**
 * MythicWardrobe Studio v2.0 - 100% Client-Side Engine (Zero Python / Zero Server / Zero CDN!)
 * Runs directly via file:// in any browser or inside sandboxed iframes.
 *
 * Includes:
 *   - Built-in RFC-1951/PKZIP Store ZIP generator with CRC32 & UTF-8 filenames
 *   - .bbmodel Analyzer & MythicArmors 9-bone auto-mapper (CamelCase + aliases + spatial coords)
 *   - N-way .bbmodel UV Atlas Stitcher on HTML5 Canvas (preserving _e.png glow & _t.png tint masks)
 *   - 2D Skin Auto-Fitter on HTML5 Canvas (64x32->64x64, HD->64x64, Wide<->Slim, Region Cutter, HSV/Tint)
 *   - Full v1.7.0 + v1.8.0 feature support: belt_3d, belt_skin, Layer/Slot Blocking (Blocks + BlockMode),
 *     GUI-safe skin apply & Back-button config generator!
 */
(function (global) {
  "use strict";

  // ==================== 1. CONSTANTS & METADATA ====================
  const CANONICAL_BONES = {
    head:       { pivot: [0, 24, 0],  slot: "HEAD",  label: "Голова (head)" },
    body:       { pivot: [0, 24, 0],  slot: "CHEST", label: "Торс (body)" },
    right_arm:  { pivot: [5, 22, 0],  slot: "CHEST", label: "Правая рука (right_arm)" },
    left_arm:   { pivot: [-5, 22, 0], slot: "CHEST", label: "Левая рука (left_arm)" },
    waist:      { pivot: [0, 12, 0],  slot: "LEGS",  label: "Пояс (waist)" },
    right_leg:  { pivot: [2, 12, 0],  slot: "LEGS",  label: "Правая нога (right_leg)" },
    left_leg:   { pivot: [-2, 12, 0], slot: "LEGS",  label: "Левая нога (left_leg)" },
    right_foot: { pivot: [2, 12, 0],  slot: "FEET",  label: "Правая ступня (right_foot)" },
    left_foot:  { pivot: [-2, 12, 0], slot: "FEET",  label: "Левая ступня (left_foot)" },
  };

  const SLOT_BONES = {
    HEAD:  ["head"],
    CHEST: ["body", "right_arm", "left_arm"],
    LEGS:  ["waist", "right_leg", "left_leg"],
    FEET:  ["right_foot", "left_foot"],
  };

  const SLOT_SUFFIX = {
    HEAD:  "head_piece",
    CHEST: "chest_piece",
    LEGS:  "legs_piece",
    FEET:  "feet_piece",
  };

  const SLOT_DEFAULT_MATERIAL = {
    HEAD:  "LEATHER_HELMET",
    CHEST: "LEATHER_CHESTPLATE",
    LEGS:  "LEATHER_LEGGINGS",
    FEET:  "LEATHER_BOOTS",
  };

  const ICON_KEYWORDS = ["helmet", "chestplate", "leggings", "boots"];

  // All 15 MythicWardrobe v1.7+ / v1.8+ 2D skin layers (top-to-bottom priority.yml order!)
  const DEFAULT_SKIN_LAYERS = [
    { id: "hat",        folder: "hats",        label: "Шапка (2D)",         alias: "hat",        clearOuter: ["HEAD_OUTER"] },
    { id: "mask_skin",  folder: "masks",       label: "Маска (2D)",         alias: "mask",       clearOuter: [] },
    { id: "hair",       folder: "hairs",       label: "Причёска (2D)",      alias: "hair",       clearOuter: ["HEAD_OUTER"] },
    { id: "eyebrows",   folder: "eyebrows",    label: "Брови",              alias: "brows",      clearOuter: [] },
    { id: "eyes",       folder: "eyes",        label: "Глаза",              alias: "eyes",       clearOuter: [] },
    { id: "beard",      folder: "beards",      label: "Борода / Усы",       alias: "beard",      clearOuter: [] },
    { id: "gloves",     folder: "gloves",      label: "Перчатки",           alias: "gloves",     clearOuter: [] },
    { id: "jacket",     folder: "jackets",     label: "Куртка / Костюм",    alias: "jacket",     clearOuter: ["BODY_OUTER", "RIGHT_ARM_OUTER", "LEFT_ARM_OUTER"] },
    { id: "undershirt", folder: "undershirts", label: "Футболка / Майка",   alias: "undershirt", clearOuter: ["BODY_OUTER"] },
    { id: "belt_skin",  folder: "belts",       label: "Пояс / Ремень (2D)", alias: "belt",       clearOuter: [] },
    { id: "boots_skin", folder: "boots",       label: "Ботинки (2D)",       alias: "boots_skin", clearOuter: [] },
    { id: "pants",      folder: "pants",       label: "Штаны",              alias: "pants",      clearOuter: ["RIGHT_LEG_OUTER", "LEFT_LEG_OUTER"] },
    { id: "socks",      folder: "socks",       label: "Носки",              alias: "socks",      clearOuter: [] },
    { id: "scars",      folder: "scars",       label: "Шрамы / Тату",       alias: "scars",      clearOuter: [] },
    { id: "body_skin",  folder: "body_skin",   label: "Тело / Тон кожи",    alias: "body",       clearOuter: [], replacesBase: true },
  ];

  const SKIN_SLOT_IDS = ["hat", "mask_skin", "gloves", "jacket", "undershirt", "belt_skin", "boots_skin", "pants", "socks"];
  const DISPLAY_3D_SLOT_IDS = ["hair_3d", "mask_3d", "backpack_3d", "belt_3d"];
  const COSMETIC_SLOT_IDS = ["helmet_cosmetic", "chest_cosmetic", "legs_cosmetic", "boots_cosmetic"];
  const REAL_ARMOR_SLOT_IDS = ["helmet_real", "chest_real", "legs_real", "boots_real"];

  const BLOCKABLE_CHOICES = [
    { id: "undershirt",      label: "Футболка (2D)",         group: "2D" },
    { id: "pants",           label: "Штаны (2D)",            group: "2D" },
    { id: "belt_skin",       label: "Пояс (2D)",             group: "2D" },
    { id: "gloves",          label: "Перчатки (2D)",         group: "2D" },
    { id: "socks",           label: "Носки (2D)",            group: "2D" },
    { id: "boots_skin",      label: "Ботинки (2D)",          group: "2D" },
    { id: "mask_skin",       label: "Маска (2D)",            group: "2D" },
    { id: "hat",             label: "Шапка (2D)",            group: "2D" },
    { id: "jacket",          label: "Куртка (2D)",           group: "2D" },
    { id: "hair",            label: "Волосы (2D)",           group: "2D" },
    { id: "helmet_real",     label: "Шлем (3D Броня)",       group: "3D Броня" },
    { id: "chest_real",      label: "Нагрудник (3D Броня)",  group: "3D Броня" },
    { id: "legs_real",       label: "Поножи (3D Броня)",     group: "3D Броня" },
    { id: "boots_real",      label: "Ботинки (3D Броня)",    group: "3D Броня" },
    { id: "helmet_cosmetic", label: "Шлем (3D Космет.)",     group: "3D Космет." },
    { id: "chest_cosmetic",  label: "Нагрудник (3D Космет.)",group: "3D Космет." },
    { id: "legs_cosmetic",   label: "Поножи (3D Космет.)",   group: "3D Космет." },
    { id: "boots_cosmetic",  label: "Ботинки (3D Космет.)",  group: "3D Космет." },
    { id: "hair_3d",         label: "Причёска (3D)",         group: "3D Аксесс." },
    { id: "mask_3d",         label: "Маска (3D)",            group: "3D Аксесс." },
    { id: "backpack_3d",     label: "Рюкзак (3D)",           group: "3D Аксесс." },
    { id: "belt_3d",         label: "Пояс / Декор (3D)",     group: "3D Аксесс." },
  ];

  const BLOCK_SHORTCUTS = [
    { id: "all_clothing", label: "all_clothing (Вся 2D-одежда)" },
    { id: "suit",         label: "suit (Комбинезон: торс+ноги+руки)" },
    { id: "all_3d",       label: "all_3d (Все 3D-аксессуары)" },
    { id: "all_real",     label: "all_real (Вся реальная 3D-броня)" },
    { id: "all_cosmetic", label: "all_cosmetic (Вся 3D-косметика)" },
    { id: "all",          label: "all (Все остальные слоты)" },
  ];

  // Minecraft 64x64 UV rectangles [x0, y0, x1_excl, y1_excl]
  const REGION_RECTS = {
    full:       [[0, 0, 64, 64]],
    head_all:   [[0, 0, 64, 16]],
    head_inner: [[0, 0, 32, 16]],
    head_outer: [[32, 0, 64, 16]],
    torso_arms: [
      [16, 16, 40, 32], [16, 32, 40, 48],
      [40, 16, 56, 32], [40, 32, 56, 48],
      [32, 48, 48, 64], [48, 48, 64, 64],
    ],
    torso_only: [[16, 16, 40, 32], [16, 32, 40, 48]],
    arms_only:  [[40, 16, 56, 32], [40, 32, 56, 48], [32, 48, 48, 64], [48, 48, 64, 64]],
    waist_belt: [
      [16, 28, 40, 32], [16, 44, 40, 48],
      [0, 20, 16, 25],  [0, 36, 16, 42],
      [16, 52, 32, 57], [0, 52, 16, 58],
    ],
    legs:       [[0, 16, 16, 32], [0, 32, 16, 48], [16, 48, 32, 64], [0, 48, 16, 64]],
    boots: [
      [0, 26, 16, 32], [8, 16, 12, 20],
      [0, 42, 16, 48], [8, 32, 12, 36],
      [16, 58, 32, 64], [24, 48, 28, 52],
      [0, 58, 16, 64], [8, 48, 12, 52],
    ],
    outer_only: [[32, 0, 64, 16], [0, 32, 56, 48], [0, 48, 16, 64], [48, 48, 64, 64]],
  };

  const OUTER_UV_RECTS = {
    HEAD_OUTER:      [32, 0, 32, 16],
    BODY_OUTER:      [16, 32, 24, 16],
    RIGHT_ARM_OUTER: [40, 32, 16, 16],
    LEFT_ARM_OUTER:  [48, 48, 16, 16],
    RIGHT_LEG_OUTER: [0, 32, 16, 16],
    LEFT_LEG_OUTER:  [0, 48, 16, 16],
  };

  // ==================== 2. STRING / UUID / ZIP HELPERS ====================
  function sanitizeId(name, fallback = "model") {
    const s = String(name || "")
      .trim()
      .replace(/\.bbmodel$/i, "")
      .replace(/[^a-zA-Z0-9_]+/g, "_")
      .replace(/^_+|_+$/g, "")
      .toLowerCase();
    return s || fallback;
  }

  function toPascalId(name) {
    const parts = String(name || "").split(/[^a-zA-Z0-9]+/).filter(Boolean);
    return parts.map(p => p[0].toUpperCase() + p.slice(1)).join("") || "CustomItem";
  }

  function yamlEscape(s) {
    return String(s || "").replace(/'/g, "''");
  }

  function genUuid() {
    if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
    });
  }

  function deepClone(obj) {
    return JSON.parse(JSON.stringify(obj));
  }

  function dataUriToUint8Array(dataUri) {
    const b64 = String(dataUri || "").includes(",") ? dataUri.split(",", 2)[1] : String(dataUri || "");
    const bin = atob(b64);
    const arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    return arr;
  }

  // Zero-dependency PKZIP Store Writer with CRC32 & UTF-8 filenames
  const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
      t[i] = c >>> 0;
    }
    return t;
  })();

  function crc32(bytes) {
    let c = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }

  class StudioZip {
    constructor() {
      this.files = [];
      this.encoder = new TextEncoder();
    }
    addText(path, text) {
      this.addBytes(path, this.encoder.encode(String(text || "")));
    }
    addBytes(path, uint8) {
      const cleanPath = String(path).replace(/^\/+/, "");
      const nameBytes = this.encoder.encode(cleanPath);
      const data = uint8 instanceof Uint8Array ? uint8 : new Uint8Array(uint8);
      this.files.push({ nameBytes, data, crc: crc32(data) });
    }
    toUint8Array() {
      const localChunks = [];
      const centralChunks = [];
      let offset = 0;
      const now = new Date();
      const dosTime = ((now.getHours() & 31) << 11) | ((now.getMinutes() & 63) << 5) | ((now.getSeconds() >> 1) & 31);
      const dosDate = (((Math.max(1980, now.getFullYear()) - 1980) & 127) << 9) | (((now.getMonth() + 1) & 15) << 5) | (now.getDate() & 31);

      for (const f of this.files) {
        const lh = new Uint8Array(30 + f.nameBytes.length);
        const lv = new DataView(lh.buffer);
        lv.setUint32(0, 0x04034b50, true);
        lv.setUint16(4, 20, true);          // version needed
        lv.setUint16(6, 0x0800, true);      // UTF-8 filename flag
        lv.setUint16(8, 0, true);           // method 0 = Store
        lv.setUint16(10, dosTime, true);
        lv.setUint16(12, dosDate, true);
        lv.setUint32(14, f.crc, true);
        lv.setUint32(18, f.data.length, true);
        lv.setUint32(22, f.data.length, true);
        lv.setUint16(26, f.nameBytes.length, true);
        lv.setUint16(28, 0, true);
        lh.set(f.nameBytes, 30);
        localChunks.push(lh, f.data);

        const ch = new Uint8Array(46 + f.nameBytes.length);
        const cv = new DataView(ch.buffer);
        cv.setUint32(0, 0x02014b50, true);
        cv.setUint16(4, 20, true);
        cv.setUint16(6, 20, true);
        cv.setUint16(8, 0x0800, true);
        cv.setUint16(10, 0, true);
        cv.setUint16(12, dosTime, true);
        cv.setUint16(14, dosDate, true);
        cv.setUint32(16, f.crc, true);
        cv.setUint32(20, f.data.length, true);
        cv.setUint32(24, f.data.length, true);
        cv.setUint16(28, f.nameBytes.length, true);
        cv.setUint16(30, 0, true);
        cv.setUint16(32, 0, true);
        cv.setUint16(34, 0, true);
        cv.setUint16(36, 0, true);
        cv.setUint32(38, 0, true);
        cv.setUint32(42, offset, true);
        ch.set(f.nameBytes, 46);
        centralChunks.push(ch);

        offset += lh.length + f.data.length;
      }

      let centralSize = 0;
      for (const c of centralChunks) centralSize += c.length;

      const eocd = new Uint8Array(22);
      const ev = new DataView(eocd.buffer);
      ev.setUint32(0, 0x06054b50, true);
      ev.setUint16(4, 0, true);
      ev.setUint16(6, 0, true);
      ev.setUint16(8, this.files.length, true);
      ev.setUint16(10, this.files.length, true);
      ev.setUint32(12, centralSize, true);
      ev.setUint32(16, offset, true);
      ev.setUint16(20, 0, true);

      const total = offset + centralSize + eocd.length;
      const out = new Uint8Array(total);
      let pos = 0;
      for (const chunk of localChunks) { out.set(chunk, pos); pos += chunk.length; }
      for (const chunk of centralChunks) { out.set(chunk, pos); pos += chunk.length; }
      out.set(eocd, pos);
      return out;
    }
    toBlob() {
      return new Blob([this.toUint8Array()], { type: "application/zip" });
    }
  }

  // ==================== 3. CANVAS IMAGE HELPERS ====================
  function makeCanvas(w, h) {
    const c = document.createElement("canvas");
    c.width = w;
    c.height = h;
    return c;
  }

  function loadImageFromDataUri(src) {
    return new Promise((resolve) => {
      if (!src || !String(src).includes(",")) return resolve(null);
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => resolve(null);
      img.src = src;
    });
  }

  function imageToCanvas(img, targetW, targetH) {
    const w = targetW || img.width;
    const h = targetH || img.height;
    const c = makeCanvas(w, h);
    const ctx = c.getContext("2d");
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(img, 0, 0, w, h);
    return c;
  }

  function blankCanvas(w, h, rgba) {
    const c = makeCanvas(w, h);
    if (rgba) {
      const ctx = c.getContext("2d");
      ctx.fillStyle = rgba;
      ctx.fillRect(0, 0, w, h);
    }
    return c;
  }

  // ==================== 4. BLOCKING RULES ENGINE (v1.8.0) ====================
  const LAYER_ALIASES = {
    body: "body_skin", skin: "body_skin",
    brows: "eyebrows", brow: "eyebrows",
    mask: "mask_skin", masks: "mask_skin",
    jackets: "jacket", coat: "jacket",
    shirt: "undershirt", tshirt: "undershirt",
    belt: "belt_skin", belts: "belt_skin", sash: "belt_skin", waist_skin: "belt_skin",
    boots: "boots_skin", shoes: "boots_skin",
    hairs: "hair", hats: "hat",
  };

  function resolveLayerId(raw) {
    const k = String(raw || "").trim().toLowerCase();
    return LAYER_ALIASES[k] || k;
  }

  function expandBlockedTargets(sourceId, rawTargets) {
    if (!rawTargets || !rawTargets.length) return [];
    const srcCanon = resolveLayerId(sourceId);
    const out = new Set();

    for (const raw of rawTargets) {
      if (!raw) continue;
      for (const part of String(raw).split(/[,;\s]+/)) {
        const tok = part.trim().toLowerCase();
        if (!tok) continue;
        if (tok === "all_clothing" || tok === "clothing") {
          SKIN_SLOT_IDS.forEach(id => out.add(id));
        } else if (tok === "all_body" || tok === "suit" || tok === "jumpsuit" || tok === "full_body") {
          ["undershirt", "pants", "belt_skin", "socks", "gloves", "boots_skin"].forEach(id => out.add(id));
        } else if (tok === "all_skin" || tok === "skin") {
          DEFAULT_SKIN_LAYERS.forEach(l => { if (!l.replacesBase) out.add(l.id); });
        } else if (tok === "all_3d" || tok === "accessories" || tok === "3d") {
          DISPLAY_3D_SLOT_IDS.forEach(id => out.add(id));
        } else if (tok === "all_cosmetic" || tok === "cosmetic") {
          COSMETIC_SLOT_IDS.forEach(id => out.add(id));
        } else if (tok === "all" || tok === "*") {
          [...SKIN_SLOT_IDS, ...DISPLAY_3D_SLOT_IDS, ...COSMETIC_SLOT_IDS, ...REAL_ARMOR_SLOT_IDS].forEach(id => out.add(id));
          DEFAULT_SKIN_LAYERS.forEach(l => { if (!l.replacesBase) out.add(l.id); });
        } else {
          out.add(resolveLayerId(tok));
        }
      }
    }
    if (srcCanon) out.delete(srcCanon);
    out.delete("body_skin");
    return Array.from(out);
  }

  // ==================== 5. BBMODEL ANALYZER & STITCHER ====================
  function autoMapBoneName(rawName, avgPos) {
    const spaced = String(rawName || "").trim().replace(/([a-z0-9])([A-Z])/g, "$1_$2");
    const n = spaced.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
    if (CANONICAL_BONES[n]) return n;

    const has = (arr) => arr.some(k => n.includes(k));
    if (has(["head", "helm", "hat", "hair", "mask", "visor", "face", "horn", "ear", "crown", "glasses"])) return "head";
    if (has(["right_arm", "r_arm", "arm_r", "arm_right", "rarm", "right_sleeve", "r_sleeve", "r_shoulder", "right_shoulder", "right_hand", "r_hand"])) return "right_arm";
    if (has(["left_arm", "l_arm", "arm_l", "arm_left", "larm", "left_sleeve", "l_sleeve", "l_shoulder", "left_shoulder", "left_hand", "l_hand"])) return "left_arm";
    if (has(["right_foot", "r_foot", "foot_r", "right_boot", "r_boot", "boot_r", "right_shoe"])) return "right_foot";
    if (has(["left_foot", "l_foot", "foot_l", "left_boot", "l_boot", "boot_l", "left_shoe"])) return "left_foot";
    if (has(["right_leg", "r_leg", "leg_r", "rleg", "right_thigh", "right_pant"])) return "right_leg";
    if (has(["left_leg", "l_leg", "leg_l", "lleg", "left_thigh", "left_pant"])) return "left_leg";
    if (has(["waist", "pelvis", "hip", "belt", "skirt", "pants", "legging", "holster", "sash", "pouch"])) return "waist";
    if (has(["body", "torso", "chest", "vest", "jacket", "backpack", "bag", "cape", "cloak", "wing", "back", "spine"])) return "body";

    if (avgPos) {
      const [ax, ay] = avgPos;
      if (ay >= 23.5) return "head";
      if (ay >= 11.5) {
        if (ax >= 4.2) return "right_arm";
        if (ax <= -4.2) return "left_arm";
        return "body";
      }
      if (ay >= 5.0) {
        if (ax >= 0.4) return "right_leg";
        if (ax <= -0.4) return "left_leg";
        return "waist";
      }
      return ax >= 0 ? "right_foot" : "left_foot";
    }
    return "body";
  }

  function classifyTexture(name, usedIndices, idx) {
    const stem = String(name || "").toLowerCase().replace(/\.png$/, "");
    if (stem.endsWith("_layer_1")) return "layer_1";
    if (stem.endsWith("_layer_2")) return "layer_2";
    if (stem.endsWith("_t_e")) return "tint_emissive";
    if (stem.endsWith("_e")) return "emissive";
    if (stem.endsWith("_t")) return "tint";
    const tokens = stem.split(/[^a-z0-9]+/).filter(Boolean);
    for (const kw of ICON_KEYWORDS) {
      if (tokens.includes(kw) && !usedIndices.has(idx)) return `icon_${kw}`;
    }
    return "main";
  }

  function collectGroupCubeUuids(node, out) {
    if (typeof node === "string") {
      out.push(node);
    } else if (node && typeof node === "object" && Array.isArray(node.children)) {
      for (const ch of node.children) collectGroupCubeUuids(ch, out);
    }
  }

  function extractBoneAssignments(bb) {
    const elements = (bb.elements || []).filter(e => e && typeof e === "object" && e.uuid);
    const elemByUuid = new Map(elements.map(e => [e.uuid, e]));
    const cubeToGroup = {};
    const groupToCubes = {};

    for (const node of (bb.outliner || [])) {
      if (node && typeof node === "object") {
        const gname = node.name || "group";
        const uuids = [];
        collectGroupCubeUuids(node, uuids);
        const valid = uuids.filter(u => elemByUuid.has(u));
        if (valid.length) {
          groupToCubes[gname] = (groupToCubes[gname] || []).concat(valid);
          valid.forEach(u => { cubeToGroup[u] = gname; });
        }
      } else if (typeof node === "string" && elemByUuid.has(node)) {
        groupToCubes["(root)"] = (groupToCubes["(root)"] || []).concat([node]);
        cubeToGroup[node] = "(root)";
      }
    }
    for (const u of elemByUuid.keys()) {
      if (!cubeToGroup[u]) {
        groupToCubes["(root)"] = (groupToCubes["(root)"] || []).concat([u]);
        cubeToGroup[u] = "(root)";
      }
    }
    return { cubeToGroup, groupToCubes, elemByUuid };
  }

  function analyzeBBModel(bb, filename = "model.bbmodel") {
    const elements = (bb.elements || []).filter(e => e && typeof e === "object");
    const textures = (bb.textures || []).filter(t => t && typeof t === "object");
    const usedTex = new Set();
    for (const e of elements) {
      for (const f of Object.values(e.faces || {})) {
        if (f && typeof f.texture === "number") usedTex.add(f.texture);
      }
    }

    const { groupToCubes, elemByUuid } = extractBoneAssignments(bb);
    const bonesInfo = [];
    const suggestedBoneMap = {};
    const detectedSlots = new Set();

    for (const [gname, cuuids] of Object.entries(groupToCubes)) {
      let sx = 0, sy = 0, sz = 0;
      for (const u of cuuids) {
        const e = elemByUuid.get(u);
        const fr = e.from || [0, 0, 0], to = e.to || [0, 0, 0];
        sx += (fr[0] + to[0]) * 0.5;
        sy += (fr[1] + to[1]) * 0.5;
        sz += (fr[2] + to[2]) * 0.5;
      }
      const n = cuuids.length || 1;
      const mapped = autoMapBoneName(gname, [sx / n, sy / n, sz / n]);
      suggestedBoneMap[gname] = mapped;
      detectedSlots.add(CANONICAL_BONES[mapped].slot);
      bonesInfo.push({
        original: gname,
        mapped,
        cubeCount: cuuids.length,
        slot: CANONICAL_BONES[mapped].slot,
        isCanonical: Boolean(CANONICAL_BONES[gname]),
      });
    }

    let hasTint = false, hasEmissive = false, hasLayer1 = false, hasLayer2 = false;
    const hasIcons = [];
    const texInfo = textures.map((t, idx) => {
      const tname = t.name || `tex_${idx}.png`;
      const role = classifyTexture(tname, usedTex, idx);
      if (role === "tint" || role === "tint_emissive") hasTint = true;
      if (role === "emissive" || role === "tint_emissive") hasEmissive = true;
      if (role === "layer_1") hasLayer1 = true;
      if (role === "layer_2") hasLayer2 = true;
      if (role.startsWith("icon_")) hasIcons.push(role.slice(5));
      return {
        index: idx,
        name: tname,
        role,
        width: t.width || 64,
        height: t.height || 64,
        used: usedTex.has(idx),
      };
    });

    const cleanId = sanitizeId(bb.name || filename);
    const slotsArr = Array.from(detectedSlots).sort();
    const primarySlot = (slotsArr.length === 1 && slotsArr[0] === "HEAD") ? "HEAD"
      : detectedSlots.has("CHEST") ? "CHEST"
      : detectedSlots.has("LEGS") ? "LEGS"
      : detectedSlots.has("FEET") ? "FEET" : "HEAD";

    const looksCosmetic = /(cosmetic|skin|paint|gold|white|camo|recolor|ornament)/i.test(cleanId);
    let suggestedCategory = looksCosmetic ? "chest_cosmetic" : "chest_real";
    if (cleanId.includes("hair")) suggestedCategory = "hair_3d";
    else if (cleanId.includes("mask") || cleanId.includes("gasmask") || cleanId.includes("glasses")) suggestedCategory = "mask_3d";
    else if (cleanId.includes("belt") || cleanId.includes("holster") || cleanId.includes("sash") || cleanId.includes("pouch")) suggestedCategory = "belt_3d";
    else if (cleanId.includes("backpack") || cleanId.includes("bag") || cleanId.includes("cape") || cleanId.includes("wing")) suggestedCategory = "backpack_3d";
    else if (primarySlot === "HEAD") suggestedCategory = looksCosmetic ? "helmet_cosmetic" : "helmet_real";
    else if (primarySlot === "CHEST") suggestedCategory = looksCosmetic ? "chest_cosmetic" : "chest_real";
    else if (primarySlot === "LEGS") suggestedCategory = looksCosmetic ? "legs_cosmetic" : "legs_real";
    else if (primarySlot === "FEET") suggestedCategory = looksCosmetic ? "boots_cosmetic" : "boots_real";

    return {
      id: cleanId,
      name: bb.name || cleanId,
      filename,
      cubeCount: elements.length,
      resolution: bb.resolution || { width: 64, height: 64 },
      bones: bonesInfo,
      suggestedBoneMap,
      detectedSlots: slotsArr,
      primarySlot,
      suggestedCategory,
      textures: texInfo,
      hasTint,
      hasEmissive,
      hasLayer1,
      hasLayer2,
      hasIcons,
    };
  }

  function findAuxTextureSource(textures, baseName, suffix) {
    const stem = String(baseName || "").toLowerCase().replace(/\.png$/, "");
    const target = stem + suffix;
    for (const t of textures) {
      const ts = String(t.name || "").toLowerCase().replace(/\.png$/, "");
      if (ts === target) return t.source || null;
    }
    for (const t of textures) {
      const ts = String(t.name || "").toLowerCase().replace(/\.png$/, "");
      if (ts.endsWith(suffix) && !(suffix === "_e" && ts.endsWith("_t_e"))) return t.source || null;
    }
    return null;
  }

  function transformCube(cube, pivot, offset, scale) {
    const c = deepClone(cube);
    const [px, py, pz] = pivot;
    const [dx, dy, dz] = offset;
    const fr = c.from || [0, 0, 0];
    const to = c.to || [0, 0, 0];
    const orig = c.origin || [...pivot];
    const xf = (pt) => [
      Math.round((px + (pt[0] - px) * scale + dx) * 10000) / 10000,
      Math.round((py + (pt[1] - py) * scale + dy) * 10000) / 10000,
      Math.round((pz + (pt[2] - pz) * scale + dz) * 10000) / 10000,
    ];
    const nfr = xf(fr), nto = xf(to);
    c.from = [Math.min(nfr[0], nto[0]), Math.min(nfr[1], nto[1]), Math.min(nfr[2], nto[2])];
    c.to = [Math.max(nfr[0], nto[0]), Math.max(nfr[1], nto[1]), Math.max(nfr[2], nto[2])];
    c.origin = xf(orig);
    if (typeof c.inflate === "number") c.inflate = Math.round(c.inflate * scale * 10000) / 10000;
    return c;
  }

  function generateAutoTintMask(srcCanvas, mode) {
    if (mode === "full") return imageToCanvas(srcCanvas, srcCanvas.width, srcCanvas.height);
    if (mode === "light_only") {
      const out = imageToCanvas(srcCanvas, srcCanvas.width, srcCanvas.height);
      const ctx = out.getContext("2d");
      const imgData = ctx.getImageData(0, 0, out.width, out.height);
      const d = imgData.data;
      for (let i = 0; i < d.length; i += 4) {
        if (d[i + 3] === 0) continue;
        const r = d[i], g = d[i + 1], b = d[i + 2];
        const lum = 0.299 * r + 0.587 * g + 0.114 * b;
        const spread = Math.max(r, g, b) - Math.min(r, g, b);
        if (lum < 150 || spread > 55) d[i + 3] = 0;
      }
      ctx.putImageData(imgData, 0, 0);
      return out;
    }
    return null;
  }

  function generateAutoEmissiveMask(srcCanvas, mode) {
    if (mode !== "bright_neon") return null;
    const out = imageToCanvas(srcCanvas, srcCanvas.width, srcCanvas.height);
    const ctx = out.getContext("2d");
    const imgData = ctx.getImageData(0, 0, out.width, out.height);
    const d = imgData.data;
    let kept = 0;
    for (let i = 0; i < d.length; i += 4) {
      if (d[i + 3] === 0) continue;
      const r = d[i], g = d[i + 1], b = d[i + 2];
      const maxC = Math.max(r, g, b), minC = Math.min(r, g, b);
      const sat = maxC > 0 ? (maxC - minC) / maxC : 0;
      const lum = 0.299 * r + 0.587 * g + 0.114 * b;
      // Keep vivid neon or super-bright glowing pixels (visors, LEDs, energy runes)
      if (!((maxC >= 205 && sat >= 0.48) || lum >= 238)) {
        d[i + 3] = 0;
      } else {
        kept++;
      }
    }
    ctx.putImageData(imgData, 0, 0);
    return kept > 0 ? out : null;
  }

  const SLOT_TO_ICON_KW = { HEAD: "helmet", CHEST: "chestplate", LEGS: "leggings", FEET: "boots" };

  async function combineBBModels(inputs, outputName, targetSlot = null, texOptions = {}) {
    const cleanName = sanitizeId(outputName, "combined_model");
    const allowedBones = new Set((targetSlot && SLOT_BONES[targetSlot]) ? SLOT_BONES[targetSlot] : Object.keys(CANONICAL_BONES));

    const atlasSlots = [];
    const slotLookup = new Map();
    const preparedCubes = [];

    let layer1Canvas = null;
    let layer2Canvas = null;
    const iconsByRole = {};

    for (let mIdx = 0; mIdx < inputs.length; mIdx++) {
      const spec = inputs[mIdx];
      const bb = spec.bbmodel;
      const boneMap = spec.boneMap || {};
      const offset = spec.offset || [0, 0, 0];
      const scale = Number(spec.scale || 1.0) || 1.0;
      const stripKws = (spec.stripKeywords || []).map(k => String(k || "").trim().toLowerCase()).filter(Boolean);
      const excludeUuids = new Set(spec.excludeCubeUuids || []);
      const slotFilter = spec.slotFilter || targetSlot;
      const modelAllowed = new Set((slotFilter && SLOT_BONES[slotFilter]) ? SLOT_BONES[slotFilter] : allowedBones);

      const textures = bb.textures || [];
      const res = bb.resolution || {};
      const resW = Number(res.width || 64) || 64;
      const resH = Number(res.height || 64) || 64;

      const elements = (bb.elements || []).filter(e => e && typeof e === "object");
      const { cubeToGroup } = extractBoneAssignments(bb);

      const usedInSrc = new Set();
      for (const e of elements) {
        for (const f of Object.values(e.faces || {})) {
          if (f && typeof f.texture === "number") usedInSrc.add(f.texture);
        }
      }

      for (let tidx = 0; tidx < textures.length; tidx++) {
        const t = textures[tidx];
        const role = classifyTexture(t.name || "", usedInSrc, tidx);
        if (role === "layer_1" && !layer1Canvas) {
          const im = await loadImageFromDataUri(t.source);
          if (im) layer1Canvas = imageToCanvas(im);
        } else if (role === "layer_2" && !layer2Canvas) {
          const im = await loadImageFromDataUri(t.source);
          if (im) layer2Canvas = imageToCanvas(im);
        } else if (role.startsWith("icon_") && !iconsByRole[role]) {
          const im = await loadImageFromDataUri(t.source);
          if (im) iconsByRole[role] = imageToCanvas(im);
        }
      }

      for (const e of elements) {
        if (excludeUuids.has(e.uuid)) continue;
        const cnameLow = String(e.name || "").toLowerCase();
        if (stripKws.length && stripKws.some(kw => cnameLow.includes(kw))) continue;

        const origGroup = cubeToGroup[e.uuid] || "(root)";
        const fr = e.from || [0, 0, 0], to = e.to || [0, 0, 0];
        const avg = [(fr[0] + to[0]) * 0.5, (fr[1] + to[1]) * 0.5, (fr[2] + to[2]) * 0.5];
        let targetBone = boneMap[origGroup] || autoMapBoneName(origGroup, avg);
        if (!CANONICAL_BONES[targetBone]) targetBone = "body";
        if (!modelAllowed.has(targetBone)) continue;

        for (const f of Object.values(e.faces || {})) {
          if (!f || typeof f !== "object") continue;
          let tidx = typeof f.texture === "number" ? f.texture : (textures.length ? 0 : -1);
          if (tidx < 0 || tidx >= textures.length) tidx = textures.length ? 0 : -1;
          const key = `${mIdx}:${tidx}`;
          if (tidx >= 0 && !slotLookup.has(key)) {
            const tObj = textures[tidx];
            const mainImg = await loadImageFromDataUri(tObj.source);
            const mainC = mainImg ? imageToCanvas(mainImg) : blankCanvas(Math.round(resW), Math.round(resH), "#b4b4b4");
            let emisImg = await loadImageFromDataUri(findAuxTextureSource(textures, tObj.name, "_e"));
            let tintImg = await loadImageFromDataUri(findAuxTextureSource(textures, tObj.name, "_t"));
            const teImg = await loadImageFromDataUri(findAuxTextureSource(textures, tObj.name, "_t_e"));
            if (teImg) {
              if (!emisImg) emisImg = teImg;
              if (!tintImg) tintImg = teImg;
            }
            slotLookup.set(key, atlasSlots.length);
            atlasSlots.push({
              img: mainC,
              emis: emisImg ? imageToCanvas(emisImg, mainC.width, mainC.height) : null,
              tint: tintImg ? imageToCanvas(tintImg, mainC.width, mainC.height) : null,
              w: mainC.width,
              h: mainC.height,
              x: 0,
            });
          }
        }

        const xfCube = transformCube(e, CANONICAL_BONES[targetBone].pivot, offset, scale);
        xfCube.uuid = genUuid();
        preparedCubes.push({ bone: targetBone, cube: xfCube, mIdx, resW, resH });
      }
    }

    if (!atlasSlots.length) {
      atlasSlots.push({ img: blankCanvas(64, 64, "#a0a0a0"), emis: null, tint: null, w: 64, h: 64, x: 0 });
    }

    const atlasW = atlasSlots.reduce((acc, s) => acc + s.w, 0);
    const atlasH = Math.max(...atlasSlots.map(s => s.h));

    const stitchedMain = makeCanvas(atlasW, atlasH);
    const stitchedEmis = makeCanvas(atlasW, atlasH);
    const stitchedTint = makeCanvas(atlasW, atlasH);
    const ctxM = stitchedMain.getContext("2d");
    const ctxE = stitchedEmis.getContext("2d");
    const ctxT = stitchedTint.getContext("2d");
    ctxM.imageSmoothingEnabled = ctxE.imageSmoothingEnabled = ctxT.imageSmoothingEnabled = false;

    let hasAnyEmis = false, hasAnyTint = false;
    let xCur = 0;
    for (const s of atlasSlots) {
      s.x = xCur;
      ctxM.drawImage(s.img, xCur, 0);
      if (s.emis) { ctxE.drawImage(s.emis, xCur, 0); hasAnyEmis = true; }
      if (s.tint) { ctxT.drawImage(s.tint, xCur, 0); hasAnyTint = true; }
      xCur += s.w;
    }

    const bonesOut = {};
    const finalElements = [];

    for (const item of preparedCubes) {
      const { bone, cube, mIdx, resW, resH } = item;
      for (const face of Object.values(cube.faces || {})) {
        if (!face || typeof face !== "object") continue;
        const key = `${mIdx}:${typeof face.texture === "number" ? face.texture : 0}`;
        const slotIdx = slotLookup.has(key) ? slotLookup.get(key) : 0;
        const s = atlasSlots[slotIdx];
        const uv = face.uv || [0, 0, 16, 16];
        const sx = s.w / Math.max(1, resW);
        const sy = s.h / Math.max(1, resH);
        face.uv = [
          Math.round((uv[0] * sx + s.x) * 10000) / 10000,
          Math.round((uv[1] * sy) * 10000) / 10000,
          Math.round((uv[2] * sx + s.x) * 10000) / 10000,
          Math.round((uv[3] * sy) * 10000) / 10000,
        ];
        face.texture = 0;
      }
      finalElements.push(cube);
      if (!bonesOut[bone]) bonesOut[bone] = [];
      bonesOut[bone].push(cube.uuid);
    }

    const outTextures = [];
    const addTex = (tname, canvas) => {
      outTextures.push({
        path: "",
        name: tname,
        folder: "",
        namespace: "",
        id: String(outTextures.length),
        width: canvas.width,
        height: canvas.height,
        uv_width: canvas.width,
        uv_height: canvas.height,
        particle: outTextures.length === 0,
        use_as_default: outTextures.length === 0,
        layers_enabled: false,
        render_mode: "default",
        render_sides: "auto",
        frame_time: 1,
        frame_order_type: "loop",
        frame_order: "",
        frame_interpolate: false,
        visible: true,
        internal: true,
        saved: true,
        uuid: genUuid(),
        relative_path: `../textures/${tname}`,
        source: canvas.toDataURL("image/png"),
      });
    };

    // Apply auto texture completion options (for raw models without layer_1/layer_2/_t/_e/icon!)
    const autoLayers = texOptions.autoLayers !== false;
    const autoTintMode = texOptions.autoTintMode || "keep";         // keep | full | light_only | none
    const autoEmissiveMode = texOptions.autoEmissiveMode || "keep"; // keep | bright_neon | none

    let finalEmis = hasAnyEmis ? stitchedEmis : null;
    if (autoEmissiveMode === "none") finalEmis = null;
    else if (autoEmissiveMode === "bright_neon") {
      const genE = generateAutoEmissiveMask(stitchedMain, "bright_neon");
      if (genE) finalEmis = genE;
    }

    let finalTint = hasAnyTint ? stitchedTint : null;
    if (autoTintMode === "none") finalTint = null;
    else if (autoTintMode === "full" || autoTintMode === "light_only") {
      finalTint = generateAutoTintMask(stitchedMain, autoTintMode);
    }

    if (texOptions.customIconDataUri) {
      const iconImg = await loadImageFromDataUri(texOptions.customIconDataUri);
      if (iconImg) {
        const kw = SLOT_TO_ICON_KW[texOptions.customIconSlot || targetSlot || "HEAD"] || "helmet";
        iconsByRole[`icon_${kw}`] = imageToCanvas(iconImg);
      }
    }

    addTex(`${cleanName}_main.png`, stitchedMain);
    if (finalEmis) addTex(`${cleanName}_main_e.png`, finalEmis);
    if (finalTint) addTex(`${cleanName}_main_t.png`, finalTint);
    if (autoLayers || layer1Canvas) addTex(`${cleanName}_layer_1.png`, layer1Canvas || blankCanvas(64, 32));
    // Only emit _layer_2.png for LEGS models (or when source already had layer_2) to keep CHEST models clean
    if (layer2Canvas || (autoLayers && targetSlot === "LEGS")) {
      addTex(`${cleanName}_layer_2.png`, layer2Canvas || blankCanvas(64, 32));
    }
    for (const kw of ICON_KEYWORDS) {
      const role = `icon_${kw}`;
      if (iconsByRole[role]) addTex(`${cleanName}_${kw}.png`, iconsByRole[role]);
    }

    const outliner = [];
    for (const [bname, bmeta] of Object.entries(CANONICAL_BONES)) {
      const cuuids = bonesOut[bname];
      if (!cuuids || !cuuids.length) continue;
      outliner.push({
        name: bname,
        origin: [...bmeta.pivot],
        rotation: [0, 0, 0],
        color: 0,
        uuid: genUuid(),
        export: true,
        mirror_uv: false,
        isOpen: true,
        locked: false,
        visibility: true,
        autouv: 0,
        children: cuuids,
      });
    }

    return {
      meta: { format_version: "4.10", model_format: "free", box_uv: false },
      name: cleanName,
      model_identifier: cleanName,
      visible_box: [1, 1, 0],
      variable_placeholders: "",
      variable_placeholder_buttons: [],
      timeline_setups: [],
      unhandled_root_fields: {},
      resolution: { width: atlasW, height: atlasH },
      elements: finalElements,
      outliner,
      textures: outTextures,
    };
  }

  // ==================== 6. 2D SKIN AUTO-FITTER (HTML5 CANVAS) ====================
  function drawFlippedX(ctx, src, sx, sy, sw, sh, dx, dy) {
    ctx.save();
    ctx.translate(dx + sw, dy);
    ctx.scale(-1, 1);
    ctx.drawImage(src, sx, sy, sw, sh, 0, 0, sw, sh);
    ctx.restore();
  }

  function upgradeSkinTo64x64(img) {
    const w = img.width, h = img.height;
    if (w === 64 && h === 64) {
      return { canvas: imageToCanvas(img, 64, 64), note: "64×64 (стандарт)" };
    }
    if (w === 64 && h === 32) {
      const out = makeCanvas(64, 64);
      const ctx = out.getContext("2d");
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(img, 0, 0);
      const copyLimb = (sx, sy, dx, dy) => {
        drawFlippedX(ctx, img, sx + 4, sy, 4, 4, dx + 4, dy);
        drawFlippedX(ctx, img, sx + 8, sy, 4, 4, dx + 8, dy);
        drawFlippedX(ctx, img, sx + 4, sy + 4, 4, 12, dx + 4, dy + 4);
        drawFlippedX(ctx, img, sx + 12, sy + 4, 4, 12, dx + 12, dy + 4);
        drawFlippedX(ctx, img, sx + 8, sy + 4, 4, 12, dx, dy + 4);
        drawFlippedX(ctx, img, sx, sy + 4, 4, 12, dx + 8, dy + 4);
      };
      copyLimb(0, 16, 16, 48);
      copyLimb(40, 16, 32, 48);
      return { canvas: out, note: "64×32 → сконвертирован в 64×64" };
    }
    if (w === h * 2) {
      const half = imageToCanvas(img, 64, 32);
      const up = upgradeSkinTo64x64(half);
      return { canvas: up.canvas, note: `${w}×${h} (HD legacy) → 64×64` };
    }
    return { canvas: imageToCanvas(img, 64, 64), note: `${w}×${h} → подогнан в 64×64` };
  }

  function convertArmWidthCanvas(srcCanvas, targetSlim) {
    const out = imageToCanvas(srcCanvas, 64, 64);
    const ctx = out.getContext("2d");
    ctx.imageSmoothingEnabled = false;
    const armBlocks = [[40, 16], [40, 32], [32, 48], [48, 48]];
    const srcCtx = srcCanvas.getContext("2d");

    for (const [bx, by] of armBlocks) {
      const data = srcCtx.getImageData(bx, by, 16, 16).data;
      let anyAlpha = false;
      for (let i = 3; i < data.length; i += 4) { if (data[i] > 0) { anyAlpha = true; break; } }
      if (!anyAlpha) continue;

      const dst = makeCanvas(16, 16);
      const dctx = dst.getContext("2d");
      dctx.imageSmoothingEnabled = false;
      if (targetSlim) {
        dctx.drawImage(srcCanvas, bx + 4, by, 4, 4, 4, 0, 3, 4);
        dctx.drawImage(srcCanvas, bx + 8, by, 4, 4, 7, 0, 3, 4);
        dctx.drawImage(srcCanvas, bx + 0, by + 4, 4, 12, 0, 4, 4, 12);
        dctx.drawImage(srcCanvas, bx + 4, by + 4, 4, 12, 4, 4, 3, 12);
        dctx.drawImage(srcCanvas, bx + 8, by + 4, 4, 12, 7, 4, 4, 12);
        dctx.drawImage(srcCanvas, bx + 12, by + 4, 4, 12, 11, 4, 3, 12);
      } else {
        dctx.drawImage(srcCanvas, bx + 4, by, 3, 4, 4, 0, 4, 4);
        dctx.drawImage(srcCanvas, bx + 7, by, 3, 4, 8, 0, 4, 4);
        dctx.drawImage(srcCanvas, bx + 0, by + 4, 4, 12, 0, 4, 4, 12);
        dctx.drawImage(srcCanvas, bx + 4, by + 4, 3, 12, 4, 4, 4, 12);
        dctx.drawImage(srcCanvas, bx + 7, by + 4, 4, 12, 8, 4, 4, 12);
        dctx.drawImage(srcCanvas, bx + 11, by + 4, 3, 12, 12, 4, 4, 12);
      }
      ctx.clearRect(bx, by, 16, 16);
      ctx.drawImage(dst, bx, by);
    }
    return out;
  }

  function extractRegionCanvas(srcCanvas, region) {
    const rects = REGION_RECTS[region];
    if (!rects || region === "full") return imageToCanvas(srcCanvas, 64, 64);
    const out = makeCanvas(64, 64);
    const ctx = out.getContext("2d");
    ctx.imageSmoothingEnabled = false;
    for (const [x0, y0, x1, y1] of rects) {
      ctx.drawImage(srcCanvas, x0, y0, x1 - x0, y1 - y0, x0, y0, x1 - x0, y1 - y0);
    }
    return out;
  }

  function rgbToHsv(r, g, b) {
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    const d = max - min;
    let h = 0;
    const s = max === 0 ? 0 : d / max;
    const v = max;
    if (d !== 0) {
      if (max === r) h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
      else if (max === g) h = ((b - r) / d + 2) / 6;
      else h = ((r - g) / d + 4) / 6;
    }
    return [h, s, v];
  }

  function hsvToRgb(h, s, v) {
    const i = Math.floor(h * 6);
    const f = h * 6 - i;
    const p = v * (1 - s);
    const q = v * (1 - f * s);
    const t = v * (1 - (1 - f) * s);
    switch (i % 6) {
      case 0: return [v, t, p];
      case 1: return [q, v, p];
      case 2: return [p, v, t];
      case 3: return [p, q, v];
      case 4: return [t, p, v];
      default: return [v, p, q];
    }
  }

  function recolorCanvas(srcCanvas, hueShift, satMult, valMult, tintHex, tintStrength) {
    if (Math.abs(hueShift) < 0.001 && Math.abs(satMult - 1) < 0.001 && Math.abs(valMult - 1) < 0.001 && tintStrength <= 0.001) {
      return srcCanvas;
    }
    let tr = 255, tg = 255, tb = 255;
    const cleanHex = String(tintHex || "").replace(/^#/, "");
    if (cleanHex.length === 6) {
      tr = parseInt(cleanHex.slice(0, 2), 16);
      tg = parseInt(cleanHex.slice(2, 4), 16);
      tb = parseInt(cleanHex.slice(4, 6), 16);
    }
    const out = imageToCanvas(srcCanvas, srcCanvas.width, srcCanvas.height);
    const ctx = out.getContext("2d");
    const imgData = ctx.getImageData(0, 0, out.width, out.height);
    const d = imgData.data;
    const k = Math.max(0, Math.min(1, tintStrength || 0));

    for (let i = 0; i < d.length; i += 4) {
      if (d[i + 3] === 0) continue;
      let [hh, ss, vv] = rgbToHsv(d[i] / 255, d[i + 1] / 255, d[i + 2] / 255);
      hh = (((hh + hueShift / 360) % 1) + 1) % 1;
      ss = Math.max(0, Math.min(1, ss * satMult));
      vv = Math.max(0, Math.min(1, vv * valMult));
      let [nr, ng, nb] = hsvToRgb(hh, ss, vv);
      if (k > 0.001 && cleanHex.length === 6) {
        nr = nr * (1 - k) + (nr * tr / 255) * k;
        ng = ng * (1 - k) + (ng * tg / 255) * k;
        nb = nb * (1 - k) + (nb * tb / 255) * k;
      }
      d[i] = Math.round(nr * 255);
      d[i + 1] = Math.round(ng * 255);
      d[i + 2] = Math.round(nb * 255);
    }
    ctx.putImageData(imgData, 0, 0);
    return out;
  }

  async function processSkinLayer(dataUri, opts = {}) {
    const img = await loadImageFromDataUri(dataUri);
    if (!img) throw new Error("Не удалось прочитать PNG-изображение");
    const origSize = `${img.width}×${img.height}`;
    let { canvas, note } = upgradeSkinTo64x64(img);
    if (opts.armConvert === "to_slim") {
      canvas = convertArmWidthCanvas(canvas, true);
      note += " + Wide→Slim";
    } else if (opts.armConvert === "to_wide") {
      canvas = convertArmWidthCanvas(canvas, false);
      note += " + Slim→Wide";
    }
    canvas = extractRegionCanvas(canvas, opts.region || "full");
    canvas = recolorCanvas(
      canvas,
      Number(opts.hueShift || 0),
      Number(opts.satMult ?? 1),
      Number(opts.valMult ?? 1),
      opts.tintHex || "",
      Number(opts.tintStrength || 0)
    );
    return {
      pngBase64: canvas.toDataURL("image/png"),
      originalSize: origSize,
      note,
    };
  }

  // ==================== 7. YAML & FULL SERVER ZIP EXPORT (v1.8.0!) ====================
  const DYEABLE_MATERIALS = new Set([
    "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS", "LEATHER_HORSE_ARMOR", "WOLF_ARMOR",
  ]);

  function renderMythicItemYaml({
    itemId, modelId, slot, displayName, loreLines, material, customModelData,
    colorRgb, wardrobeSlot, blocks, blockMode, hasEmbeddedIcon,
  }) {
    const mat = String(material || SLOT_DEFAULT_MATERIAL[slot] || "LEATHER_CHESTPLATE").trim().toUpperCase();
    const suffix = SLOT_SUFFIX[slot] || "chest_piece";
    const pieceKey = `mythicarmor:${modelId}_${suffix}`;
    const cmd = (customModelData !== undefined && customModelData !== null && String(customModelData).trim() !== "")
      ? parseInt(String(customModelData).trim(), 10) : null;
    const hasValidCmd = Number.isFinite(cmd) && cmd > 0;

    const lines = [
      `${itemId}:`,
      `  Material: ${mat}`,
      `  Display: '${yamlEscape(displayName)}'`,
    ];
    if (hasValidCmd) {
      lines.push(`  Model: ${cmd}`);
    }
    lines.push(`  Lore:`);
    for (const l of (loreLines || [])) lines.push(`    - '${yamlEscape(l)}'`);
    lines.push("  Equippable:");
    if (slot !== "HEAD") lines.push(`    Model: ${pieceKey}`);
    lines.push(`    Slot: ${slot}`);
    lines.push("    EquipSound: 'item.armor.equip_leather'");
    lines.push("  Options:");
    if (slot === "HEAD") {
      // On HEAD, Options.ItemModel overrides CustomModelData in 1.21.4+!
      // If user chose CustomModelData, omit ItemModel and provide display_context JSON snippet instead.
      if (!hasValidCmd) {
        lines.push(`    ItemModel: ${pieceKey}`);
      }
    } else {
      // On CHEST/LEGS/FEET, 3D armor comes from Equippable.Model; ItemModel is ONLY for embedded .bbmodel icons!
      if (!hasValidCmd && hasEmbeddedIcon) {
        lines.push(`    ItemModel: ${pieceKey}`);
      }
    }
    if (colorRgb && DYEABLE_MATERIALS.has(mat)) {
      lines.push(`    Color: ${colorRgb}`);
    }
    lines.push("    Unbreakable: true");
    lines.push("    HideFlags: true");

    const isRealArmor = Boolean(wardrobeSlot && String(wardrobeSlot).endsWith("_real"));
    const REAL_ATTR_SLOT = { HEAD: "Head", CHEST: "Chest", LEGS: "Legs", FEET: "Feet" };
    const REAL_DEF_ARMOR = { HEAD: 3, CHEST: 8, LEGS: 6, FEET: 3 };
    if (isRealArmor) {
      const attrSlot = REAL_ATTR_SLOT[slot] || "Chest";
      const defArmor = REAL_DEF_ARMOR[slot] || 4;
      lines.push("  Attributes:");
      lines.push(`    ${attrSlot}:`);
      lines.push(`      Armor: ${defArmor}`);
      lines.push("      ArmorToughness: 2");
    }

    const hasBlocks = Array.isArray(blocks) && blocks.length > 0;
    if (wardrobeSlot || hasBlocks) {
      lines.push("  Wardrobe:");
      if (wardrobeSlot) lines.push(`    Slot: ${wardrobeSlot}`);
      if (hasBlocks) {
        lines.push("    Blocks:");
        for (const b of blocks) lines.push(`      - ${b}`);
        if (blockMode) lines.push(`    BlockMode: ${blockMode}`);
      }
    }
    return lines.join("\n");
  }

  function buildHeadCmdResourcepackJson(materialLower, entries) {
    // Minecraft 1.21.4+ items/<material>.json switching by CustomModelData + display_context (head=3D, fallback=2D icon)
    const sorted = [...entries].sort((a, b) => a.cmd - b.cmd);
    return JSON.stringify({
      model: {
        type: "minecraft:range_dispatch",
        property: "minecraft:custom_model_data",
        entries: sorted.map(e => ({
          threshold: e.cmd,
          model: {
            type: "minecraft:select",
            property: "minecraft:display_context",
            cases: [
              {
                when: "head",
                model: {
                  type: "minecraft:model",
                  model: `mythicarmor:item/${e.modelId}_head_piece`,
                },
              },
            ],
            fallback: {
              type: "minecraft:model",
              model: e.iconModel || `mypack:item/${e.modelId}_icon`,
            },
          },
        })),
        fallback: {
          type: "minecraft:model",
          model: `minecraft:item/${materialLower}`,
        },
      },
    }, null, 2);
  }

  function cartesianProduct(arrays) {
    return arrays.reduce((acc, cur) => acc.flatMap(a => cur.map(b => [...a, b])), [[]]);
  }

  function generatePriorityYml(orderIds) {
    return [
      "# ==============================================================================",
      "# MythicWardrobe v1.9.0 - Приоритет 2D-слоёв скина (ОТ ВЕРХНЕГО К НИЖНЕМУ)",
      "# Сгенерировано в MythicWardrobe Studio v2.2 (HTML Standalone)",
      "# Положите в: plugins/MythicWardrobe/priority.yml и выполните /wardrobe reload",
      "# ==============================================================================",
      "",
      "order:",
      ...orderIds.map(lid => `  - ${lid}`),
      "",
    ].join("\n");
  }

  /**
   * Replaces or adds a texture inside a loaded .bbmodel in place (returns updated deep clone).
   * role: "main" | "tint" (_t.png) | "emissive" (_e.png)
   */
  function replaceBBModelTexture(bb, role, dataUri, filename = "custom.png") {
    const out = deepClone(bb);
    if (!Array.isArray(out.textures)) out.textures = [];
    const baseModel = sanitizeId(out.name || out.model_identifier || "model");
    const usedIndices = new Set();
    for (const el of (out.elements || [])) {
      for (const f of Object.values(el.faces || {})) {
        if (f && typeof f.texture === "number") usedIndices.add(f.texture);
      }
    }
    const primaryIdx = usedIndices.size ? Math.min(...usedIndices) : 0;
    const primaryTex = out.textures[primaryIdx] || out.textures[0];
    const primaryStem = primaryTex ? String(primaryTex.name || `${baseModel}_main`).replace(/\.png$/i, "") : `${baseModel}_main`;

    if (role === "main") {
      if (out.textures[primaryIdx]) {
        out.textures[primaryIdx].source = dataUri;
        out.textures[primaryIdx].name = filename || out.textures[primaryIdx].name;
      } else {
        out.textures.unshift({
          id: "0",
          name: filename || `${baseModel}_main.png`,
          source: dataUri,
          mode: "bitmap",
          visible: true,
          saved: true,
          uuid: genUuid(),
        });
      }
      return out;
    }

    const suffix = role === "tint" ? "_t" : "_e";
    const targetName = `${primaryStem}${suffix}.png`;
    let existingIdx = -1;
    for (let i = 0; i < out.textures.length; i++) {
      const nm = String(out.textures[i].name || "").toLowerCase().replace(/\.png$/, "");
      if (nm === `${primaryStem.toLowerCase()}${suffix}` || (nm.endsWith(suffix) && !(suffix === "_e" && nm.endsWith("_t_e")))) {
        existingIdx = i;
        break;
      }
    }
    if (existingIdx >= 0) {
      out.textures[existingIdx].source = dataUri;
      out.textures[existingIdx].name = targetName;
    } else {
      out.textures.push({
        id: String(out.textures.length),
        name: targetName,
        source: dataUri,
        mode: "bitmap",
        visible: true,
        saved: true,
        uuid: genUuid(),
      });
    }
    return out;
  }

  function parseListCsv(val) {
    if (Array.isArray(val)) return val.map(s => String(s || "").trim()).filter(Boolean);
    return String(val || "").split(/[\n,;]+/).map(s => s.trim()).filter(Boolean);
  }

  /**
   * Generates plugins/MythicWardrobe/hair-3d.yml (v1.9.0) from all models configured as 3D hair
   * or having 2D-hair bindings / helmet-hiding rules.
   */
  function generateHair3DYml(models, opts = {}) {
    const list = (models || []).filter(m =>
      m.category === "hair_3d" || m.wardrobeSlot === "hair_3d" || (m.hair3d && (m.hair3d.bind2dHair || m.hair3d.helmetMode))
    );
    const manualOverrides = opts.manualOverrides !== false;
    const trigger2dHat = opts.triggerOn2dHat !== false;
    const lines = [
      "# ==============================================================================",
      "# MythicWardrobe v1.9.0 - Автопривязка 3D-волос к 2D-волосам и скрытие под шлемом",
      "# Сгенерировано в MythicWardrobe Studio v2.2",
      "# Положите в: plugins/MythicWardrobe/hair-3d.yml и выполните /wardrobe reload",
      "# ==============================================================================",
      "",
      "enabled: true",
      `manual-slot-overrides-binding: ${manualOverrides}`,
      `trigger-on-2d-hat: ${trigger2dHat}`,
      "default-helmet-list-mode: ALL",
      "default-helmets: []",
      "default-exceptions: []",
      "",
    ];

    const bindingsLines = [];
    const itemsLines = [];

    for (const m of list) {
      const h = m.hair3d || {};
      const cleanMid = sanitizeId(m.exportId || m.id);
      const itemId = m.mythicId || toPascalId(cleanMid);
      const helmetMode = String(h.helmetMode || "HIDE_ALL").toUpperCase();
      const hiddenCubes = Array.isArray(h.helmetHideCubes) ? h.helmetHideCubes.filter(Boolean) : [];
      const effectiveMode = helmetMode === "SWAP_MODEL"
        ? "SWAP_MODEL"
        : ((helmetMode === "PARTIAL_MODEL" && hiddenCubes.length > 0) ? "PARTIAL_MODEL" : helmetMode);
      const swapModelId = String(h.swapMythicId || h.partialMythicId || `${itemId}HelmetSafe`).trim();
      const listMode = String(h.helmetListMode || "ALL").toUpperCase();
      const helmets = parseListCsv(h.helmets);
      const exceptions = parseListCsv(h.exceptions);

      const writeRuleBody = (targetLines, includeMythicItem) => {
        if (includeMythicItem) targetLines.push(`    mythic-item: "${yamlEscape(itemId)}"`);
        if (m.colorRgb && String(m.colorRgb).trim()) {
          targetLines.push(`    color: "${yamlEscape(m.colorRgb)}"`);
        }
        targetLines.push(`    helmet-mode: ${effectiveMode}`);
        if (effectiveMode === "SWAP_MODEL") {
          targetLines.push(`    helmet-model: "${yamlEscape(swapModelId)}"`);
        } else if (effectiveMode === "PARTIAL_MODEL") {
          targetLines.push(`    helmet-partial-item: "${yamlEscape(swapModelId)}"`);
        }
        targetLines.push(`    helmet-list-mode: ${listMode}`);
        if (helmets.length) {
          targetLines.push("    helmets:");
          for (const hm of helmets) targetLines.push(`      - "${yamlEscape(hm)}"`);
        } else {
          targetLines.push("    helmets: []");
        }
        if (exceptions.length) {
          targetLines.push("    exceptions:");
          for (const ex of exceptions) targetLines.push(`      - "${yamlEscape(ex)}"`);
        } else {
          targetLines.push("    exceptions: []");
        }
      };

      const bind2d = String(h.bind2dHair || "").trim();
      if (bind2d) {
        const keyName = bind2d.endsWith(".png") ? bind2d : `${bind2d}.png`;
        bindingsLines.push(`  "${yamlEscape(keyName)}":`);
        writeRuleBody(bindingsLines, true);
        bindingsLines.push("");
      }

      itemsLines.push(`  ${itemId}:`);
      writeRuleBody(itemsLines, false);
      itemsLines.push("");
    }

    lines.push("bindings:");
    if (bindingsLines.length) lines.push(...bindingsLines);
    else lines.push("  {}");
    lines.push("");
    lines.push("items:");
    if (itemsLines.length) lines.push(...itemsLines);
    else lines.push("  {}");
    lines.push("");
    return lines.join("\n");
  }

  // ==================== GUI INVENTORY EDITOR (menus.yml — ALL 21 SEPARATED SLOTS) ====================
  const GUI_SLOT_CATALOG = [
    // HEAD (6 отдельных слотов)
    { id: "hat",             label: "2D Шапка",                    section: "HEAD",  group: "2D Скин",       defaultMat: "WHITE_STAINED_GLASS_PANE" },
    { id: "mask_skin",       label: "2D Маска",                    section: "HEAD",  group: "2D Скин",       defaultMat: "LIGHT_BLUE_STAINED_GLASS_PANE" },
    { id: "hair_3d",         label: "3D Причёска",                 section: "HEAD",  group: "3D Аксессуар",  defaultMat: "MAGENTA_STAINED_GLASS_PANE" },
    { id: "mask_3d",         label: "3D Маска / Очки",             section: "HEAD",  group: "3D Аксессуар",  defaultMat: "ORANGE_STAINED_GLASS_PANE" },
    { id: "helmet_real",     label: "3D Шлем (Броня / Статы)",     section: "HEAD",  group: "3D Броня",      defaultMat: "IRON_HELMET" },
    { id: "helmet_cosmetic", label: "3D Шлем (Косметика)",         section: "HEAD",  group: "3D Косметика",  defaultMat: "LEATHER_HELMET" },
    // CHEST (6 отдельных слотов)
    { id: "undershirt",      label: "2D Футболка / Майка",         section: "CHEST", group: "2D Скин",       defaultMat: "LIGHT_BLUE_STAINED_GLASS_PANE" },
    { id: "jacket",          label: "2D Куртка / Костюм",          section: "CHEST", group: "2D Скин",       defaultMat: "CYAN_STAINED_GLASS_PANE" },
    { id: "gloves",          label: "2D Перчатки",                 section: "CHEST", group: "2D Скин",       defaultMat: "BLUE_STAINED_GLASS_PANE" },
    { id: "chest_real",      label: "3D Нагрудник (Броня / Статы)",section: "CHEST", group: "3D Броня",      defaultMat: "IRON_CHESTPLATE" },
    { id: "chest_cosmetic",  label: "3D Нагрудник (Косметика)",    section: "CHEST", group: "3D Косметика",  defaultMat: "LEATHER_CHESTPLATE" },
    { id: "backpack_3d",     label: "3D Рюкзак / Плащ",            section: "CHEST", group: "3D Аксессуар",  defaultMat: "BROWN_STAINED_GLASS_PANE" },
    // LEGS (5 отдельных слотов)
    { id: "pants",           label: "2D Штаны",                    section: "LEGS",  group: "2D Скин",       defaultMat: "BLUE_STAINED_GLASS_PANE" },
    { id: "belt_skin",       label: "2D Пояс / Ремень",            section: "LEGS",  group: "2D Скин",       defaultMat: "YELLOW_STAINED_GLASS_PANE" },
    { id: "legs_real",       label: "3D Поножи (Броня / Статы)",   section: "LEGS",  group: "3D Броня",      defaultMat: "IRON_LEGGINGS" },
    { id: "legs_cosmetic",   label: "3D Поножи (Косметика)",       section: "LEGS",  group: "3D Косметика",  defaultMat: "LEATHER_LEGGINGS" },
    { id: "belt_3d",         label: "3D Пояс / Кобура / Декор",    section: "LEGS",  group: "3D Аксессуар",  defaultMat: "ORANGE_STAINED_GLASS_PANE" },
    // FEET (4 отдельных слота)
    { id: "socks",           label: "2D Носки",                    section: "FEET",  group: "2D Скин",       defaultMat: "YELLOW_STAINED_GLASS_PANE" },
    { id: "boots_skin",      label: "2D Ботинки",                  section: "FEET",  group: "2D Скин",       defaultMat: "ORANGE_STAINED_GLASS_PANE" },
    { id: "boots_real",      label: "3D Ботинки (Броня / Статы)",  section: "FEET",  group: "3D Броня",      defaultMat: "IRON_BOOTS" },
    { id: "boots_cosmetic",  label: "3D Ботинки (Косметика)",      section: "FEET",  group: "3D Косметика",  defaultMat: "LEATHER_BOOTS" },
  ];

  const GUI_BUTTON_ACTIONS = [
    { id: "BACK",           label: "BACK — Назад (открывает инвентарь / команду)" },
    { id: "OPEN_HEAD",      label: "OPEN_HEAD — Открыть раздел «Шлем / Голова»" },
    { id: "OPEN_CHEST",     label: "OPEN_CHEST — Открыть раздел «Нагрудник / Торс»" },
    { id: "OPEN_LEGS",      label: "OPEN_LEGS — Открыть раздел «Поножи / Пояса»" },
    { id: "OPEN_FEET",      label: "OPEN_FEET — Открыть раздел «Ботинки / Обувь»" },
    { id: "OPEN_HUB",       label: "OPEN_HUB — Открыть хаб выбора разделов (COMPACT)" },
    { id: "OPEN_UNIFIED",   label: "OPEN_UNIFIED — Открыть полное меню (UNIFIED)" },
    { id: "OPEN_INVENTORY", label: "OPEN_INVENTORY — Открыть Инвентарь и Экипировку" },
    { id: "CYCLE_MODE",     label: "CYCLE_MODE — Переключить режим (COMPACT/TABBED/UNIFIED)" },
    { id: "REFRESH_SKIN",   label: "REFRESH_SKIN — Пересобрать 2D-скин и 3D-броню" },
    { id: "RESET_SKIN",     label: "RESET_SKIN — Сбросить временные слои скина" },
    { id: "RUN_COMMAND",    label: "RUN_COMMAND — Выполнить команду" },
    { id: "CLOSE",          label: "CLOSE — Полностью закрыть меню" },
    { id: "NONE",           label: "NONE — Декоративный предмет (без действия)" },
  ];

  function makeGuiSlotCell(slotId, material, name, lore) {
    return {
      kind: "slot",
      slotId,
      emptyIcon: {
        material,
        name,
        lore: lore || [],
        customModelData: "",
        itemModel: "",
        glint: false,
      },
    };
  }

  function makeGuiBtnCell(action, material, name, lore, extra = {}) {
    return {
      kind: action === "NONE" ? "decor" : "button",
      action,
      material,
      name,
      lore: lore || [],
      customModelData: "",
      itemModel: "",
      glint: Boolean(extra.glint),
      hideTooltip: Boolean(extra.hideTooltip),
      command: extra.command || "",
    };
  }

  function makeTabbedNavCells(activeSec) {
    return {
      1: makeGuiBtnCell("OPEN_HEAD",  activeSec === "HEAD"  ? "DIAMOND_HELMET"     : "IRON_HELMET",     activeSec === "HEAD"  ? "&6▶ &fШлем и Голова &6◀"     : "&fШлем и Голова",     ["&76 слотов: 2D + 3D Броня + Косметика"], { glint: activeSec === "HEAD" }),
      3: makeGuiBtnCell("OPEN_CHEST", activeSec === "CHEST" ? "DIAMOND_CHESTPLATE" : "IRON_CHESTPLATE", activeSec === "CHEST" ? "&6▶ &fНагрудник и Торс &6◀"  : "&fНагрудник и Торс",  ["&76 слотов: 2D + 3D Броня + Косметика"], { glint: activeSec === "CHEST" }),
      5: makeGuiBtnCell("OPEN_LEGS",  activeSec === "LEGS"  ? "DIAMOND_LEGGINGS"   : "IRON_LEGGINGS",   activeSec === "LEGS"  ? "&6▶ &fПоножи и Пояса &6◀"    : "&fПоножи и Пояса",    ["&75 слотов: 2D + 3D Броня + Косметика"], { glint: activeSec === "LEGS" }),
      7: makeGuiBtnCell("OPEN_FEET",  activeSec === "FEET"  ? "DIAMOND_BOOTS"      : "IRON_BOOTS",      activeSec === "FEET"  ? "&6▶ &fБотинки и Обувь &6◀"   : "&fБотинки и Обувь",   ["&74 слота: 2D + 3D Броня + Косметика"],  { glint: activeSec === "FEET" }),
      18: makeGuiBtnCell("BACK",         "ARROW",      "&e◀ Назад",        ["&7Открыть инвентарь / назад"]),
      20: makeGuiBtnCell("CYCLE_MODE",   "COMPARATOR", "&eВид меню",       ["&7Переключить COMPACT / TABBED / UNIFIED"]),
      22: makeGuiBtnCell("REFRESH_SKIN", "LIME_DYE",   "&aОбновить скин",  ["&7Пересобрать 2D-слои и 3D-броню"]),
      24: makeGuiBtnCell("RESET_SKIN",   "RED_DYE",    "&cСбросить слои",  ["&7Сбросить слои от команд/скиллов"]),
      26: makeGuiBtnCell("CLOSE",        "BARRIER",    "&cЗакрыть",        ["&7Полностью закрыть меню"]),
    };
  }

  function createDefaultMenusState() {
    const darkFiller = () => ({ material: "BLACK_STAINED_GLASS_PANE", name: " ", customModelData: "", itemModel: "", hideTooltip: true });
    const grayFiller = () => ({ material: "GRAY_STAINED_GLASS_PANE", name: " ", customModelData: "", itemModel: "", hideTooltip: true });

    return {
      defaultMode: "COMPACT",
      backCommand: "",
      backFallback: "INVENTORY",
      inventoryTitle: "&8Инвентарь и Экипировка",
      screens: {
        tabbed_HEAD: {
          label: "TABBED: Голова (6 слотов)",
          title: "&8Экипировка: Шлем и Голова",
          rows: 3,
          fillerEnabled: true,
          filler: grayFiller(),
          showIndicators: true,
          cells: {
            ...makeTabbedNavCells("HEAD"),
            10: makeGuiSlotCell("hat",             "WHITE_STAINED_GLASS_PANE",      "&fШапка (2D Скин)",           ["&72D-слой шапки на скин"]),
            11: makeGuiSlotCell("mask_skin",       "LIGHT_BLUE_STAINED_GLASS_PANE", "&fМаска (2D Скин)",           ["&72D-слой маски / лица"]),
            12: makeGuiSlotCell("hair_3d",         "MAGENTA_STAINED_GLASS_PANE",    "&dПричёска (3D)",             ["&73D-причёска MythicArmors"]),
            13: makeGuiSlotCell("mask_3d",         "ORANGE_STAINED_GLASS_PANE",     "&6Маска / Очки (3D)",         ["&73D-маска поверх шлема"]),
            15: makeGuiSlotCell("helmet_real",     "IRON_HELMET",                   "&aШлем (3D Броня / Статы)",   ["&aДаёт защиту, чары и статы"]),
            16: makeGuiSlotCell("helmet_cosmetic", "LEATHER_HELMET",                "&dШлем (3D Косметика)",       ["&7Визуальный скин шлема"]),
          },
        },
        tabbed_CHEST: {
          label: "TABBED: Торс (6 слотов)",
          title: "&8Экипировка: Нагрудник и Торс",
          rows: 3,
          fillerEnabled: true,
          filler: grayFiller(),
          showIndicators: true,
          cells: {
            ...makeTabbedNavCells("CHEST"),
            10: makeGuiSlotCell("undershirt",     "LIGHT_BLUE_STAINED_GLASS_PANE", "&fФутболка (2D Скин)",          ["&7Нижний 2D-слой торса"]),
            11: makeGuiSlotCell("jacket",         "CYAN_STAINED_GLASS_PANE",       "&bКуртка / Костюм (2D Скин)",   ["&7Верхний 2D-слой торса"]),
            12: makeGuiSlotCell("gloves",         "BLUE_STAINED_GLASS_PANE",       "&9Перчатки (2D Скин)",          ["&72D-слой перчаток"]),
            14: makeGuiSlotCell("chest_real",     "IRON_CHESTPLATE",               "&aНагрудник (3D Броня / Статы)",["&aДаёт защиту, чары и статы"]),
            15: makeGuiSlotCell("chest_cosmetic", "LEATHER_CHESTPLATE",            "&dНагрудник (3D Косметика)",    ["&7Скин / мультимодальная краска"]),
            16: makeGuiSlotCell("backpack_3d",    "BROWN_STAINED_GLASS_PANE",      "&eРюкзак / Плащ (3D)",          ["&73D-рюкзак поверх брони"]),
          },
        },
        tabbed_LEGS: {
          label: "TABBED: Ноги (5 слотов)",
          title: "&8Экипировка: Поножи и Пояса",
          rows: 3,
          fillerEnabled: true,
          filler: grayFiller(),
          showIndicators: true,
          cells: {
            ...makeTabbedNavCells("LEGS"),
            10: makeGuiSlotCell("pants",         "BLUE_STAINED_GLASS_PANE",   "&9Штаны (2D Скин)",           ["&7Нижний 2D-слой на ноги"]),
            11: makeGuiSlotCell("belt_skin",     "YELLOW_STAINED_GLASS_PANE", "&eПояс / Ремень (2D Скин)",   ["&72D-слой пояса"]),
            13: makeGuiSlotCell("legs_real",     "IRON_LEGGINGS",             "&aПоножи (3D Броня / Статы)", ["&aДаёт защиту, чары и статы"]),
            14: makeGuiSlotCell("legs_cosmetic", "LEATHER_LEGGINGS",          "&dПоножи (3D Косметика)",     ["&7Визуальный скин поножей"]),
            16: makeGuiSlotCell("belt_3d",       "ORANGE_STAINED_GLASS_PANE", "&6Пояс / Кобура (3D)",        ["&73D-пояс поверх поножей"]),
          },
        },
        tabbed_FEET: {
          label: "TABBED: Обувь (4 слота)",
          title: "&8Экипировка: Ботинки и Обувь",
          rows: 3,
          fillerEnabled: true,
          filler: grayFiller(),
          showIndicators: true,
          cells: {
            ...makeTabbedNavCells("FEET"),
            10: makeGuiSlotCell("socks",          "YELLOW_STAINED_GLASS_PANE", "&eНоски (2D Скин)",            ["&7Нижний 2D-слой ступней"]),
            11: makeGuiSlotCell("boots_skin",     "ORANGE_STAINED_GLASS_PANE", "&6Ботинки (2D Скин)",          ["&7Верхний 2D-слой обуви"]),
            14: makeGuiSlotCell("boots_real",     "IRON_BOOTS",                "&aБотинки (3D Броня / Статы)", ["&aДаёт защиту, чары и статы"]),
            15: makeGuiSlotCell("boots_cosmetic", "LEATHER_BOOTS",             "&dБотинки (3D Косметика)",     ["&7Визуальный скин ботинок"]),
          },
        },
        unified: {
          label: "UNIFIED (Все 21 слотов)",
          title: "&8Полный Гардероб и Броня (21 слот)",
          rows: 5,
          fillerEnabled: true,
          filler: darkFiller(),
          showIndicators: false,
          cells: {
            // Row 1: HEAD (6)
            0: makeGuiBtnCell("NONE", "DIAMOND_HELMET",     "&bШлем и Голова",    ["&76 отдельных слотов справа"]),
            2: makeGuiSlotCell("hat",             "WHITE_STAINED_GLASS_PANE",      "&fШапка (2D)"),
            3: makeGuiSlotCell("mask_skin",       "LIGHT_BLUE_STAINED_GLASS_PANE", "&fМаска (2D)"),
            4: makeGuiSlotCell("hair_3d",         "MAGENTA_STAINED_GLASS_PANE",    "&dПричёска (3D)"),
            5: makeGuiSlotCell("mask_3d",         "ORANGE_STAINED_GLASS_PANE",     "&6Маска (3D)"),
            6: makeGuiSlotCell("helmet_real",     "IRON_HELMET",                   "&aШлем (3D Броня)"),
            7: makeGuiSlotCell("helmet_cosmetic", "LEATHER_HELMET",                "&dШлем (3D Косметика)"),
            // Row 2: CHEST (6)
            9:  makeGuiBtnCell("NONE", "DIAMOND_CHESTPLATE", "&bНагрудник и Торс", ["&76 отдельных слотов справа"]),
            11: makeGuiSlotCell("undershirt",     "LIGHT_BLUE_STAINED_GLASS_PANE", "&fФутболка (2D)"),
            12: makeGuiSlotCell("jacket",         "CYAN_STAINED_GLASS_PANE",       "&bКуртка (2D)"),
            13: makeGuiSlotCell("gloves",         "BLUE_STAINED_GLASS_PANE",       "&9Перчатки (2D)"),
            14: makeGuiSlotCell("chest_real",     "IRON_CHESTPLATE",               "&aНагрудник (3D Броня)"),
            15: makeGuiSlotCell("chest_cosmetic", "LEATHER_CHESTPLATE",            "&dНагрудник (3D Косметика)"),
            16: makeGuiSlotCell("backpack_3d",    "BROWN_STAINED_GLASS_PANE",      "&eРюкзак (3D)"),
            // Row 3: LEGS (5)
            18: makeGuiBtnCell("NONE", "DIAMOND_LEGGINGS",   "&bПоножи и Пояса",   ["&75 отдельных слотов справа"]),
            20: makeGuiSlotCell("pants",         "BLUE_STAINED_GLASS_PANE",   "&9Штаны (2D)"),
            21: makeGuiSlotCell("belt_skin",     "YELLOW_STAINED_GLASS_PANE", "&eПояс (2D)"),
            23: makeGuiSlotCell("legs_real",     "IRON_LEGGINGS",             "&aПоножи (3D Броня)"),
            24: makeGuiSlotCell("legs_cosmetic", "LEATHER_LEGGINGS",          "&dПоножи (3D Косметика)"),
            25: makeGuiSlotCell("belt_3d",       "ORANGE_STAINED_GLASS_PANE", "&6Пояс (3D)"),
            // Row 4: FEET (4)
            27: makeGuiBtnCell("NONE", "DIAMOND_BOOTS",      "&bБотинки и Обувь",  ["&74 отдельных слота справа"]),
            29: makeGuiSlotCell("socks",          "YELLOW_STAINED_GLASS_PANE", "&eНоски (2D)"),
            30: makeGuiSlotCell("boots_skin",     "ORANGE_STAINED_GLASS_PANE", "&6Ботинки (2D)"),
            32: makeGuiSlotCell("boots_real",     "IRON_BOOTS",                "&aБотинки (3D Броня)"),
            33: makeGuiSlotCell("boots_cosmetic", "LEATHER_BOOTS",             "&dБотинки (3D Косметика)"),
            // Row 5: Controls
            36: makeGuiBtnCell("BACK",         "ARROW",      "&e◀ Назад",       ["&7Инвентарь / назад"]),
            38: makeGuiBtnCell("CYCLE_MODE",   "COMPARATOR", "&eВид меню",      ["&7Сменить COMPACT / TABBED / UNIFIED"]),
            40: makeGuiBtnCell("REFRESH_SKIN", "LIME_DYE",   "&aОбновить скин", ["&7Пересобрать скин и броню"]),
            42: makeGuiBtnCell("RESET_SKIN",   "RED_DYE",    "&cСбросить слои", ["&7Очистить слои от скиллов"]),
            44: makeGuiBtnCell("CLOSE",        "BARRIER",    "&cЗакрыть",       ["&7Полностью закрыть меню"]),
          },
        },
        compact_hub: {
          label: "COMPACT HUB (Выбор раздела)",
          title: "&8Гардероб &7▪ &fВыберите раздел",
          rows: 1,
          fillerEnabled: true,
          filler: darkFiller(),
          showIndicators: false,
          cells: {
            0: makeGuiBtnCell("BACK",         "ARROW",           "&e◀ Назад",        ["&7Открыть инвентарь"]),
            1: makeGuiBtnCell("OPEN_HEAD",    "IRON_HELMET",     "&fШлем и Голова",  ["&76 отдельных слотов"]),
            2: makeGuiBtnCell("OPEN_CHEST",   "IRON_CHESTPLATE", "&fНагрудник",      ["&76 отдельных слотов"]),
            3: makeGuiBtnCell("OPEN_LEGS",    "IRON_LEGGINGS",   "&fПоножи и Пояса", ["&75 отдельных слотов"]),
            4: makeGuiBtnCell("OPEN_FEET",    "IRON_BOOTS",      "&fБотинки",        ["&74 отдельных слота"]),
            6: makeGuiBtnCell("REFRESH_SKIN", "LIME_DYE",        "&aОбновить скин",  []),
            7: makeGuiBtnCell("RESET_SKIN",   "RED_DYE",         "&cСбросить слои",  []),
            8: makeGuiBtnCell("CYCLE_MODE",   "COMPARATOR",      "&eВид меню",       []),
          },
        },
        compact_HEAD: {
          label: "COMPACT: Голова (6 слотов)",
          title: "&8Шлем &7▪ &fГолова (6 слотов)",
          rows: 1,
          fillerEnabled: true,
          filler: grayFiller(),
          showIndicators: false,
          cells: {
            0: makeGuiBtnCell("BACK", "ARROW", "&e◀ Назад", []),
            1: makeGuiSlotCell("hat",             "WHITE_STAINED_GLASS_PANE",      "&fШапка (2D)"),
            2: makeGuiSlotCell("mask_skin",       "LIGHT_BLUE_STAINED_GLASS_PANE", "&fМаска (2D)"),
            3: makeGuiSlotCell("hair_3d",         "MAGENTA_STAINED_GLASS_PANE",    "&dПричёска (3D)"),
            4: makeGuiSlotCell("mask_3d",         "ORANGE_STAINED_GLASS_PANE",     "&6Маска (3D)"),
            5: makeGuiSlotCell("helmet_real",     "IRON_HELMET",                   "&aШлем (3D Броня)"),
            6: makeGuiSlotCell("helmet_cosmetic", "LEATHER_HELMET",                "&dШлем (3D Косметика)"),
            7: makeGuiBtnCell("REFRESH_SKIN", "LIME_DYE",   "&aОбновить", []),
            8: makeGuiBtnCell("CYCLE_MODE",   "COMPARATOR", "&eРежим",    []),
          },
        },
        compact_CHEST: {
          label: "COMPACT: Торс (6 слотов)",
          title: "&8Нагрудник &7▪ &fТорс (6 слотов)",
          rows: 1,
          fillerEnabled: true,
          filler: grayFiller(),
          showIndicators: false,
          cells: {
            0: makeGuiBtnCell("BACK", "ARROW", "&e◀ Назад", []),
            1: makeGuiSlotCell("undershirt",     "LIGHT_BLUE_STAINED_GLASS_PANE", "&fФутболка (2D)"),
            2: makeGuiSlotCell("jacket",         "CYAN_STAINED_GLASS_PANE",       "&bКуртка (2D)"),
            3: makeGuiSlotCell("gloves",         "BLUE_STAINED_GLASS_PANE",       "&9Перчатки (2D)"),
            4: makeGuiSlotCell("chest_real",     "IRON_CHESTPLATE",               "&aНагрудник (3D Броня)"),
            5: makeGuiSlotCell("chest_cosmetic", "LEATHER_CHESTPLATE",            "&dНагрудник (3D Косметика)"),
            6: makeGuiSlotCell("backpack_3d",    "BROWN_STAINED_GLASS_PANE",      "&eРюкзак (3D)"),
            7: makeGuiBtnCell("REFRESH_SKIN", "LIME_DYE",   "&aОбновить", []),
            8: makeGuiBtnCell("CYCLE_MODE",   "COMPARATOR", "&eРежим",    []),
          },
        },
        compact_LEGS: {
          label: "COMPACT: Ноги (5 слотов)",
          title: "&8Поножи &7▪ &fНоги и Пояса (5 слотов)",
          rows: 1,
          fillerEnabled: true,
          filler: grayFiller(),
          showIndicators: false,
          cells: {
            0: makeGuiBtnCell("BACK", "ARROW", "&e◀ Назад", []),
            1: makeGuiSlotCell("pants",         "BLUE_STAINED_GLASS_PANE",   "&9Штаны (2D)"),
            2: makeGuiSlotCell("belt_skin",     "YELLOW_STAINED_GLASS_PANE", "&eПояс (2D)"),
            3: makeGuiSlotCell("legs_real",     "IRON_LEGGINGS",             "&aПоножи (3D Броня)"),
            4: makeGuiSlotCell("legs_cosmetic", "LEATHER_LEGGINGS",          "&dПоножи (3D Косметика)"),
            5: makeGuiSlotCell("belt_3d",       "ORANGE_STAINED_GLASS_PANE", "&6Пояс (3D)"),
            7: makeGuiBtnCell("REFRESH_SKIN", "LIME_DYE",   "&aОбновить", []),
            8: makeGuiBtnCell("CYCLE_MODE",   "COMPARATOR", "&eРежим",    []),
          },
        },
        compact_FEET: {
          label: "COMPACT: Обувь (4 слота)",
          title: "&8Ботинки &7▪ &fОбувь (4 слота)",
          rows: 1,
          fillerEnabled: true,
          filler: grayFiller(),
          showIndicators: false,
          cells: {
            0: makeGuiBtnCell("BACK", "ARROW", "&e◀ Назад", []),
            1: makeGuiSlotCell("socks",          "YELLOW_STAINED_GLASS_PANE", "&eНоски (2D)"),
            2: makeGuiSlotCell("boots_skin",     "ORANGE_STAINED_GLASS_PANE", "&6Ботинки (2D)"),
            4: makeGuiSlotCell("boots_real",     "IRON_BOOTS",                "&aБотинки (3D Броня)"),
            5: makeGuiSlotCell("boots_cosmetic", "LEATHER_BOOTS",             "&dБотинки (3D Косметика)"),
            7: makeGuiBtnCell("REFRESH_SKIN", "LIME_DYE",   "&aОбновить", []),
            8: makeGuiBtnCell("CYCLE_MODE",   "COMPARATOR", "&eРежим",    []),
          },
        },
      },
    };
  }

  function normalizeMenuActionForPlugin(rawAct, section) {
    const a = String(rawAct || "NONE").trim().toUpperCase();
    const sec = String(section || "HEAD").trim().toUpperCase();
    if (a === "DECOR") return "NONE";
    if (a === "SWITCH_MODE") return "CYCLE_MODE";
    if (a === "SWITCH_TAB" || a === "OPEN_SECTION") {
      return ["HEAD", "CHEST", "LEGS", "FEET"].includes(sec) ? `OPEN_${sec}` : "OPEN_HEAD";
    }
    return a;
  }

  function writeIconYamlProps(lines, indent, spec) {
    if (!spec) return;
    const mat = String(spec.material || "GRAY_STAINED_GLASS_PANE").trim().toUpperCase();
    lines.push(`${indent}material: ${mat}`);
    if (spec.name !== undefined && spec.name !== null && spec.name !== "") {
      lines.push(`${indent}name: '${yamlEscape(spec.name)}'`);
    }
    const cmd = parseInt(String(spec.customModelData || "").trim(), 10);
    if (Number.isFinite(cmd) && cmd > 0) {
      lines.push(`${indent}custom-model-data: ${cmd}`);
    }
    if (spec.itemModel && String(spec.itemModel).trim()) {
      lines.push(`${indent}item-model: '${yamlEscape(String(spec.itemModel).trim())}'`);
    }
    if (spec.colorHex && String(spec.colorHex).trim()) {
      lines.push(`${indent}color: '${yamlEscape(String(spec.colorHex).trim())}'`);
    }
    if (spec.glint) lines.push(`${indent}glint: true`);
    if (spec.hideTooltip) lines.push(`${indent}hide-tooltip: true`);
    const lore = Array.isArray(spec.lore) ? spec.lore.filter(l => l !== undefined && l !== null && String(l).length > 0) : [];
    if (lore.length) {
      lines.push(`${indent}lore:`);
      for (const l of lore) lines.push(`${indent}  - '${yamlEscape(l)}'`);
    }
  }

  function renderMenuLayoutYaml(lines, baseIndent, screen) {
    const ind = baseIndent;
    const rows = Math.max(1, Math.min(6, parseInt(screen.rows || 3, 10) || 3));
    const maxSlot = rows * 9 - 1;
    lines.push(`${ind}title: '${yamlEscape(screen.title || "&8Гардероб")}'`);
    lines.push(`${ind}rows: ${rows}`);
    if (screen.showIndicators) lines.push(`${ind}show-equipped-indicators: true`);

    lines.push(`${ind}filler:`);
    lines.push(`${ind}  enabled: ${screen.fillerEnabled !== false}`);
    writeIconYamlProps(lines, `${ind}  `, screen.filler || { material: "GRAY_STAINED_GLASS_PANE", name: " ", hideTooltip: true });

    if (screen.sectionTitles) {
      lines.push(`${ind}section-titles:`);
      for (const [sec, st] of Object.entries(screen.sectionTitles)) {
        lines.push(`${ind}  ${sec}: '${yamlEscape(st)}'`);
      }
    }

    const slotPositions = [];
    const emptySlots = [];
    const buttons = [];
    const cells = screen.cells || {};
    for (const [idxStr, cell] of Object.entries(cells)) {
      const slotIdx = parseInt(idxStr, 10);
      if (!Number.isFinite(slotIdx) || slotIdx < 0 || slotIdx > maxSlot || !cell) continue;
      if (cell.kind === "slot" && cell.slotId) {
        slotPositions.push({ slotId: cell.slotId, slotIdx });
        if (cell.emptyIcon) emptySlots.push({ slotId: cell.slotId, icon: cell.emptyIcon });
      } else if (cell.kind === "button" || cell.kind === "decor") {
        buttons.push({ slotIdx, cell });
      }
    }

    if (slotPositions.length) {
      lines.push(`${ind}slot-positions:`);
      for (const sp of slotPositions) lines.push(`${ind}  ${sp.slotId}: ${sp.slotIdx}`);
    }

    if (emptySlots.length) {
      lines.push(`${ind}empty-slots:`);
      const seen = new Set();
      for (const es of emptySlots) {
        if (seen.has(es.slotId)) continue;
        seen.add(es.slotId);
        lines.push(`${ind}  ${es.slotId}:`);
        writeIconYamlProps(lines, `${ind}    `, es.icon);
      }
    }

    if (buttons.length) {
      lines.push(`${ind}buttons:`);
      buttons.sort((a, b) => a.slotIdx - b.slotIdx);
      for (let i = 0; i < buttons.length; i++) {
        const { slotIdx, cell } = buttons[i];
        const act = cell.kind === "decor" ? "NONE" : normalizeMenuActionForPlugin(cell.action, cell.section);
        lines.push(`${ind}  btn_${slotIdx}:`);
        lines.push(`${ind}    slots: [${slotIdx}]`);
        lines.push(`${ind}    action: ${act}`);
        if (act === "RUN_COMMAND" && cell.command) {
          lines.push(`${ind}    command: '${yamlEscape(cell.command)}'`);
        }
        writeIconYamlProps(lines, `${ind}    `, cell);
      }
    }
  }

  function generateMenusYml(menusState) {
    const st = menusState || createDefaultMenusState();
    const rawMode = String(st.defaultMode || "COMPACT").trim().toUpperCase();
    const mode = rawMode === "PER_SLOT" ? "COMPACT" : rawMode;
    const lines = [
      "# ==============================================================================",
      "# MythicWardrobe v1.11.0 - Визуальная настройка GUI инвентаря (все 21 слотов)",
      "# Сгенерировано в MythicWardrobe Studio v2.6 (Визуальный GUI Редактор)",
      "# Положите в: plugins/MythicWardrobe/menus.yml и выполните /wardrobe reload",
      "# ==============================================================================",
      "",
      `default-mode: '${mode}'`,
      "allow-player-switch: true",
      `back-command: '${yamlEscape(st.backCommand || "")}'`,
      `back-fallback: '${st.backFallback || "INVENTORY"}'`,
      `inventory-title: '${yamlEscape(st.inventoryTitle || "&8Инвентарь и Экипировка")}'`,
      "",
    ];

    const sc = st.screens || {};
    const tabbedSecs = ["HEAD", "CHEST", "LEGS", "FEET"].filter(s => Boolean(sc[`tabbed_${s}`]));
    if (tabbedSecs.length) {
      const baseTab = sc.tabbed_HEAD || sc[`tabbed_${tabbedSecs[0]}`];
      const mergedPositions = {};
      const mergedEmpty = {};
      const sectionTitles = {};
      for (const sec of tabbedSecs) {
        const sObj = sc[`tabbed_${sec}`];
        sectionTitles[sec] = sObj.title || `&8Экипировка: ${sec}`;
        const maxS = Math.max(1, Math.min(6, parseInt(sObj.rows || 3, 10) || 3)) * 9 - 1;
        for (const [idxStr, cell] of Object.entries(sObj.cells || {})) {
          const idx = parseInt(idxStr, 10);
          if (!Number.isFinite(idx) || idx < 0 || idx > maxS || !cell) continue;
          if (cell.kind === "slot" && cell.slotId) {
            mergedPositions[cell.slotId] = idx;
            if (cell.emptyIcon && !mergedEmpty[cell.slotId]) mergedEmpty[cell.slotId] = cell.emptyIcon;
          }
        }
      }
      lines.push("tabbed:");
      lines.push("  title: '&8Гардероб &7▪ &f<section>'");
      lines.push(`  rows: ${Math.max(1, Math.min(6, parseInt(baseTab.rows || 3, 10) || 3))}`);
      if (baseTab.showIndicators !== false) lines.push("  show-equipped-indicators: true");
      lines.push("  filler:");
      lines.push(`    enabled: ${baseTab.fillerEnabled !== false}`);
      writeIconYamlProps(lines, "    ", baseTab.filler || { material: "GRAY_STAINED_GLASS_PANE", name: " ", hideTooltip: true });
      lines.push("  section-titles:");
      for (const [sec, title] of Object.entries(sectionTitles)) {
        lines.push(`    ${sec}: '${yamlEscape(title)}'`);
      }
      if (Object.keys(mergedPositions).length) {
        lines.push("  slot-positions:");
        for (const [sid, pos] of Object.entries(mergedPositions)) {
          lines.push(`    ${sid}: ${pos}`);
        }
      }
      if (Object.keys(mergedEmpty).length) {
        lines.push("  empty-slots:");
        for (const [sid, icon] of Object.entries(mergedEmpty)) {
          lines.push(`    ${sid}:`);
          writeIconYamlProps(lines, "      ", icon);
        }
      }
      // Base navigation buttons from HEAD tab
      const baseBtns = Object.entries(baseTab.cells || {})
        .map(([idxStr, cell]) => ({ slotIdx: parseInt(idxStr, 10), cell }))
        .filter(x => Number.isFinite(x.slotIdx) && x.cell && (x.cell.kind === "button" || x.cell.kind === "decor"))
        .sort((a, b) => a.slotIdx - b.slotIdx);
      if (baseBtns.length) {
        lines.push("  buttons:");
        for (const { slotIdx, cell } of baseBtns) {
          const act = cell.kind === "decor" ? "NONE" : normalizeMenuActionForPlugin(cell.action, cell.section);
          lines.push(`    btn_${slotIdx}:`);
          lines.push(`      slots: [${slotIdx}]`);
          lines.push(`      action: ${act}`);
          if (act === "RUN_COMMAND" && cell.command) {
            lines.push(`      command: '${yamlEscape(cell.command)}'`);
          }
          writeIconYamlProps(lines, "      ", cell);
        }
      }
      // Per-section separated tabbed overrides (supported natively by MythicWardrobe v1.11.0+)
      lines.push("  sections:");
      for (const sec of tabbedSecs) {
        lines.push(`    ${sec}:`);
        renderMenuLayoutYaml(lines, "      ", sc[`tabbed_${sec}`]);
      }
      lines.push("");
    } else if (sc.tabbed) {
      lines.push("tabbed:");
      renderMenuLayoutYaml(lines, "  ", sc.tabbed);
      lines.push("");
    }
    if (sc.unified) {
      lines.push("unified:");
      renderMenuLayoutYaml(lines, "  ", sc.unified);
      lines.push("");
    }
    if (sc.compact_hub) {
      lines.push("compact_hub:");
      renderMenuLayoutYaml(lines, "  ", sc.compact_hub);
      lines.push("");
    }
    lines.push("compact:");
    for (const sec of ["HEAD", "CHEST", "LEGS", "FEET"]) {
      const sObj = sc[`compact_${sec}`];
      if (!sObj) continue;
      lines.push(`  ${sec}:`);
      renderMenuLayoutYaml(lines, "    ", sObj);
      lines.push("");
    }
    return lines.join("\n");
  }

  async function buildStudioPackZip(payload) {
    const packName = sanitizeId(payload.packName || "mythicwardrobe_custom_pack");
    const modelsById = new Map((payload.models || []).map(m => [m.id, m]));
    const links = payload.links || [];
    const includeStandalone = payload.includeStandalone !== false;

    const zip = new StudioZip();
    const writtenModels = [];
    const mythicItemsBlocks = [];
    const comboRulesBlocks = [];
    const wardrobeItemsBlocks = [];
    const giveCommands = [];
    const summaryCombos = [];
    const standaloneItemIds = new Map();
    const headCmdByMaterial = new Map(); // materialLower -> [{ cmd, modelId, iconModel }]

    // 1. Standalone 3D models
    if (includeStandalone) {
      for (const [mid, m] of modelsById.entries()) {
        const cleanMid = sanitizeId(m.exportId || mid);
        const bbFixed = await combineBBModels([{
          bbmodel: m.bbmodel,
          boneMap: m.boneMap || {},
          offset: m.offset || [0, 0, 0],
          scale: m.scale || 1.0,
          stripKeywords: [],
          slotFilter: null,
        }], cleanMid, null, {
          autoLayers: m.autoLayers !== false,
          autoTintMode: m.autoTintMode || "keep",
          autoEmissiveMode: m.autoEmissiveMode || "keep",
          customIconDataUri: m.customIconDataUri || null,
          customIconSlot: m.slot || "HEAD",
        });

        zip.addText(`plugins/MythicArmors/models/${cleanMid}.bbmodel`, JSON.stringify(bbFixed));
        writtenModels.push(`${cleanMid}.bbmodel`);

        const presentBones = new Set((bbFixed.outliner || []).map(g => g.name));
        let presentSlots = Object.entries(SLOT_BONES)
          .filter(([, blist]) => blist.some(b => presentBones.has(b)))
          .map(([sl]) => sl);
        if (!presentSlots.length) presentSlots = [m.slot || "HEAD"];

        const hasEmbeddedIcon = Boolean(m.customIconDataUri) ||
          (bbFixed.textures || []).some(t => ICON_KEYWORDS.some(kw => String(t.name || "").toLowerCase().includes(kw)));

        const ruNames = { HEAD: "Голова", CHEST: "Торс", LEGS: "Ноги", FEET: "Ботинки" };
        for (const sl of presentSlots) {
          const baseTitle = m.displayName || m.name || cleanMid;
          const itemDisplay = presentSlots.length === 1 ? baseTitle : `${baseTitle} (${ruNames[sl]})`;
          const itemId = (presentSlots.length === 1 && m.mythicId) ? m.mythicId
            : (presentSlots.length === 1 ? toPascalId(cleanMid) : `${toPascalId(cleanMid)}${sl[0] + sl.slice(1).toLowerCase()}`);
          standaloneItemIds.set(`${mid}:${sl}`, itemId);

          const lore = (m.lore && m.lore.length) ? [...m.lore] : [
            `&7Модель MythicArmors: &f${cleanMid}.bbmodel`,
            "&aСгенерировано в MythicWardrobe Studio",
          ];
          const blocks = (m.blocks || []).filter(Boolean);
          const blockMode = m.blockMode || "UNEQUIP";
          const mat = String(m.material || SLOT_DEFAULT_MATERIAL[sl]).trim().toUpperCase();
          const cmd = m.customModelData ? parseInt(String(m.customModelData).trim(), 10) : null;

          if (sl === "HEAD" && Number.isFinite(cmd) && cmd > 0) {
            const mlow = mat.toLowerCase();
            if (!headCmdByMaterial.has(mlow)) headCmdByMaterial.set(mlow, []);
            headCmdByMaterial.get(mlow).push({
              cmd,
              modelId: cleanMid,
              iconModel: m.customIconModelPath || `mypack:item/${cleanMid}_icon`,
            });
          }

          mythicItemsBlocks.push(renderMythicItemYaml({
            itemId,
            modelId: cleanMid,
            slot: sl,
            displayName: itemDisplay,
            loreLines: lore,
            material: mat,
            customModelData: cmd,
            colorRgb: m.colorRgb,
            wardrobeSlot: m.wardrobeSlot || "",
            blocks,
            blockMode,
            hasEmbeddedIcon,
          }));
          giveCommands.push(`/mm i give @s ${itemId}`);

          if (m.wardrobeSlot || blocks.length) {
            const wLines = [`  ${itemId}:`];
            if (m.wardrobeSlot) wLines.push(`    slot: ${m.wardrobeSlot}`);
            if (blocks.length) {
              wLines.push(`    blocks: [${blocks.join(", ")}]`);
              wLines.push(`    block-mode: ${blockMode}`);
            }
            wardrobeItemsBlocks.push(wLines.join("\n"));
          }

          // v1.9.0 PARTIAL_MODEL under-helmet 3D hair variant (auto-strips selected cubes like high ponytail!)
          const h3d = m.hair3d;
          if (sl === "HEAD" && h3d && h3d.helmetMode === "PARTIAL_MODEL"
              && Array.isArray(h3d.helmetHideCubes) && h3d.helmetHideCubes.length > 0) {
            const partialModelId = sanitizeId(`${cleanMid}_under_helmet`);
            const partialItemId = h3d.partialMythicId || `${itemId}HelmetSafe`;
            const bbPartial = await combineBBModels([{
              bbmodel: m.bbmodel,
              boneMap: m.boneMap || {},
              offset: m.offset || [0, 0, 0],
              scale: m.scale || 1.0,
              stripKeywords: [],
              excludeCubeUuids: h3d.helmetHideCubes,
              slotFilter: "HEAD",
            }], partialModelId, "HEAD", {
              autoLayers: m.autoLayers !== false,
              autoTintMode: m.autoTintMode || "keep",
              autoEmissiveMode: m.autoEmissiveMode || "keep",
              customIconDataUri: m.customIconDataUri || null,
              customIconSlot: "HEAD",
            });
            if (bbPartial.elements && bbPartial.elements.length > 0) {
              zip.addText(`plugins/MythicArmors/models/${partialModelId}.bbmodel`, JSON.stringify(bbPartial));
              writtenModels.push(`${partialModelId}.bbmodel`);
              mythicItemsBlocks.push(renderMythicItemYaml({
                itemId: partialItemId,
                modelId: partialModelId,
                slot: "HEAD",
                displayName: `${itemDisplay} &7[Под шлемом]`,
                loreLines: [
                  `&7Частичный вариант под шлем (hair-3d.yml PARTIAL_MODEL)`,
                  `&7Скрыто элементов: &f${h3d.helmetHideCubes.length} &7(остались только пряди!)`,
                ],
                material: mat,
                customModelData: null,
                colorRgb: m.colorRgb,
                wardrobeSlot: "hair_3d",
                blocks: [],
                blockMode: "UNEQUIP",
                hasEmbeddedIcon,
              }));
            }
          }
        }
      }
    }

    // 2. Cross-combined models from category links
    for (const link of links) {
      if (link.enabled === false) continue;
      const slot = String(link.slot || "CHEST").toUpperCase();
      const groups = (link.groups || []).filter(g => Array.isArray(g) && g.length > 0);
      if (groups.length < 2) continue;

      const stripStraps = Boolean(link.stripFrontStraps);
      const secSlot = link.secondarySlot || (slot === "HEAD" ? "mask_3d" : slot === "LEGS" ? "belt_3d" : "backpack_3d");

      for (const tuple of cartesianProduct(groups)) {
        if (new Set(tuple).size < tuple.length) continue;
        const comboModels = tuple.map(id => modelsById.get(id)).filter(Boolean);
        if (comboModels.length < 2) continue;

        const partIds = comboModels.map(cm => sanitizeId(cm.exportId || cm.id));
        const comboModelId = sanitizeId(partIds.join("_plus_"));
        const comboItemId = toPascalId(comboModelId) + "Combo";

        const specs = comboModels.map((cm, idx) => {
          const kws = [...(cm.stripKeywords || [])];
          if (idx > 0 && stripStraps && !kws.includes("strap")) kws.push("strap");
          return {
            bbmodel: cm.bbmodel,
            boneMap: cm.boneMap || {},
            offset: cm.offset || [0, 0, 0],
            scale: cm.scale || 1.0,
            stripKeywords: kws,
            slotFilter: slot,
          };
        });

        const merged = await combineBBModels(specs, comboModelId, slot);
        if (!merged.elements.length) continue;

        zip.addText(`plugins/MythicArmors/models/${comboModelId}.bbmodel`, JSON.stringify(merged));
        writtenModels.push(`${comboModelId}.bbmodel`);

        const namesJoined = comboModels.map(cm => cm.displayName || cm.name || cm.id).join(" + ");
        const colorRgb = (comboModels.find(cm => cm.colorRgb) || {}).colorRgb || null;
        mythicItemsBlocks.push(renderMythicItemYaml({
          itemId: comboItemId,
          modelId: comboModelId,
          slot,
          displayName: `&6${namesJoined} &7[Комбо 3D]`,
          loreLines: [
            `&7Комбо-модель (0 лага): &f${comboModelId}.bbmodel`,
            `&bСкрещено: &f${namesJoined}`,
            "&7Подставляется автоматически через combo-overrides.yml",
          ],
          material: comboModels[0].material,
          colorRgb,
        }));
        giveCommands.push(`/mm i give @s ${comboItemId}`);

        const pId = standaloneItemIds.get(`${comboModels[0].id}:${slot}`) || toPascalId(partIds[0]);
        const sId = standaloneItemIds.get(`${comboModels[1].id}:${slot}`) || toPascalId(partIds[1]);
        comboRulesBlocks.push([
          `  ${comboModelId}:`,
          `    equipment-slot: ${slot}`,
          `    primary-mythic-item: "${pId}"`,
          `    secondary-slot: "${secSlot}"`,
          `    secondary-mythic-item: "${sId}"`,
          `    result-mythic-item: "${comboItemId}"`,
          `    hide-secondary-display: true`,
        ].join("\n"));

        summaryCombos.push({
          modelId: comboModelId,
          itemId: comboItemId,
          slot,
          parts: partIds,
          cubeCount: merged.elements.length,
          atlasSize: `${merged.resolution.width}x${merged.resolution.height}`,
        });
      }
    }

    // 3. MythicMobs 3D Items YAML
    if (mythicItemsBlocks.length) {
      zip.addText(
        `plugins/MythicMobs/Items/${toPascalId(packName)}_Items.yml`,
        `# MythicWardrobe Studio v2.0 - Сгенерированные 3D-предметы (${packName})\n` +
        `# Положите в: plugins/MythicMobs/Items/\n\n` +
        mythicItemsBlocks.join("\n\n") + "\n"
      );
    }

    // 4. combo-overrides.yml
    if (comboRulesBlocks.length) {
      zip.addText(
        "plugins/MythicWardrobe/combo-overrides.yml",
        `# MythicWardrobe v1.8.0 - Автоподмена скрещенных 3D-моделей (0 лага!)\ncombos:\n` +
        comboRulesBlocks.join("\n\n") + "\n"
      );
    }

    // 5. Optional 2D Skin Overlays + Blocks + priority.yml
    const skinBundle = payload.skinBundle;
    if (skinBundle && skinBundle.include) {
      if (skinBundle.priorityYaml) {
        zip.addText("plugins/MythicWardrobe/priority.yml", skinBundle.priorityYaml);
      }
      if (skinBundle.previewPngBase64) {
        zip.addBytes("preview_skin_64x64.png", dataUriToUint8Array(skinBundle.previewPngBase64));
      }
      const skinMmBlocks = [];
      for (const ov of (skinBundle.overlays || [])) {
        const folder = sanitizeId(ov.folder || "misc");
        const fname = sanitizeId(ov.filename || "layer") + ".png";
        zip.addBytes(`plugins/MythicWardrobe/overlays/${folder}/${fname}`, dataUriToUint8Array(ov.pngBase64));

        const mmId = ov.mythicId ? toPascalId(ov.mythicId) : toPascalId(`wardrobe_${ov.layerId || folder}_${fname.slice(0, -4)}`);
        const disp = ov.displayName || `&b${fname.slice(0, -4)} &7[2D Слой]`;
        const blocks = (ov.blocks || []).filter(Boolean);
        const blockMode = ov.blockMode || "UNEQUIP";
        const mat2d = String(ov.material || "PAPER").trim().toUpperCase();
        const cmd2d = (ov.customModelData !== undefined && ov.customModelData !== null && String(ov.customModelData).trim() !== "")
          ? parseInt(String(ov.customModelData).trim(), 10) : null;
        const customLore = Array.isArray(ov.lore) ? ov.lore.filter(Boolean) : [];

        const lines = [
          `${mmId}:`,
          `  Material: ${mat2d}`,
          `  Display: '${yamlEscape(disp)}'`,
        ];
        if (Number.isFinite(cmd2d) && cmd2d > 0) {
          lines.push(`  Model: ${cmd2d}`);
        }
        lines.push(`  Lore:`);
        if (customLore.length) {
          for (const l of customLore) lines.push(`    - '${yamlEscape(l)}'`);
        } else {
          lines.push(`    - '&72D-слой скина: &f${ov.layerId || folder}'`);
          lines.push(`    - '&7Текстура: &f${fname}'`);
        }
        if (blocks.length) {
          lines.push(`    - '&cБлокирует слои: &7${blocks.join(", ")}'`);
        }
        lines.push(`  Wardrobe:`);
        lines.push(`    Slot: ${ov.layerId || folder}`);
        lines.push(`    Texture: ${fname}`);
        if (blocks.length) {
          lines.push(`    Blocks:`);
          for (const b of blocks) lines.push(`      - ${b}`);
          lines.push(`    BlockMode: ${blockMode}`);
        }
        skinMmBlocks.push(lines.join("\n"));
        giveCommands.push(`/mm i give @s ${mmId}`);

        const wLines = [
          `  ${mmId}:`,
          `    slot: ${ov.layerId || folder}`,
          `    texture: "${fname}"`,
        ];
        if (blocks.length) {
          wLines.push(`    blocks: [${blocks.join(", ")}]`);
          wLines.push(`    block-mode: ${blockMode}`);
        }
        wardrobeItemsBlocks.push(wLines.join("\n"));
      }
      if (skinMmBlocks.length) {
        zip.addText(
          `plugins/MythicMobs/Items/${toPascalId(packName)}_2DSkins.yml`,
          `# MythicWardrobe Studio v2.2 - 2D-слои скина, Lore и костюмы с блокировкой слоёв\n\n` +
          skinMmBlocks.join("\n\n") + "\n"
        );
      }
    }

    // 5b. v1.9.0 hair-3d.yml (auto-binding 2D hair -> 3D hair + helmet hide/partial rules)
    const hairModels = (payload.models || []).filter(m =>
      m.category === "hair_3d" || m.wardrobeSlot === "hair_3d" || (m.hair3d && (m.hair3d.bind2dHair || m.hair3d.helmetMode))
    );
    if (hairModels.length) {
      zip.addText("plugins/MythicWardrobe/hair-3d.yml", generateHair3DYml(payload.models || []));
    }

    // 5c. v1.9.0 Visual GUI menus.yml (if edited/enabled)
    if (payload.menusYaml) {
      zip.addText("plugins/MythicWardrobe/menus.yml", payload.menusYaml);
    }

    // 6. MythicWardrobe items.yml
    if (wardrobeItemsBlocks.length) {
      zip.addText(
        "plugins/MythicWardrobe/items.yml",
        `# MythicWardrobe v1.8.0 - Привязка слотов и блокировок (plugins/MythicWardrobe/items.yml)\nitems:\n` +
        wardrobeItemsBlocks.join("\n") + "\n"
      );
    }

    // 7. Optional v1.8 config snippet file
    if (payload.configSnippetYaml) {
      zip.addText("plugins/MythicWardrobe/v1_8_settings_snippet.yml", payload.configSnippetYaml);
    }

    // 7b. Resourcepack JSON snippets for HEAD items using CustomModelData (e.g. ECHO_SHARD + display_context)
    for (const [mlow, entries] of headCmdByMaterial.entries()) {
      zip.addText(
        `resourcepack_snippet/assets/minecraft/items/${mlow}.json`,
        buildHeadCmdResourcepackJson(mlow, entries) + "\n"
      );
    }

    // 8. README_УСТАНОВКА.txt
    const readme = [
      "================================================================================",
      `  MythicWardrobe Studio v2.0 (HTML) - Готовый пак (${packName})`,
      "================================================================================",
      "",
      `Всего .bbmodel в архиве: ${writtenModels.length}`,
      `Скрещенных комбо-моделей: ${summaryCombos.length}`,
      "",
      "УСТАНОВКА НА СЕРВЕР (MythicWardrobe v1.8.0+):",
      "  1. Распакуйте папку plugins/ из архива в корень сервера.",
      "  2. Выполните в игре / консоли:",
      "       /ma reload",
      "       /mm reload",
      "       /wardrobe reload",
      "       /wardrobe buildheadpack   (если надеваете несколько 3D-моделей на голову)",
      "  3. Подключите обновлённый ресурспак (plugins/MythicArmors/pack.zip или",
      "     plugins/MythicWardrobe/merged_pack.zip после buildheadpack).",
      "",
      "КОМАНДЫ ДЛЯ ВЫДАЧИ ВСЕХ ПРЕДМЕТОВ:",
      ...giveCommands.map(c => `  ${c}`),
    ];
    if (summaryCombos.length) {
      readme.push("", "СКРЕЩЕННЫЕ КОМБО-МОДЕЛИ:");
      for (const c of summaryCombos) {
        readme.push(`  • ${c.modelId}.bbmodel (${c.slot}, ${c.cubeCount} кубов, атлас ${c.atlasSize}) -> ${c.itemId}`);
      }
    }
    zip.addText("README_УСТАНОВКА.txt", readme.join("\n") + "\n");

    return {
      blob: zip.toBlob(),
      summary: {
        modelFiles: writtenModels,
        comboCount: summaryCombos.length,
        combos: summaryCombos,
        itemCount: giveCommands.length,
      },
    };
  }

  async function buildSkinPackZip(payload) {
    return buildStudioPackZip({
      packName: payload.packName || "wardrobe_2d_pack",
      includeStandalone: false,
      models: [],
      links: [],
      skinBundle: {
        include: true,
        priorityYaml: generatePriorityYml(payload.order || DEFAULT_SKIN_LAYERS.map(l => l.alias)),
        previewPngBase64: payload.previewPngBase64 || null,
        overlays: payload.overlays || [],
      },
    });
  }

  global.StudioEngine = {
    CANONICAL_BONES,
    SLOT_BONES,
    SLOT_SUFFIX,
    SLOT_DEFAULT_MATERIAL,
    DYEABLE_MATERIALS,
    DEFAULT_SKIN_LAYERS,
    BLOCKABLE_CHOICES,
    BLOCK_SHORTCUTS,
    OUTER_UV_RECTS,
    GUI_SLOT_CATALOG,
    GUI_BUTTON_ACTIONS,
    StudioZip,
    sanitizeId,
    toPascalId,
    resolveLayerId,
    expandBlockedTargets,
    analyzeBBModel,
    replaceBBModelTexture,
    combineBBModels,
    processSkinLayer,
    generatePriorityYml,
    generateHair3DYml,
    createDefaultMenusState,
    generateMenusYml,
    buildHeadCmdResourcepackJson,
    buildStudioPackZip,
    buildSkinPackZip,
  };
})(typeof window !== "undefined" ? window : globalThis);
