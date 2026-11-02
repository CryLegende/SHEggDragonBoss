package org.Cry_Legende.sHEggDragonBoss.ritual;

import de.oliver.fancyholograms.api.data.DisplayHologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.RitualSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.CreakingHeart;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Un rituel en cours sur un bloc : objets déjà donnés, hologramme, puis (compte à rebours lancé) animation du bloc.
 */
public class RitualProcess {
    private final SHEggDragonBoss plugin;
    private final RitualSettings ritual;
    private final NamespacedKey animatorKey;
    private final Location location;
    private int currentItems = 0;
    private Hologram hologram;
    private boolean countdownStarted = false;
    private BukkitTask task;
    private BlockDisplay blockAnimator;

    public RitualProcess(SHEggDragonBoss plugin, RitualSettings ritual, Location location) {
        this.plugin = plugin;
        this.ritual = ritual;
        this.animatorKey = new NamespacedKey(plugin, "egg_animator");
        this.location = location;
        createHologram();
    }

    public RitualSettings getRitual() {
        return ritual;
    }

    public Location getLocation() {
        return location;
    }

    public int getCurrentItems() {
        return currentItems;
    }

    public void setCurrentItems(int items) {
        this.currentItems = items;
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

    public BlockDisplay getBlockAnimator() {
        return blockAnimator;
    }

    public int addItems(int amount) {
        int canAdd = ritual.requiredAmount() - this.currentItems;
        int toAdd = Math.min(amount, canAdd);
        this.currentItems += toAdd;
        return toAdd;
    }

    public void destroy() {
        removeHologram();
        stopBlockAnimation();
        if (task != null) {
            task.cancel();
        }
    }

    /** Retire le bloc du rituel (il est consommé) et le remplace par un affichage qui grossit pendant le compte à rebours. */
    public void startBlockAnimation() {
        location.getBlock().setType(Material.AIR);
        blockAnimator = (BlockDisplay) location.getWorld().spawnEntity(location.clone().add(0.5, 0, 0.5), EntityType.BLOCK_DISPLAY);
        // Jamais sauvegardé (pas d'animation orpheline après un redémarrage) et marqué pour ne retirer que le nôtre
        blockAnimator.setPersistent(false);
        blockAnimator.getPersistentDataContainer().set(animatorKey, PersistentDataType.BYTE, (byte) 1);
        BlockData data = ritual.block().createBlockData();
        if (data instanceof CreakingHeart heart) heart.setCreakingHeartState(CreakingHeart.State.AWAKE);
        blockAnimator.setBlock(data);
        Transformation transformation = blockAnimator.getTransformation();
        transformation.getTranslation().set(-0.5f, 0.0f, -0.5f);
        blockAnimator.setTransformation(transformation);
    }

    public void stopBlockAnimation() {
        if (blockAnimator != null && !blockAnimator.isDead()) {
            blockAnimator.remove();
        }
        // Seuls nos affichages : d'autres plugins (blocs personnalisés) placent aussi des BlockDisplay près des blocs
        location.getNearbyEntitiesByType(BlockDisplay.class, 1).stream()
            .filter(d -> d.getPersistentDataContainer().has(animatorKey, PersistentDataType.BYTE))
            .forEach(Entity::remove);
    }

    public void transformHologramForCountdown() {
        if (this.hologram == null || this.hologram.getData() == null) return;
        DisplayHologramData data = (DisplayHologramData) this.hologram.getData();
        data.setLocation(this.location.clone().add(0.5, ritual.hologram().countdownHeight(), 0.5));
        float scale = (float) ritual.hologram().countdownScale();
        data.setScale(new Vector3f(scale, scale, scale));
        this.hologram.forceUpdate();
    }

    private void createHologram() {
        RitualSettings.Hologram settings = ritual.hologram();
        Location holoLocation = location.clone().add(0.5, settings.height(), 0.5);
        // Un nom unique par type de rituel et par position : le dragon et le creaking ne se marchent jamais dessus
        String hologramName = settings.namePrefix() + "-" + location.getWorld().getName() + "_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();
        TextHologramData data = new TextHologramData(hologramName, holoLocation);
        data.setText(formatHologramText(settings.progress()));
        // Recréé par le plugin à chaque chargement : FancyHolograms ne doit pas le sauvegarder (doublons, hologrammes orphelins)
        data.setPersistent(false);
        data.setTextShadow(settings.textShadow());
        data.setBackground(Hologram.TRANSPARENT);
        data.setBillboard(Display.Billboard.VERTICAL);
        this.hologram = plugin.getFancyHologramsPlugin().getHologramManager().create(data);
        if (this.hologram != null) {
            plugin.getFancyHologramsPlugin().getHologramManager().addHologram(this.hologram);
        }
    }

    public void updateHologram(int... countdownTime) {
        if (this.hologram == null || this.hologram.getData() == null) return;
        TextHologramData data = (TextHologramData) this.hologram.getData();
        if (countdownStarted && countdownTime.length > 0) {
            data.setText(formatHologramText(ritual.hologram().countdown(), String.valueOf(countdownTime[0])));
        } else {
            data.setText(formatHologramText(ritual.hologram().progress()));
        }
        this.hologram.forceUpdate();
    }

    public void removeHologram() {
        if (this.hologram != null) {
            plugin.getFancyHologramsPlugin().getHologramManager().removeHologram(this.hologram);
            this.hologram = null;
        }
    }

    private List<String> formatHologramText(List<String> lines, String... replacements) {
        return lines.stream().map(line -> {
            String formatted = line
                .replace("{current}", String.valueOf(currentItems))
                .replace("{required}", String.valueOf(ritual.requiredAmount()));
            if (replacements.length > 0) {
                formatted = formatted.replace("{time}", replacements[0]);
            }
            return formatted;
        }).collect(Collectors.toList());
    }
}
