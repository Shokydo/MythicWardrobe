#!/usr/bin/env python3
"""
Generates a complete, production-ready MythicArmors + MythicWardrobe test armor pack:
  - 3 Blockbench (.bbmodel) 3D armor sets with exact MythicArmors canonical bones & pivots:
      1) tactical_warden.bbmodel       (Full 3D Tactical Warden armor + emissive + 2D fallbacks + 4 icons)
      2) gold_warden_cosmetic.bbmodel  (Full 3D Gold/Obsidian Cosmetic armor + crest + ruby emissive + icons)
      3) tactical_warden_masked.bbmodel (Combo 3D Helmet + Tactical Respirator for combo-overrides.yml)
  - MythicMobs 5.13+ item configs using official MythicArmors Equippable / ItemModel syntax
  - Pre-wired MythicWardrobe items.yml & combo-overrides.yml
  - Step-by-step Russian installation & testing guide
  - Packs everything into MythicWardrobe/releases/MythicWardrobe_MythicArmors_TestPack.zip
  - Also renders a visual verification preview PNG so we can inspect textures & silhouettes!
"""
import base64
import io
import json
import math
import os
import uuid
import zipfile
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "mythicarmors-test-pack")
ZIP_PATH = os.path.join(ROOT, "releases", "MythicWardrobe_MythicArmors_TestPack.zip")
PREVIEW_PNG = os.path.join(ROOT, "mythicarmors-test-pack", "_preview_check.png")


def deterministic_uuid(seed: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, "mythicwardrobe.armor." + seed))


def img_to_data_uri(img: Image.Image) -> str:
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("ascii")


def shade(rgb, delta):
    return tuple(max(0, min(255, c + delta)) for c in rgb[:3]) + ((rgb[3],) if len(rgb) == 4 else (255,))


# =============================================================================
# 1. PROCEDURAL PIXEL-ART TEXTURE PAINTERS (64x64 MAIN + EMISSIVE + FALLBACK + ICONS)
# =============================================================================
# 64x64 Atlas Tile Layout (each material tile is 16x16 so every cube face gets clean shading, rivets, trim!):
#   (0,  0, 16, 16) : Primary Heavy Armor Plate (beveled + rivets)
#   (16, 0, 16, 16) : Dark Under-Armor / Ballistic Weave (diagonal tactical mesh)
#   (32, 0, 16, 16) : Trim / Accent Metal (gold or brushed steel strips)
#   (48, 0, 16, 16) : Emissive Visor / Core Glow (bright glowing cyan / ruby / emerald!)
#   (0, 16, 16, 16) : Tactical MOLLE Pouch / Strap Texture
#   (16,16, 16, 16) : Boot Leather / Heavy Sole Tread
#   (32,16, 16, 16) : Helmet Side / Earcup Mechanical Detail
#   (48,16, 16, 16) : Gas Mask / Respirator Filter Grid + Glow Ring

UV_PLATE   = (0, 0, 16, 16)
UV_WEAVE   = (16, 0, 16, 16)
UV_TRIM    = (32, 0, 16, 16)
UV_GLOW    = (48, 0, 16, 16)
UV_POUCH   = (0, 16, 16, 16)
UV_BOOT    = (16, 16, 16, 16)
UV_MECH    = (32, 16, 16, 16)
UV_FILTER  = (48, 16, 16, 16)


def paint_armor_atlas(primary, dark, trim, glow, pouch, boot, filter_glow=None):
    """Returns (main_64x64, emissive_64x64)."""
    main = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    emis = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    dm = ImageDraw.Draw(main)
    de = ImageDraw.Draw(emis)

    # Tile 0: Heavy Armor Plate (0,0)
    dm.rectangle([0, 0, 15, 15], fill=primary)
    dm.rectangle([0, 0, 15, 1], fill=shade(primary, 24))      # top bevel
    dm.rectangle([0, 0, 1, 15], fill=shade(primary, 16))      # left bevel
    dm.rectangle([0, 14, 15, 15], fill=shade(primary, -26))   # bottom shadow
    dm.rectangle([14, 0, 15, 15], fill=shade(primary, -22))   # right shadow
    dm.rectangle([3, 3, 12, 12], outline=shade(primary, -14)) # inner panel seam
    # Corner rivets
    for rx, ry in [(2, 2), (13, 2), (2, 13), (13, 13)]:
        dm.point((rx, ry), fill=trim)

    # Tile 1: Tactical Ballistic Weave (16,0)
    dm.rectangle([16, 0, 31, 15], fill=dark)
    for y in range(16):
        for x in range(16):
            if (x + y) % 3 == 0:
                dm.point((16 + x, y), fill=shade(dark, 12))
            elif (x + y) % 3 == 2:
                dm.point((16 + x, y), fill=shade(dark, -10))

    # Tile 2: Accent Metal Trim (32,0)
    dm.rectangle([32, 0, 47, 15], fill=trim)
    dm.rectangle([32, 0, 47, 2], fill=shade(trim, 32))
    dm.rectangle([32, 13, 47, 15], fill=shade(trim, -32))
    for x in range(34, 46, 3):
        dm.line([(x, 4), (x, 11)], fill=shade(trim, -16))

    # Tile 3: Emissive Visor / Core Glow (48,0)
    dm.rectangle([48, 0, 63, 15], fill=shade(glow, -40))
    dm.rectangle([49, 1, 62, 14], fill=glow)
    dm.rectangle([50, 3, 61, 6], fill=shade(glow, 55))  # bright visor sheen
    dm.rectangle([48, 0, 63, 15], outline=dark)
    # Copy glowing core directly to emissive mask!
    de.rectangle([49, 1, 62, 14], fill=glow)
    de.rectangle([50, 3, 61, 6], fill=shade(glow, 60))

    # Tile 4: MOLLE Pouch / Straps (0,16)
    dm.rectangle([0, 16, 15, 31], fill=pouch)
    dm.rectangle([0, 16, 15, 20], fill=shade(pouch, 14))      # top flap
    dm.line([(0, 21), (15, 21)], fill=shade(pouch, -28))      # flap shadow
    dm.rectangle([6, 20, 9, 23], fill=trim)                   # buckle clasp
    for y in (25, 28):
        dm.line([(1, y), (14, y)], fill=shade(pouch, -16))    # MOLLE webbing

    # Tile 5: Armored Boot / Tread (16,16)
    dm.rectangle([16, 16, 31, 31], fill=boot)
    dm.rectangle([16, 16, 31, 18], fill=shade(boot, 18))
    dm.rectangle([16, 28, 31, 31], fill=shade(boot, -20))     # heavy rubber sole
    for x in range(18, 30, 3):
        dm.rectangle([x, 22, x + 1, 25], fill=trim)           # metal boot eyelets

    # Tile 6: Mechanical Earcup / Side Rail (32,16)
    dm.rectangle([32, 16, 47, 31], fill=dark)
    dm.rectangle([34, 18, 45, 29], fill=primary, outline=trim)
    dm.rectangle([38, 22, 41, 25], fill=glow)
    de.rectangle([38, 22, 41, 25], fill=glow)

    # Tile 7: Respirator / Gas Mask Filter Grid + bright 2px glowing ring (48,16)
    fg = filter_glow if filter_glow else glow
    dm.rectangle([48, 16, 63, 31], fill=shade(dark, -8))
    dm.rectangle([49, 17, 62, 30], fill=fg)
    dm.rectangle([51, 19, 60, 28], fill=dark)
    de.rectangle([49, 17, 62, 30], fill=fg)
    de.rectangle([51, 19, 60, 28], fill=(0, 0, 0, 0))
    for y in range(20, 28, 2):
        dm.line([(52, y), (59, y)], fill=shade(dark, 34))

    return main, emis


def paint_fallback_layers(primary, dark, trim, glow):
    """Paints standard 64x32 Minecraft vanilla armor layer_1 (helmet/chest/boots) and layer_2 (leggings)."""
    l1 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    d1 = ImageDraw.Draw(l1)
    # Helmet (0..31, 0..15)
    d1.rectangle([8, 0, 15, 7], fill=primary)
    d1.rectangle([0, 8, 31, 15], fill=primary)
    d1.rectangle([9, 10, 14, 12], fill=glow)   # visor
    d1.rectangle([8, 8, 15, 9], fill=trim)
    # Chestplate torso (16..39, 16..31) + arms (40..55, 16..31)
    d1.rectangle([20, 16, 35, 19], fill=trim)
    d1.rectangle([16, 20, 39, 31], fill=primary)
    d1.rectangle([21, 22, 26, 28], fill=dark, outline=trim)
    d1.rectangle([23, 23, 24, 24], fill=glow)
    d1.rectangle([44, 16, 51, 19], fill=trim)
    d1.rectangle([40, 20, 55, 31], fill=primary)
    d1.rectangle([40, 20, 55, 24], fill=trim)
    # Boots (0..15, 16..31)
    d1.rectangle([0, 26, 15, 31], fill=dark)
    d1.rectangle([0, 26, 15, 27], fill=trim)

    l2 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    d2 = ImageDraw.Draw(l2)
    # Waist (16..39, 16..31) + Legs (0..15, 16..31)
    d2.rectangle([16, 26, 39, 31], fill=dark)
    d2.rectangle([22, 28, 25, 30], fill=trim)
    d2.rectangle([4, 16, 11, 19], fill=dark)
    d2.rectangle([0, 20, 15, 29], fill=primary)
    d2.rectangle([4, 24, 7, 26], fill=trim)
    return l1, l2


def paint_icons(primary, dark, trim, glow, has_crest=False, has_mask=False):
    """Paints crisp 16x16 pixel-art inventory icons for helmet, chestplate, leggings, boots."""
    icons = {}

    # 1. Helmet icon
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if has_crest:
        d.rectangle([7, 0, 8, 3], fill=trim)
        d.rectangle([6, 1, 9, 2], fill=glow)
    d.rectangle([3, 3, 12, 12], fill=primary, outline=dark)
    d.rectangle([4, 4, 11, 5], fill=shade(primary, 24))
    d.rectangle([4, 7, 11, 9], fill=glow)       # glowing visor
    d.rectangle([3, 6, 12, 6], fill=trim)
    if has_mask:
        d.rectangle([5, 10, 10, 14], fill=dark, outline=trim)
        d.rectangle([3, 11, 5, 13], fill=(46, 214, 124, 255))
        d.rectangle([10, 11, 12, 13], fill=(46, 214, 124, 255))
    else:
        d.rectangle([6, 11, 9, 12], fill=(0, 0, 0, 0))
    icons["helmet"] = im

    # 2. Chestplate icon
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([2, 2, 13, 6], fill=trim, outline=dark)       # pauldrons
    d.rectangle([4, 3, 11, 13], fill=primary, outline=dark)   # vest core
    d.rectangle([6, 2, 9, 4], fill=(0, 0, 0, 0))              # neck cutout
    d.rectangle([6, 6, 9, 7], fill=glow)                      # chest LED
    d.rectangle([5, 10, 10, 12], fill=dark, outline=trim)     # pouches
    icons["chestplate"] = im

    # 3. Leggings icon
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([3, 2, 12, 5], fill=dark, outline=trim)       # tactical belt
    d.rectangle([7, 3, 8, 4], fill=trim)                      # buckle
    d.rectangle([3, 6, 7, 13], fill=primary, outline=dark)    # right leg
    d.rectangle([8, 6, 12, 13], fill=primary, outline=dark)   # left leg
    d.rectangle([4, 9, 6, 11], fill=trim)                     # kneepads
    d.rectangle([9, 9, 11, 11], fill=trim)
    icons["leggings"] = im

    # 4. Boots icon
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([2, 6, 6, 13], fill=primary, outline=dark)
    d.rectangle([1, 11, 6, 13], fill=dark)
    d.rectangle([2, 8, 6, 9], fill=trim)
    d.rectangle([9, 6, 13, 13], fill=primary, outline=dark)
    d.rectangle([9, 11, 14, 13], fill=dark)
    d.rectangle([9, 8, 13, 9], fill=trim)
    icons["boots"] = im

    return icons


# =============================================================================
# 2. BLOCKBENCH (.BBMODEL) BUILDER WITH EXACT MYTHICARMORS CANONICAL BONES
# =============================================================================
class BBModelBuilder:
    def __init__(self, model_name: str, namespace: str):
        self.model_name = model_name
        self.namespace = namespace
        self.elements = []
        self.groups = []
        self.textures = []

    def add_texture(self, filename: str, img: Image.Image, uv_w=None, uv_h=None) -> int:
        idx = len(self.textures)
        self.textures.append({
            "path": "",
            "name": filename,
            "folder": "armor",
            "namespace": self.namespace,
            "id": str(idx),
            "width": img.width,
            "height": img.height,
            "uv_width": uv_w if uv_w else img.width,
            "uv_height": uv_h if uv_h else img.height,
            "particle": False,
            "layers_enabled": False,
            "sync_to_project": "",
            "render_mode": "default",
            "render_sides": "auto",
            "frame_time": 1,
            "frame_order_type": "loop",
            "frame_order": "",
            "frame_interpolate": False,
            "visible": True,
            "internal": True,
            "saved": True,
            "uuid": deterministic_uuid(f"{self.model_name}.tex.{filename}"),
            "source": img_to_data_uri(img),
        })
        return idx

    def add_bone(self, name: str, origin):
        grp = {
            "name": name,
            "origin": list(origin),
            "color": len(self.groups) % 8,
            "uuid": deterministic_uuid(f"{self.model_name}.bone.{name}"),
            "export": True,
            "mirror_uv": False,
            "isOpen": True,
            "locked": False,
            "visibility": True,
            "autouv": 0,
            "children": [],
        }
        self.groups.append(grp)
        return grp

    def add_cube(self, bone, name: str, fr, to, uv_rect, rotation=(0, 0, 0), pivot=None, face_uvs=None, tex_idx=0):
        uid = deterministic_uuid(f"{self.model_name}.{bone['name']}.{name}")
        x0, y0, z0 = fr
        x1, y1, z1 = to
        assert x1 > x0 and y1 > y0 and z1 > z0, f"Invalid cube bounds {name}: {fr} -> {to}"

        u0, v0, u1, v1 = uv_rect[0], uv_rect[1], uv_rect[0] + uv_rect[2], uv_rect[1] + uv_rect[3]
        faces = {}
        for side in ("north", "east", "south", "west", "up", "down"):
            rect = face_uvs.get(side, uv_rect) if face_uvs else uv_rect
            su0, sv0, su1, sv1 = rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]
            faces[side] = {"uv": [su0, sv0, su1, sv1], "texture": tex_idx}

        elem = {
            "name": name,
            "box_uv": False,
            "rescale": False,
            "locked": False,
            "light_emission": 0,
            "render_order": "default",
            "allow_mirror_modeling": True,
            "from": [round(x0, 3), round(y0, 3), round(z0, 3)],
            "to": [round(x1, 3), round(y1, 3), round(z1, 3)],
            "autouv": 0,
            "color": bone["color"],
            "origin": list(pivot) if pivot else list(bone["origin"]),
            "rotation": list(rotation),
            "faces": faces,
            "type": "cube",
            "uuid": uid,
        }
        self.elements.append(elem)
        bone["children"].append(uid)

    def to_dict(self):
        return {
            "meta": {
                "format_version": "4.10",
                "model_format": "free",
                "box_uv": False,
            },
            "name": self.model_name,
            "model_identifier": "",
            "visible_box": [1, 1, 0],
            "variable_placeholders": "",
            "variable_placeholder_buttons": [],
            "timeline_setups": [],
            "unhandled_root_fields": {},
            "resolution": {"width": getattr(self, "res_w", 64), "height": getattr(self, "res_h", 64)},
            "elements": self.elements,
            "outliner": self.groups,
            "textures": self.textures,
        }


def build_warden_set(model_name, palette, include_body=True, crest=False, gas_mask=False):
    b = BBModelBuilder(model_name, model_name)
    main_img, emis_img = paint_armor_atlas(
        palette["primary"], palette["dark"], palette["trim"],
        palette["glow"], palette["pouch"], palette["boot"],
        palette.get("filter_glow")
    )
    l1, l2 = paint_fallback_layers(palette["primary"], palette["dark"], palette["trim"], palette["glow"])
    icons = paint_icons(palette["primary"], palette["dark"], palette["trim"], palette["glow"], crest, gas_mask)

    # Index 0: Main texture applied to cubes
    b.add_texture(f"{model_name}_main.png", main_img, 64, 64)
    # Auxiliary unapplied MythicArmors textures (emissive, 2D fallbacks, inventory icons)
    b.add_texture(f"{model_name}_main_e.png", emis_img, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_layer_2.png", l2, 64, 32)
    b.add_texture(f"{model_name}_helmet.png", icons["helmet"], 16, 16)
    if include_body:
        b.add_texture(f"{model_name}_chestplate.png", icons["chestplate"], 16, 16)
        b.add_texture(f"{model_name}_leggings.png", icons["leggings"], 16, 16)
        b.add_texture(f"{model_name}_boots.png", icons["boots"], 16, 16)

    # --- HEAD BONE (pivot [0, 24, 0]) ---
    head = b.add_bone("head", [0, 24, 0])
    b.add_cube(head, "helmet_dome", [-4.65, 28.6, -4.65], [4.65, 32.85, 4.65], UV_PLATE)
    b.add_cube(head, "helmet_ridge", [-1.4, 32.6, -4.85], [1.4, 33.6, 4.5], UV_TRIM)
    b.add_cube(head, "helmet_brim", [-4.75, 28.5, -5.45], [4.75, 29.4, -4.4], UV_TRIM)
    b.add_cube(head, "helmet_visor", [-4.3, 26.8, -5.05], [4.3, 28.7, -4.2], UV_WEAVE, face_uvs={"north": UV_GLOW})
    b.add_cube(head, "helmet_back", [-4.65, 24.4, 2.2], [4.65, 28.7, 4.75], UV_PLATE)
    b.add_cube(head, "helmet_cheek_r", [-4.75, 24.8, -3.4], [-3.9, 28.7, 3.0], UV_PLATE)
    b.add_cube(head, "helmet_cheek_l", [3.9, 24.8, -3.4], [4.75, 28.7, 3.0], UV_PLATE)
    b.add_cube(head, "helmet_ear_r", [-5.25, 26.1, -1.1], [-4.65, 28.3, 1.3], UV_MECH)
    b.add_cube(head, "helmet_ear_l", [4.65, 26.1, -1.1], [5.25, 28.3, 1.3], UV_MECH)

    if crest:
        # Royal cosmetic gilded crest + horns!
        b.add_cube(head, "crest_fin_mid", [-0.75, 33.3, -5.2], [0.75, 36.2, 5.2], UV_TRIM, face_uvs={"north": UV_GLOW})
        b.add_cube(head, "crest_wing_r", [-5.4, 29.0, -2.0], [-4.5, 33.2, 1.6], UV_TRIM, rotation=[0, 0, -15], pivot=[-4.8, 29.0, 0])
        b.add_cube(head, "crest_wing_l", [4.5, 29.0, -2.0], [5.4, 33.2, 1.6], UV_TRIM, rotation=[0, 0, 15], pivot=[4.8, 29.0, 0])
    else:
        b.add_cube(head, "nvg_mount", [-1.1, 31.2, -5.45], [1.1, 33.1, -4.5], UV_MECH)

    if gas_mask:
        # Sculpted 3D Tactical Respirator integrated with helmet!
        b.add_cube(head, "mask_chin_wrap", [-4.3, 23.9, -4.9], [4.3, 26.9, -3.6], UV_WEAVE)
        b.add_cube(head, "mask_snout", [-1.9, 24.1, -6.1], [1.9, 26.7, -4.6], UV_PLATE, face_uvs={"north": UV_FILTER})
        b.add_cube(head, "mask_filter_r", [-4.6, 24.0, -5.85], [-2.1, 26.3, -4.1], UV_FILTER, rotation=[0, 22, 0], pivot=[-3.3, 25.1, -4.9])
        b.add_cube(head, "mask_filter_l", [2.1, 24.0, -5.85], [4.6, 26.3, -4.1], UV_FILTER, rotation=[0, -22, 0], pivot=[3.3, 25.1, -4.9])
        b.add_cube(head, "mask_strap_r", [-4.85, 24.8, -3.8], [-4.2, 26.0, 4.4], UV_WEAVE)
        b.add_cube(head, "mask_strap_l", [4.2, 24.8, -3.8], [4.85, 26.0, 4.4], UV_WEAVE)

    if not include_body:
        return b, main_img, emis_img, icons

    # --- BODY BONE (pivot [0, 24, 0]) ---
    body = b.add_bone("body", [0, 24, 0])
    b.add_cube(body, "vest_base", [-4.55, 13.0, -2.55], [4.55, 24.3, 2.55], UV_WEAVE)
    b.add_cube(body, "vest_front_plate", [-3.85, 15.2, -3.35], [3.85, 23.1, -2.4], UV_PLATE)
    b.add_cube(body, "vest_back_plate", [-3.85, 15.0, 2.4], [3.85, 23.2, 3.35], UV_PLATE)
    b.add_cube(body, "vest_collar", [-3.7, 23.7, -2.85], [3.7, 25.1, 2.85], UV_TRIM)
    b.add_cube(body, "vest_strap_r", [-3.6, 22.8, -2.95], [-1.7, 24.75, 2.95], UV_TRIM)
    b.add_cube(body, "vest_strap_l", [1.7, 22.8, -2.95], [3.6, 24.75, 2.95], UV_TRIM)
    b.add_cube(body, "vest_core_led", [-1.35, 20.2, -3.65], [1.35, 21.8, -3.2], UV_TRIM, face_uvs={"north": UV_GLOW})
    b.add_cube(body, "vest_pouch_r", [-3.55, 13.8, -3.95], [-1.35, 17.5, -3.15], UV_POUCH)
    b.add_cube(body, "vest_pouch_m", [-1.1, 13.8, -3.95], [1.1, 17.5, -3.15], UV_POUCH)
    b.add_cube(body, "vest_pouch_l", [1.35, 13.8, -3.95], [3.55, 17.5, -3.15], UV_POUCH)

    # --- RIGHT ARM BONE (character RIGHT = East +X, pivot [+5, 22, 0]) ---
    r_arm = b.add_bone("right_arm", [5, 22, 0])
    b.add_cube(r_arm, "r_pauldron", [3.9, 19.6, -2.85], [9.05, 24.7, 2.85], UV_PLATE)
    b.add_cube(r_arm, "r_pauldron_cap", [4.35, 22.8, -2.65], [9.35, 25.25, 2.65], UV_TRIM, rotation=[0, 0, -10], pivot=[6.5, 23.5, 0])
    b.add_cube(r_arm, "r_bracer", [3.9, 13.0, -2.55], [8.75, 17.6, 2.55], UV_PLATE)
    b.add_cube(r_arm, "r_gauntlet_fin", [8.55, 13.8, -1.5], [9.15, 17.0, 1.5], UV_TRIM)

    # --- LEFT ARM BONE (character LEFT = West -X, pivot [-5, 22, 0]) ---
    l_arm = b.add_bone("left_arm", [-5, 22, 0])
    b.add_cube(l_arm, "l_pauldron", [-9.05, 19.6, -2.85], [-3.9, 24.7, 2.85], UV_PLATE)
    b.add_cube(l_arm, "l_pauldron_cap", [-9.35, 22.8, -2.65], [-4.35, 25.25, 2.65], UV_TRIM, rotation=[0, 0, 10], pivot=[-6.5, 23.5, 0])
    b.add_cube(l_arm, "l_bracer", [-8.75, 13.0, -2.55], [-3.9, 17.6, 2.55], UV_PLATE)
    b.add_cube(l_arm, "l_gauntlet_fin", [-9.15, 13.8, -1.5], [-8.55, 17.0, 1.5], UV_TRIM)

    # --- WAIST BONE (pivot [0, 12, 0]) ---
    waist = b.add_bone("waist", [0, 12, 0])
    b.add_cube(waist, "belt_wrap", [-4.6, 10.6, -2.7], [4.6, 13.0, 2.7], UV_WEAVE)
    b.add_cube(waist, "belt_buckle", [-1.5, 10.8, -3.2], [1.5, 12.8, -2.5], UV_TRIM)
    b.add_cube(waist, "belt_pouch_r", [4.35, 10.3, -1.4], [5.15, 12.9, 1.4], UV_POUCH)
    b.add_cube(waist, "belt_pouch_l", [-5.15, 10.3, -1.4], [-4.35, 12.9, 1.4], UV_POUCH)

    # --- RIGHT LEG BONE (character RIGHT = East +X, pivot [+2, 12, 0]) ---
    r_leg = b.add_bone("right_leg", [2, 12, 0])
    b.add_cube(r_leg, "r_thigh", [0.08, 4.6, -2.45], [4.45, 11.4, 2.45], UV_PLATE)
    b.add_cube(r_leg, "r_kneepad", [0.5, 4.7, -3.15], [3.7, 7.6, -2.2], UV_TRIM)
    b.add_cube(r_leg, "r_holster", [4.25, 6.2, -1.35], [5.0, 10.2, 1.35], UV_POUCH)

    # --- LEFT LEG BONE (character LEFT = West -X, pivot [-2, 12, 0]) ---
    l_leg = b.add_bone("left_leg", [-2, 12, 0])
    b.add_cube(l_leg, "l_thigh", [-4.45, 4.6, -2.45], [-0.08, 11.4, 2.45], UV_PLATE)
    b.add_cube(l_leg, "l_kneepad", [-3.7, 4.7, -3.15], [-0.5, 7.6, -2.2], UV_TRIM)
    b.add_cube(l_leg, "l_thigh_plate", [-4.85, 6.8, -1.5], [-4.25, 10.0, 1.5], UV_TRIM)

    # --- RIGHT FOOT BONE (character RIGHT = East +X, pivot [+2, 12, 0]) ---
    r_foot = b.add_bone("right_foot", [2, 12, 0])
    b.add_cube(r_foot, "r_boot_shell", [0.05, 0.0, -2.6], [4.55, 4.6, 2.55], UV_BOOT)
    b.add_cube(r_foot, "r_boot_toe", [0.2, 0.0, -3.45], [4.4, 2.4, -2.4], UV_PLATE)
    b.add_cube(r_foot, "r_boot_sole", [0.02, -0.05, -3.55], [4.65, 1.0, 2.7], UV_WEAVE)
    b.add_cube(r_foot, "r_boot_cuff", [0.02, 3.7, -2.75], [4.65, 4.8, 2.7], UV_TRIM)

    # --- LEFT FOOT BONE (character LEFT = West -X, pivot [-2, 12, 0]) ---
    l_foot = b.add_bone("left_foot", [-2, 12, 0])
    b.add_cube(l_foot, "l_boot_shell", [-4.55, 0.0, -2.6], [-0.05, 4.6, 2.55], UV_BOOT)
    b.add_cube(l_foot, "l_boot_toe", [-4.4, 0.0, -3.45], [-0.2, 2.4, -2.4], UV_PLATE)
    b.add_cube(l_foot, "l_boot_sole", [-4.65, -0.05, -3.55], [-0.02, 1.0, 2.7], UV_WEAVE)
    b.add_cube(l_foot, "l_boot_cuff", [-4.65, 3.7, -2.75], [-0.02, 4.8, 2.7], UV_TRIM)

    return b, main_img, emis_img, icons


# =============================================================================
# 2B. 3D TACTICAL BACKPACK WORN IN CHESTPLATE SLOT (tactical_backpack.bbmodel)
# =============================================================================
BP_UV_CANVAS  = (0, 0, 16, 16)
BP_UV_MOLLE   = (16, 0, 16, 16)
BP_UV_STRAP   = (32, 0, 16, 16)
BP_UV_METAL   = (48, 0, 16, 16)
BP_UV_BEDROLL = (0, 16, 16, 16)
BP_UV_POUCH   = (16, 16, 16, 16)
BP_UV_GLOW    = (32, 16, 16, 16)
BP_UV_PATCH   = (48, 16, 16, 16)


def mul_shade(rgb, factor):
    return tuple(max(0, min(255, int(c * factor))) for c in rgb[:3]) + ((rgb[3],) if len(rgb) == 4 else (255,))


def paint_beveled_tile(img: Image.Image, x0: int, y0: int, w: int, h: int, col, border_dark=0.70, hi=1.15):
    d = ImageDraw.Draw(img)
    d.rectangle([x0, y0, x0 + w - 1, y0 + h - 1], fill=col)
    d.line([(x0, y0), (x0 + w - 1, y0)], fill=mul_shade(col, hi))
    d.line([(x0, y0), (x0, y0 + h - 1)], fill=mul_shade(col, (1.0 + hi) / 2.0))
    d.line([(x0, y0 + h - 1), (x0 + w - 1, y0 + h - 1)], fill=mul_shade(col, border_dark))
    d.line([(x0 + w - 1, y0), (x0 + w - 1, y0 + h - 1)], fill=mul_shade(col, border_dark))


def paint_backpack_atlas():
    main = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    emis = Image.new("RGBA", (64, 64), (0, 0, 0, 0))

    canvas_col  = (72, 86, 62, 255)   # Tactical ranger olive canvas
    dark_col    = (30, 34, 38, 255)   # Ballistic nylon straps
    metal_col   = (124, 134, 146, 255)# Gunmetal hardware/buckles
    bedroll_col = (164, 134, 92, 255) # Coyote tan bedroll
    pouch_col   = (58, 70, 50, 255)   # Darker olive utility pouch
    glow_col    = (46, 224, 248, 255) # Tactical cyan IFF LED

    # 1. Olive canvas with subtle weave + stitched border
    paint_beveled_tile(main, 0, 0, 16, 16, canvas_col, border_dark=0.68, hi=1.12)
    for y in range(2, 14):
        for x in range(2, 14):
            if (x + y) % 2 == 0:
                main.putpixel((x, y), mul_shade(canvas_col, 0.94))

    # 2. MOLLE webbing tile (horizontal tactical loops)
    paint_beveled_tile(main, 16, 0, 16, 16, canvas_col, border_dark=0.68, hi=1.10)
    for row in (3, 7, 11):
        for x in range(17, 31):
            main.putpixel((x, row), mul_shade(dark_col, 1.15))
            main.putpixel((x, row + 1), mul_shade(dark_col, 0.85))
        for bx in (20, 24, 28):
            main.putpixel((bx, row), mul_shade(canvas_col, 1.2))
            main.putpixel((bx, row + 1), mul_shade(canvas_col, 0.78))

    # 3. Ballistic nylon straps
    paint_beveled_tile(main, 32, 0, 16, 16, dark_col, border_dark=0.55, hi=1.25)
    for y in range(2, 14, 3):
        for x in range(34, 46):
            main.putpixel((x, y), mul_shade(dark_col, 1.32))

    # 4. Gunmetal buckles / radio housing
    paint_beveled_tile(main, 48, 0, 16, 16, metal_col, border_dark=0.60, hi=1.22)
    for x in range(51, 61):
        main.putpixel((x, 7), mul_shade(dark_col, 0.9))
        main.putpixel((x, 8), mul_shade(dark_col, 0.9))

    # 5. Coyote tan rolled sleeping mat (with roll ridges!)
    paint_beveled_tile(main, 0, 16, 16, 16, bedroll_col, border_dark=0.72, hi=1.14)
    for y in range(18, 30, 3):
        for x in range(1, 15):
            main.putpixel((x, y), mul_shade(bedroll_col, 0.84))

    # 6. Utility / canteen side pouch
    paint_beveled_tile(main, 16, 16, 16, 16, pouch_col, border_dark=0.65, hi=1.15)
    for x in range(18, 30):
        main.putpixel((x, 21), mul_shade(dark_col, 1.0))
        main.putpixel((x, 22), mul_shade(metal_col, 0.9))

    # 7. Emissive cyan tactical beacon
    paint_beveled_tile(main, 32, 16, 16, 16, dark_col, border_dark=0.55, hi=1.1)
    for y in range(19, 29):
        for x in range(35, 45):
            c = mul_shade(glow_col, 1.15 if 21 <= y <= 26 else 0.92)
            main.putpixel((x, y), c)
            emis.putpixel((x, y), c)

    # 8. Velcro morale patch on top flap
    paint_beveled_tile(main, 48, 16, 16, 16, canvas_col, border_dark=0.70, hi=1.1)
    for y in range(20, 28):
        for x in range(51, 61):
            main.putpixel((x, y), dark_col)
    # Subtle chevron inside patch
    for i in range(4):
        main.putpixel((55 - i, 22 + i), mul_shade(bedroll_col, 1.1))
        main.putpixel((56 + i, 22 + i), mul_shade(bedroll_col, 1.1))

    # 2D Fallback layer_1 (for macOS / far distance)
    l1 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    d1 = ImageDraw.Draw(l1)
    d1.rectangle([21, 20, 22, 31], fill=dark_col)
    d1.rectangle([25, 20, 26, 31], fill=dark_col)
    d1.rectangle([22, 24, 25, 25], fill=metal_col)
    d1.rectangle([32, 20, 39, 31], fill=canvas_col, outline=mul_shade(canvas_col, 0.65))
    d1.rectangle([33, 21, 38, 22], fill=bedroll_col)
    d1.rectangle([34, 27, 37, 28], fill=glow_col)

    # Custom 16x16 inventory icon: crisp pixel-art tactical backpack!
    icon = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    di = ImageDraw.Draw(icon)
    di.rectangle([3, 1, 12, 3], fill=bedroll_col, outline=mul_shade(bedroll_col, 0.62))
    di.line([(5, 1), (5, 3)], fill=dark_col)
    di.line([(10, 1), (10, 3)], fill=dark_col)
    di.line([(12, 0), (12, 3)], fill=dark_col)
    icon.putpixel((12, 0), glow_col)
    di.rectangle([3, 4, 12, 14], fill=canvas_col, outline=mul_shade(canvas_col, 0.58))
    di.rectangle([1, 7, 2, 12], fill=pouch_col, outline=mul_shade(pouch_col, 0.58))
    di.rectangle([13, 7, 14, 12], fill=pouch_col, outline=mul_shade(pouch_col, 0.58))
    di.rectangle([3, 4, 12, 7], fill=mul_shade(canvas_col, 1.12), outline=mul_shade(canvas_col, 0.62))
    di.line([(5, 4), (5, 9)], fill=dark_col)
    di.line([(10, 4), (10, 9)], fill=dark_col)
    icon.putpixel((5, 7), metal_col)
    icon.putpixel((10, 7), metal_col)
    di.rectangle([4, 10, 11, 13], fill=pouch_col, outline=mul_shade(pouch_col, 0.60))
    di.line([(7, 11), (8, 11)], fill=glow_col)

    return main, emis, l1, icon


def build_tactical_backpack(model_name="tactical_backpack"):
    b = BBModelBuilder(model_name, model_name)
    main_img, emis_img, l1, icon = paint_backpack_atlas()

    b.add_texture(f"{model_name}_main.png", main_img, 64, 64)
    b.add_texture(f"{model_name}_main_e.png", emis_img, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_chestplate.png", icon, 16, 16)

    # Chestplate rig: `body` carries harness + backpack; keep empty `right_arm`/`left_arm` bones clean
    body = b.add_bone("body", [0, 24, 0])
    b.add_bone("right_arm", [5, 22, 0])
    b.add_bone("left_arm", [-5, 22, 0])

    # --- FRONT HARNESS STRAPS (hugging player torso Z = -2..+2 with outer-layer clearance!) ---
    b.add_cube(body, "strap_front_r",     [1.75, 13.6, -2.65],  [3.55, 24.45, -2.02], BP_UV_STRAP)
    b.add_cube(body, "strap_front_l",     [-3.55, 13.6, -2.65], [-1.75, 24.45, -2.02], BP_UV_STRAP)
    b.add_cube(body, "strap_top_r",       [1.75, 24.02, -2.65], [3.55, 24.72, 2.75],  BP_UV_STRAP)
    b.add_cube(body, "strap_top_l",       [-3.55, 24.02, -2.65], [-1.75, 24.72, 2.75], BP_UV_STRAP)
    b.add_cube(body, "strap_under_r",     [3.55, 13.6, -2.65],  [4.18, 14.95, 2.85],  BP_UV_STRAP)
    b.add_cube(body, "strap_under_l",     [-4.18, 13.6, -2.65], [-3.55, 14.95, 2.85], BP_UV_STRAP)
    b.add_cube(body, "chest_cross_strap", [-2.2, 18.8, -2.75],  [2.2, 20.1, -2.2],    BP_UV_STRAP)
    b.add_cube(body, "chest_buckle",      [-0.85, 18.55, -3.05], [0.85, 20.35, -2.45], BP_UV_METAL,
               face_uvs={"north": BP_UV_GLOW})
    b.add_cube(body, "shoulder_radio_mic", [1.9, 20.5, -3.15],  [3.4, 22.75, -2.4],   BP_UV_METAL)

    # --- BACKPACK MAIN COMPARTMENT (South +Z, hugs bare skin AND clears chestplate backplates!) ---
    b.add_cube(body, "pack_back_pad",      [-4.1, 13.4, 2.1],   [4.1, 23.3, 3.35],   BP_UV_STRAP)
    b.add_cube(body, "pack_main_body",     [-4.35, 13.2, 3.1],  [4.35, 23.1, 7.15],  BP_UV_CANVAS,
               face_uvs={"south": BP_UV_MOLLE})
    b.add_cube(body, "pack_top_flap",      [-4.55, 21.8, 2.85], [4.55, 23.95, 7.6],  BP_UV_CANVAS,
               face_uvs={"south": BP_UV_PATCH})
    b.add_cube(body, "pack_flap_strap_r",  [1.85, 18.5, 6.95],  [2.95, 24.1, 7.8],   BP_UV_STRAP)
    b.add_cube(body, "pack_flap_strap_l",  [-2.95, 18.5, 6.95], [-1.85, 24.1, 7.8],  BP_UV_STRAP)
    b.add_cube(body, "pack_buckle_r",      [1.75, 18.7, 7.3],   [3.05, 19.8, 7.95],  BP_UV_METAL)
    b.add_cube(body, "pack_buckle_l",      [-3.05, 18.7, 7.3],  [-1.75, 19.8, 7.95], BP_UV_METAL)
    b.add_cube(body, "pack_lower_pouch",   [-3.55, 13.6, 6.95], [3.55, 18.4, 8.55],  BP_UV_POUCH,
               face_uvs={"south": BP_UV_MOLLE})
    b.add_cube(body, "pack_beacon",        [-1.15, 16.9, 8.4],  [1.15, 17.85, 8.8],  BP_UV_METAL,
               face_uvs={"south": BP_UV_GLOW})
    b.add_cube(body, "pack_side_pouch_r",  [4.15, 14.0, 3.55],  [5.55, 19.7, 6.55],  BP_UV_POUCH)
    b.add_cube(body, "pack_side_pouch_l",  [-5.55, 14.0, 3.55], [-4.15, 19.7, 6.55], BP_UV_POUCH)

    # --- ROLLED SLEEPING MAT ON TOP + COMMS RADIO ANTENNA (zero intersection!) ---
    b.add_cube(body, "pack_bedroll",         [-4.75, 23.95, 3.7],  [4.75, 26.65, 6.4],  BP_UV_BEDROLL)
    b.add_cube(body, "pack_bedroll_strap_r", [1.95, 23.8, 3.5],    [2.85, 26.85, 6.6],  BP_UV_STRAP)
    b.add_cube(body, "pack_bedroll_strap_l", [-2.85, 23.8, 3.5],   [-1.95, 26.85, 6.6], BP_UV_STRAP)
    b.add_cube(body, "pack_radio_box",       [2.65, 20.1, 6.7],    [4.45, 23.65, 8.0],  BP_UV_METAL)
    b.add_cube(body, "pack_antenna",         [3.35, 23.65, 7.1],   [3.85, 30.2, 7.6],   BP_UV_STRAP)
    b.add_cube(body, "pack_antenna_tip",     [3.25, 29.95, 7.0],   [3.95, 30.7, 7.7],   BP_UV_GLOW)

    return b, main_img, emis_img, icon


# =============================================================================
# 2C. STANDALONE 3D TACTICAL GAS MASK (tactical_gasmask.bbmodel)
#     Can be worn ALONE in helmet slot OR layered in mask_3d over ANY helmet!
# =============================================================================
def build_tactical_gasmask(model_name, palette):
    b = BBModelBuilder(model_name, model_name)
    main_img, emis_img = paint_armor_atlas(
        palette["primary"], palette["dark"], palette["trim"],
        palette["glow"], palette["pouch"], palette["boot"],
        palette.get("filter_glow")
    )
    l1, l2 = paint_fallback_layers(palette["primary"], palette["dark"], palette["trim"], palette["glow"])

    # Dedicated 16x16 pixel-art gas mask icon
    icon = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    di = ImageDraw.Draw(icon)
    dark = palette["dark"]
    plate = palette["primary"]
    trim = palette["trim"]
    fglow = palette.get("filter_glow", (46, 232, 134, 255))
    di.rectangle([3, 3, 12, 12], fill=dark, outline=mul_shade(dark, 0.55))
    di.rectangle([4, 4, 7, 7], fill=mul_shade(plate, 1.25), outline=trim)
    di.rectangle([8, 4, 11, 7], fill=mul_shade(plate, 1.25), outline=trim)
    di.rectangle([6, 8, 9, 13], fill=plate, outline=trim)
    di.rectangle([1, 9, 4, 13], fill=dark, outline=trim)
    di.rectangle([2, 10, 3, 12], fill=fglow)
    di.rectangle([11, 9, 14, 13], fill=dark, outline=trim)
    di.rectangle([12, 10, 13, 12], fill=fglow)
    icon.putpixel((7, 10), fglow)
    icon.putpixel((8, 10), fglow)

    b.add_texture(f"{model_name}_main.png", main_img, 64, 64)
    b.add_texture(f"{model_name}_main_e.png", emis_img, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_layer_2.png", l2, 64, 32)
    b.add_texture(f"{model_name}_helmet.png", icon, 16, 16)

    head = b.add_bone("head", [0, 24, 0])
    # Sculpted lower-face tactical respirator + dual angled canisters + straps (clears both bare head & helmets!)
    b.add_cube(head, "mask_chin_wrap",  [-4.4, 23.85, -5.05], [4.4, 26.85, -3.75], UV_WEAVE)
    b.add_cube(head, "mask_nose_bridge",[-1.6, 26.4, -5.25],  [1.6, 27.6, -4.15],  UV_PLATE)
    b.add_cube(head, "mask_snout",      [-1.95, 24.0, -6.25], [1.95, 26.7, -4.7],  UV_PLATE, face_uvs={"north": UV_FILTER})
    b.add_cube(head, "mask_filter_r",   [2.15, 23.95, -6.0],  [4.7, 26.35, -4.15], UV_FILTER, rotation=[0, -22, 0], pivot=[3.35, 25.1, -5.0])
    b.add_cube(head, "mask_filter_l",   [-4.7, 23.95, -6.0],  [-2.15, 26.35, -4.15], UV_FILTER, rotation=[0, 22, 0], pivot=[-3.35, 25.1, -5.0])
    b.add_cube(head, "mask_strap_r",    [4.25, 24.75, -3.8],  [4.95, 26.0, 4.55],  UV_WEAVE)
    b.add_cube(head, "mask_strap_l",    [-4.95, 24.75, -3.8], [-4.25, 26.0, 4.55], UV_WEAVE)
    b.add_cube(head, "mask_strap_back", [-4.6, 24.75, 4.1],   [4.6, 26.0, 4.85],   UV_WEAVE)

    return b, main_img, emis_img, icon


# =============================================================================
# 2D. ZERO-LAG NATIVE COMBO MERGER (Chestplate + Backpack / Helmet + GasMask)
#     Stitches two 64x64 atlases into one 128x64 atlas (and 128x64 _e.png) so
#     both models fuse natively onto the player's own bone with 0 entities & 0 lag!
# =============================================================================
def merge_two_models(b_a: BBModelBuilder, img_a: Image.Image, emis_a: Image.Image,
                     b_b: BBModelBuilder, img_b: Image.Image, emis_b: Image.Image,
                     out_name: str, keep_bones=None, skip_b_names=()):
    d_a = b_a.to_dict()
    d_b = b_b.to_dict()

    w_a, h_a = img_a.size
    w_b, h_b = img_b.size
    out_w, out_h = w_a + w_b, max(h_a, h_b)

    stitched_main = Image.new("RGBA", (out_w, out_h), (0, 0, 0, 0))
    stitched_main.paste(img_a, (0, 0))
    stitched_main.paste(img_b, (w_a, 0))

    stitched_emis = Image.new("RGBA", (out_w, out_h), (0, 0, 0, 0))
    if emis_a is not None:
        stitched_emis.paste(emis_a, (0, 0))
    if emis_b is not None:
        stitched_emis.paste(emis_b, (w_a, 0))

    # Build bone->child-uuids maps
    def bone_elements(model_dict):
        by_uuid = {e["uuid"]: e for e in model_dict["elements"]}
        out = {}
        for g in model_dict["outliner"]:
            bname = g["name"]
            out[bname] = (g["origin"], [by_uuid[cid] for cid in g["children"] if cid in by_uuid])
        return out

    bones_a = bone_elements(d_a)
    bones_b = bone_elements(d_b)

    out_b = BBModelBuilder(out_name, out_name)
    out_b.res_w = out_w
    out_b.res_h = out_h
    out_b.add_texture(f"{out_name}_main.png", stitched_main, out_w, out_h)
    out_b.add_texture(f"{out_name}_main_e.png", stitched_emis, out_w, out_h)

    # Copy fallback & icon textures from model A (index >= 2)
    for t in d_a["textures"][2:]:
        t_copy = dict(t)
        t_name = t["name"]
        suffix = t_name.split(d_a["name"] + "_")[-1]
        new_t_name = f"{out_name}_{suffix}"
        t_copy["name"] = new_t_name
        t_copy["path"] = new_t_name
        t_copy["id"] = str(len(out_b.textures))
        t_copy["uuid"] = deterministic_uuid(f"{out_name}.copytex.{new_t_name}")
        out_b.textures.append(t_copy)

    all_bone_names = list(bones_a.keys()) + [bn for bn in bones_b.keys() if bn not in bones_a]
    if keep_bones is not None:
        all_bone_names = [bn for bn in all_bone_names if bn in keep_bones]

    idx_counter = 0
    for bname in all_bone_names:
        origin = bones_a[bname][0] if bname in bones_a else bones_b[bname][0]
        grp = out_b.add_bone(bname, origin)

        if bname in bones_a:
            for el in bones_a[bname][1]:
                el_copy = json.loads(json.dumps(el))
                idx_counter += 1
                el_copy["uuid"] = deterministic_uuid(f"{out_name}.el.{idx_counter}")
                out_b.elements.append(el_copy)
                grp["children"].append(el_copy["uuid"])

        if bname in bones_b:
            for el in bones_b[bname][1]:
                if el["name"] in skip_b_names:
                    continue
                el_copy = json.loads(json.dumps(el))
                idx_counter += 1
                el_copy["uuid"] = deterministic_uuid(f"{out_name}.el.{idx_counter}")
                for f in el_copy["faces"].values():
                    u0, v0, u1, v1 = f["uv"]
                    f["uv"] = [u0 + w_a, v0, u1 + w_a, v1]
                    f["texture"] = 0
                out_b.elements.append(el_copy)
                grp["children"].append(el_copy["uuid"])

    return out_b, stitched_main, stitched_emis


# =============================================================================
# 2E. 3D HAIRSTYLES (COLOR-TINTABLE VIA MYTHICARMORS `_t.png`!)
#     hair_spiky_3d.bbmodel, hair_ponytail_3d.bbmodel, hair_bob_3d.bbmodel
# =============================================================================
HAIR_UV_STRAND = (0, 0, 16, 16)    # Bright shaded grayscale strands (in _t.png -> tintable to ANY hair color!)
HAIR_UV_ROOT   = (16, 0, 16, 16)   # Slightly deeper root/under-layer strands (in _t.png)
HAIR_UV_HIGH   = (32, 0, 16, 16)   # Glossy top highlight strands (in _t.png)
HAIR_UV_TIE    = (48, 0, 16, 16)   # Non-tintable hair tie / clip accent (gold/crimson, NOT in _t.png!)


def paint_hair_atlas(icon_kind="spiky"):
    main = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    tint = Image.new("RGBA", (64, 64), (0, 0, 0, 0))

    # Helper to draw vertical hair strands in bright grayscale (so RGB dye multiplies cleanly!)
    def draw_strands(x0, y0, base_v, amp, shine_row=None):
        for x in range(16):
            col_off = ((x * 7) % 5 - 2) * amp
            for y in range(16):
                # Subtle vertical gradient (slightly darker at tips/roots)
                v = base_v + col_off + (8 if shine_row and abs(y - shine_row) <= 1 else 0)
                if y == 0 or y == 15:
                    v -= 16
                v = max(135, min(252, int(v)))
                px = (v, v, v, 255)
                main.putpixel((x0 + x, y0 + y), px)
                tint.putpixel((x0 + x, y0 + y), px)

    draw_strands(0, 0, 218, 9, shine_row=5)
    draw_strands(16, 0, 186, 8, shine_row=None)
    draw_strands(32, 0, 236, 7, shine_row=4)

    # Non-tintable gold/crimson hair tie tile at (48, 0) — NOT painted onto `tint`!
    tie_col = (212, 62, 78, 255)
    gold_col = (236, 192, 72, 255)
    paint_beveled_tile(main, 48, 0, 16, 16, tie_col, border_dark=0.65, hi=1.18)
    for x in range(50, 62):
        main.putpixel((x, 7), gold_col)
        main.putpixel((x, 8), gold_col)

    # 2D fallback layers
    l1 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    l2 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    ImageDraw.Draw(l1).rectangle([8, 0, 15, 7], fill=(215, 215, 215, 255))

    # Crisp 16x16 inventory icon (warm chestnut preview silhouette)
    icon = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    di = ImageDraw.Draw(icon)
    hc = (146, 98, 62, 255)
    hd = (96, 62, 38, 255)
    hl = (188, 134, 88, 255)
    if icon_kind == "spiky":
        di.polygon([(3, 12), (2, 7), (4, 3), (7, 1), (9, 3), (12, 2), (14, 6), (13, 12), (10, 8), (8, 10), (5, 8)], fill=hc, outline=hd)
        di.line([(5, 4), (10, 4)], fill=hl)
    elif icon_kind == "ponytail":
        di.rectangle([2, 3, 10, 9], fill=hc, outline=hd)
        di.rectangle([10, 5, 11, 7], fill=tie_col)
        di.polygon([(11, 4), (14, 6), (14, 13), (11, 14), (10, 9)], fill=hc, outline=hd)
        di.line([(4, 4), (8, 4)], fill=hl)
    else:  # bob
        di.rectangle([2, 2, 13, 13], fill=hc, outline=hd)
        di.rectangle([5, 7, 10, 13], fill=(0, 0, 0, 0))
        di.line([(4, 4), (11, 4)], fill=hl)

    return main, tint, l1, l2, icon


def build_hair_spiky(model_name="hair_spiky_3d"):
    b = BBModelBuilder(model_name, model_name)
    main, tint, l1, l2, icon = paint_hair_atlas("spiky")
    b.add_texture(f"{model_name}_main.png", main, 64, 64)
    b.add_texture(f"{model_name}_main_t.png", tint, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_layer_2.png", l2, 64, 32)
    b.add_texture(f"{model_name}_helmet.png", icon, 16, 16)

    head = b.add_bone("head", [0, 24, 0])
    # Crown base cap hugging head smoothly (head vanilla X[-4,4], Y[24,32], Z[-4,4])
    b.add_cube(head, "hair_crown",      [-4.45, 31.3, -4.45], [4.45, 32.85, 4.45], HAIR_UV_STRAND)
    b.add_cube(head, "hair_back_upper", [-4.55, 27.6, 3.65],  [4.55, 32.3, 4.75],  HAIR_UV_STRAND)
    b.add_cube(head, "hair_back_lower", [-4.35, 24.4, 3.75],  [4.35, 28.0, 4.85],  HAIR_UV_ROOT)
    # Sculpted side locks & sideburns
    b.add_cube(head, "hair_side_r",     [3.85, 26.6, -3.55],  [4.75, 32.0, 4.35],  HAIR_UV_STRAND)
    b.add_cube(head, "hair_side_l",     [-4.75, 26.6, -3.55], [-3.85, 32.0, 4.35], HAIR_UV_STRAND)
    b.add_cube(head, "hair_burn_r",     [3.95, 25.0, -3.85],  [4.65, 28.2, -1.6],  HAIR_UV_ROOT)
    b.add_cube(head, "hair_burn_l",     [-4.65, 25.0, -3.85], [-3.95, 28.2, -1.6], HAIR_UV_ROOT)
    # Layered front bangs (clear above eyes Y >= 28.0 so face & 3D mask remain 100% visible!)
    b.add_cube(head, "bang_center",     [-1.55, 28.5, -5.05], [1.65, 32.4, -3.85], HAIR_UV_HIGH, rotation=[-12, 0, 4], pivot=[0, 32.0, -4.2])
    b.add_cube(head, "bang_right",      [1.3, 28.1, -4.95],   [4.35, 32.2, -3.8],  HAIR_UV_STRAND, rotation=[-10, -8, -10], pivot=[2.8, 31.8, -4.2])
    b.add_cube(head, "bang_left",       [-4.35, 28.2, -4.95], [-1.3, 32.2, -3.8],  HAIR_UV_STRAND, rotation=[-10, 8, 10], pivot=[-2.8, 31.8, -4.2])
    # Dynamic spiky top tufts!
    b.add_cube(head, "spike_top_c",     [-1.6, 32.4, -3.6],   [1.7, 34.4, 1.4],    HAIR_UV_HIGH, rotation=[18, 0, -6], pivot=[0, 32.6, -1.0])
    b.add_cube(head, "spike_top_r",     [1.2, 32.2, -2.5],    [4.4, 34.1, 2.2],    HAIR_UV_HIGH, rotation=[14, -10, -16], pivot=[2.8, 32.5, 0])
    b.add_cube(head, "spike_top_l",     [-4.4, 32.2, -2.5],   [-1.2, 34.1, 2.2],   HAIR_UV_HIGH, rotation=[14, 10, 16], pivot=[-2.8, 32.5, 0])
    b.add_cube(head, "spike_back_c",    [-1.8, 31.2, 2.6],    [1.8, 33.8, 5.9],    HAIR_UV_STRAND, rotation=[-24, 0, 0], pivot=[0, 32.0, 4.0])
    b.add_cube(head, "spike_back_r",    [1.6, 29.8, 3.0],     [4.5, 32.4, 5.7],    HAIR_UV_ROOT, rotation=[-18, 12, -10], pivot=[3.0, 31.0, 4.2])
    b.add_cube(head, "spike_back_l",    [-4.5, 29.8, 3.0],    [-1.6, 32.4, 5.7],   HAIR_UV_ROOT, rotation=[-18, -12, 10], pivot=[-3.0, 31.0, 4.2])

    return b, main, icon


def build_hair_ponytail(model_name="hair_ponytail_3d"):
    b = BBModelBuilder(model_name, model_name)
    main, tint, l1, l2, icon = paint_hair_atlas("ponytail")
    b.add_texture(f"{model_name}_main.png", main, 64, 64)
    b.add_texture(f"{model_name}_main_t.png", tint, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_layer_2.png", l2, 64, 32)
    b.add_texture(f"{model_name}_helmet.png", icon, 16, 16)

    head = b.add_bone("head", [0, 24, 0])
    # Sleek crown + sides + back
    b.add_cube(head, "pt_crown",        [-4.4, 31.35, -4.4],  [4.4, 32.75, 4.45],  HAIR_UV_HIGH)
    b.add_cube(head, "pt_back",         [-4.45, 25.0, 3.7],   [4.45, 32.1, 4.65],  HAIR_UV_STRAND)
    b.add_cube(head, "pt_side_r",       [3.85, 26.2, -3.5],   [4.65, 32.0, 4.35],  HAIR_UV_STRAND)
    b.add_cube(head, "pt_side_l",       [-4.65, 26.2, -3.5],  [-3.85, 32.0, 4.35], HAIR_UV_STRAND)
    # Swept parted bangs + framing strands
    b.add_cube(head, "pt_bang_r",       [0.3, 28.8, -4.85],   [4.4, 32.2, -3.85],  HAIR_UV_HIGH, rotation=[-6, 0, -8], pivot=[2.2, 31.8, -4.2])
    b.add_cube(head, "pt_bang_l",       [-4.4, 28.8, -4.85],  [-0.3, 32.2, -3.85], HAIR_UV_HIGH, rotation=[-6, 0, 8], pivot=[-2.2, 31.8, -4.2])
    b.add_cube(head, "pt_strand_r",     [3.85, 23.4, -4.45],  [4.8, 29.0, -2.9],   HAIR_UV_STRAND, rotation=[0, 0, -5], pivot=[4.2, 28.5, -3.6])
    b.add_cube(head, "pt_strand_l",     [-4.8, 23.4, -4.45],  [-3.85, 29.0, -2.9], HAIR_UV_STRAND, rotation=[0, 0, 5], pivot=[-4.2, 28.5, -3.6])
    # Non-tintable crimson+gold hair scrunchie!
    b.add_cube(head, "pt_scrunchie",    [-1.75, 29.1, 4.35],  [1.75, 31.1, 5.65],  HAIR_UV_TIE)
    # 3-segment sculpted flowing ponytail (angled gently out behind the head, clears torso cleanly!)
    b.add_cube(head, "pt_tail_top",     [-1.95, 27.6, 5.1],   [1.95, 31.6, 7.6],   HAIR_UV_HIGH, rotation=[-24, 0, 0], pivot=[0, 30.2, 5.3])
    b.add_cube(head, "pt_tail_mid",     [-1.75, 22.8, 5.8],   [1.75, 28.2, 8.2],   HAIR_UV_STRAND, rotation=[-12, 0, 0], pivot=[0, 27.8, 6.8])
    b.add_cube(head, "pt_tail_tip",     [-1.35, 18.8, 6.1],   [1.35, 23.4, 8.0],   HAIR_UV_ROOT, rotation=[-6, 0, 0], pivot=[0, 23.0, 7.0])

    return b, main, icon


def build_hair_ponytail_under_helmet(model_name="hair_ponytail_3d_under_helmet"):
    """Partial 3D hair variant worn under helmets: high ponytail & crown hidden, front framing strands remain!"""
    b = BBModelBuilder(model_name, model_name)
    main, tint, l1, l2, icon = paint_hair_atlas("ponytail")
    b.add_texture(f"{model_name}_main.png", main, 64, 64)
    b.add_texture(f"{model_name}_main_t.png", tint, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_layer_2.png", l2, 64, 32)
    b.add_texture(f"{model_name}_helmet.png", icon, 16, 16)

    head = b.add_bone("head", [0, 24, 0])
    # Only the front framing strands & lower bangs stay visible under a helmet (high ponytail removed!)
    b.add_cube(head, "pt_bang_r",       [0.3, 28.8, -4.85],   [4.2, 31.4, -3.85],  HAIR_UV_HIGH, rotation=[-6, 0, -8], pivot=[2.2, 31.4, -4.2])
    b.add_cube(head, "pt_bang_l",       [-4.2, 28.8, -4.85],  [-0.3, 31.4, -3.85], HAIR_UV_HIGH, rotation=[-6, 0, 8], pivot=[-2.2, 31.4, -4.2])
    b.add_cube(head, "pt_strand_r",     [3.85, 23.4, -4.45],  [4.8, 29.0, -2.9],   HAIR_UV_STRAND, rotation=[0, 0, -5], pivot=[4.2, 28.5, -3.6])
    b.add_cube(head, "pt_strand_l",     [-4.8, 23.4, -4.45],  [-3.85, 29.0, -2.9], HAIR_UV_STRAND, rotation=[0, 0, 5], pivot=[-4.2, 28.5, -3.6])

    return b, main, icon


def build_hair_bob(model_name="hair_bob_3d"):
    b = BBModelBuilder(model_name, model_name)
    main, tint, l1, l2, icon = paint_hair_atlas("bob")
    b.add_texture(f"{model_name}_main.png", main, 64, 64)
    b.add_texture(f"{model_name}_main_t.png", tint, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_layer_2.png", l2, 64, 32)
    b.add_texture(f"{model_name}_helmet.png", icon, 16, 16)

    head = b.add_bone("head", [0, 24, 0])
    # Rounded volumetric bob crown
    b.add_cube(head, "bob_crown",       [-4.55, 31.3, -4.55], [4.55, 32.95, 4.55], HAIR_UV_HIGH)
    b.add_cube(head, "bob_top_puff",    [-3.9, 32.6, -3.7],   [3.9, 33.45, 3.8],   HAIR_UV_HIGH)
    # Soft straight fringe above eyes
    b.add_cube(head, "bob_fringe_m",    [-2.4, 29.0, -4.85],  [2.4, 32.1, -3.9],   HAIR_UV_STRAND)
    b.add_cube(head, "bob_fringe_r",    [2.2, 28.6, -4.95],   [4.6, 32.0, -3.75],  HAIR_UV_STRAND)
    b.add_cube(head, "bob_fringe_l",    [-4.6, 28.6, -4.95],  [-2.2, 32.0, -3.75], HAIR_UV_STRAND)
    # Lush curved bob sides framing cheeks
    b.add_cube(head, "bob_side_r",      [3.95, 23.6, -3.95],  [5.05, 31.9, 4.45],  HAIR_UV_STRAND, rotation=[0, 0, -4], pivot=[4.4, 31.5, 0])
    b.add_cube(head, "bob_side_l",      [-5.05, 23.6, -3.95], [-3.95, 31.9, 4.45], HAIR_UV_STRAND, rotation=[0, 0, 4], pivot=[-4.4, 31.5, 0])
    # Full layered back bob + subtle hair clip accent
    b.add_cube(head, "bob_back_main",   [-4.7, 23.5, 3.7],    [4.7, 32.0, 5.05],   HAIR_UV_ROOT, rotation=[4, 0, 0], pivot=[0, 31.5, 4.2])
    b.add_cube(head, "bob_clip_r",      [4.55, 29.4, -3.2],   [5.15, 30.3, -1.6],  HAIR_UV_TIE)

    return b, main, icon


# =============================================================================
# 2F. 3D BELTS & WAIST DECORATIVE ACCESSORIES (`waist` + `right_leg` / `left_leg`)
#     belt_tactical_3d, belt_adventurer_3d, belt_chains_3d
# =============================================================================
BELT_UV_STRAP  = (0, 0, 16, 16)
BELT_UV_POUCH  = (16, 0, 16, 16)
BELT_UV_METAL  = (32, 0, 16, 16)
BELT_UV_ACCENT = (48, 0, 16, 16)
BELT_UV_GLOW   = (0, 16, 16, 16)
BELT_UV_DARK   = (16, 16, 16, 16)


def paint_belt_atlas(kind="tactical"):
    main = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    emis = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    tint = Image.new("RGBA", (64, 64), (0, 0, 0, 0))

    if kind == "tactical":
        paint_beveled_tile(main, 0, 0, 16, 16, (44, 50, 44, 255))        # webbing strap
        paint_beveled_tile(main, 16, 0, 16, 16, (66, 78, 58, 255))       # olive MOLLE pouch
        paint_beveled_tile(main, 32, 0, 16, 16, (124, 134, 148, 255))    # gunmetal cobra buckle
        paint_beveled_tile(main, 48, 0, 16, 16, (88, 72, 54, 255))       # tan canteen/holster trim
        paint_beveled_tile(main, 0, 16, 16, 16, (46, 232, 134, 255))     # green tactical LED
        paint_beveled_tile(emis, 0, 16, 16, 16, (46, 232, 134, 255))
        paint_beveled_tile(main, 16, 16, 16, 16, (28, 30, 34, 255))      # dark polymer
        ic_col, ic_bkl = (66, 78, 58, 255), (144, 156, 172, 255)
    elif kind == "adventurer":
        paint_beveled_tile(main, 0, 0, 16, 16, (112, 68, 38, 255))       # warm saddle leather
        paint_beveled_tile(main, 16, 0, 16, 16, (86, 52, 30, 255))       # stitched leather pouch
        paint_beveled_tile(main, 32, 0, 16, 16, (232, 188, 66, 255))     # brass buckle & dagger hilt
        paint_beveled_tile(main, 48, 0, 16, 16, (164, 128, 86, 255))     # cork / parchment / rope
        paint_beveled_tile(main, 0, 16, 16, 16, (56, 212, 248, 255))     # glowing mana potion vial!
        paint_beveled_tile(emis, 0, 16, 16, 16, (56, 212, 248, 255))
        paint_beveled_tile(main, 16, 16, 16, 16, (54, 48, 56, 255))      # scabbard steel
        ic_col, ic_bkl = (118, 72, 42, 255), (236, 192, 68, 255)
    else:  # chains (royal tintable sash + gold chains + glowing ruby!)
        # Tile (0,0) & (16,0) are bright grayscale sash fabric -> put into _t.png for unlimited RGB dye!
        paint_beveled_tile(main, 0, 0, 16, 16, (226, 226, 226, 255))
        paint_beveled_tile(tint, 0, 0, 16, 16, (226, 226, 226, 255))
        paint_beveled_tile(main, 16, 0, 16, 16, (192, 192, 192, 255))
        paint_beveled_tile(tint, 16, 0, 16, 16, (192, 192, 192, 255))
        paint_beveled_tile(main, 32, 0, 16, 16, (242, 198, 62, 255))     # bright royal gold chain/buckle
        paint_beveled_tile(main, 48, 0, 16, 16, (186, 138, 28, 255))     # shaded gold link
        paint_beveled_tile(main, 0, 16, 16, 16, (238, 48, 78, 255))      # glowing ruby gem
        paint_beveled_tile(emis, 0, 16, 16, 16, (238, 48, 78, 255))
        paint_beveled_tile(main, 16, 16, 16, 16, (36, 30, 44, 255))      # obsidian setting
        ic_col, ic_bkl = (176, 36, 54, 255), (242, 198, 62, 255)

    l1 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    l2 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    ImageDraw.Draw(l2).rectangle([16, 28, 39, 31], fill=ic_col)

    icon = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    di = ImageDraw.Draw(icon)
    di.rectangle([1, 6, 14, 10], fill=ic_col, outline=(28, 24, 26, 255))
    di.rectangle([6, 5, 9, 11], fill=ic_bkl, outline=(28, 24, 26, 255))
    di.rectangle([7, 7, 8, 9], fill=(28, 24, 26, 255))
    if kind == "adventurer":
        di.rectangle([11, 10, 13, 14], fill=(56, 212, 248, 255))
    elif kind == "chains":
        di.line([(3, 11), (6, 13), (10, 13), (13, 11)], fill=ic_bkl)

    return main, emis, tint, l1, l2, icon


def build_belt_tactical(model_name="belt_tactical_3d"):
    b = BBModelBuilder(model_name, model_name)
    main, emis, _tint, l1, l2, icon = paint_belt_atlas("tactical")
    b.add_texture(f"{model_name}_main.png", main, 64, 64)
    b.add_texture(f"{model_name}_main_e.png", emis, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_layer_2.png", l2, 64, 32)
    b.add_texture(f"{model_name}_leggings.png", icon, 16, 16)

    waist = b.add_bone("waist", [0, 12, 0])
    rleg = b.add_bone("right_leg", [2, 12, 0])
    # Wrap-around tactical belt band
    b.add_cube(waist, "belt_band_f",    [-4.55, 11.4, -2.55], [4.55, 13.3, -1.85], BELT_UV_STRAP)
    b.add_cube(waist, "belt_band_b",    [-4.55, 11.4, 1.85],  [4.55, 13.3, 2.55],  BELT_UV_STRAP)
    b.add_cube(waist, "belt_band_r",    [3.85, 11.4, -2.55],  [4.6, 13.3, 2.55],   BELT_UV_STRAP)
    b.add_cube(waist, "belt_band_l",    [-4.6, 11.4, -2.55],  [-3.85, 13.3, 2.55], BELT_UV_STRAP)
    # Metallic Cobra buckle + green LED
    b.add_cube(waist, "cobra_buckle",   [-1.55, 11.2, -3.0],  [1.55, 13.5, -2.3],  BELT_UV_METAL)
    b.add_cube(waist, "cobra_core",     [-0.7, 11.8, -3.12],  [0.7, 12.9, -2.85],  BELT_UV_DARK)
    b.add_cube(waist, "belt_led",       [4.85, 12.4, -1.4],   [5.55, 13.0, -0.7],  BELT_UV_GLOW)
    # Side MOLLE ammo pouches & back utility kit
    b.add_cube(waist, "pouch_left_1",   [-5.85, 9.8, -1.7],   [-4.45, 13.1, 0.3],  BELT_UV_POUCH)
    b.add_cube(waist, "pouch_left_2",   [-5.75, 10.1, 0.45],  [-4.45, 13.0, 2.25], BELT_UV_POUCH)
    b.add_cube(waist, "pouch_back_l",   [-3.4, 10.0, 2.35],   [-0.4, 13.2, 3.85],  BELT_UV_POUCH)
    b.add_cube(waist, "pouch_back_r",   [0.4, 10.2, 2.35],    [3.3, 13.1, 3.75],   BELT_UV_ACCENT)
    # Drop-leg tactical holster attached cleanly to right_leg!
    b.add_cube(rleg, "holster_strap",   [-0.25, 7.6, -2.35],  [4.35, 8.6, 2.35],   BELT_UV_DARK)
    b.add_cube(rleg, "holster_hang",    [4.15, 8.2, -0.7],    [4.65, 11.6, 0.7],   BELT_UV_STRAP)
    b.add_cube(rleg, "holster_body",    [4.15, 5.8, -1.25],   [5.55, 9.8, 1.35],   BELT_UV_DARK, rotation=[0, 0, -5], pivot=[4.5, 9.2, 0])
    b.add_cube(rleg, "holster_grip",    [4.4, 9.4, -0.45],    [5.45, 11.1, 1.45],  BELT_UV_METAL, rotation=[-18, 0, -5], pivot=[4.8, 9.6, 0.4])

    return b, main, emis, icon


def build_belt_adventurer(model_name="belt_adventurer_3d"):
    b = BBModelBuilder(model_name, model_name)
    main, emis, _tint, l1, l2, icon = paint_belt_atlas("adventurer")
    b.add_texture(f"{model_name}_main.png", main, 64, 64)
    b.add_texture(f"{model_name}_main_e.png", emis, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_layer_2.png", l2, 64, 32)
    b.add_texture(f"{model_name}_leggings.png", icon, 16, 16)

    waist = b.add_bone("waist", [0, 12, 0])
    # Saddle leather belt + ornate brass buckle
    b.add_cube(waist, "adv_band_f",     [-4.55, 11.3, -2.5],  [4.55, 13.2, -1.85], BELT_UV_STRAP)
    b.add_cube(waist, "adv_band_b",     [-4.55, 11.3, 1.85],  [4.55, 13.2, 2.5],   BELT_UV_STRAP)
    b.add_cube(waist, "adv_band_r",     [3.85, 11.3, -2.5],   [4.55, 13.2, 2.5],   BELT_UV_STRAP)
    b.add_cube(waist, "adv_band_l",     [-4.55, 11.3, -2.5],  [-3.85, 13.2, 2.5],  BELT_UV_STRAP)
    b.add_cube(waist, "adv_buckle",     [-1.4, 11.0, -2.95],  [1.4, 13.5, -2.25],  BELT_UV_METAL)
    b.add_cube(waist, "adv_buckle_in",  [-0.6, 11.7, -3.02],  [0.6, 12.8, -2.75],  BELT_UV_POUCH)
    # Leather adventurer satchel on left hip
    b.add_cube(waist, "adv_satchel",    [-5.9, 8.8, -1.6],    [-4.3, 12.8, 1.6],   BELT_UV_POUCH)
    b.add_cube(waist, "adv_flap",       [-6.05, 11.0, -1.7],  [-4.35, 12.9, 1.7],  BELT_UV_STRAP)
    b.add_cube(waist, "adv_clasp",      [-6.2, 10.6, -0.45],  [-5.85, 11.5, 0.45], BELT_UV_METAL)
    # Glowing mana potion vial hanging on right hip!
    b.add_cube(waist, "potion_loop",    [4.35, 11.2, -1.65],  [5.2, 12.5, -0.55],  BELT_UV_STRAP)
    b.add_cube(waist, "potion_glass",   [4.4, 8.6, -1.9],     [5.9, 11.1, -0.3],   BELT_UV_GLOW)
    b.add_cube(waist, "potion_neck",    [4.8, 11.0, -1.45],   [5.5, 11.8, -0.75],  BELT_UV_ACCENT)
    # Rolled scroll + sheathed dagger across back of waist!
    b.add_cube(waist, "scroll_roll",    [0.6, 10.4, 2.35],    [4.0, 12.1, 3.85],   BELT_UV_ACCENT, rotation=[0, 0, 8], pivot=[2.2, 11.2, 3.0])
    b.add_cube(waist, "dagger_sheath",  [-4.4, 10.5, 2.35],   [1.2, 11.9, 3.45],   BELT_UV_DARK, rotation=[0, 0, -22], pivot=[-1.5, 11.2, 2.9])
    b.add_cube(waist, "dagger_hilt",    [-5.5, 11.3, 2.45],   [-4.1, 12.5, 3.35],  BELT_UV_METAL, rotation=[0, 0, -22], pivot=[-1.5, 11.2, 2.9])

    return b, main, emis, icon


def build_belt_chains(model_name="belt_chains_3d"):
    b = BBModelBuilder(model_name, model_name)
    main, emis, tint, l1, l2, icon = paint_belt_atlas("chains")
    b.add_texture(f"{model_name}_main.png", main, 64, 64)
    b.add_texture(f"{model_name}_main_e.png", emis, 64, 64)
    b.add_texture(f"{model_name}_main_t.png", tint, 64, 64)
    b.add_texture(f"{model_name}_layer_1.png", l1, 64, 32)
    b.add_texture(f"{model_name}_layer_2.png", l2, 64, 32)
    b.add_texture(f"{model_name}_leggings.png", icon, 16, 16)

    waist = b.add_bone("waist", [0, 12, 0])
    # Tintable royal waist sash + hanging front cloth banner
    b.add_cube(waist, "sash_f",         [-4.55, 11.3, -2.5],  [4.55, 13.3, -1.85], BELT_UV_STRAP)
    b.add_cube(waist, "sash_b",         [-4.55, 11.3, 1.85],  [4.55, 13.3, 2.5],   BELT_UV_STRAP)
    b.add_cube(waist, "sash_r",         [3.85, 11.3, -2.5],   [4.55, 13.3, 2.5],   BELT_UV_STRAP)
    b.add_cube(waist, "sash_l",         [-4.55, 11.3, -2.5],  [-3.85, 13.3, 2.5],  BELT_UV_STRAP)
    b.add_cube(waist, "sash_tail_l",    [-4.35, 6.2, -2.58],  [-2.15, 11.5, -1.95], BELT_UV_POUCH, rotation=[-6, 0, 4], pivot=[-3.2, 11.4, -2.2])
    b.add_cube(waist, "sash_trim_bot",  [-4.4, 6.0, -2.62],   [-2.1, 6.7, -1.92],  BELT_UV_METAL, rotation=[-6, 0, 4], pivot=[-3.2, 11.4, -2.2])
    # Royal gold buckle + glowing ruby gem
    b.add_cube(waist, "royal_buckle",   [-1.55, 10.9, -2.95], [1.55, 13.6, -2.25], BELT_UV_METAL)
    b.add_cube(waist, "royal_ruby",     [-0.75, 11.6, -3.12], [0.75, 12.9, -2.8],  BELT_UV_GLOW)
    # 3D hanging gold chains draped on both sides & front!
    b.add_cube(waist, "chain_r_1",      [1.4, 10.1, -2.85],   [3.6, 11.0, -2.35],  BELT_UV_METAL, rotation=[0, 0, 18], pivot=[2.5, 10.6, -2.6])
    b.add_cube(waist, "chain_r_2",      [3.1, 9.7, -2.75],    [4.95, 10.6, -1.6],  BELT_UV_ACCENT, rotation=[0, 0, -20], pivot=[4.0, 10.2, -2.2])
    b.add_cube(waist, "chain_r_side",   [4.35, 9.6, -1.8],    [5.1, 10.5, 2.0],    BELT_UV_METAL, rotation=[12, 0, 0], pivot=[4.7, 10.2, 0])
    b.add_cube(waist, "chain_l_side",   [-5.1, 9.6, -1.8],    [-4.35, 10.5, 2.0],  BELT_UV_METAL, rotation=[-12, 0, 0], pivot=[-4.7, 10.2, 0])
    b.add_cube(waist, "charm_gem_r",    [4.45, 8.2, -0.5],    [5.25, 9.7, 0.5],    BELT_UV_GLOW)

    return b, main, emis, icon


# =============================================================================
# 3. QUICK ISOMETRIC 3D PREVIEW RENDERER (FOR VISUAL VERIFICATION!)
# =============================================================================
def render_model_preview(model_dict, main_img: Image.Image, canvas_size=(260, 320), yaw_deg=28, pitch_deg=14, show_mannequin=False):
    img = Image.new("RGBA", canvas_size, (16, 22, 34, 255))
    draw = ImageDraw.Draw(img)
    w, h = canvas_size
    yaw = math.radians(yaw_deg)
    pitch = math.radians(pitch_deg)
    cy, sy = math.cos(yaw), math.sin(yaw)
    cp, sp = math.cos(pitch), math.sin(pitch)

    def avg_color(uv):
        u0, v0, u1, v1 = int(uv[0]), int(uv[1]), int(uv[2]), int(uv[3])
        cx = max(0, min(main_img.width - 1, (u0 + u1) // 2))
        cy_ = max(0, min(main_img.height - 1, (v0 + v1) // 2))
        return main_img.getpixel((cx, cy_))

    def rotate_pt(p, rot_deg, piv):
        rx_d, ry_d, rz_d = rot_deg
        if rx_d == 0 and ry_d == 0 and rz_d == 0:
            return p
        x, y, z = p[0] - piv[0], p[1] - piv[1], p[2] - piv[2]
        if rx_d:
            a = math.radians(rx_d)
            y, z = y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a)
        if ry_d:
            a = math.radians(ry_d)
            x, z = x * math.cos(a) + z * math.sin(a), -x * math.sin(a) + z * math.cos(a)
        if rz_d:
            a = math.radians(rz_d)
            x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
        return (x + piv[0], y + piv[1], z + piv[2])

    def proj(p):
        # Model front is -Z, character RIGHT is +X; looking at front from -Z: +X is screen-left
        x, y, z = -p[0], p[1] - 17.5, -p[2]
        rx = x * cy + z * sy
        rz = -x * sy + z * cy
        ry = y * cp - rz * sp
        rz2 = y * sp + rz * cp
        scale = 6.6
        return (w / 2 + rx * scale, h / 2 - ry * scale, rz2)

    quads = []

    def add_box(x0, y0, z0, x1, y1, z1, rot, piv, face_colors):
        verts = [
            (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
            (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
        ]
        verts = [rotate_pt(v, rot, piv) for v in verts]
        face_defs = [
            ("north", (0, 1, 2, 3), 1.0),
            ("south", (5, 4, 7, 6), 0.78),
            ("west",  (4, 0, 3, 7), 0.86),
            ("east",  (1, 5, 6, 2), 0.90),
            ("up",    (3, 2, 6, 7), 1.08),
            ("down",  (4, 5, 1, 0), 0.64),
        ]
        for fname, idxs, light in face_defs:
            pts = [proj(verts[i]) for i in idxs]
            depth = sum(p[2] for p in pts) / 4.0
            col = face_colors[fname]
            lit = tuple(max(0, min(255, int(c * light))) for c in col[:3]) + (255,)
            quads.append((depth, [(p[0], p[1]) for p in pts], lit))

    if show_mannequin:
        skin_c = {s: (146, 152, 164, 255) for s in ("north", "south", "west", "east", "up", "down")}
        # Vanilla Steve mannequin (head, torso, arms, legs)
        add_box(-4, 24, -4, 4, 32, 4, (0, 0, 0), (0, 24, 0), skin_c)
        add_box(-4, 12, -2, 4, 24, 2, (0, 0, 0), (0, 24, 0), skin_c)
        add_box(4, 12, -2, 8, 24, 2, (0, 0, 0), (5, 22, 0), skin_c)
        add_box(-8, 12, -2, -4, 24, 2, (0, 0, 0), (-5, 22, 0), skin_c)
        add_box(0, 0, -2, 4, 12, 2, (0, 0, 0), (2, 12, 0), skin_c)
        add_box(-4, 0, -2, 0, 12, 2, (0, 0, 0), (-2, 12, 0), skin_c)

    for el in model_dict["elements"]:
        x0, y0, z0 = el["from"]
        x1, y1, z1 = el["to"]
        fcols = {s: avg_color(el["faces"][s]["uv"]) for s in ("north", "south", "west", "east", "up", "down")}
        add_box(x0, y0, z0, x1, y1, z1, el.get("rotation", (0, 0, 0)), el.get("origin", (0, 0, 0)), fcols)

    quads.sort(key=lambda q: q[0])
    for _, poly, col in quads:
        draw.polygon(poly, fill=col, outline=(12, 16, 24, 255))
    return img


# =============================================================================
# 4. GENERATE ALL MODELS, CONFIGS, PREVIEW & ZIP ARCHIVE
# =============================================================================
MYTHICMOBS_ITEMS_YML = """# ==============================================================================
# MythicWardrobe + MythicArmors — Тестовый набор 3D-брони и 2D-одежды
# ==============================================================================
# Положите этот файл в: plugins/MythicMobs/Items/MythicWardrobe_TestArmorPack.yml
# А 3 файла .bbmodel в:  plugins/MythicArmors/models/
# После этого выполните:
#   /ma reload
#   /mm reload
#   /wardrobe reload
# И выдайте игроку тестовый набор:
#   /mm i give <ник> TacticalWardenHelmet
#   /mm i give <ник> TacticalWardenVest
#   /mm i give <ник> TacticalWardenLeggings
#   /mm i give <ник> TacticalWardenBoots
#   /mm i give <ник> GoldWardenHelmetCosmetic
#   /mm i give <ник> GoldWardenChestCosmetic
#   /mm i give <ник> TacticalGasMask3D
#   /mm i give <ник> TacticalBackpack3D
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. ОСНОВНАЯ 3D-БРОНЯ (MythicArmors: tactical_warden.bbmodel)
#    Подходит в слоты «Статы» И в слоты «Косметика»!
#    ВАЖНО по документации MythicArmors:
#    • У шлемов указывается Equippable.Slot: HEAD и Options.ItemModel (БЕЗ Equippable.Model!)
#    • У нагрудника/поножей/ботинок указывается Equippable.Model + Slot + Options.ItemModel
# ------------------------------------------------------------------------------
TacticalWardenHelmet:
  Material: LEATHER_HELMET
  Display: '&bТактический шлем Стража &7[3D]'
  Lore:
    - '&7Настоящая 3D-броня MythicArmors'
    - '&8Слот: &fШлем (Статы / Косметика)'
    - '&bКомбо: &7+ Тактический противогаз 3D = совмещённая 3D-модель!'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_netherite'
  Options:
    ItemModel: mythicarmor:tactical_warden_head_piece
    Unbreakable: true
    HideFlags: true
  Attributes:
    Head:
      Armor: 4
      ArmorToughness: 2

TacticalWardenVest:
  Material: LEATHER_CHESTPLATE
  Display: '&bТактический бронежилет Стража &7[3D]'
  Lore:
    - '&7Настоящая 3D-броня MythicArmors'
    - '&8Слот: &fБронежилет (Статы / Косметика)'
    - '&aСовместим с 3D-рюкзаком поверх бронежилета!'
  Equippable:
    Model: mythicarmor:tactical_warden_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_netherite'
  Options:
    ItemModel: mythicarmor:tactical_warden_chest_piece
    Unbreakable: true
    HideFlags: true
  Attributes:
    Chest:
      Armor: 8
      ArmorToughness: 3

TacticalWardenLeggings:
  Material: LEATHER_LEGGINGS
  Display: '&bТактические поножи Стража &7[3D]'
  Lore:
    - '&7Настоящая 3D-броня MythicArmors'
    - '&8Слот: &fПоножи (Статы / Косметика)'
  Equippable:
    Model: mythicarmor:tactical_warden_legs_piece
    Slot: LEGS
    EquipSound: 'item.armor.equip_netherite'
  Options:
    ItemModel: mythicarmor:tactical_warden_legs_piece
    Unbreakable: true
    HideFlags: true
  Attributes:
    Legs:
      Armor: 6
      ArmorToughness: 2

TacticalWardenBoots:
  Material: LEATHER_BOOTS
  Display: '&bТактические ботинки Стража &7[3D]'
  Lore:
    - '&7Настоящая 3D-броня MythicArmors'
    - '&8Слот: &fБотинки / Пояс (Статы / Косметика)'
  Equippable:
    Model: mythicarmor:tactical_warden_feet_piece
    Slot: FEET
    EquipSound: 'item.armor.equip_netherite'
  Options:
    ItemModel: mythicarmor:tactical_warden_feet_piece
    Unbreakable: true
    HideFlags: true
  Attributes:
    Feet:
      Armor: 3
      ArmorToughness: 2

# ------------------------------------------------------------------------------
# 2. КОСМЕТИЧЕСКАЯ 3D-БРОНЯ (MythicArmors: gold_warden_cosmetic.bbmodel)
#    Надевается в слоты «Косметика» — перекрывает вид реальной брони, сохраняя её статы!
# ------------------------------------------------------------------------------
GoldWardenHelmetCosmetic:
  Material: LEATHER_HELMET
  Display: '&6Золотой шлем Стража &d[3D]'
  Lore:
    - '&dСамостоятельный 3D-шлем (Статы / Косметика / Маска 3D)'
    - '&7Можно носить отдельно ИЛИ вместе с другой 3D-моделью!'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_gold'
  Options:
    ItemModel: mythicarmor:gold_warden_cosmetic_head_piece
    Unbreakable: true
    HideFlags: true

GoldWardenChestCosmetic:
  Material: LEATHER_CHESTPLATE
  Display: '&6Золотой бронежилет Стража &d[3D]'
  Lore:
    - '&dСамостоятельный 3D-нагрудник (Статы / Косметика / 3D-слой)'
  Equippable:
    Model: mythicarmor:gold_warden_cosmetic_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_gold'
  Options:
    ItemModel: mythicarmor:gold_warden_cosmetic_chest_piece
    Unbreakable: true
    HideFlags: true

GoldWardenLegsCosmetic:
  Material: LEATHER_LEGGINGS
  Display: '&6Золотые поножи Стража &d[3D]'
  Lore:
    - '&dСамостоятельные 3D-поножи (Статы / Косметика)'
  Equippable:
    Model: mythicarmor:gold_warden_cosmetic_legs_piece
    Slot: LEGS
    EquipSound: 'item.armor.equip_gold'
  Options:
    ItemModel: mythicarmor:gold_warden_cosmetic_legs_piece
    Unbreakable: true
    HideFlags: true

GoldWardenBootsCosmetic:
  Material: LEATHER_BOOTS
  Display: '&6Золотые ботинки Стража &d[3D]'
  Lore:
    - '&dСамостоятельные 3D-ботинки (Статы / Косметика)'
  Equippable:
    Model: mythicarmor:gold_warden_cosmetic_feet_piece
    Slot: FEET
    EquipSound: 'item.armor.equip_gold'
  Options:
    ItemModel: mythicarmor:gold_warden_cosmetic_feet_piece
    Unbreakable: true
    HideFlags: true

# ------------------------------------------------------------------------------
# 2B. 3D-РЮКЗАК MYTHICARMORS (tactical_backpack.bbmodel)
#     100% самостоятельный! Надевается ОТДЕЛЬНО (Нагрудник / Косметика / Рюкзак 3D)
#     ИЛИ ОДНОВРЕМЕННО поверх ЛЮБОГО бронежилета!
# ------------------------------------------------------------------------------
TacticalBackpackArmor:
  Material: LEATHER_CHESTPLATE
  Display: '&aТактический полевой рюкзак &7[3D]'
  Lore:
    - '&7Модель MythicArmors: &ftactical_backpack.bbmodel'
    - '&aМожно надеть ОТДЕЛЬНО &7(в любой слот торса)'
    - '&bИЛИ ОДНОВРЕМЕННО &7с любым бронежилетом!'
    - '&8Лямки на груди + рюкзак, спальник и рация за спиной'
  Attributes:
    Chest:
      Armor: 4
      ArmorToughness: 1
  Equippable:
    Model: mythicarmor:tactical_backpack_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:tactical_backpack_chest_piece
    Unbreakable: true
    HideFlags: true

# ------------------------------------------------------------------------------
# 3. НУЛЕВОЙ ЛАГ: КОМБО-МОДЕЛИ (Бронежилет + Рюкзак / Шлем + Противогаз)
#    Подставляются АВТОМАТИЧЕСКИ через combo-overrides.yml, когда надеты ОБА
#    предмета одновременно — 0 сущностей, 0 лага, жёсткая привязка к кости тела!
#    При этом маска и рюкзак по-прежнему работают 100% САМОСТОЯТЕЛЬНО без брони!
# ------------------------------------------------------------------------------
TacticalWardenHelmetMasked:
  Material: LEATHER_HELMET
  Display: '&bШлем Стража с противогазом &7[3D]'
  Lore:
    - '&7Цельная 3D-модель (Шлем Стража + Противогаз)'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_netherite'
  Options:
    ItemModel: mythicarmor:tactical_warden_masked_head_piece
    Unbreakable: true
    HideFlags: true

GoldWardenHelmetMasked:
  Material: LEATHER_HELMET
  Display: '&6Золотой шлем Стража с противогазом &7[3D]'
  Lore:
    - '&7Цельная 3D-модель (Золотой шлем + Противогаз)'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_gold'
  Options:
    ItemModel: mythicarmor:gold_warden_masked_head_piece
    Unbreakable: true
    HideFlags: true

TacticalWardenVestBackpack:
  Material: LEATHER_CHESTPLATE
  Display: '&bБронежилет Стража + Рюкзак &7[Combo 3D]'
  Lore:
    - '&7Цельная 3D-модель: Тактический жилет + 3D-рюкзак на одной кости body!'
    - '&aНулевой лаг при беге и прыжках!'
  Equippable:
    Model: mythicarmor:tactical_warden_backpack_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_netherite'
  Options:
    ItemModel: mythicarmor:tactical_warden_backpack_chest_piece
    Unbreakable: true
    HideFlags: true

GoldWardenChestBackpack:
  Material: LEATHER_CHESTPLATE
  Display: '&6Золотой бронежилет + Рюкзак &7[Combo 3D]'
  Lore:
    - '&7Цельная 3D-модель: Золотой жилет + 3D-рюкзак на одной кости body!'
    - '&aНулевой лаг при беге и прыжках!'
  Equippable:
    Model: mythicarmor:gold_warden_backpack_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_gold'
  Options:
    ItemModel: mythicarmor:gold_warden_backpack_chest_piece
    Unbreakable: true
    HideFlags: true

# ------------------------------------------------------------------------------
# 4. САМОСТОЯТЕЛЬНАЯ 3D-МАСКА И РЮКЗАК (100% НЕЗАВИСИМЫЕ МОДЕЛИ!)
#    Каждая модель надевается САМА ПО СЕБЕ (без шлема/бронежилета!)
#    И ОДНОВРЕМЕННО с любой другой 3D-бронёй!
# ------------------------------------------------------------------------------
TacticalGasMask3D:
  Material: LEATHER_HELMET
  Display: '&aТактический противогаз &7[3D]'
  Lore:
    - '&7Модель MythicArmors: &ftactical_gasmask.bbmodel'
    - '&aНадевается САМОСТОЯТЕЛЬНО &7(в Шлем, Косметику ИЛИ Маску 3D — БЕЗ шлема!)'
    - '&bИЛИ ОДНОВРЕМЕННО &7поверх ЛЮБОГО шлема!'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:tactical_gasmask_head_piece
    Unbreakable: true
    HideFlags: true

TacticalBackpack3D:
  Material: LEATHER_CHESTPLATE
  Display: '&6Полевой тактический рюкзак &7[Рюкзак 3D]'
  Lore:
    - '&7Модель MythicArmors: &ftactical_backpack.bbmodel'
    - '&aНадевается САМОСТОЯТЕЛЬНО &7или &bОДНОВРЕМЕННО с бронежилетом!'
  Equippable:
    Model: mythicarmor:tactical_backpack_chest_piece
    Slot: CHEST
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:tactical_backpack_chest_piece
    Unbreakable: true
    HideFlags: true

# ------------------------------------------------------------------------------
# 4B. 3D ПРИЧЁСКИ (ПЕРЕКРАШИВАЕМЫЕ ЧЕРЕЗ Options.Color + _t.png!)
#     Надеваются ОТДЕЛЬНО (в слот Шлема или Причёски 3D) ИЛИ ОДНОВРЕМЕННО
#     с 3D-маской и 3D-шлемом (с /wardrobe buildheadpack — 0 лага!)
# ------------------------------------------------------------------------------
HairSpiky3D_Dark:
  Material: LEATHER_HELMET
  Display: '&d3D Причёска «Взъерошенная» &8[Тёмная]'
  Lore:
    - '&7Модель MythicArmors: &fhair_spiky_3d.bbmodel'
    - '&aНосится отдельно ИЛИ в слоте «Причёска (3D)» вместе с маской/шлемом!'
    - '&dМеняйте Options.Color для любого цвета волос!'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:hair_spiky_3d_head_piece
    Color: 42,36,46
    Unbreakable: true
    HideFlags: true

HairSpiky3D_Blonde:
  Material: LEATHER_HELMET
  Display: '&d3D Причёска «Взъерошенная» &e[Блонд]'
  Lore:
    - '&7Модель MythicArmors: &fhair_spiky_3d.bbmodel'
    - '&aНосится отдельно ИЛИ вместе с маской/шлемом!'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:hair_spiky_3d_head_piece
    Color: 235,198,96
    Unbreakable: true
    HideFlags: true

HairSpiky3D_Crimson:
  Material: LEATHER_HELMET
  Display: '&d3D Причёска «Взъерошенная» &c[Алая]'
  Lore:
    - '&7Модель MythicArmors: &fhair_spiky_3d.bbmodel'
    - '&aНосится отдельно ИЛИ вместе с маской/шлемом!'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:hair_spiky_3d_head_piece
    Color: 196,46,56
    Unbreakable: true
    HideFlags: true

HairPonytail3D_Chestnut:
  Material: LEATHER_HELMET
  Display: '&d3D Причёска «Хвост» &6[Каштановая]'
  Lore:
    - '&7Модель MythicArmors: &fhair_ponytail_3d.bbmodel'
    - '&aДлинный 3D-хвост с резинкой (перекрашивается Options.Color!)'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:hair_ponytail_3d_head_piece
    Color: 124,74,44
    Unbreakable: true
    HideFlags: true

HairPonytail3D_Silver:
  Material: LEATHER_HELMET
  Display: '&d3D Причёска «Хвост» &f[Серебристая]'
  Lore:
    - '&7Модель MythicArmors: &fhair_ponytail_3d.bbmodel'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:hair_ponytail_3d_head_piece
    Color: 222,228,242
    Unbreakable: true
    HideFlags: true

HairPonytail3D_HelmetSafe:
  Material: LEATHER_HELMET
  Display: '&d3D Причёска «Хвост» &7[Под шлемом — только пряди]'
  Lore:
    - '&7Служебный вариант (hair-3d.yml PARTIAL_MODEL):'
    - '&7Высокий хвост скрыт под шлемом, передние пряди остаются!'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:hair_ponytail_3d_under_helmet_head_piece
    Color: 225,192,110
    Unbreakable: true
    HideFlags: true

HairBob3D_Black:
  Material: LEATHER_HELMET
  Display: '&d3D Причёска «Каре» &8[Чёрная]'
  Lore:
    - '&7Модель MythicArmors: &fhair_bob_3d.bbmodel'
    - '&aОбъёмное 3D-каре с заколкой (перекрашивается Options.Color!)'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:hair_bob_3d_head_piece
    Color: 34,32,40
    Unbreakable: true
    HideFlags: true

HairBob3D_Rose:
  Material: LEATHER_HELMET
  Display: '&d3D Причёска «Каре» &d[Розовая]'
  Lore:
    - '&7Модель MythicArmors: &fhair_bob_3d.bbmodel'
  Equippable:
    Slot: HEAD
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:hair_bob_3d_head_piece
    Color: 236,132,178
    Unbreakable: true
    HideFlags: true

# ------------------------------------------------------------------------------
# 4C. 3D ПОЯСА, КОБУРЫ И ДЕКОР НА ТАЛИЮ (слот belt_3d или Поножи!)
#     Носятся САМОСТОЯТЕЛЬНО (без поножей!) ИЛИ ОДНОВРЕМЕННО с поножами!
# ------------------------------------------------------------------------------
TacticalUtilityBelt3D:
  Material: LEATHER_LEGGINGS
  Display: '&6Тактический пояс с кобурой &7[Пояс 3D]'
  Lore:
    - '&7Модель MythicArmors: &fbelt_tactical_3d.bbmodel'
    - '&a3D-подсумки, кобура на бедре и светящийся LED!'
    - '&bКладётся в слот «Пояс / Декор (3D)» в разделе Штанов'
  Equippable:
    Model: mythicarmor:belt_tactical_3d_legs_piece
    Slot: LEGS
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:belt_tactical_3d_legs_piece
    Unbreakable: true
    HideFlags: true

AdventurerPouchBelt3D:
  Material: LEATHER_LEGGINGS
  Display: '&eПояс искателя с зельем и кинжалом &7[Пояс 3D]'
  Lore:
    - '&7Модель MythicArmors: &fbelt_adventurer_3d.bbmodel'
    - '&aСумка, светящаяся колба маны, свиток и кинжал в ножнах!'
    - '&bКладётся в слот «Пояс / Декор (3D)» в разделе Штанов'
  Equippable:
    Model: mythicarmor:belt_adventurer_3d_legs_piece
    Slot: LEGS
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:belt_adventurer_3d_legs_piece
    Unbreakable: true
    HideFlags: true

RoyalChainBelt3D:
  Material: LEATHER_LEGGINGS
  Display: '&cКоролевский пояс с золотыми цепями &7[Пояс 3D]'
  Lore:
    - '&7Модель MythicArmors: &fbelt_chains_3d.bbmodel'
    - '&aЗолотые цепи, рубин и перекрашиваемый кушак (_t.png)!'
    - '&dМеняйте Options.Color для любого цвета ткани!'
  Equippable:
    Model: mythicarmor:belt_chains_3d_legs_piece
    Slot: LEGS
    EquipSound: 'item.armor.equip_leather'
  Options:
    ItemModel: mythicarmor:belt_chains_3d_legs_piece
    Color: 178,34,48
    Unbreakable: true
    HideFlags: true

# Комбо-модели Поножи + 3D-Пояс (0 лага, автоподмена через combo-overrides.yml!)
TacticalWardenLegsWithBelt:
  Material: DIAMOND_LEGGINGS
  Display: '&bТактические поножи «Стража» + Пояс &7[Комбо 3D]'
  Lore:
    - '&7Склеенная комбо-модель (0 лага): &ftactical_warden_belt.bbmodel'
  Equippable:
    Model: mythicarmor:tactical_warden_belt_legs_piece
    Slot: LEGS
    EquipSound: 'item.armor.equip_diamond'
  Options:
    ItemModel: mythicarmor:tactical_warden_belt_legs_piece
    Unbreakable: true
    HideFlags: true

GoldWardenLegsWithBelt:
  Material: LEATHER_LEGGINGS
  Display: '&6Золотые поножи «Стража» + Цепи &7[Комбо 3D]'
  Lore:
    - '&7Склеенная комбо-модель (0 лага): &fgold_warden_belt.bbmodel'
  Equippable:
    Model: mythicarmor:gold_warden_belt_legs_piece
    Slot: LEGS
    EquipSound: 'item.armor.equip_gold'
  Options:
    ItemModel: mythicarmor:gold_warden_belt_legs_piece
    Color: 178,34,48
    Unbreakable: true
    HideFlags: true

# ------------------------------------------------------------------------------
# 5. ПРИМЕРЫ 2D-ОДЕЖДЫ ИЗ СТАРТОВОГО ПАКА (носится ПОД 3D-бронёй!)
# ------------------------------------------------------------------------------
WardrobeLeatherJacket:
  Material: LEATHER_CHESTPLATE
  Display: '&eКожаная куртка &7[Слой скина 2D]'
  Lore:
    - '&7Слот: &fКуртка / Кофта (Скин)'
    - '&7Рисуется прямо на 2D-скине под 3D-бронежилетом!'
  Options:
    Color: 96,60,38
  Wardrobe:
    Slot: jacket
    Texture: leather_brown.png

WardrobeBlueJeans:
  Material: LEATHER_LEGGINGS
  Display: '&9Синие джинсы &7[Слой скина 2D]'
  Lore:
    - '&7Слот: &fШтаны (Скин)'
  Options:
    Color: 58,92,148
  Wardrobe:
    Slot: pants
    Texture: blue_jeans.png

WardrobeCombatBoots:
  Material: LEATHER_BOOTS
  Display: '&8Армейские берцы &7[Слой скина 2D]'
  Lore:
    - '&7Слот: &fБотинки (Скин)'
  Options:
    Color: 38,36,38
  Wardrobe:
    Slot: boots_skin
    Texture: combat_boots.png

WardrobeRedBandana:
  Material: RED_DYE
  Display: '&cКрасная бандана &7[Маска 2D]'
  Lore:
    - '&7Слот: &fМаска (Скин)'
  Wardrobe:
    Slot: mask_skin
    Texture: bandana_red.png

# 2D-пояса и декоративные ремни (слот belt_skin — поверх штанов!)
WardrobeLeatherBelt:
  Material: LEATHER
  Display: '&6Кожаный ремень с пряжкой &7[Пояс 2D]'
  Lore:
    - '&7Слот: &fПояс / Ремень (Скин)'
    - '&7Рисуется поверх любых штанов!'
  Wardrobe:
    Slot: belt_skin
    Texture: leather_belt.png

WardrobeTacticalBelt:
  Material: IRON_NUGGET
  Display: '&aТактический ремень с подсумками &7[Пояс 2D]'
  Lore:
    - '&7Слот: &fПояс / Ремень (Скин)'
  Wardrobe:
    Slot: belt_skin
    Texture: tactical_belt.png

WardrobeGoldChainBelt:
  Material: GOLD_NUGGET
  Display: '&eПояс с золотой цепью и рубином &7[Пояс 2D]'
  Lore:
    - '&7Слот: &fПояс / Ремень (Скин)'
  Wardrobe:
    Slot: belt_skin
    Texture: gold_chain_belt.png

WardrobeHolsterBelt:
  Material: TRIPWIRE_HOOK
  Display: '&6Ремень охотника с кобурой и флягой &7[Пояс 2D]'
  Lore:
    - '&7Слот: &fПояс / Ремень (Скин)'
  Wardrobe:
    Slot: belt_skin
    Texture: utility_holster_belt.png

WardrobeCrimsonSash:
  Material: RED_BANNER
  Display: '&cАлый боевой кушак &7[Пояс 2D]'
  Lore:
    - '&7Слот: &fПояс / Ремень (Скин)'
  Wardrobe:
    Slot: belt_skin
    Texture: crimson_sash.png

# ------------------------------------------------------------------------------
# 6. ПРИМЕР ЦЕЛЬНОГО КОСТЮМА С БЛОКИРОВКОЙ СЛОЁВ (v1.8.0)
#    Надевается в слот `jacket`, автоматически снимает и блокирует штаны,
#    футболку, пояс, перчатки, носки, ботинки, маску и шапку!
# ------------------------------------------------------------------------------
WardrobeHazmatSuit:
  Material: YELLOW_DYE
  Display: '&eКостюм химзащиты &7[Полный комплект 2D]'
  Lore:
    - '&7Слот: &fКуртка / Костюм (Скин)'
    - '&cБлокирует слои: &7футболка, штаны, пояс,'
    - '&7перчатки, носки, ботинки, маска, шапка'
    - '&eПри надевании мешающая одежда вернётся в инвентарь!'
  Wardrobe:
    Slot: jacket
    Texture: hazmat_suit.png
    Blocks:
      - undershirt
      - pants
      - belt_skin
      - gloves
      - socks
      - boots_skin
      - mask_skin
      - hat
    BlockMode: UNEQUIP
"""

WARDROBE_ITEMS_YML = """# ==============================================================================
# MythicWardrobe v1.4.1 — Привязка предметов MythicMobs к слотам гардероба
# Положите (или допишите секцию items:) в: plugins/MythicWardrobe/items.yml
# ==============================================================================
# ВАЖНО (v1.4.1+):
# • Все 3D-модели MythicArmors (TacticalGasMask3D, TacticalBackpackArmor,
#   TacticalWarden*, GoldWarden*) НЕ нуждаются в привязке здесь!
#   Каждая из них 100% самостоятельна и автоматически работает в ЛЮБОМ слоте
#   своей части тела: Статы, Косметика И 3D-слой (mask_3d / backpack_3d) —
#   как в одиночку (без другой брони!), так и одновременно по несколько моделей!
# • В items.yml достаточно указывать только 2D-одежду скина (SKIN_LAYER).
# ==============================================================================

items:
  # 2D-одежда из стартового пака (носится под 3D-бронёй)
  WardrobeLeatherJacket:
    slot: jacket
    texture: "leather_brown.png"
  WardrobeBlueJeans:
    slot: pants
    texture: "blue_jeans.png"
  WardrobeCombatBoots:
    slot: boots_skin
    texture: "combat_boots.png"
  WardrobeRedBandana:
    slot: mask_skin
    texture: "bandana_red.png"

  # 2D-пояса и ремни (слот belt_skin — рисуются поверх любых штанов!)
  WardrobeLeatherBelt:
    slot: belt_skin
    texture: "leather_belt.png"
  WardrobeTacticalBelt:
    slot: belt_skin
    texture: "tactical_belt.png"
  WardrobeGoldChainBelt:
    slot: belt_skin
    texture: "gold_chain_belt.png"
  WardrobeHolsterBelt:
    slot: belt_skin
    texture: "utility_holster_belt.png"
  WardrobeCrimsonSash:
    slot: belt_skin
    texture: "crimson_sash.png"

  # Цельный костюм химзащиты с блокировкой других слоёв одежды (v1.8.0)
  WardrobeHazmatSuit:
    slot: jacket
    texture: "hazmat_suit.png"
    blocks: [undershirt, pants, belt_skin, gloves, socks, boots_skin, mask_skin, hat]
    block-mode: UNEQUIP

  # 3D-пояса и декор на талию (явная привязка к belt_3d для быстрого клика ПКМ!)
  TacticalUtilityBelt3D:
    slot: belt_3d
  AdventurerPouchBelt3D:
    slot: belt_3d
  RoyalChainBelt3D:
    slot: belt_3d
"""

COMBO_OVERRIDES_YML = """# ==============================================================================
# MythicWardrobe v1.5.0 — Жёсткая 0-lag привязка комбо-моделей (combo-overrides.yml)
# Положите в: plugins/MythicWardrobe/combo-overrides.yml
# ==============================================================================
# КАК ЭТО РАБОТАЕТ:
# • Маска (TacticalGasMask3D) и Рюкзак (TacticalBackpackArmor) по-прежнему
#   надеваются 100% САМОСТОЯТЕЛЬНО без шлема / без бронежилета!
# • Когда же надеты ОБА предмета одновременно (напр. Бронежилет + Рюкзак в backpack_3d),
#   плагин подменяет визуальный пакет на цельную склеенную комбо-модель MythicArmors
#   (0 ArmorStand, 0 отставаний при беге — рюкзак приварен прямо к кости торса!).
# • Свои комбо для ЛЮБЫХ новых моделей создаются за 1 секунду прямо в игре командой:
#     /wardrobe merge <modelA> <modelB> <outputName>
# ==============================================================================

combos:
  # --- Бронежилет + 3D Рюкзак (жёсткая привязка к кости body, 0 лага!) ---
  tactical_vest_plus_backpack_armor:
    equipment-slot: CHEST
    primary-mythic-item: "TacticalWardenVest"
    secondary-slot: "backpack_3d"
    secondary-mythic-item: "TacticalBackpackArmor"
    result-mythic-item: "TacticalWardenVestBackpack"
    hide-secondary-display: true

  tactical_vest_plus_backpack_3d:
    equipment-slot: CHEST
    primary-mythic-item: "TacticalWardenVest"
    secondary-slot: "backpack_3d"
    secondary-mythic-item: "TacticalBackpack3D"
    result-mythic-item: "TacticalWardenVestBackpack"
    hide-secondary-display: true

  gold_vest_plus_backpack_armor:
    equipment-slot: CHEST
    primary-mythic-item: "GoldWardenChestCosmetic"
    secondary-slot: "backpack_3d"
    secondary-mythic-item: "TacticalBackpackArmor"
    result-mythic-item: "GoldWardenChestBackpack"
    hide-secondary-display: true

  gold_vest_plus_backpack_3d:
    equipment-slot: CHEST
    primary-mythic-item: "GoldWardenChestCosmetic"
    secondary-slot: "backpack_3d"
    secondary-mythic-item: "TacticalBackpack3D"
    result-mythic-item: "GoldWardenChestBackpack"
    hide-secondary-display: true

  # --- Шлем + 3D Противогаз (жёсткая привязка к кости head, 0 лага!) ---
  warden_helmet_plus_gasmask:
    equipment-slot: HEAD
    primary-mythic-item: "TacticalWardenHelmet"
    secondary-slot: "mask_3d"
    secondary-mythic-item: "TacticalGasMask3D"
    result-mythic-item: "TacticalWardenHelmetMasked"
    hide-secondary-display: true

  gold_helmet_plus_gasmask:
    equipment-slot: HEAD
    primary-mythic-item: "GoldWardenHelmetCosmetic"
    secondary-slot: "mask_3d"
    secondary-mythic-item: "TacticalGasMask3D"
    result-mythic-item: "GoldWardenHelmetMasked"
    hide-secondary-display: true

  tactical_legs_plus_belt:
    equipment-slot: LEGS
    primary-mythic-item: "TacticalWardenLeggings"
    secondary-slot: "belt_3d"
    secondary-mythic-item: "TacticalUtilityBelt3D"
    result-mythic-item: "TacticalWardenLegsWithBelt"
    hide-secondary-display: true

  gold_legs_plus_chains_belt:
    equipment-slot: LEGS
    primary-mythic-item: "GoldWardenLeggingsCosmetic"
    secondary-slot: "belt_3d"
    secondary-mythic-item: "RoyalChainBelt3D"
    result-mythic-item: "GoldWardenLegsWithBelt"
    hide-secondary-display: true
"""

HAIR_3D_YML = """# MythicWardrobe v1.9.0 — Автопривязка 3D-волос к 2D-волосам и частичное скрытие под шлемом
enabled: true
manual-slot-overrides-binding: true
trigger-on-2d-hat: true
default-helmet-list-mode: ALL
default-helmets: []
default-exceptions: []

bindings:
  hair_brown.png:
    mythic-item: "HairSpiky3D_Golden"
    color: "135,78,52"
    helmet-mode: HIDE_ALL
    helmet-list-mode: ALL
    helmets: []
    exceptions: []

  hair_blonde.png:
    mythic-item: "HairPonytail3D_Chestnut"
    color: "225,192,110"
    helmet-mode: PARTIAL_MODEL
    helmet-partial-item: "HairPonytail3D_HelmetSafe"
    helmet-list-mode: ALL
    helmets: []
    exceptions: []

  hair_black.png:
    mythic-item: "HairBob3D_Black"
    color: "42,36,34"
    helmet-mode: HIDE_ALL
    helmet-list-mode: ALL
    helmets: []
    exceptions: []

items:
  HairPonytail3D_Chestnut:
    helmet-mode: PARTIAL_MODEL
    helmet-partial-item: "HairPonytail3D_HelmetSafe"
    helmet-list-mode: ALL
    helmets: []
    exceptions: []

  HairPonytail3D_Silver:
    helmet-mode: PARTIAL_MODEL
    helmet-partial-item: "HairPonytail3D_HelmetSafe"
    helmet-list-mode: ALL
    helmets: []
    exceptions: []

  HairSpiky3D_Golden:
    helmet-mode: HIDE_ALL
    helmet-list-mode: ALL
    helmets: []
    exceptions: []

  HairBob3D_Black:
    helmet-mode: HIDE_ALL
    helmet-list-mode: ALL
    helmets: []
    exceptions: []
"""

README_RU = """================================================================================
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
"""


def main():
    os.makedirs(os.path.join(OUT_DIR, "plugins", "MythicArmors", "models"), exist_ok=True)
    os.makedirs(os.path.join(OUT_DIR, "plugins", "MythicMobs", "Items"), exist_ok=True)
    os.makedirs(os.path.join(OUT_DIR, "plugins", "MythicWardrobe"), exist_ok=True)
    os.makedirs(os.path.dirname(ZIP_PATH), exist_ok=True)

    # 1. Tactical Warden (Slate blue-grey steel + dark kevlar + gunmetal trim + glowing cyan visor)
    tactical_palette = {
        "primary": (64, 78, 96, 255),
        "dark":    (30, 34, 42, 255),
        "trim":    (138, 152, 172, 255),
        "glow":    (46, 224, 248, 255),
        "pouch":   (56, 66, 52, 255),
        "boot":    (38, 40, 46, 255),
        "filter_glow": (46, 232, 134, 255),
    }

    # 2. Gold Warden Cosmetic (Obsidian dark steel + regal gold trim + glowing ruby visor)
    gold_palette = {
        "primary": (44, 40, 52, 255),
        "dark":    (24, 22, 30, 255),
        "trim":    (232, 186, 62, 255),
        "glow":    (244, 54, 78, 255),
        "pouch":   (112, 42, 46, 255),
        "boot":    (36, 32, 42, 255),
    }

    m_tac, img_tac, emis_tac, icons_tac = build_warden_set("tactical_warden", tactical_palette, include_body=True, crest=False, gas_mask=False)
    m_gold, img_gold, emis_gold, icons_gold = build_warden_set("gold_warden_cosmetic", gold_palette, include_body=True, crest=True, gas_mask=False)
    m_mask, img_mask, emis_mask, icons_mask = build_warden_set("tactical_warden_masked", tactical_palette, include_body=False, crest=False, gas_mask=True)
    m_gmask, img_gmask, emis_gmask, icon_gmask = build_tactical_gasmask("tactical_gasmask", tactical_palette)
    m_bp, img_bp, emis_bp, icon_bp = build_tactical_backpack("tactical_backpack")

    # Zero-lag native combo models (chestplate + backpack fused on body bone; gold helmet + gasmask fused on head bone!)
    # Omit bare-skin front straps when merging over heavy armored chestplates so front armor plates stay 100% clean!
    bp_front_straps = {"strap_front_r", "strap_front_l", "chest_cross_strap", "chest_buckle", "shoulder_radio_mic"}
    m_tac_bp, img_tac_bp, _ = merge_two_models(
        m_tac, img_tac, emis_tac, m_bp, img_bp, emis_bp,
        "tactical_warden_backpack",
        keep_bones={"body", "right_arm", "left_arm"},
        skip_b_names=bp_front_straps
    )
    m_gold_bp, img_gold_bp, _ = merge_two_models(
        m_gold, img_gold, emis_gold, m_bp, img_bp, emis_bp,
        "gold_warden_backpack",
        keep_bones={"body", "right_arm", "left_arm"},
        skip_b_names=bp_front_straps
    )
    m_gold_mask, img_gold_mask, _ = merge_two_models(
        m_gold, img_gold, emis_gold, m_gmask, img_gmask, emis_gmask,
        "gold_warden_masked",
        keep_bones={"head"}
    )

    m_hair_spiky, img_h_spiky, icon_h_spiky = build_hair_spiky("hair_spiky_3d")
    m_hair_pt, img_h_pt, icon_h_pt = build_hair_ponytail("hair_ponytail_3d")
    m_hair_pt_uh, _, _ = build_hair_ponytail_under_helmet("hair_ponytail_3d_under_helmet")
    m_hair_bob, img_h_bob, icon_h_bob = build_hair_bob("hair_bob_3d")

    m_belt_tac, img_b_tac, emis_b_tac, icon_b_tac = build_belt_tactical("belt_tactical_3d")
    m_belt_adv, img_b_adv, emis_b_adv, icon_b_adv = build_belt_adventurer("belt_adventurer_3d")
    m_belt_chn, img_b_chn, emis_b_chn, icon_b_chn = build_belt_chains("belt_chains_3d")

    m_tac_belt, img_tac_belt, _ = merge_two_models(
        m_tac, img_tac, emis_tac, m_belt_tac, img_b_tac, emis_b_tac,
        "tactical_warden_belt",
        keep_bones={"waist", "right_leg", "left_leg"},
    )
    m_gold_belt, img_gold_belt, _ = merge_two_models(
        m_gold, img_gold, emis_gold, m_belt_chn, img_b_chn, emis_b_chn,
        "gold_warden_belt",
        keep_bones={"waist", "right_leg", "left_leg"},
    )

    models = [
        ("tactical_warden.bbmodel", m_tac),
        ("gold_warden_cosmetic.bbmodel", m_gold),
        ("tactical_warden_masked.bbmodel", m_mask),
        ("gold_warden_masked.bbmodel", m_gold_mask),
        ("tactical_gasmask.bbmodel", m_gmask),
        ("tactical_backpack.bbmodel", m_bp),
        ("tactical_warden_backpack.bbmodel", m_tac_bp),
        ("gold_warden_backpack.bbmodel", m_gold_bp),
        ("hair_spiky_3d.bbmodel", m_hair_spiky),
        ("hair_ponytail_3d.bbmodel", m_hair_pt),
        ("hair_ponytail_3d_under_helmet.bbmodel", m_hair_pt_uh),
        ("hair_bob_3d.bbmodel", m_hair_bob),
        ("belt_tactical_3d.bbmodel", m_belt_tac),
        ("belt_adventurer_3d.bbmodel", m_belt_adv),
        ("belt_chains_3d.bbmodel", m_belt_chn),
        ("tactical_warden_belt.bbmodel", m_tac_belt),
        ("gold_warden_belt.bbmodel", m_gold_belt),
    ]
    for fn, builder in models:
        p = os.path.join(OUT_DIR, "plugins", "MythicArmors", "models", fn)
        with open(p, "w", encoding="utf-8") as f:
            json.dump(builder.to_dict(), f, indent=2)
        print(f"Wrote {fn}: {len(builder.elements)} cubes, {len(builder.groups)} bones, {len(builder.textures)} textures")

    with open(os.path.join(OUT_DIR, "plugins", "MythicMobs", "Items", "MythicWardrobe_TestArmorPack.yml"), "w", encoding="utf-8") as f:
        f.write(MYTHICMOBS_ITEMS_YML.strip() + "\n")
    with open(os.path.join(OUT_DIR, "plugins", "MythicWardrobe", "items.yml"), "w", encoding="utf-8") as f:
        f.write(WARDROBE_ITEMS_YML.strip() + "\n")
    with open(os.path.join(OUT_DIR, "plugins", "MythicWardrobe", "combo-overrides.yml"), "w", encoding="utf-8") as f:
        f.write(COMBO_OVERRIDES_YML.strip() + "\n")
    with open(os.path.join(OUT_DIR, "plugins", "MythicWardrobe", "hair-3d.yml"), "w", encoding="utf-8") as f:
        f.write(HAIR_3D_YML.strip() + "\n")
    with open(os.path.join(OUT_DIR, "README_УСТАНОВКА.txt"), "w", encoding="utf-8") as f:
        f.write(README_RU.strip() + "\n")

    # Package clean ZIP (excluding _preview_check.png)
    if os.path.exists(ZIP_PATH):
        os.remove(ZIP_PATH)
    with zipfile.ZipFile(ZIP_PATH, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for dirpath, _, filenames in os.walk(OUT_DIR):
            for fn in sorted(filenames):
                if fn.startswith("_"):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, OUT_DIR)
                zf.write(full, arcname=rel)

    print(f"Packaged ZIP: {ZIP_PATH} ({os.path.getsize(ZIP_PATH)} bytes)")

    # Render 4-panel visual check of the 3 NEW 3D Belts + Tactical Leggings+Belt combo!
    def tint_chain_preview(img, rgb):
        out = img.copy()
        px = out.load()
        for y in range(out.height):
            for x in range(out.width):
                r, g, b, a = px[x, y]
                if a > 0 and x < 32 and y < 16:
                    px[x, y] = (r * rgb[0] // 255, g * rgb[1] // 255, b * rgb[2] // 255, a)
        return out

    sheet = Image.new("RGBA", (1080, 410), (11, 15, 23, 255))
    d = ImageDraw.Draw(sheet)
    p1 = render_model_preview(m_belt_tac.to_dict(), img_b_tac, (255, 315), yaw_deg=32, pitch_deg=10, show_mannequin=True)
    p2 = render_model_preview(m_belt_adv.to_dict(), img_b_adv, (255, 315), yaw_deg=-32, pitch_deg=10, show_mannequin=True)
    p3 = render_model_preview(m_belt_chn.to_dict(), tint_chain_preview(img_b_chn, (178, 34, 48)), (255, 315), yaw_deg=26, pitch_deg=10, show_mannequin=True)
    p4 = render_model_preview(m_tac_belt.to_dict(), img_tac_belt, (255, 315), yaw_deg=32, pitch_deg=10, show_mannequin=True)
    sheet.paste(p1, (12, 18))
    sheet.paste(p2, (279, 18))
    sheet.paste(p3, (546, 18))
    sheet.paste(p4, (813, 18))
    d.text((20, 340), "1. belt_tactical_3d (HOLSTER)", fill=(74, 222, 128, 255))
    d.text((285, 340), "2. belt_adventurer_3d (POTION)", fill=(56, 189, 248, 255))
    d.text((555, 340), "3. belt_chains_3d (GOLD+SASH)", fill=(250, 204, 21, 255))
    d.text((820, 340), "4. tactical_warden_belt (COMBO)", fill=(247, 174, 248, 255))

    sheet.paste(icon_b_tac.resize((32, 32), Image.NEAREST), (120, 362), icon_b_tac.resize((32, 32), Image.NEAREST))
    sheet.paste(icon_b_adv.resize((32, 32), Image.NEAREST), (388, 362), icon_b_adv.resize((32, 32), Image.NEAREST))
    sheet.paste(icon_b_chn.resize((32, 32), Image.NEAREST), (655, 362), icon_b_chn.resize((32, 32), Image.NEAREST))

    sheet.save(PREVIEW_PNG)
    print(f"Rendered preview check: {PREVIEW_PNG}")


if __name__ == "__main__":
    main()
