package dev.mythicwardrobe.skin;

import dev.mythicwardrobe.model.SkinLayerDef;
import dev.mythicwardrobe.model.SkinModelVariant;
import dev.mythicwardrobe.model.WardrobeSlotDef;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Loads, indexes and caches 64x64 transparent PNG skin overlays from plugins/MythicWardrobe/overlays/.
 * Supports dynamic folders from layers.yml and automatic wide/classic vs slim arm variant resolution.
 */
public final class SkinLayerRegistry {

    public record LoadedOverlay(String relativeKey, File file, BufferedImage image) {
    }

    private final File overlaysDir;
    private final File baseSkinsDir;
    private final Logger logger;

    // normalized relative path (e.g. "body_skin/slim/demo_light.png") -> LoadedOverlay
    private final Map<String, LoadedOverlay> byRelativePath = new ConcurrentHashMap<>();
    // bare lowercase filename (e.g. "demo_jacket.png") -> first matching LoadedOverlay
    private final Map<String, LoadedOverlay> byFileName = new ConcurrentHashMap<>();

    public SkinLayerRegistry(File dataFolder, Logger logger) {
        this.overlaysDir = new File(dataFolder, "overlays");
        this.baseSkinsDir = new File(dataFolder, "base_skins");
        this.logger = logger;
    }

    public void ensureDirectories(Collection<SkinLayerDef> layers, Collection<WardrobeSlotDef> slots, boolean createExamples) {
        overlaysDir.mkdirs();
        baseSkinsDir.mkdirs();

        Set<String> folders = new LinkedHashSet<>();
        if (layers != null) {
            for (SkinLayerDef layer : layers) {
                if (layer.folder() != null && !layer.folder().isBlank()) {
                    folders.add(layer.folder());
                }
            }
        }
        if (slots != null) {
            for (WardrobeSlotDef def : slots) {
                if (def.isSkinLayer() && def.folder() != null && !def.folder().isBlank()) {
                    folders.add(def.folder());
                }
            }
        }
        if (folders.isEmpty()) {
            folders.addAll(List.of("body_skin", "eyes", "hairs", "masks", "undershirts", "jackets", "pants", "socks", "boots"));
        }

        for (String folder : folders) {
            File dir = new File(overlaysDir, folder);
            dir.mkdirs();
        }

        // Always create wide/ & slim/ subfolders for body_skin (and any replaces-base-skin layer)
        if (layers != null) {
            for (SkinLayerDef layer : layers) {
                if (layer.replacesBaseSkin() && layer.folder() != null && !layer.folder().isBlank()) {
                    new File(overlaysDir, layer.folder() + "/wide").mkdirs();
                    new File(overlaysDir, layer.folder() + "/slim").mkdirs();
                }
            }
        }

        ensureDefaultBaseSkin();
        if (createExamples) {
            SampleOverlayPackGenerator.generateMissingCategories(overlaysDir, logger, false);
        }
    }

    private void ensureDefaultBaseSkin() {
        File classicFile = new File(baseSkinsDir, "default_classic.png");
        if (!classicFile.exists()) {
            try {
                ImageIO.write(generateNeutralMannequin(), "png", classicFile);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not generate default_classic.png", e);
            }
        }
    }

    public static BufferedImage generateCleanBodySkin(boolean slim, Color skinColor) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color shaded = new Color(
                Math.max(0, skinColor.getRed() - 16),
                Math.max(0, skinColor.getGreen() - 14),
                Math.max(0, skinColor.getBlue() - 12),
                255
        );
        Color underwear = new Color(62, 68, 78, 255);

        // Head base (0,0 -> 32,16)
        g.setColor(skinColor);
        g.fillRect(8, 0, 16, 8);
        g.fillRect(0, 8, 32, 8);

        // Body base (16,16 -> 40,32)
        g.setColor(skinColor);
        g.fillRect(20, 16, 16, 4);
        g.fillRect(16, 20, 24, 12);
        // Subtle shading on torso edges
        g.setColor(shaded);
        g.fillRect(16, 20, 4, 12);
        g.fillRect(28, 20, 4, 12);
        // Modest neutral base shorts/waistband
        g.setColor(underwear);
        g.fillRect(16, 29, 24, 3);

        // Arms: Classic = 4px wide (width 16 total), Slim = 3px wide (width 14 total)
        int armWidth = slim ? 14 : 16;
        int armTopWidth = slim ? 6 : 8;
        g.setColor(skinColor);
        // Right arm (40,16)
        g.fillRect(44, 16, armTopWidth, 4);
        g.fillRect(40, 20, armWidth, 12);
        // Left arm (32,48)
        g.fillRect(36, 48, armTopWidth, 4);
        g.fillRect(32, 52, armWidth, 12);

        // Legs: Right leg (0,16 -> 16,32) & Left leg (16,48 -> 32,64)
        g.setColor(skinColor);
        g.fillRect(4, 16, 8, 4);
        g.fillRect(0, 20, 16, 12);
        g.fillRect(20, 48, 8, 4);
        g.fillRect(16, 52, 16, 12);
        g.setColor(underwear);
        g.fillRect(0, 20, 16, 3);
        g.fillRect(16, 52, 16, 3);

        g.dispose();
        return img;
    }

    public static BufferedImage generateNeutralMannequin() {
        BufferedImage img = generateCleanBodySkin(false, new Color(225, 190, 160, 255));
        Graphics2D g = img.createGraphics();
        // Hair & eyes
        g.setColor(new Color(72, 54, 42, 255));
        g.fillRect(8, 0, 8, 8);
        g.fillRect(0, 8, 32, 2);
        g.setColor(new Color(40, 40, 45, 255));
        g.fillRect(9, 11, 2, 2);
        g.fillRect(13, 11, 2, 2);
        // Basic neutral shirt & pants
        g.setColor(new Color(185, 190, 198, 255));
        g.fillRect(20, 16, 16, 4);
        g.fillRect(16, 20, 24, 12);
        g.setColor(new Color(75, 85, 105, 255));
        g.fillRect(4, 16, 8, 4);
        g.fillRect(0, 20, 16, 12);
        g.fillRect(20, 48, 8, 4);
        g.fillRect(16, 52, 16, 12);
        g.dispose();
        return img;
    }

    public int reload() {
        byRelativePath.clear();
        byFileName.clear();
        if (!overlaysDir.exists()) return 0;

        Path root = overlaysDir.toPath();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .forEach(p -> {
                        File f = p.toFile();
                        try {
                            BufferedImage img = ImageIO.read(f);
                            if (img == null) {
                                logger.warning("Skipping unreadable PNG overlay: " + f.getName());
                                return;
                            }
                            if (img.getWidth() != 64 || (img.getHeight() != 64 && img.getHeight() != 32)) {
                                logger.warning("Skipping invalid skin overlay dimensions (" + img.getWidth() + "x" + img.getHeight()
                                        + ") for " + root.relativize(p) + " — expected 64x64.");
                                return;
                            }
                            if (img.getHeight() == 32) {
                                img = SkinCompositor.upgradeLegacy64x32(img);
                            }
                            String rel = normalize(root.relativize(p).toString());
                            LoadedOverlay loaded = new LoadedOverlay(rel, f, img);
                            byRelativePath.put(rel, loaded);
                            byFileName.putIfAbsent(f.getName().toLowerCase(Locale.ROOT), loaded);
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Failed to read overlay PNG: " + f.getAbsolutePath(), e);
                        }
                    });
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to scan overlays directory", e);
        }
        return byRelativePath.size();
    }

    /**
     * Resolves an overlay texture by relative path, layer folder, bare name, and wide/slim arm variant.
     */
    public Optional<LoadedOverlay> resolve(String folderHint, String textureRef, SkinModelVariant variant) {
        if (textureRef == null || textureRef.isBlank()) return Optional.empty();
        String cleaned = normalize(textureRef);
        if (!cleaned.endsWith(".png")) {
            cleaned = cleaned + ".png";
        }
        String baseNoExt = cleaned.substring(0, cleaned.length() - 4);
        String fh = (folderHint != null && !folderHint.isBlank()) ? normalize(folderHint) : null;

        List<String> candidates = new ArrayList<>();

        // 1. Variant-specific folders & suffixes first (slim/ vs wide/ or classic/)
        if (variant == SkinModelVariant.SLIM) {
            addVariantCandidates(candidates, fh, cleaned, baseNoExt, List.of("slim"));
        } else if (variant == SkinModelVariant.CLASSIC) {
            addVariantCandidates(candidates, fh, cleaned, baseNoExt, List.of("wide", "classic"));
        }

        // 2. Exact relative path & default folder path
        candidates.add(cleaned);
        if (fh != null && !cleaned.contains("/")) {
            candidates.add(fh + "/" + cleaned);
        }

        // 3. Opposite variant fallback if only one variant exists inside wide/ or slim/
        if (variant == SkinModelVariant.SLIM) {
            addVariantCandidates(candidates, fh, cleaned, baseNoExt, List.of("wide", "classic"));
        } else {
            addVariantCandidates(candidates, fh, cleaned, baseNoExt, List.of("slim"));
        }

        for (String c : candidates) {
            LoadedOverlay found = byRelativePath.get(c);
            if (found != null) return Optional.of(found);
        }

        // 4. Bare filename fallback anywhere in overlays/
        String bareName = cleaned.contains("/") ? cleaned.substring(cleaned.lastIndexOf('/') + 1) : cleaned;
        return Optional.ofNullable(byFileName.get(bareName));
    }

    private static void addVariantCandidates(
            List<String> candidates,
            String folderHint,
            String cleaned,
            String baseNoExt,
            List<String> variantDirs
    ) {
        int slash = cleaned.lastIndexOf('/');
        for (String v : variantDirs) {
            String suffixed = baseNoExt + "_" + v + ".png";
            if (folderHint != null && slash < 0) {
                candidates.add(folderHint + "/" + v + "/" + cleaned);
                candidates.add(folderHint + "/" + suffixed);
            }
            if (slash > 0) {
                candidates.add(cleaned.substring(0, slash) + "/" + v + "/" + cleaned.substring(slash + 1));
            }
            candidates.add(v + "/" + cleaned);
            candidates.add(suffixed);
        }
    }

    /**
     * Returns clean texture names strictly inside the given folder (collapsing wide/slim/classic subfolders).
     * Returns an empty list if the folder currently has no PNG overlays.
     */
    public List<String> getTexturesInFolder(String folder) {
        Set<String> result = new TreeSet<>();
        String prefix = (folder != null && !folder.isBlank()) ? normalize(folder) + "/" : "";
        if (prefix.isEmpty()) return List.of();
        for (String rel : byRelativePath.keySet()) {
            if (rel.startsWith(prefix)) {
                String inside = rel.substring(prefix.length());
                if (inside.startsWith("wide/")) {
                    result.add(inside.substring("wide/".length()));
                } else if (inside.startsWith("slim/")) {
                    result.add(inside.substring("slim/".length()));
                } else if (inside.startsWith("classic/")) {
                    result.add(inside.substring("classic/".length()));
                } else {
                    result.add(inside);
                }
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * Returns user-friendly texture suggestions for a specific layer folder (collapsing wide/slim duplicates into clean names).
     */
    public List<String> getSuggestionsForFolder(String folder) {
        List<String> inFolder = getTexturesInFolder(folder);
        if (!inFolder.isEmpty()) {
            return inFolder;
        }
        return new ArrayList<>(new TreeSet<>(byRelativePath.keySet()));
    }

    public Optional<BufferedImage> loadFallbackBaseSkin(String filename) {
        if (filename != null && !filename.isBlank()) {
            File f = new File(baseSkinsDir, filename);
            if (f.exists() && f.isFile()) {
                try {
                    BufferedImage img = ImageIO.read(f);
                    if (img != null && img.getWidth() == 64 && (img.getHeight() == 64 || img.getHeight() == 32)) {
                        return Optional.of(img.getHeight() == 32 ? SkinCompositor.upgradeLegacy64x32(img) : img);
                    }
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to read fallback base skin " + f.getName(), e);
                }
            }
        }
        return Optional.of(generateNeutralMannequin());
    }

    public Set<String> getAllOverlayKeys() {
        return Collections.unmodifiableSet(new TreeSet<>(byRelativePath.keySet()));
    }

    public int getOverlayCount() {
        return byRelativePath.size();
    }

    public File getOverlaysDir() {
        return overlaysDir;
    }

    public File getBaseSkinsDir() {
        return baseSkinsDir;
    }

    private static String normalize(String path) {
        String s = path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }
}
