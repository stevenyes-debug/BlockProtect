package com.blockprotect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BlockProtectConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public List<String> blocks = new ArrayList<>();

    private transient final Set<String> protectedKeys = new LinkedHashSet<>();
    private transient Path path;

    public static BlockProtectConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("blockprotect.json");
        BlockProtectConfig config = new BlockProtectConfig();
        config.path = path;
        if (Files.exists(path)) {
            try {
                BlockProtectConfig loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), BlockProtectConfig.class);
                if (loaded != null) {
                    config = loaded;
                    config.path = path;
                }
            } catch (Exception e) {
                BlockProtectMod.LOGGER.error("[BlockProtect] Failed to read {}", path, e);
            }
        }
        config.rebuildIndex();
        config.save();
        return config;
    }

    public boolean add(Level level, BlockPos pos) {
        String key = key(level, pos);
        boolean added = protectedKeys.add(key);
        if (added) {
            save();
        }
        return added;
    }

    public boolean remove(Level level, BlockPos pos) {
        boolean removed = protectedKeys.remove(key(level, pos));
        if (removed) {
            save();
        }
        return removed;
    }

    public boolean isProtected(Level level, BlockPos pos) {
        return protectedKeys.contains(key(level, pos));
    }

    public int size() {
        return protectedKeys.size();
    }

    public void clear() {
        protectedKeys.clear();
        save();
    }

    public List<String> entries() {
        return new ArrayList<>(protectedKeys);
    }

    public void save() {
        if (path == null) {
            path = FabricLoader.getInstance().getConfigDir().resolve("blockprotect.json");
        }
        blocks = new ArrayList<>(protectedKeys);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BlockProtectMod.LOGGER.error("[BlockProtect] Failed to write {}", path, e);
        }
    }

    private void rebuildIndex() {
        protectedKeys.clear();
        if (blocks == null) {
            blocks = new ArrayList<>();
            return;
        }
        protectedKeys.addAll(blocks);
    }

    private static String key(Level level, BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }
}
