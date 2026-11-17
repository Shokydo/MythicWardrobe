package dev.mythicwardrobe.storage;

import dev.mythicwardrobe.model.MenuMode;
import dev.mythicwardrobe.model.SignedSkinData;
import dev.mythicwardrobe.model.SkinModelVariant;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite persistence for player wardrobe slots, skill-applied layers, base skins, and MineSkin SHA-256 cache.
 */
public final class WardrobeDatabase implements AutoCloseable {

    private final File dbFile;
    private final Logger logger;
    private Connection connection;
    private final Object lock = new Object();

    public WardrobeDatabase(File dbFile, Logger logger) {
        this.dbFile = dbFile;
        this.logger = logger;
    }

    public void init() throws SQLException {
        synchronized (lock) {
            File parent = dbFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
                // JDBC 4 driver auto-registration fallback
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA synchronous=NORMAL;");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS player_slots (
                            uuid TEXT NOT NULL,
                            slot_id TEXT NOT NULL,
                            item_bytes BLOB NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY (uuid, slot_id)
                        );
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS player_skill_layers (
                            uuid TEXT NOT NULL,
                            layer_type TEXT NOT NULL,
                            texture_path TEXT NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY (uuid, layer_type)
                        );
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS player_base_skins (
                            uuid TEXT PRIMARY KEY,
                            value TEXT NOT NULL,
                            signature TEXT NOT NULL,
                            texture_url TEXT,
                            variant TEXT NOT NULL,
                            updated_at INTEGER NOT NULL
                        );
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS mineskin_cache (
                            skin_hash TEXT PRIMARY KEY,
                            variant TEXT NOT NULL,
                            value TEXT NOT NULL,
                            signature TEXT NOT NULL,
                            texture_url TEXT,
                            created_at INTEGER NOT NULL
                        );
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS player_body_types (
                            uuid TEXT PRIMARY KEY,
                            body_type TEXT NOT NULL,
                            updated_at INTEGER NOT NULL
                        );
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS player_menu_modes (
                            uuid TEXT PRIMARY KEY,
                            menu_mode TEXT NOT NULL,
                            updated_at INTEGER NOT NULL
                        );
                        """);
            }
        }
    }

    public Map<String, byte[]> loadRawSlotItems(UUID uuid) {
        Map<String, byte[]> result = new HashMap<>();
        synchronized (lock) {
            if (connection == null) return result;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT slot_id, item_bytes FROM player_slots WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("slot_id"), rs.getBytes("item_bytes"));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to load wardrobe slots for " + uuid, e);
            }
        }
        return result;
    }

    public Map<String, ItemStack> loadSlotItems(UUID uuid) {
        Map<String, byte[]> raw = loadRawSlotItems(uuid);
        Map<String, ItemStack> out = new HashMap<>();
        for (Map.Entry<String, byte[]> e : raw.entrySet()) {
            try {
                ItemStack stack = ItemStack.deserializeBytes(e.getValue());
                if (stack != null && !stack.getType().isAir()) {
                    out.put(e.getKey(), stack);
                }
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Failed to deserialize item in slot " + e.getKey() + " for " + uuid, ex);
            }
        }
        return out;
    }

    public void saveRawSlotItem(UUID uuid, String slotId, byte[] itemBytes) {
        synchronized (lock) {
            if (connection == null) return;
            try {
                if (itemBytes == null || itemBytes.length == 0) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM player_slots WHERE uuid = ? AND slot_id = ?")) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, slotId.toLowerCase());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO player_slots (uuid, slot_id, item_bytes, updated_at)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT(uuid, slot_id) DO UPDATE SET
                                item_bytes = excluded.item_bytes,
                                updated_at = excluded.updated_at
                            """)) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, slotId.toLowerCase());
                        ps.setBytes(3, itemBytes);
                        ps.setLong(4, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to save slot " + slotId + " for " + uuid, e);
            }
        }
    }

    public void saveSlotItem(UUID uuid, String slotId, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            saveRawSlotItem(uuid, slotId, null);
        } else {
            saveRawSlotItem(uuid, slotId, item.serializeAsBytes());
        }
    }

    public void saveAllSlotItems(UUID uuid, Map<String, ItemStack> items) {
        synchronized (lock) {
            if (connection == null) return;
            try {
                boolean prevAuto = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement del = connection.prepareStatement("DELETE FROM player_slots WHERE uuid = ?")) {
                        del.setString(1, uuid.toString());
                        del.executeUpdate();
                    }
                    try (PreparedStatement ins = connection.prepareStatement(
                            "INSERT INTO player_slots (uuid, slot_id, item_bytes, updated_at) VALUES (?, ?, ?, ?)")) {
                        long now = System.currentTimeMillis();
                        for (Map.Entry<String, ItemStack> entry : items.entrySet()) {
                            ItemStack stack = entry.getValue();
                            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
                            ins.setString(1, uuid.toString());
                            ins.setString(2, entry.getKey().toLowerCase());
                            ins.setBytes(3, stack.serializeAsBytes());
                            ins.setLong(4, now);
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                    connection.commit();
                } catch (SQLException ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(prevAuto);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to batch save wardrobe items for " + uuid, e);
            }
        }
    }

    public Map<String, String> loadSkillLayers(UUID uuid) {
        Map<String, String> result = new HashMap<>();
        synchronized (lock) {
            if (connection == null) return result;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT layer_type, texture_path FROM player_skill_layers WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("layer_type"), rs.getString("texture_path"));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to load skill layers for " + uuid, e);
            }
        }
        return result;
    }

    public void saveSkillLayers(UUID uuid, Map<String, String> layers) {
        synchronized (lock) {
            if (connection == null) return;
            try {
                boolean prevAuto = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement del = connection.prepareStatement("DELETE FROM player_skill_layers WHERE uuid = ?")) {
                        del.setString(1, uuid.toString());
                        del.executeUpdate();
                    }
                    try (PreparedStatement ins = connection.prepareStatement(
                            "INSERT INTO player_skill_layers (uuid, layer_type, texture_path, updated_at) VALUES (?, ?, ?, ?)")) {
                        long now = System.currentTimeMillis();
                        for (Map.Entry<String, String> entry : layers.entrySet()) {
                            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
                            ins.setString(1, uuid.toString());
                            ins.setString(2, entry.getKey().toLowerCase());
                            ins.setString(3, entry.getValue());
                            ins.setLong(4, now);
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                    connection.commit();
                } catch (SQLException ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(prevAuto);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to save skill layers for " + uuid, e);
            }
        }
    }

    public Optional<SignedSkinData> loadBaseSkin(UUID uuid) {
        synchronized (lock) {
            if (connection == null) return Optional.empty();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT value, signature, texture_url, variant FROM player_base_skins WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new SignedSkinData(
                                rs.getString("value"),
                                rs.getString("signature"),
                                rs.getString("texture_url"),
                                SkinModelVariant.fromString(rs.getString("variant"), SkinModelVariant.CLASSIC)
                        ));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to load base skin for " + uuid, e);
            }
        }
        return Optional.empty();
    }

    public void saveBaseSkin(UUID uuid, SignedSkinData skin) {
        if (skin == null || !skin.isValid()) return;
        synchronized (lock) {
            if (connection == null) return;
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO player_base_skins (uuid, value, signature, texture_url, variant, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        value = excluded.value,
                        signature = excluded.signature,
                        texture_url = excluded.texture_url,
                        variant = excluded.variant,
                        updated_at = excluded.updated_at
                    """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, skin.value());
                ps.setString(3, skin.signature());
                ps.setString(4, skin.textureUrl());
                ps.setString(5, skin.variant().name());
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to save base skin for " + uuid, e);
            }
        }
    }

    public Optional<SignedSkinData> getCachedSkin(String skinHash) {
        synchronized (lock) {
            if (connection == null) return Optional.empty();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT value, signature, texture_url, variant FROM mineskin_cache WHERE skin_hash = ?")) {
                ps.setString(1, skinHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new SignedSkinData(
                                rs.getString("value"),
                                rs.getString("signature"),
                                rs.getString("texture_url"),
                                SkinModelVariant.fromString(rs.getString("variant"), SkinModelVariant.CLASSIC)
                        ));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to query mineskin cache for " + skinHash, e);
            }
        }
        return Optional.empty();
    }

    public void putCachedSkin(String skinHash, SignedSkinData skin) {
        if (skinHash == null || skin == null || !skin.isValid()) return;
        synchronized (lock) {
            if (connection == null) return;
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO mineskin_cache (skin_hash, variant, value, signature, texture_url, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(skin_hash) DO UPDATE SET
                        variant = excluded.variant,
                        value = excluded.value,
                        signature = excluded.signature,
                        texture_url = excluded.texture_url,
                        created_at = excluded.created_at
                    """)) {
                ps.setString(1, skinHash);
                ps.setString(2, skin.variant().name());
                ps.setString(3, skin.value());
                ps.setString(4, skin.signature());
                ps.setString(5, skin.textureUrl());
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to write mineskin cache for " + skinHash, e);
            }
        }
    }

    public Optional<SkinModelVariant> loadBodyType(UUID uuid) {
        synchronized (lock) {
            if (connection == null) return Optional.empty();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT body_type FROM player_body_types WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.ofNullable(SkinModelVariant.parseOrNull(rs.getString("body_type")));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to load body type for " + uuid, e);
            }
        }
        return Optional.empty();
    }

    public void saveBodyType(UUID uuid, SkinModelVariant variant) {
        synchronized (lock) {
            if (connection == null) return;
            try {
                if (variant == null) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM player_body_types WHERE uuid = ?")) {
                        ps.setString(1, uuid.toString());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO player_body_types (uuid, body_type, updated_at)
                            VALUES (?, ?, ?)
                            ON CONFLICT(uuid) DO UPDATE SET
                                body_type = excluded.body_type,
                                updated_at = excluded.updated_at
                            """)) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, variant.name());
                        ps.setLong(3, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to save body type for " + uuid, e);
            }
        }
    }

    public Optional<MenuMode> loadMenuMode(UUID uuid) {
        synchronized (lock) {
            if (connection == null) return Optional.empty();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT menu_mode FROM player_menu_modes WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.ofNullable(MenuMode.parseOrNull(rs.getString("menu_mode")));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to load menu mode for " + uuid, e);
            }
        }
        return Optional.empty();
    }

    public void saveMenuMode(UUID uuid, MenuMode mode) {
        synchronized (lock) {
            if (connection == null) return;
            try {
                if (mode == null) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM player_menu_modes WHERE uuid = ?")) {
                        ps.setString(1, uuid.toString());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO player_menu_modes (uuid, menu_mode, updated_at)
                            VALUES (?, ?, ?)
                            ON CONFLICT(uuid) DO UPDATE SET
                                menu_mode = excluded.menu_mode,
                                updated_at = excluded.updated_at
                            """)) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, mode.name());
                        ps.setLong(3, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to save menu mode for " + uuid, e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
                connection = null;
            }
        }
    }
}
