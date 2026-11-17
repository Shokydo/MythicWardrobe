package dev.mythicwardrobe.skin;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generates a curated starter pack of UV-accurate 64x64 pixel-art Minecraft skins & clothing overlays
 * across body_skin (wide + slim), eyes, hairs, undershirts, jackets (wide + slim), pants, socks, boots, and masks.
 */
public final class SampleOverlayPackGenerator {

    private SampleOverlayPackGenerator() {
    }

    public static void generatePack(File overlaysDir, Logger logger) {
        generateMissingCategories(overlaysDir, logger, true);
    }

    /**
     * Populates any empty starter category folder inside overlaysDir without overwriting
     * existing user textures (or forces regeneration of all starter files when forceAll=true).
     */
    public static int generateMissingCategories(File overlaysDir, Logger logger, boolean forceAll) {
        int written = 0;
        try {
            // 1. BODY SKINS (wide 4px & slim 3px)
            if (forceAll || isCategoryEmpty(overlaysDir, "body_skin")) {
                generateBodySkinVariants(overlaysDir, "light", new Color(240, 204, 176), new Color(62, 68, 82));
                generateBodySkinVariants(overlaysDir, "tan", new Color(214, 165, 126), new Color(56, 62, 74));
                generateBodySkinVariants(overlaysDir, "olive", new Color(192, 152, 114), new Color(52, 58, 66));
                generateBodySkinVariants(overlaysDir, "dark", new Color(120, 78, 52), new Color(46, 52, 62));
                generateBodySkinVariants(overlaysDir, "pale_elf", new Color(224, 232, 238), new Color(54, 68, 78));
                written += 10;
            }

            // 2. EYES (face inner layer at x=8..15, y=8..15)
            if (forceAll || isCategoryEmpty(overlaysDir, "eyes")) {
                save(overlaysDir, "eyes/blue.png", makeEyes(new Color(45, 130, 225), new Color(25, 82, 165)));
                save(overlaysDir, "eyes/green.png", makeEyes(new Color(52, 168, 92), new Color(28, 110, 58)));
                save(overlaysDir, "eyes/brown.png", makeEyes(new Color(122, 76, 44), new Color(74, 44, 24)));
                save(overlaysDir, "eyes/amber.png", makeEyes(new Color(224, 158, 38), new Color(165, 102, 18)));
                save(overlaysDir, "eyes/crimson.png", makeEyes(new Color(208, 46, 62), new Color(132, 22, 34)));
                written += 5;
            }

            // 3. EYEBROWS (2 variations above eyes on face inner layer)
            if (forceAll || isCategoryEmpty(overlaysDir, "eyebrows")) {
                save(overlaysDir, "eyebrows/dark_thick.png", makeEyebrows(new Color(44, 34, 28), false));
                save(overlaysDir, "eyebrows/light_arched.png", makeEyebrows(new Color(172, 132, 72), true));
                written += 2;
            }

            // 4. HAIRS (head outer layer + inner top)
            if (forceAll || isCategoryEmpty(overlaysDir, "hairs")) {
                save(overlaysDir, "hairs/brown_short.png", makeHair(new Color(92, 62, 42), new Color(66, 44, 28), HairStyle.SHORT));
                save(overlaysDir, "hairs/black_messy.png", makeHair(new Color(34, 36, 44), new Color(20, 22, 28), HairStyle.MESSY));
                save(overlaysDir, "hairs/blonde_swept.png", makeHair(new Color(228, 196, 102), new Color(188, 152, 64), HairStyle.SWEPT));
                save(overlaysDir, "hairs/red_bob.png", makeHair(new Color(182, 66, 44), new Color(136, 44, 28), HairStyle.BOB));
                save(overlaysDir, "hairs/silver_long.png", makeHair(new Color(214, 222, 234), new Color(168, 178, 196), HairStyle.SWEPT));
                written += 5;
            }

            // 5. BEARDS (2 variations: full beard + goatee)
            if (forceAll || isCategoryEmpty(overlaysDir, "beards")) {
                save(overlaysDir, "beards/full_beard.png", makeBeard(new Color(74, 48, 32), false));
                save(overlaysDir, "beards/goatee_dark.png", makeBeard(new Color(38, 36, 40), true));
                written += 2;
            }

            // 6. SCARS & MARKINGS (2 variations: battle eye scar + blue warpaint)
            if (forceAll || isCategoryEmpty(overlaysDir, "scars")) {
                save(overlaysDir, "scars/eye_scar.png", makeScar(false));
                save(overlaysDir, "scars/warpaint_blue.png", makeScar(true));
                written += 2;
            }

            // 7. UNDERSHIRTS (wide + slim)
            if (forceAll || isCategoryEmpty(overlaysDir, "undershirts")) {
                generateUndershirtVariants(overlaysDir, "white_tshirt", new Color(236, 238, 242), new Color(206, 210, 218), false, false);
                generateUndershirtVariants(overlaysDir, "black_tshirt", new Color(42, 45, 52), new Color(28, 30, 36), false, false);
                generateUndershirtVariants(overlaysDir, "striped_navy", new Color(44, 68, 112), new Color(230, 234, 240), true, false);
                generateUndershirtVariants(overlaysDir, "olive_tank", new Color(92, 108, 72), new Color(70, 84, 54), false, true);
                written += 8;
            }

            // 8. JACKETS (outer torso + arms, wide + slim)
            if (forceAll || isCategoryEmpty(overlaysDir, "jackets")) {
                generateJacketVariants(overlaysDir, "leather_brown", new Color(96, 60, 38), new Color(68, 42, 26), new Color(190, 152, 92), false);
                generateJacketVariants(overlaysDir, "black_hoodie", new Color(36, 39, 48), new Color(24, 26, 34), new Color(215, 220, 230), true);
                generateJacketVariants(overlaysDir, "military_parka", new Color(72, 90, 62), new Color(52, 66, 44), new Color(156, 136, 94), true);
                generateJacketVariants(overlaysDir, "red_bomber", new Color(164, 42, 48), new Color(118, 28, 34), new Color(224, 218, 206), false);
                save(overlaysDir, "jackets/wide/hazmat_suit.png", makeHazmatSuit(false));
                save(overlaysDir, "jackets/slim/hazmat_suit.png", makeHazmatSuit(true));
                written += 10;
            } else if (!new File(overlaysDir, "jackets/wide/hazmat_suit.png").isFile()) {
                save(overlaysDir, "jackets/wide/hazmat_suit.png", makeHazmatSuit(false));
                save(overlaysDir, "jackets/slim/hazmat_suit.png", makeHazmatSuit(true));
                written += 2;
            }

            // 9. PANTS
            if (forceAll || isCategoryEmpty(overlaysDir, "pants")) {
                save(overlaysDir, "pants/blue_jeans.png", makePants(new Color(58, 92, 148), new Color(42, 68, 112), new Color(168, 138, 86)));
                save(overlaysDir, "pants/cargo_olive.png", makePants(new Color(82, 96, 66), new Color(60, 72, 48), new Color(48, 52, 44)));
                save(overlaysDir, "pants/tactical_black.png", makePants(new Color(38, 42, 48), new Color(26, 28, 34), new Color(78, 84, 94)));
                save(overlaysDir, "pants/grey_joggers.png", makePants(new Color(128, 134, 144), new Color(98, 104, 114), new Color(210, 214, 220)));
                written += 4;
            }

            // 9B. BELTS / WAIST ACCESSORIES (5 distinct 2D belt, pouch, holster, chain & sash variations!)
            if (forceAll || isCategoryEmpty(overlaysDir, "belts")) {
                save(overlaysDir, "belts/leather_belt.png", makeBelt(BeltStyle.LEATHER));
                save(overlaysDir, "belts/tactical_belt.png", makeBelt(BeltStyle.TACTICAL));
                save(overlaysDir, "belts/gold_chain_belt.png", makeBelt(BeltStyle.GOLD_CHAIN));
                save(overlaysDir, "belts/utility_holster_belt.png", makeBelt(BeltStyle.HOLSTER));
                save(overlaysDir, "belts/crimson_sash.png", makeBelt(BeltStyle.CRIMSON_SASH));
                written += 5;
            }

            // 10. SOCKS (lower legs inner layer)
            if (forceAll || isCategoryEmpty(overlaysDir, "socks")) {
                save(overlaysDir, "socks/white_socks.png", makeSocks(new Color(238, 240, 244), new Color(185, 58, 62)));
                save(overlaysDir, "socks/black_socks.png", makeSocks(new Color(36, 38, 44), new Color(82, 86, 98)));
                save(overlaysDir, "socks/striped_socks.png", makeSocks(new Color(232, 236, 242), new Color(48, 92, 168)));
                written += 3;
            }

            // 11. BOOTS (lower legs outer layer + soles)
            if (forceAll || isCategoryEmpty(overlaysDir, "boots")) {
                save(overlaysDir, "boots/combat_boots.png", makeBoots(new Color(38, 36, 38), new Color(24, 22, 24), new Color(116, 118, 124)));
                save(overlaysDir, "boots/red_sneakers.png", makeBoots(new Color(184, 46, 54), new Color(236, 238, 242), new Color(244, 244, 244)));
                save(overlaysDir, "boots/leather_boots.png", makeBoots(new Color(108, 68, 40), new Color(68, 42, 24), new Color(186, 144, 88)));
                written += 3;
            }

            // 12. GLOVES (wide + slim, 2 variations)
            if (forceAll || isCategoryEmpty(overlaysDir, "gloves")) {
                generateGloveVariants(overlaysDir, "leather_fingerless", new Color(104, 66, 40), new Color(184, 152, 92), true);
                generateGloveVariants(overlaysDir, "tactical_black", new Color(34, 38, 44), new Color(86, 94, 108), false);
                written += 4;
            }

            // 13. MASKS (head outer layer)
            if (forceAll || isCategoryEmpty(overlaysDir, "masks")) {
                save(overlaysDir, "masks/bandana_red.png", makeMask(new Color(168, 38, 46), new Color(234, 228, 220), MaskStyle.BANDANA));
                save(overlaysDir, "masks/balaclava_black.png", makeMask(new Color(32, 34, 40), new Color(56, 60, 70), MaskStyle.BALACLAVA));
                save(overlaysDir, "masks/respirator.png", makeMask(new Color(52, 58, 66), new Color(76, 186, 152), MaskStyle.RESPIRATOR));
                written += 3;
            }

            // 14. HATS (head outer layer, 2 variations)
            if (forceAll || isCategoryEmpty(overlaysDir, "hats")) {
                save(overlaysDir, "hats/beanie_grey.png", makeHat(false));
                save(overlaysDir, "hats/ushanka_brown.png", makeHat(true));
                written += 2;
            }

            if (written > 0 && logger != null) {
                logger.info("Generated " + written + " curated 64x64 starter overlay PNGs across all categories.");
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "Failed to generate sample overlay pack", e);
            }
        }
        return written;
    }

    private static boolean isCategoryEmpty(File overlaysDir, String folder) {
        File dir = new File(overlaysDir, folder);
        if (!dir.exists()) return true;
        return !containsPng(dir);
    }

    private static boolean containsPng(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (containsPng(f)) return true;
            } else if (f.getName().toLowerCase().endsWith(".png")) {
                return true;
            }
        }
        return false;
    }

    private enum HairStyle { SHORT, MESSY, SWEPT, BOB }
    private enum MaskStyle { BANDANA, BALACLAVA, RESPIRATOR }

    private static void generateBodySkinVariants(File overlaysDir, String name, Color base, Color underwear) throws IOException {
        save(overlaysDir, "body_skin/wide/" + name + ".png", makeBodySkin(false, base, underwear));
        save(overlaysDir, "body_skin/slim/" + name + ".png", makeBodySkin(true, base, underwear));
    }

    private static void generateUndershirtVariants(File overlaysDir, String name, Color primary, Color accent, boolean striped, boolean tank) throws IOException {
        save(overlaysDir, "undershirts/wide/" + name + ".png", makeUndershirt(false, primary, accent, striped, tank));
        save(overlaysDir, "undershirts/slim/" + name + ".png", makeUndershirt(true, primary, accent, striped, tank));
    }

    private static void generateJacketVariants(File overlaysDir, String name, Color primary, Color dark, Color accent, boolean hoodie) throws IOException {
        save(overlaysDir, "jackets/wide/" + name + ".png", makeJacket(false, primary, dark, accent, hoodie));
        save(overlaysDir, "jackets/slim/" + name + ".png", makeJacket(true, primary, dark, accent, hoodie));
    }

    public static BufferedImage makeBodySkin(boolean slim, Color base, Color underwear) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color shade = darken(base, 18);
        Color deep = darken(base, 32);
        Color hi = lighten(base, 12);

        // HEAD inner (0,0 -> 32,16)
        g.setColor(base);
        g.fillRect(8, 0, 16, 8);
        g.fillRect(0, 8, 32, 8);
        // Face soft shading & subtle nose/mouth
        g.setColor(hi);
        g.fillRect(9, 9, 6, 5);
        g.setColor(shade);
        g.fillRect(8, 15, 8, 1);   // chin shadow
        g.fillRect(11, 13, 2, 1);  // subtle mouth line
        g.setColor(deep);
        g.fillRect(16, 0, 8, 8);   // bottom of head

        // TORSO inner (16,16 -> 40,32)
        g.setColor(base);
        g.fillRect(20, 16, 16, 4);
        g.fillRect(16, 20, 24, 12);
        g.setColor(shade);
        g.fillRect(16, 20, 4, 12);  // right side torso
        g.fillRect(28, 20, 4, 12);  // left side torso
        g.fillRect(21, 22, 6, 1);   // chest line
        g.fillRect(22, 24, 4, 1);
        g.setColor(hi);
        g.fillRect(21, 20, 6, 2);   // upper chest highlight
        // Modest base underwear shorts on lower torso + upper legs
        g.setColor(underwear);
        g.fillRect(16, 29, 24, 3);
        g.fillRect(28, 16, 8, 4);

        // ARMS: Classic (4px wide -> total strip width 16) vs Slim (3px wide -> total strip width 14)
        int armStripW = slim ? 14 : 16;
        int armTopW = slim ? 6 : 8;
        int frontW = slim ? 3 : 4;

        // Right Arm inner (40,16)
        g.setColor(base);
        g.fillRect(44, 16, armTopW, 4);
        g.fillRect(40, 20, armStripW, 12);
        g.setColor(shade);
        g.fillRect(40, 20, 4, 12);          // outer edge shading
        g.fillRect(44, 30, frontW, 2);      // wrist shade
        g.setColor(hi);
        g.fillRect(44, 21, frontW, 6);

        // Left Arm inner (32,48)
        g.setColor(base);
        g.fillRect(36, 48, armTopW, 4);
        g.fillRect(32, 52, armStripW, 12);
        g.setColor(shade);
        g.fillRect(32 + 4 + frontW, 52, 4, 12);
        g.fillRect(36, 62, frontW, 2);
        g.setColor(hi);
        g.fillRect(36, 53, frontW, 6);

        // LEGS: Right Leg (0,16 -> 16,32) & Left Leg (16,48 -> 32,64)
        g.setColor(base);
        g.fillRect(4, 16, 8, 4);
        g.fillRect(0, 20, 16, 12);
        g.fillRect(20, 48, 8, 4);
        g.fillRect(16, 52, 16, 12);

        g.setColor(shade);
        g.fillRect(0, 20, 4, 12);
        g.fillRect(24, 52, 4, 12);
        g.fillRect(4, 30, 4, 2);
        g.fillRect(20, 62, 4, 2);

        g.setColor(underwear);
        g.fillRect(0, 20, 16, 3);
        g.fillRect(16, 52, 16, 3);
        g.fillRect(4, 16, 4, 4);
        g.fillRect(20, 48, 4, 4);

        g.dispose();
        return img;
    }

    private static BufferedImage makeEyes(Color iris, Color pupil) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Face inner layer is x=8..15, y=8..15
        // Left eye (viewer left = x=9..10, y=11..12) and Right eye (x=13..14, y=11..12)
        g.setColor(new Color(246, 248, 252, 255)); // sclera white
        g.fillRect(9, 11, 1, 2);
        g.fillRect(14, 11, 1, 2);
        g.setColor(iris);
        g.fillRect(10, 11, 1, 2);
        g.fillRect(13, 11, 1, 2);
        g.setColor(pupil);
        g.fillRect(10, 11, 1, 1);
        g.fillRect(13, 11, 1, 1);
        g.dispose();
        return img;
    }

    private static BufferedImage makeHair(Color main, Color dark, HairStyle style) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color hi = lighten(main, 22);

        // Paint top & back on inner head (so there are no bald gaps) AND textured locks on outer head (32,0 -> 64,16)
        // Inner head top (8,0,8,8) & back (24,8,8,8)
        g.setColor(main);
        g.fillRect(8, 0, 8, 8);
        g.fillRect(24, 8, 8, 6);
        g.fillRect(0, 8, 8, 3);
        g.fillRect(16, 8, 8, 3);
        g.fillRect(8, 8, 8, 2);

        // Outer head top (40,0,8,8)
        g.setColor(main);
        g.fillRect(40, 0, 8, 8);
        g.setColor(hi);
        g.fillRect(41, 1, 6, 2);
        g.setColor(dark);
        g.fillRect(40, 6, 8, 2);

        // Outer back (56,8,8,8)
        g.setColor(main);
        g.fillRect(56, 8, 8, style == HairStyle.BOB ? 8 : 6);
        g.setColor(dark);
        g.fillRect(56, 12, 8, style == HairStyle.BOB ? 4 : 2);

        // Outer sides: Right (32,8,8,8) & Left (48,8,8,8)
        int sideLen = switch (style) {
            case SHORT -> 3;
            case MESSY -> 4;
            case SWEPT -> 5;
            case BOB -> 8;
        };
        g.setColor(main);
        g.fillRect(32, 8, 8, sideLen);
        g.fillRect(48, 8, 8, sideLen);
        g.setColor(dark);
        g.fillRect(32, 8 + sideLen - 1, 8, 1);
        g.fillRect(48, 8 + sideLen - 1, 8, 1);
        // Sideburns near ears
        g.fillRect(32, 8, 3, Math.min(6, sideLen + 2));
        g.fillRect(53, 8, 3, Math.min(6, sideLen + 2));

        // Outer front bangs (40,8,8,8)
        g.setColor(main);
        switch (style) {
            case SHORT -> {
                g.fillRect(40, 8, 8, 2);
                g.setColor(dark);
                g.fillRect(40, 10, 2, 1);
                g.fillRect(46, 10, 2, 1);
            }
            case MESSY -> {
                g.fillRect(40, 8, 8, 2);
                g.fillRect(41, 10, 2, 1);
                g.fillRect(44, 10, 2, 1);
                g.setColor(hi);
                g.fillRect(42, 8, 3, 1);
                g.setColor(dark);
                g.fillRect(40, 10, 1, 2);
                g.fillRect(47, 10, 1, 2);
            }
            case SWEPT -> {
                g.fillRect(40, 8, 8, 2);
                g.fillRect(40, 10, 5, 1);
                g.fillRect(40, 11, 2, 1);
                g.setColor(hi);
                g.fillRect(41, 9, 4, 1);
            }
            case BOB -> {
                g.fillRect(40, 8, 8, 2);
                g.fillRect(40, 10, 1, 6);
                g.fillRect(47, 10, 1, 6);
                g.setColor(dark);
                g.fillRect(40, 14, 1, 2);
                g.fillRect(47, 14, 1, 2);
            }
        }

        g.dispose();
        return img;
    }

    private static BufferedImage makeUndershirt(boolean slim, Color primary, Color accent, boolean striped, boolean tank) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color shade = darken(primary, 20);

        // Torso inner layer (16,16 -> 40,32)
        g.setColor(primary);
        g.fillRect(20, 16, 16, 4);
        g.fillRect(16, 20, 24, 11);

        if (striped) {
            g.setColor(accent);
            for (int y = 21; y < 31; y += 2) {
                g.fillRect(16, y, 24, 1);
            }
        } else {
            g.setColor(shade);
            g.fillRect(16, 20, 4, 11);
            g.fillRect(28, 20, 4, 11);
            g.fillRect(16, 30, 24, 1);
        }

        // Collar V/U cutout on front neck (22..25, y=20)
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(22, 20, 4, 1);
        if (tank) {
            g.fillRect(22, 21, 4, 1);
            g.fillRect(20, 20, 1, 2);
            g.fillRect(27, 20, 1, 2);
        }
        g.setComposite(AlphaComposite.SrcOver);

        // Short sleeves on arms (skip if tank top!)
        if (!tank) {
            int armW = slim ? 14 : 16;
            int topW = slim ? 6 : 8;
            g.setColor(primary);
            // Right arm top & upper sleeve
            g.fillRect(44, 16, topW, 4);
            g.fillRect(40, 20, armW, 5);
            // Left arm top & upper sleeve
            g.fillRect(36, 48, topW, 4);
            g.fillRect(32, 52, armW, 5);
            if (striped) {
                g.setColor(accent);
                g.fillRect(40, 21, armW, 1);
                g.fillRect(40, 23, armW, 1);
                g.fillRect(32, 53, armW, 1);
                g.fillRect(32, 55, armW, 1);
            } else {
                g.setColor(shade);
                g.fillRect(40, 24, armW, 1);
                g.fillRect(32, 56, armW, 1);
            }
        }

        g.dispose();
        return img;
    }

    private static BufferedImage makeJacket(boolean slim, Color primary, Color dark, Color accent, boolean hoodie) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color hi = lighten(primary, 16);

        // Torso OUTER layer (16,32 -> 40,48)
        g.setColor(primary);
        g.fillRect(20, 32, 16, 4);  // shoulders/bottom
        g.fillRect(16, 36, 24, 12); // full wrap

        // Side & hem shading
        g.setColor(dark);
        g.fillRect(16, 36, 4, 12);
        g.fillRect(28, 36, 4, 12);
        g.fillRect(16, 46, 24, 2);  // ribbed hem

        // Back detail (32..39, 36..47)
        g.setColor(hi);
        g.fillRect(33, 37, 6, 2);
        if (hoodie) {
            // Hood draped on upper back
            g.setColor(dark);
            g.fillRect(32, 36, 8, 4);
            g.setColor(primary);
            g.fillRect(33, 36, 6, 3);
        }

        // Front (20..27, 36..47): open collar showing undershirt + zipper/pockets!
        if (hoodie) {
            // Hoodie kangaroo pocket + drawstrings
            g.setColor(dark);
            g.fillRect(21, 43, 6, 3);
            g.setColor(accent);
            g.fillRect(22, 37, 1, 4);
            g.fillRect(25, 37, 1, 4);
        } else {
            // Open front jacket with lapels & zipper trim
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(23, 36, 2, 10); // leaves undershirt visible in center!
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(accent);
            g.fillRect(22, 36, 1, 11);
            g.fillRect(25, 36, 1, 11);
            // Chest pockets
            g.setColor(dark);
            g.fillRect(20, 41, 2, 2);
            g.fillRect(26, 41, 2, 2);
        }

        // Full sleeves on arm OUTER layers: Right Arm Outer (40,32) & Left Arm Outer (48,48)
        int armW = slim ? 14 : 16;
        int topW = slim ? 6 : 8;

        g.setColor(primary);
        g.fillRect(44, 32, topW, 4);
        g.fillRect(40, 36, armW, 10);
        g.fillRect(52, 48, topW, 4);
        g.fillRect(48, 52, armW, 10);

        // Cuffs & shoulder accent
        g.setColor(dark);
        g.fillRect(40, 44, armW, 2);
        g.fillRect(48, 60, armW, 2);
        g.setColor(accent);
        g.fillRect(40, 45, armW, 1);
        g.fillRect(48, 61, armW, 1);

        g.dispose();
        return img;
    }

    /**
     * Full-body protective yellow Hazmat Suit in the `jackets` layer (covers hood, torso, arms, rubber gloves,
     * legs & chemical boots in a single 64x64 overlay — pairs with `Blocks:` to lock pants/undershirt/etc.!).
     */
    private static BufferedImage makeHazmatSuit(boolean slim) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        Color yellow = new Color(234, 198, 34);
        Color yellowDark = new Color(186, 148, 20);
        Color yellowHi = new Color(252, 224, 72);
        Color rubber = new Color(34, 38, 44);
        Color rubberLight = new Color(64, 70, 80);
        Color visor = new Color(128, 228, 248, 115);

        int armW = slim ? 14 : 16;
        int topW = slim ? 6 : 8;

        // 1. Torso INNER (16,16..40,32) + OUTER (16,32..40,48)
        g.setColor(yellow);
        g.fillRect(20, 16, 16, 4);
        g.fillRect(16, 20, 24, 12);
        g.fillRect(20, 32, 16, 4);
        g.fillRect(16, 36, 24, 12);
        // Side shading & hazard black seam/zipper + waist seal
        g.setColor(yellowDark);
        g.fillRect(16, 36, 4, 12);
        g.fillRect(28, 36, 4, 12);
        g.setColor(rubber);
        g.fillRect(23, 36, 2, 11);  // sealed front zipper
        g.fillRect(16, 46, 24, 2);  // black waist tape seal
        g.fillRect(16, 30, 24, 2);
        // Black & yellow chest biohazard patch + back air pack
        g.setColor(rubber);
        g.fillRect(20, 38, 3, 3);
        g.setColor(yellowHi);
        g.fillRect(21, 39, 1, 1);
        g.setColor(rubberLight);
        g.fillRect(33, 38, 6, 6);
        g.setColor(rubber);
        g.fillRect(34, 39, 4, 4);

        // 2. Arms INNER & OUTER + heavy black chemical rubber gloves!
        g.setColor(yellow);
        // Right Arm inner (40,16) + outer (40,32)
        g.fillRect(44, 16, topW, 4);
        g.fillRect(40, 20, armW, 12);
        g.fillRect(44, 32, topW, 4);
        g.fillRect(40, 36, armW, 12);
        // Left Arm inner (32,48) + outer (48,48)
        g.fillRect(36, 48, topW, 4);
        g.fillRect(32, 52, armW, 12);
        g.fillRect(52, 48, topW, 4);
        g.fillRect(48, 52, armW, 12);
        // Black rubber seal tape & gloves on wrists/hands
        g.setColor(rubber);
        g.fillRect(40, 28, armW, 4);
        g.fillRect(40, 44, armW, 4);
        g.fillRect(32, 60, armW, 4);
        g.fillRect(48, 60, armW, 4);
        g.setColor(rubberLight);
        g.fillRect(40, 44, armW, 1);
        g.fillRect(48, 60, armW, 1);

        // 3. Legs INNER & OUTER + knee pads + heavy black chemical boots!
        g.setColor(yellow);
        // Right Leg inner (0,16) + outer (0,32)
        g.fillRect(4, 16, 8, 4);
        g.fillRect(0, 20, 16, 12);
        g.fillRect(4, 32, 8, 4);
        g.fillRect(0, 36, 16, 12);
        // Left Leg inner (16,48) + outer (0,48)
        g.fillRect(20, 48, 8, 4);
        g.fillRect(16, 52, 16, 12);
        g.fillRect(4, 48, 8, 4);
        g.fillRect(0, 52, 16, 12);
        // Knee reinforcement + black rubber boots
        g.setColor(yellowDark);
        g.fillRect(4, 40, 4, 2);
        g.fillRect(4, 56, 4, 2);
        g.setColor(rubber);
        g.fillRect(0, 28, 16, 4);
        g.fillRect(0, 44, 16, 4);
        g.fillRect(16, 60, 16, 4);
        g.fillRect(0, 60, 16, 4);
        g.setColor(rubberLight);
        g.fillRect(0, 44, 16, 1);
        g.fillRect(0, 60, 16, 1);

        // 4. Sealed Hazmat Hood on HEAD OUTER (32,0 -> 64,16) with clear tinted visor!
        g.setColor(yellow);
        g.fillRect(40, 0, 8, 8);   // hood top
        g.fillRect(48, 0, 8, 8);   // chin/neck bottom
        g.fillRect(32, 8, 8, 8);   // right side
        g.fillRect(48, 8, 16, 8);  // left side + back of hood
        // Front face frame (40..47, 8..15): yellow outer edge + black gasket + clear visor
        g.fillRect(40, 8, 8, 8);
        g.setColor(rubber);
        g.fillRect(41, 9, 6, 6);
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(42, 10, 4, 4);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(visor);
        g.fillRect(42, 10, 4, 4);

        g.dispose();
        return img;
    }

    private static BufferedImage makePants(Color primary, Color dark, Color belt) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Waistband on torso outer bottom (16,46 -> 40,48) & inner bottom (16,30 -> 40,32)
        g.setColor(belt);
        g.fillRect(16, 30, 24, 2);
        g.setColor(lighten(belt, 35));
        g.fillRect(23, 30, 2, 2); // belt buckle

        // Right Leg inner (0,16) + outer (0,32) & Left Leg inner (16,48) + outer (0,48)
        drawLegPants(g, 0, 16, primary, dark);
        drawLegPants(g, 16, 48, primary, dark);
        // Subtle cargo pocket / knee pad detail on outer leg layers
        g.setColor(dark);
        g.fillRect(0, 39, 4, 3);  // right side pocket outer
        g.fillRect(8, 55, 4, 3);  // left side pocket outer
        g.fillRect(4, 41, 4, 2);  // right knee pad
        g.fillRect(4, 57, 4, 2);  // left knee pad

        g.dispose();
        return img;
    }

    private static void drawLegPants(Graphics2D g, int ox, int oy, Color primary, Color dark) {
        g.setColor(primary);
        g.fillRect(ox + 4, oy, 4, 4);      // top
        g.fillRect(ox, oy + 4, 16, 10);    // down to ankle (leaves bottom 2px for socks/boots!)
        g.setColor(dark);
        g.fillRect(ox, oy + 4, 4, 10);
        g.fillRect(ox, oy + 13, 16, 1);    // hem
    }

    private static BufferedImage makeSocks(Color main, Color stripe) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Lower 4px of both legs on INNER layer
        // Right leg (0,20..32 -> y=28..31) & sole (8,16,4,4)
        g.setColor(main);
        g.fillRect(0, 28, 16, 4);
        g.fillRect(8, 16, 4, 4);
        g.fillRect(16, 60, 16, 4);
        g.fillRect(24, 48, 4, 4);

        g.setColor(stripe);
        g.fillRect(0, 29, 16, 1);
        g.fillRect(16, 61, 16, 1);
        g.dispose();
        return img;
    }

    private static BufferedImage makeBoots(Color upper, Color sole, Color laces) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Draw boots on OUTER leg layer bottom (y+12..y+15) so they layer cleanly over socks & jeans!
        // Right leg outer (0,32 -> y=44..47) & Left leg outer (0,48 -> y=60..63)
        drawBootOuter(g, 0, 32, upper, sole, laces);
        drawBootOuter(g, 0, 48, upper, sole, laces);
        g.dispose();
        return img;
    }

    private static void drawBootOuter(Graphics2D g, int ox, int oy, Color upper, Color sole, Color laces) {
        g.setColor(upper);
        g.fillRect(ox, oy + 11, 16, 4);
        g.setColor(sole);
        g.fillRect(ox, oy + 15, 16, 1);
        g.fillRect(ox + 8, oy, 4, 4); // bottom sole
        g.setColor(laces);
        g.fillRect(ox + 5, oy + 12, 2, 1);
        g.fillRect(ox + 5, oy + 14, 2, 1);
    }

    private static BufferedImage makeMask(Color main, Color accent, MaskStyle style) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color dark = darken(main, 16);

        // Head OUTER layer: front=(40,8,8,8), right=(32,8,8,8), left=(48,8,8,8), back=(56,8,8,8), top=(40,0,8,8)
        switch (style) {
            case BANDANA -> {
                g.setColor(main);
                g.fillRect(40, 12, 8, 4); // lower face
                g.fillRect(32, 13, 8, 3);
                g.fillRect(48, 13, 8, 3);
                g.fillRect(56, 13, 8, 2);
                g.setColor(accent);
                g.fillRect(42, 13, 4, 1);
                g.fillRect(43, 14, 2, 1);
            }
            case BALACLAVA -> {
                g.setColor(main);
                g.fillRect(40, 0, 8, 8);  // top
                g.fillRect(32, 8, 32, 8); // full wrap
                g.setColor(dark);
                g.fillRect(40, 14, 8, 2);
                // Eye opening cutout on front (41..46, y=11..12)
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(41, 11, 6, 2);
                g.setComposite(AlphaComposite.SrcOver);
                g.setColor(accent);
                g.fillRect(43, 11, 2, 2); // nose bridge
            }
            case RESPIRATOR -> {
                g.setColor(dark);
                g.fillRect(32, 13, 32, 2); // strap around head
                g.setColor(main);
                g.fillRect(41, 12, 6, 4);  // central mask body
                g.setColor(accent);
                g.fillRect(40, 13, 2, 2);  // right filter canister
                g.fillRect(46, 13, 2, 2);  // left filter canister
                g.setColor(lighten(main, 25));
                g.fillRect(43, 13, 2, 2);  // valve
            }
        }
        g.dispose();
        return img;
    }

    private static BufferedImage makeEyebrows(Color main, boolean arched) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color soft = new Color(main.getRed(), main.getGreen(), main.getBlue(), 195);

        // Inner head front face is (8..15, 8..15); eyes sit at y=11, eyebrows sit at y=10 (and y=9 for arch)
        if (!arched) {
            g.setColor(main);
            g.fillRect(9, 10, 2, 1);
            g.fillRect(13, 10, 2, 1);
            g.setColor(soft);
            g.fillRect(8, 10, 1, 1);
            g.fillRect(15, 10, 1, 1);
        } else {
            g.setColor(main);
            g.fillRect(10, 10, 1, 1);
            g.fillRect(13, 10, 1, 1);
            g.fillRect(9, 9, 1, 1);
            g.fillRect(14, 9, 1, 1);
            g.setColor(soft);
            g.fillRect(8, 10, 1, 1);
            g.fillRect(15, 10, 1, 1);
        }
        g.dispose();
        return img;
    }

    private static BufferedImage makeBeard(Color main, boolean goateeOnly) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color dark = darken(main, 18);
        Color soft = new Color(main.getRed(), main.getGreen(), main.getBlue(), 170);

        if (!goateeOnly) {
            // Full beard on lower inner face + chin + outer volume
            g.setColor(main);
            g.fillRect(8, 13, 8, 3);
            g.fillRect(6, 13, 2, 3);  // right cheek sideburn
            g.fillRect(16, 13, 2, 3); // left cheek sideburn
            g.fillRect(16, 0, 8, 8);  // under-chin bottom face
            // Mouth gap
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(11, 13, 2, 1);
            g.setComposite(AlphaComposite.SrcOver);
            // Outer 2nd-layer 3D chin volume
            g.setColor(dark);
            g.fillRect(40, 14, 8, 2);
            g.fillRect(38, 14, 2, 2);
            g.fillRect(48, 14, 2, 2);
            g.setColor(main);
            g.fillRect(41, 13, 6, 1);
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(43, 13, 2, 1);
            g.setComposite(AlphaComposite.SrcOver);
        } else {
            // Stylized goatee + moustache + subtle jaw stubble
            g.setColor(soft);
            g.fillRect(9, 14, 6, 2);
            g.setColor(main);
            g.fillRect(10, 13, 4, 1); // moustache
            g.fillRect(10, 14, 1, 2);
            g.fillRect(13, 14, 1, 2);
            g.fillRect(11, 15, 2, 1); // chin tuft
            g.setColor(dark);
            g.fillRect(42, 15, 4, 1); // outer chin highlight
        }
        g.dispose();
        return img;
    }

    private static BufferedImage makeScar(boolean warpaint) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        if (!warpaint) {
            // Vertical battle scar across right eye (x=10, y=9..13)
            g.setColor(new Color(148, 42, 46, 235));
            g.fillRect(10, 9, 1, 1);
            g.fillRect(10, 10, 1, 1);
            g.fillRect(10, 12, 1, 2);
            g.setColor(new Color(225, 168, 162, 155));
            g.fillRect(9, 10, 1, 1);
            g.fillRect(11, 12, 1, 1);
            // Subtle cheek nick
            g.setColor(new Color(156, 52, 56, 210));
            g.fillRect(14, 13, 1, 1);
        } else {
            // Tribal blue warpaint stripes on cheeks + forehead mark
            Color blue = new Color(36, 128, 224, 230);
            Color cyan = new Color(96, 196, 248, 210);
            g.setColor(blue);
            g.fillRect(8, 12, 3, 1);
            g.fillRect(13, 12, 3, 1);
            g.fillRect(11, 9, 2, 1);
            g.setColor(cyan);
            g.fillRect(9, 13, 2, 1);
            g.fillRect(13, 13, 2, 1);
            g.fillRect(11, 8, 2, 1);
        }
        g.dispose();
        return img;
    }

    private static void generateGloveVariants(File overlaysDir, String name, Color main, Color accent, boolean fingerless) throws IOException {
        save(overlaysDir, "gloves/wide/" + name + ".png", makeGloves(false, main, accent, fingerless));
        save(overlaysDir, "gloves/slim/" + name + ".png", makeGloves(true, main, accent, fingerless));
    }

    private static BufferedImage makeGloves(boolean slim, Color main, Color accent, boolean fingerless) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color dark = darken(main, 16);

        int armW = slim ? 3 : 4;
        int stripW = slim ? 14 : 16;
        int bottomUOffset = 4 + armW; // bottom cap UV x = u + 4 + armW

        // Right arm inner (40,16) + outer (40,32); Left arm inner (32,48) + outer (48,48)
        drawSingleGlove(g, 40, 16, 40, 32, stripW, armW, bottomUOffset, main, dark, accent, fingerless);
        drawSingleGlove(g, 32, 48, 48, 48, stripW, armW, bottomUOffset, main, dark, accent, fingerless);

        g.dispose();
        return img;
    }

    private static void drawSingleGlove(
            Graphics2D g,
            int inX, int inY,
            int outX, int outY,
            int stripW, int armW, int bottomUOffset,
            Color main, Color dark, Color accent,
            boolean fingerless
    ) {
        int height = fingerless ? 3 : 4;
        int startY = fingerless ? 12 : 12; // y+12..14 (fingerless leaves y+15 open) or y+12..15 (full)

        // Inner wrist/hand wrap
        g.setColor(main);
        g.fillRect(inX, inY + startY, stripW, height);
        if (!fingerless) {
            g.fillRect(inX + bottomUOffset, inY, armW, 4); // hand bottom cap
        }

        // Outer cuff & knuckle reinforcement
        g.setColor(dark);
        g.fillRect(outX, outY + 12, stripW, fingerless ? 2 : 3);
        g.setColor(accent);
        g.fillRect(outX + 4, outY + 13, armW, 1); // knuckle strap
        g.fillRect(outX, outY + 12, stripW, 1);   // cuff trim
    }

    private static BufferedImage makeHat(boolean ushanka) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Head OUTER layer: top=(40,0,8,8), right=(32,8,8,8), front=(40,8,8,8), left=(48,8,8,8), back=(56,8,8,8)
        if (!ushanka) {
            // Knit grey beanie with ribbed cuff
            Color knit = new Color(68, 74, 86);
            Color knitDark = new Color(48, 52, 62);
            Color rib = new Color(92, 100, 114);

            g.setColor(knit);
            g.fillRect(40, 0, 8, 8);   // top
            g.fillRect(32, 8, 32, 3);  // upper crown all around
            g.setColor(knitDark);
            g.fillRect(32, 10, 32, 1); // folded brim line
            // Ribbed vertical texture on brim
            g.setColor(rib);
            for (int x = 32; x < 64; x += 2) {
                g.fillRect(x, 9, 1, 2);
            }
            // Subtle pom/stitch on top
            g.setColor(rib);
            g.fillRect(43, 3, 2, 2);
        } else {
            // Warm brown fur ushanka with fluffy front flap & side ear flaps
            Color leather = new Color(102, 64, 38);
            Color leatherDark = new Color(74, 44, 24);
            Color fur = new Color(214, 206, 194);
            Color furShadow = new Color(176, 166, 152);

            g.setColor(leather);
            g.fillRect(40, 0, 8, 8);   // leather crown top
            g.fillRect(32, 8, 32, 3);  // upper sides/back
            g.setColor(leatherDark);
            g.fillRect(40, 0, 8, 1);
            g.fillRect(40, 7, 8, 1);

            // Fluffy fur front visor
            g.setColor(fur);
            g.fillRect(40, 8, 8, 3);
            g.setColor(furShadow);
            g.fillRect(40, 10, 8, 1);
            g.fillRect(42, 8, 1, 2);
            g.fillRect(45, 8, 1, 2);

            // Ear flaps hanging down right (32..39) & left (48..55) sides + back fur rim
            g.setColor(fur);
            g.fillRect(34, 11, 4, 4); // right ear flap
            g.fillRect(50, 11, 4, 4); // left ear flap
            g.fillRect(56, 10, 8, 2); // back fur trim
            g.setColor(leather);
            g.fillRect(35, 11, 2, 3);
            g.fillRect(51, 11, 2, 3);
        }
        g.dispose();
        return img;
    }

    private enum BeltStyle {
        LEATHER, TACTICAL, GOLD_CHAIN, HOLSTER, CRIMSON_SASH
    }

    /**
     * Generates crisp 64x64 2D belt & decorative waist accessories on BODY_OUTER + LEG_OUTER UV regions:
     *   BODY_OUTER:      right(16..19), front(20..27), left(28..31), back(32..39), y=45..47 (waist)
     *   RIGHT_LEG_OUTER: right(0..3), front(4..7), left(8..11), back(12..15), y=36..47
     *   LEFT_LEG_OUTER:  right(0..3), front(4..7), left(8..11), back(12..15), y=52..63
     */
    private static BufferedImage makeBelt(BeltStyle style) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        switch (style) {
            case LEATHER -> {
                Color leather = new Color(96, 58, 34);
                Color leatherDark = new Color(68, 40, 22);
                Color brass = new Color(224, 184, 72);
                Color brassHi = new Color(248, 222, 128);
                // Main strap around waist (2px tall on body outer + 1px on top of legs)
                g.setColor(leather);
                g.fillRect(16, 46, 24, 2);
                g.setColor(leatherDark);
                g.fillRect(16, 47, 24, 1);
                // Stitching rivets on sides & back
                g.setColor(brass);
                g.fillRect(18, 46, 1, 1);
                g.fillRect(29, 46, 1, 1);
                g.fillRect(35, 46, 2, 1);
                // Center brass buckle on front (x=23..24)
                g.setColor(brass);
                g.fillRect(22, 45, 4, 3);
                g.setColor(leatherDark);
                g.fillRect(23, 46, 2, 1);
                g.setColor(brassHi);
                g.fillRect(22, 45, 4, 1);
            }
            case TACTICAL -> {
                Color webbing = new Color(48, 56, 46);
                Color pouch = new Color(68, 78, 60);
                Color pouchDark = new Color(36, 42, 34);
                Color buckle = new Color(118, 126, 138);
                Color led = new Color(46, 224, 132);
                // Heavy tactical belt + MOLLE pouches
                g.setColor(webbing);
                g.fillRect(16, 45, 24, 3);
                g.setColor(pouchDark);
                g.fillRect(16, 47, 24, 1);
                // Metal quick-release cobra buckle
                g.setColor(buckle);
                g.fillRect(22, 45, 4, 3);
                g.setColor(pouchDark);
                g.fillRect(23, 46, 2, 1);
                // Side & back 2D pouches overhanging top of legs
                g.setColor(pouch);
                g.fillRect(16, 44, 4, 4); // right hip pouch
                g.fillRect(28, 44, 4, 4); // left hip pouch
                g.fillRect(33, 45, 6, 3); // back ammo pack
                g.fillRect(0, 36, 4, 3);  // right leg pouch drop
                g.fillRect(8, 52, 4, 3);  // left leg pouch drop
                g.setColor(pouchDark);
                g.fillRect(16, 46, 4, 1);
                g.fillRect(28, 46, 4, 1);
                g.fillRect(0, 38, 4, 1);
                g.fillRect(8, 54, 4, 1);
                g.setColor(led);
                g.fillRect(17, 45, 1, 1);
            }
            case GOLD_CHAIN -> {
                Color darkVelvet = new Color(42, 32, 48);
                Color gold = new Color(238, 194, 58);
                Color goldDark = new Color(176, 128, 28);
                Color gem = new Color(224, 52, 74);
                // Velvet band
                g.setColor(darkVelvet);
                g.fillRect(16, 46, 24, 2);
                // Gold chain links alternating around waist
                for (int x = 16; x < 40; x += 2) {
                    g.setColor(gold);
                    g.fillRect(x, 46, 1, 1);
                    g.setColor(goldDark);
                    g.fillRect(x + 1, 47, 1, 1);
                }
                // Ruby centerpiece medallion
                g.setColor(gold);
                g.fillRect(22, 45, 4, 3);
                g.setColor(gem);
                g.fillRect(23, 46, 2, 1);
                // Decorative swooping gold chain hanging on right & left thighs!
                g.setColor(gold);
                g.fillRect(4, 36, 1, 1);
                g.fillRect(5, 37, 2, 1);
                g.fillRect(7, 36, 1, 1);
                g.fillRect(4, 52, 1, 1);
                g.fillRect(5, 53, 2, 1);
                g.fillRect(7, 52, 1, 1);
                g.setColor(goldDark);
                g.fillRect(5, 38, 2, 1);
                g.fillRect(5, 54, 2, 1);
            }
            case HOLSTER -> {
                Color leather = new Color(112, 68, 42);
                Color leatherDark = new Color(74, 44, 26);
                Color silver = new Color(196, 206, 218);
                Color flask = new Color(76, 138, 168);
                // Main gunbelt
                g.setColor(leather);
                g.fillRect(16, 46, 24, 2);
                g.setColor(silver);
                g.fillRect(23, 46, 2, 2);
                // Thigh strap + holster on right leg outer (x=0..15, y=36..42)
                g.setColor(leatherDark);
                g.fillRect(0, 39, 16, 1); // thigh strap all around right leg
                g.setColor(leather);
                g.fillRect(0, 36, 4, 6);  // side holster body
                g.setColor(leatherDark);
                g.fillRect(1, 36, 2, 1);
                g.setColor(silver);
                g.fillRect(1, 37, 2, 2);  // pistol grip / latch
                // Canteen / flask on left hip (LEFT_LEG_OUTER left side x=8..11)
                g.setColor(flask);
                g.fillRect(9, 52, 2, 3);
                g.setColor(silver);
                g.fillRect(9, 52, 2, 1);
            }
            case CRIMSON_SASH -> {
                Color crimson = new Color(186, 36, 48);
                Color crimsonDark = new Color(128, 22, 32);
                Color goldTrim = new Color(238, 198, 76);
                // Wide wrapped martial sash
                g.setColor(crimson);
                g.fillRect(16, 45, 24, 3);
                g.setColor(crimsonDark);
                g.fillRect(16, 47, 24, 1);
                g.setColor(goldTrim);
                g.fillRect(16, 45, 24, 1);
                // Sash knot on front-left + hanging fabric tails down left thigh!
                g.setColor(crimson);
                g.fillRect(25, 45, 3, 3);
                g.fillRect(5, 52, 3, 6);
                g.setColor(crimsonDark);
                g.fillRect(6, 52, 1, 6);
                g.setColor(goldTrim);
                g.fillRect(5, 57, 3, 1);
            }
        }
        g.dispose();
        return img;
    }

    private static void save(File overlaysDir, String relativePath, BufferedImage img) throws IOException {
        File out = new File(overlaysDir, relativePath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        ImageIO.write(img, "png", out);
    }

    private static Color darken(Color c, int delta) {
        return new Color(
                Math.max(0, c.getRed() - delta),
                Math.max(0, c.getGreen() - delta),
                Math.max(0, c.getBlue() - delta),
                c.getAlpha()
        );
    }

    private static Color lighten(Color c, int delta) {
        return new Color(
                Math.min(255, c.getRed() + delta),
                Math.min(255, c.getGreen() + delta),
                Math.min(255, c.getBlue() + delta),
                c.getAlpha()
        );
    }
}
