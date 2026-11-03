package org.Cry_Legende.sHEggDragonBoss.managers;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.HeartOwner;
import org.Cry_Legende.sHEggDragonBoss.boss.PlayerText;
import org.Cry_Legende.sHEggDragonBoss.config.CreakingSettings;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.CreakingHeart;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les cœurs du Creaking : des blocs CREAKING_HEART posés par le plugin pendant une phase d'invulnérabilité.
 * <p>
 * Ils ne sont jamais cassés par le jeu : seul ce gestionnaire les détruit, à raison d'un « point de vie » par coup de
 * poing (clic gauche), indépendamment des protections d'île. Leurs positions sont écrites dans creaking-hearts.yml ;
 * après un arrêt brutal, les cœurs encore posés sont retrouvés et supprimés dès que leur chunk est chargé.
 */
public class HeartManager {

    /** Position d'un bloc, indépendante de l'objet {@link Block} (qui change d'un appel à l'autre). */
    public record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static final class Heart {
        private final BlockKey key;
        private final World world;
        private final HeartOwner owner;
        private int hitsLeft;

        private Heart(BlockKey key, World world, HeartOwner owner, int hits) {
            this.key = key;
            this.world = world;
            this.owner = owner;
            this.hitsLeft = hits;
        }

        private Block block() {
            return world.getBlockAt(key.x(), key.y(), key.z());
        }

        private boolean chunkLoaded() {
            return world.isChunkLoaded(key.x() >> 4, key.z() >> 4);
        }
    }

    /** Empêche la physique des blocs de toucher un cœur ; n'est enregistré que tant qu'il existe au moins un cœur. */
    public static final class PhysicsGuard implements Listener {
        private final HeartManager manager;

        private PhysicsGuard(HeartManager manager) {
            this.manager = manager;
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onPhysics(BlockPhysicsEvent event) {
            if (event.getBlock().getType() == Material.CREAKING_HEART && manager.isHeart(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    private final SHEggDragonBoss plugin;
    private final File file;
    private final Map<BlockKey, Heart> hearts = new HashMap<>();
    private final Map<UUID, Long> lastHitNanos = new HashMap<>();
    // Joueurs qui ont déjà reçu l'explication « clic gauche répété » pendant ce combat
    private final Set<UUID> hinted = new HashSet<>();
    // Cœurs sans propriétaire (reste d'un arrêt brutal, ou chunk déchargé au moment du retrait) : monde (minuscules) -> chunk -> positions
    private final Map<String, Map<Long, List<int[]>>> orphans = new HashMap<>();
    private final Map<String, String> worldNames = new HashMap<>();
    private final PhysicsGuard physicsGuard = new PhysicsGuard(this);
    private boolean guardRegistered = false;

    public HeartManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "creaking-hearts.yml");
        loadOrphans();
        // Les mondes sont tous là un tick après l'activation : on nettoie alors les chunks déjà chargés
        if (!orphans.isEmpty()) Bukkit.getScheduler().runTask(plugin, this::sweepLoadedChunks);
    }

    // ------------------------------------------------------------------ interrogation

    public boolean isHeart(Block block) {
        return block.getType() == Material.CREAKING_HEART && hearts.containsKey(BlockKey.of(block));
    }

    /** Nombre de cœurs encore posés pour ce boss ; ceux qui ont disparu du monde sont oubliés au passage. */
    public int countAlive(UUID bossId) {
        int alive = 0;
        boolean changed = false;
        for (Heart heart : new ArrayList<>(hearts.values())) {
            if (!heart.owner.bossId().equals(bossId)) continue;
            // Chunk déchargé : on ne le charge pas pour vérifier, le cœur est considéré comme présent
            if (heart.chunkLoaded() && heart.block().getType() != Material.CREAKING_HEART) {
                hearts.remove(heart.key);
                changed = true;
            } else {
                alive++;
            }
        }
        if (changed) {
            updateGuard();
            save();
        }
        return alive;
    }

    // ------------------------------------------------------------------ pose

    /**
     * Pose jusqu'à {@code count} cœurs autour de {@code around}. Ils vont sur des blocs d'air de l'île, dans la couche
     * du boss, entre {@code min-distance} et {@code max-distance} de lui, espacés d'au moins {@code min-spacing}. Ceux qui
     * ont un bloc solide dessous sont préférés ; les autres flottent. Aucun bloc existant n'est jamais remplacé.
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

        for (Block block : picked) {
            setHeart(block);
            hearts.put(BlockKey.of(block), new Heart(BlockKey.of(block), world, owner, cfg.hitsToBreak()));
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            cfg.placeFx().spawn(center);
            cfg.placeSound().play(center);
        }
        if (!picked.isEmpty()) {
            updateGuard();
            save();
        }
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
                if (block.getType().isAir() && world.getBlockAt(x, y - 1, z).getType().isSolid()
                    && !hearts.containsKey(BlockKey.of(world.getBlockAt(x, y - 1, z)))) {
                    return block;
                }
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
        if (hearts.containsKey(BlockKey.of(block))) return false;
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
            if (!heart.world.equals(world)) continue;
            if (heart.block().getLocation().add(0.5, 0.5, 0.5).distanceSquared(center) < minSpacingSquared) return false;
        }
        // Un cœur entre deux bûches de chêne pâle s'éveille la nuit comme un cœur naturel (la propriété « natural » n'y change
        // rien) : il ferait apparaître un Creaking vanilla et de la résine. On n'en pose jamais à un tel emplacement.
        if (betweenPaleLogs(block)) return false;
        // Jamais dans un joueur ni dans une créature : le bloc l'étoufferait
        BoundingBox box = BoundingBox.of(block).expand(0.05);
        return world.getNearbyEntities(box, e -> e instanceof LivingEntity).isEmpty();
    }

    private static boolean betweenPaleLogs(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.EAST}) {
            if (isPaleWood(block.getRelative(face)) && isPaleWood(block.getRelative(face.getOppositeFace()))) return true;
        }
        return false;
    }

    private static boolean isPaleWood(Block block) {
        Material type = block.getType();
        return type == Material.PALE_OAK_LOG || type == Material.PALE_OAK_WOOD
            || type == Material.STRIPPED_PALE_OAK_LOG || type == Material.STRIPPED_PALE_OAK_WOOD;
    }

    private static void setHeart(Block block) {
        BlockData data = Material.CREAKING_HEART.createBlockData();
        if (data instanceof CreakingHeart heart) {
            // Éveillé pour le rendu. Ce qui empêche un Creaking vanilla d'apparaître, c'est l'absence de bûches de chêne pâle
            // de part et d'autre (voir isValidSpot) ; natural=false est posé par prudence, le jeu ne le lit pas pour cela.
            heart.setNatural(false);
            heart.setCreakingHeartState(CreakingHeart.State.AWAKE);
        }
        block.setBlockData(data, false);
    }

    // ------------------------------------------------------------------ coups et destruction

    /**
     * Un joueur frappe (clic gauche) un bloc. Renvoie vrai si c'était un cœur de boss, que le coup ait compté ou non.
     */
    public boolean hit(Player player, Block block) {
        Heart heart = hearts.get(BlockKey.of(block));
        if (heart == null || block.getType() != Material.CREAKING_HEART) return false;
        CreakingSettings.Hearts cfg = plugin.getConfigManager().creaking().hearts();

        long now = System.nanoTime();
        Long last = lastHitNanos.get(player.getUniqueId());
        if (last != null && now - last < (long) (cfg.hitCooldownSeconds() * 1_000_000_000L)) return true;
        lastHitNanos.put(player.getUniqueId(), now);

        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        heart.hitsLeft--;
        if (heart.hitsLeft > 0) {
            cfg.hitFx().spawn(center);
            cfg.hitSound().play(center);
            // Le joueur voit tout de suite que son coup compte, et qu'il faut recommencer : on ne mine pas un cœur, on le frappe
            int done = cfg.hitsToBreak() - heart.hitsLeft;
            PlayerText.actionBar(player, cfg.hitActionbar()
                .replace("{bar}", "§a" + "▮".repeat(Math.max(0, done)) + "§8" + "▮".repeat(Math.max(0, heart.hitsLeft)))
                .replace("{done}", String.valueOf(done))
                .replace("{total}", String.valueOf(cfg.hitsToBreak())));
            if (hinted.add(player.getUniqueId())) {
                String hint = plugin.getConfigManager().creaking().messages().heartHint();
                if (!hint.isEmpty()) player.sendMessage(hint);
            }
            return true;
        }

        hearts.remove(heart.key);
        block.setType(Material.AIR, false);
        cfg.breakFx().spawn(center);
        cfg.breakSound().play(center);
        updateGuard();
        save();
        heart.owner.onHeartDestroyed(player);
        return true;
    }

    public void forgetPlayer(UUID playerId) {
        lastHitNanos.remove(playerId);
        hinted.remove(playerId);
    }

    /** Centres des cœurs encore posés pour ce boss, dans les chunks chargés (pour les repères visuels et la boussole). */
    public List<Location> locations(UUID bossId) {
        List<Location> result = new ArrayList<>();
        for (Heart heart : hearts.values()) {
            if (!heart.owner.bossId().equals(bossId) || !heart.chunkLoaded()) continue;
            result.add(new Location(heart.world, heart.key.x() + 0.5, heart.key.y() + 0.5, heart.key.z() + 0.5));
        }
        return result;
    }

    // ------------------------------------------------------------------ retrait

    /** Retire tous les cœurs d'un boss (mort, perte de l'entité, arrêt). */
    public void removeAll(UUID bossId) {
        boolean changed = false;
        for (Heart heart : new ArrayList<>(hearts.values())) {
            if (!heart.owner.bossId().equals(bossId)) continue;
            release(heart);
            changed = true;
        }
        if (changed) {
            // Fin de combat : le prochain combat réexplique la mécanique à tout le monde
            hinted.clear();
            updateGuard();
            save();
        }
    }

    /** Retire le cœur du monde, ou le note comme orphelin si son chunk est déchargé (on ne charge pas un chunk pour ça). */
    private void release(Heart heart) {
        hearts.remove(heart.key);
        if (heart.chunkLoaded()) {
            Block block = heart.block();
            if (block.getType() == Material.CREAKING_HEART) block.setType(Material.AIR, false);
        } else {
            addOrphan(heart.world.getName(), heart.key.x(), heart.key.y(), heart.key.z());
        }
    }

    /** À l'arrêt du plugin : plus aucun cœur ne doit rester dans les mondes. */
    public void shutdown() {
        for (Heart heart : new ArrayList<>(hearts.values())) release(heart);
        hearts.clear();
        updateGuard();
        save();
    }

    // ------------------------------------------------------------------ cœurs orphelins

    private void addOrphan(String worldName, int x, int y, int z) {
        String key = worldName.toLowerCase(Locale.ROOT);
        worldNames.put(key, worldName);
        orphans.computeIfAbsent(key, k -> new HashMap<>())
            .computeIfAbsent(Chunk.getChunkKey(x >> 4, z >> 4), k -> new ArrayList<>())
            .add(new int[]{x, y, z});
    }

    private void loadOrphans() {
        if (!file.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String raw : config.getStringList("hearts")) {
            try {
                String[] parts = raw.split(";");
                addOrphan(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Cœur illisible dans creaking-hearts.yml : '" + raw + "'.");
            }
        }
        if (!orphans.isEmpty()) {
            plugin.getLogger().info("Cœurs du Creaking laissés par un arrêt brutal : ils seront retirés au chargement de leurs chunks.");
        }
    }

    private void sweepLoadedChunks() {
        for (String key : new ArrayList<>(orphans.keySet())) {
            World world = Bukkit.getWorld(worldNames.getOrDefault(key, key));
            if (world != null) sweepWorld(world);
        }
    }

    public void onWorldLoad(World world) {
        if (orphans.isEmpty()) return;
        // Un tick plus tard : le monde est alors complètement installé
        Bukkit.getScheduler().runTask(plugin, () -> sweepWorld(world));
    }

    public void onChunkLoad(Chunk chunk) {
        if (orphans.isEmpty()) return;
        Map<Long, List<int[]>> chunks = orphans.get(chunk.getWorld().getName().toLowerCase(Locale.ROOT));
        if (chunks == null || !chunks.containsKey(chunk.getChunkKey())) return;
        Bukkit.getScheduler().runTask(plugin, () -> sweepChunk(chunk.getWorld(), chunk.getX(), chunk.getZ()));
    }

    private void sweepWorld(World world) {
        Map<Long, List<int[]>> chunks = orphans.get(world.getName().toLowerCase(Locale.ROOT));
        if (chunks == null) return;
        for (long key : new ArrayList<>(chunks.keySet())) {
            int cx = (int) key;
            int cz = (int) (key >> 32);
            if (world.isChunkLoaded(cx, cz)) sweepChunk(world, cx, cz);
        }
    }

    private void sweepChunk(World world, int cx, int cz) {
        String worldKey = world.getName().toLowerCase(Locale.ROOT);
        Map<Long, List<int[]>> chunks = orphans.get(worldKey);
        if (chunks == null || !world.isChunkLoaded(cx, cz)) return;
        List<int[]> positions = chunks.remove(Chunk.getChunkKey(cx, cz));
        if (positions == null) return;
        int removed = 0;
        for (int[] pos : positions) {
            Block block = world.getBlockAt(pos[0], pos[1], pos[2]);
            // Jamais un cœur actuellement géré : seul un reste d'un combat précédent est supprimé
            if (block.getType() == Material.CREAKING_HEART && !hearts.containsKey(BlockKey.of(block))) {
                block.setType(Material.AIR, false);
                removed++;
            }
        }
        if (chunks.isEmpty()) orphans.remove(worldKey);
        if (removed > 0) plugin.getLogger().info(removed + " cœur(s) orphelin(s) du Creaking retiré(s) dans " + world.getName() + " (chunk " + cx + ", " + cz + ").");
        save();
    }

    // ------------------------------------------------------------------ garde physique et sauvegarde

    private void updateGuard() {
        if (!hearts.isEmpty() && !guardRegistered) {
            Bukkit.getPluginManager().registerEvents(physicsGuard, plugin);
            guardRegistered = true;
        } else if (hearts.isEmpty() && guardRegistered) {
            HandlerList.unregisterAll(physicsGuard);
            guardRegistered = false;
        }
    }

    private void save() {
        List<String> lines = new ArrayList<>();
        for (Heart heart : hearts.values()) {
            lines.add(heart.world.getName() + ";" + heart.key.x() + ";" + heart.key.y() + ";" + heart.key.z());
        }
        for (Map.Entry<String, Map<Long, List<int[]>>> world : orphans.entrySet()) {
            String name = worldNames.getOrDefault(world.getKey(), world.getKey());
            for (List<int[]> positions : world.getValue().values()) {
                for (int[] pos : positions) lines.add(name + ";" + pos[0] + ";" + pos[1] + ";" + pos[2]);
            }
        }
        FileConfiguration config = new YamlConfiguration();
        config.set("hearts", lines);
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder creaking-hearts.yml !");
        }
    }
}
