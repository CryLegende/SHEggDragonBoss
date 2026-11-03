package org.Cry_Legende.sHEggDragonBoss.managers;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.HeartOwner;
import org.Cry_Legende.sHEggDragonBoss.boss.PlayerText;
import org.Cry_Legende.sHEggDragonBoss.config.CreakingSettings;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.CreakingHeart;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les cœurs du Creaking : pendant une phase d'invulnérabilité, des cœurs éveillés flottent autour du boss et les joueurs les
 * frappent (clic gauche, maintenu ou répété). Un cœur est un affichage de bloc lumineux (BlockDisplay) doublé d'une zone de
 * frappe (Interaction), jamais un vrai bloc : SuperiorSkyblock2 ne les compte donc pas dans le niveau de l'île, aucune
 * protection d'île ne les bloque, et on ne peut ni les miner, ni les pousser, ni les faire exploser.
 * <p>
 * Chaque entité porte un marqueur ; celles qui survivent à un arrêt brutal sont retirées dès que leur chunk se charge.
 */
public class HeartManager {

    private static final class Heart {
        private final World world;
        private final Location center;
        private final HeartOwner owner;
        private final UUID displayId;
        private final UUID hitboxId;
        private int hitsLeft;

        private Heart(World world, Location center, HeartOwner owner, UUID displayId, UUID hitboxId, int hits) {
            this.world = world;
            this.center = center;
            this.owner = owner;
            this.displayId = displayId;
            this.hitboxId = hitboxId;
            this.hitsLeft = hits;
        }

        private boolean chunkLoaded() {
            return world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4);
        }
    }

    private final SHEggDragonBoss plugin;
    private final NamespacedKey markerKey;
    /** Cœurs posés, par identifiant de leur zone de frappe. */
    private final Map<UUID, Heart> hearts = new HashMap<>();
    private final Map<UUID, Long> lastHitNanos = new HashMap<>();
    /** Joueurs qui ont déjà reçu l'explication « clic gauche répété » pendant ce combat. */
    private final Set<UUID> hinted = new HashSet<>();

    public HeartManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "boss_heart");
        // Les mondes sont tous là un tick après l'activation
        Bukkit.getScheduler().runTask(plugin, () -> {
            sweepLegacyBlocks();
            for (World world : Bukkit.getWorlds()) removeStray(world.getEntities());
        });
    }

    // ------------------------------------------------------------------ interrogation

    /** Vrai si l'entité est la zone de frappe d'un cœur posé. */
    public boolean isHeart(Entity entity) {
        return hearts.containsKey(entity.getUniqueId());
    }

    /** Nombre de cœurs encore posés pour ce boss ; ceux dont l'entité a disparu (retirée par un administrateur) sont oubliés. */
    public int countAlive(UUID bossId) {
        int alive = 0;
        for (Heart heart : new ArrayList<>(hearts.values())) {
            if (!heart.owner.bossId().equals(bossId)) continue;
            // Chunk déchargé : on ne le charge pas pour vérifier, le cœur est considéré comme présent
            if (heart.chunkLoaded() && !isPresent(heart)) {
                hearts.remove(heart.hitboxId);
                Entity display = Bukkit.getEntity(heart.displayId);
                if (display != null) display.remove();
            } else {
                alive++;
            }
        }
        return alive;
    }

    private static boolean isPresent(Heart heart) {
        Entity hitbox = Bukkit.getEntity(heart.hitboxId);
        return hitbox != null && hitbox.isValid();
    }

    /** Centres des cœurs encore posés pour ce boss, dans les chunks chargés (pour les repères visuels et la boussole). */
    public List<Location> locations(UUID bossId) {
        List<Location> result = new ArrayList<>();
        for (Heart heart : hearts.values()) {
            if (heart.owner.bossId().equals(bossId) && heart.chunkLoaded()) result.add(heart.center.clone());
        }
        return result;
    }

    // ------------------------------------------------------------------ pose

    /**
     * Pose jusqu'à {@code count} cœurs autour de {@code around}. Ils flottent sur des blocs d'air de l'île, dans la couche
     * du boss, entre {@code min-distance} et {@code max-distance} de lui, espacés d'au moins {@code min-spacing}. Ceux qui
     * ont un bloc solide dessous sont préférés. Aucun bloc n'est jamais touché.
     *
     * @return le nombre de cœurs réellement posés
     */
    public int place(HeartOwner owner, Island island, Location around, int count, CreakingSettings.Hearts cfg) {
        World world = around.getWorld();
        if (world == null || count <= 0) return 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Block> picked = new ArrayList<>();

        for (boolean supportedOnly : new boolean[]{true, false}) {
            for (int attempt = 0; attempt < cfg.placementAttempts() && picked.size() < count; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = cfg.minDistance() + random.nextDouble() * Math.max(0.0, cfg.maxDistance() - cfg.minDistance());
                int x = (int) Math.floor(around.getX() + Math.cos(angle) * distance);
                int z = (int) Math.floor(around.getZ() + Math.sin(angle) * distance);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block candidate = supportedOnly
                    ? findSupported(world, x, z, around.getBlockY(), cfg.verticalRange())
                    : floatingAt(world, x, z, around.getBlockY());
                if (candidate != null && isValidSpot(candidate, island, around, cfg, picked)) picked.add(candidate);
            }
        }
        for (Block block : picked) spawnHeart(owner, block, cfg);
        return picked.size();
    }

    /** Cherche, dans la colonne, un bloc d'air posé sur un bloc solide, au plus près de la hauteur du boss. */
    private Block findSupported(World world, int x, int z, int bossY, int range) {
        for (int offset = 0; offset <= range; offset++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (offset == 0 && sign == -1) continue;
                int y = bossY + sign * offset;
                if (y <= world.getMinHeight() || y >= world.getMaxHeight()) continue;
                Block block = world.getBlockAt(x, y, z);
                if (block.getType().isAir() && world.getBlockAt(x, y - 1, z).getType().isSolid()) return block;
            }
        }
        return null;
    }

    /** Un emplacement sans support, à la hauteur du boss. */
    private Block floatingAt(World world, int x, int z, int bossY) {
        if (bossY <= world.getMinHeight() || bossY >= world.getMaxHeight()) return null;
        return world.getBlockAt(x, bossY, z);
    }

    private boolean isValidSpot(Block block, Island island, Location boss, CreakingSettings.Hearts cfg, List<Block> alreadyPicked) {
        if (!block.getType().isAir()) return false;
        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        if (!island.isInside(center) || !world.getWorldBorder().isInside(center)) return false;
        double distance = center.distance(boss);
        if (distance < cfg.minDistance() || distance > cfg.maxDistance()) return false;
        double minSpacingSquared = cfg.minSpacing() * cfg.minSpacing();
        for (Block other : alreadyPicked) {
            if (other.getLocation().add(0.5, 0.5, 0.5).distanceSquared(center) < minSpacingSquared) return false;
        }
        for (Heart heart : hearts.values()) {
            if (heart.world.equals(world) && heart.center.distanceSquared(center) < minSpacingSquared) return false;
        }
        return true;
    }

    /** Un cœur : un affichage de bloc éveillé (lumineux si demandé) et, au même endroit, la zone que les joueurs frappent. */
    private void spawnHeart(HeartOwner owner, Block block, CreakingSettings.Hearts cfg) {
        World world = block.getWorld();
        Location base = block.getLocation().add(0.5, 0.0, 0.5);
        BlockData data = Material.CREAKING_HEART.createBlockData();
        if (data instanceof CreakingHeart heart) heart.setCreakingHeartState(CreakingHeart.State.AWAKE);

        BlockDisplay display = world.spawn(base, BlockDisplay.class, d -> {
            d.setBlock(data);
            Transformation transformation = d.getTransformation();
            transformation.getTranslation().set(-0.5f, 0.0f, -0.5f);
            d.setTransformation(transformation);
            if (cfg.glow()) {
                d.setGlowing(true);
                d.setGlowColorOverride(Color.fromRGB(cfg.glowColor()));
            }
            d.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        });
        Interaction hitbox = world.spawn(base, Interaction.class, i -> {
            i.setInteractionWidth(1.0f);
            i.setInteractionHeight(1.0f);
            i.setResponsive(true);
            i.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        });
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        hearts.put(hitbox.getUniqueId(), new Heart(world, center, owner, display.getUniqueId(), hitbox.getUniqueId(), cfg.hitsToBreak()));
        cfg.placeFx().spawn(center);
        cfg.placeSound().play(center);
    }

    // ------------------------------------------------------------------ coups et destruction

    /**
     * Un joueur frappe (clic gauche) une zone de cœur. Renvoie vrai si c'était un cœur de boss, que le coup ait compté ou non.
     */
    public boolean hit(Player player, Entity entity) {
        Heart heart = hearts.get(entity.getUniqueId());
        if (heart == null) return false;
        CreakingSettings.Hearts cfg = plugin.getConfigManager().creaking().hearts();

        long now = System.nanoTime();
        Long last = lastHitNanos.get(player.getUniqueId());
        if (last != null && now - last < (long) (cfg.hitCooldownSeconds() * 1_000_000_000L)) return true;
        lastHitNanos.put(player.getUniqueId(), now);

        Location center = heart.center;
        heart.hitsLeft--;
        if (heart.hitsLeft > 0) {
            cfg.hitFx().spawn(center);
            cfg.hitSound().play(center);
            // Le joueur voit tout de suite que son coup compte, et qu'il faut continuer
            int done = cfg.hitsToBreak() - heart.hitsLeft;
            PlayerText.actionBar(player, cfg.hitActionbar()
                .replace("%bar%", "§a" + "▮".repeat(Math.max(0, done)) + "§8" + "▮".repeat(Math.max(0, heart.hitsLeft)))
                .replace("%done%", String.valueOf(done))
                .replace("%total%", String.valueOf(cfg.hitsToBreak())));
            if (hinted.add(player.getUniqueId())) {
                String hint = plugin.getConfigManager().creaking().texts().heartHint();
                if (!hint.isEmpty()) player.sendMessage(hint);
            }
            return true;
        }

        discard(heart);
        cfg.breakFx().spawn(center);
        cfg.breakSound().play(center);
        heart.owner.onHeartDestroyed(player);
        return true;
    }

    public void forgetPlayer(UUID playerId) {
        lastHitNanos.remove(playerId);
        hinted.remove(playerId);
    }

    // ------------------------------------------------------------------ retrait

    /** Retire tous les cœurs d'un boss (mort, perte de l'entité, arrêt). */
    public void removeAll(UUID bossId) {
        boolean any = false;
        for (Heart heart : new ArrayList<>(hearts.values())) {
            if (!heart.owner.bossId().equals(bossId)) continue;
            discard(heart);
            any = true;
        }
        // Fin de combat : le prochain combat réexplique la mécanique à tout le monde
        if (any) hinted.clear();
    }

    /** À l'arrêt du plugin : plus aucun cœur ne doit rester dans les mondes. */
    public void shutdown() {
        for (Heart heart : new ArrayList<>(hearts.values())) discard(heart);
        hearts.clear();
    }

    /** Oublie le cœur et retire ses entités ; si leur chunk est déchargé, le marqueur permet de les retirer au chargement. */
    private void discard(Heart heart) {
        hearts.remove(heart.hitboxId);
        for (UUID id : new UUID[]{heart.displayId, heart.hitboxId}) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
    }

    // ------------------------------------------------------------------ entités orphelines

    /** Entités de cœur qui ne sont plus suivies (arrêt brutal, chunk rechargé après la fin du combat) : retirées un tick plus tard. */
    public void onEntitiesLoad(Collection<? extends Entity> entities) {
        List<Entity> marked = entities.stream().filter(this::isMarked).map(e -> (Entity) e).toList();
        if (marked.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> removeStray(marked));
    }

    private boolean isMarked(Entity entity) {
        return entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private void removeStray(Collection<? extends Entity> entities) {
        Set<UUID> tracked = new HashSet<>();
        for (Heart heart : hearts.values()) {
            tracked.add(heart.displayId);
            tracked.add(heart.hitboxId);
        }
        int removed = 0;
        for (Entity entity : entities) {
            if (isMarked(entity) && !tracked.contains(entity.getUniqueId())) {
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) plugin.getLogger().info(removed + " entité(s) de cœur du Creaking orpheline(s) retirée(s).");
    }

    /** Anciennes versions : les cœurs étaient de vrais blocs listés dans creaking-hearts.yml. Ceux qui restent sont retirés. */
    private void sweepLegacyBlocks() {
        File file = new File(plugin.getDataFolder(), "creaking-hearts.yml");
        if (!file.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        int removed = 0;
        for (String raw : config.getStringList("hearts")) {
            try {
                String[] parts = raw.split(";");
                World world = Bukkit.getWorld(parts[0]);
                int x = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[3]);
                if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block block = world.getBlockAt(x, Integer.parseInt(parts[2]), z);
                if (block.getType() == Material.CREAKING_HEART) {
                    block.setType(Material.AIR, false);
                    removed++;
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Cœur illisible dans creaking-hearts.yml : '" + raw + "'.");
            }
        }
        if (removed > 0) plugin.getLogger().info(removed + " ancien(s) cœur(s) du Creaking (blocs) retiré(s).");
        if (!file.delete()) plugin.getLogger().warning("Impossible de supprimer creaking-hearts.yml (inutilisé depuis les cœurs en affichage).");
    }
}
