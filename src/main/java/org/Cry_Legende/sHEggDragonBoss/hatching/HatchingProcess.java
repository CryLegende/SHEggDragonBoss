package org.Cry_Legende.sHEggDragonBoss.hatching;

import de.oliver.fancyholograms.api.data.DisplayHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.data.TextHologramData;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.List;
import java.util.stream.Collectors;

public class HatchingProcess {
    private final SHEggDragonBoss plugin;
    private final Location location;
    private int currentShards = 0;
    private Hologram hologram;
    private boolean countdownStarted = false;
    private BukkitTask task;
    private BlockDisplay eggAnimator;

    public HatchingProcess(SHEggDragonBoss plugin, Location location) {
        this.plugin = plugin;
        this.location = location;
        createHologram(plugin.getConfigManager());
    }

    public Location getLocation() {
        return location;
    }

    public int getCurrentShards() {
        return currentShards;
    }

    public void setCurrentShards(int shards) {
        this.currentShards = shards;
    }

    public boolean isCountdownStarted() {
        return countdownStarted;
    }

    public void setCountdownStarted(boolean countdownStarted) {
        this.countdownStarted = countdownStarted;
    }

    public BukkitTask getTask() {
        return task;
    }

    public void setTask(BukkitTask task) {
        this.task = task;
    }

    public BlockDisplay getEggAnimator() {
        return eggAnimator;
    }

    public int addShards(int amount, int requiredShards) {
        int canAdd = requiredShards - this.currentShards;
        int toAdd = Math.min(amount, canAdd);
        this.currentShards += toAdd;
        return toAdd;
    }

    public void destroy() {
        removeHologram();
        stopEggAnimation();
        if (task != null) {
            task.cancel();
        }
    }

    public void startEggAnimation() {
        location.getBlock().setType(Material.AIR);
        eggAnimator = (BlockDisplay) location.getWorld().spawnEntity(location.clone().add(0.5, 0, 0.5), EntityType.BLOCK_DISPLAY);
        eggAnimator.setBlock(Material.DRAGON_EGG.createBlockData());
        Transformation transformation = eggAnimator.getTransformation();
        transformation.getTranslation().set(-0.5f, 0.0f, -0.5f);
        eggAnimator.setTransformation(transformation);
    }

    public void stopEggAnimation() {
        if (eggAnimator != null && !eggAnimator.isDead()) {
            eggAnimator.remove();
        }
        location.getNearbyEntitiesByType(BlockDisplay.class, 1).forEach(Entity::remove);
    }

    public void transformHologramForCountdown(ConfigManager configManager) {
        if (this.hologram == null || this.hologram.getData() == null) return;
        DisplayHologramData data = (DisplayHologramData) this.hologram.getData();
        data.setLocation(this.location.clone().add(0.5, configManager.getHologramCountdownHeight(), 0.5));
        float scale = (float) configManager.getHologramCountdownScale();
        data.setScale(new Vector3f(scale, scale, scale));
        this.hologram.forceUpdate();
    }

    private void createHologram(ConfigManager configManager) {
        Location holoLocation = location.clone().add(0.5, configManager.getHologramHeight(), 0.5);
        String hologramName = "sHEDragon-" + location.getWorld().getName() + "_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();
        TextHologramData data = new TextHologramData(hologramName, holoLocation);
        data.setText(formatHologramText(configManager.getHologramProgress(), configManager.getRequiredShards()));
        data.setTextShadow(configManager.isHologramTextShadowEnabled());
        data.setBackground(Hologram.TRANSPARENT);
        data.setBillboard(Display.Billboard.VERTICAL);
        this.hologram = plugin.getFancyHologramsPlugin().getHologramManager().create(data);
        if (this.hologram != null) {
            plugin.getFancyHologramsPlugin().getHologramManager().addHologram(this.hologram);
        }
    }

    public void updateHologram(ConfigManager configManager, int... countdownTime) {
        if (this.hologram == null || this.hologram.getData() == null) return;
        TextHologramData data = (TextHologramData) this.hologram.getData();
        if (countdownStarted) {
            data.setText(formatHologramText(configManager.getHologramCountdown(), configManager.getRequiredShards(), String.valueOf(countdownTime[0])));
        } else {
            data.setText(formatHologramText(configManager.getHologramProgress(), configManager.getRequiredShards()));
        }
        this.hologram.forceUpdate();
    }

    public void removeHologram() {
        if (this.hologram != null) {
            plugin.getFancyHologramsPlugin().getHologramManager().removeHologram(this.hologram);
            this.hologram = null;
        }
    }

    private List<String> formatHologramText(List<String> lines, int requiredShards, String... replacements) {
        return lines.stream().map(line -> {
            String formatted = line
                .replace("{shards_current}", String.valueOf(currentShards))
                .replace("{shards_required}", String.valueOf(requiredShards));
            if (replacements.length > 0) {
                formatted = formatted.replace("{time}", replacements[0]);
            }
            return formatted;
        }).collect(Collectors.toList());
    }
}
