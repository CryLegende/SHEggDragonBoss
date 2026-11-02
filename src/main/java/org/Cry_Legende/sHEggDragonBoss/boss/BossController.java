package org.Cry_Legende.sHEggDragonBoss.boss;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.managers.BossDataManager;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ce que partagent les deux boss : le tick, la cible, la barre de boss, la sauvegarde « dormant » et le journal.
 * Chaque type de boss ajoute son comportement dans {@link #onTick()} et {@link #onSecond()}.
 */
public abstract class BossController extends BukkitRunnable {

    /** Pourquoi le contrôleur démarre : première apparition, réveil d'un boss dormant ou reprise après redémarrage. */
    public enum StartMode {
        FIRST_SPAWN,
        WOKEN_FROM_DORMANT,
        RESTORED
    }

    protected final SHEggDragonBoss plugin;
    protected final BossManager bossManager;
    protected final ConfigManager configManager;
    protected final Island island;
    protected final LivingEntity boss;
    protected final BossBar bossBar;
    protected final double maxHealth;

    protected Player target;
    protected Player externalTarget;
    protected int tickCounter = 0;

    private Location lastKnownLocation;
    private double lastKnownHealth;
    private BarColor shownColor;
    private BarStyle shownStyle;

    protected BossController(SHEggDragonBoss plugin, BossManager bossManager, Island island, LivingEntity boss, double maxHealth,
                             String initialTitle, BarColor color, BarStyle style) {
        this.plugin = plugin;
        this.bossManager = bossManager;
        this.configManager = plugin.getConfigManager();
        this.island = island;
        this.boss = boss;
        this.maxHealth = maxHealth;
        this.bossBar = Bukkit.createBossBar(initialTitle, color, style);
        this.shownColor = color;
        this.shownStyle = style;
        this.lastKnownLocation = boss.getLocation();
        this.lastKnownHealth = boss.getHealth();
    }

    // ------------------------------------------------------------------ à fournir par chaque boss

    public abstract BossType type();

    /** Titre de la barre de boss à l'instant présent (placeholders déjà remplacés). */
    protected abstract String barTitle();

    protected abstract BarColor barColor();

    protected abstract BarStyle barStyle();

    /** Effets d'apparition et journal ; appelé juste avant l'enregistrement du contrôleur. */
    protected abstract void onStart(StartMode mode);

    /** Appelé à chaque tick, après la mise à jour de la cible et de la barre (une fois par seconde). */
    protected abstract void onTick();

    /** Appelé une fois par seconde (tick 20, 40...). */
    protected void onSecond() {
    }

    /** Appelé à l'arrêt du contrôleur (mort, perte de l'entité, arrêt du plugin) ; peut l'être plusieurs fois. */
    protected void onCancel() {
    }

    /** Numéro de phase à sauvegarder avec le boss dormant (0 si le boss n'a pas de phases). */
    protected int savedPhase() {
        return 0;
    }

    /** Cœurs restants à sauvegarder avec le boss dormant (0 si le boss n'en a pas). */
    protected int savedHearts() {
        return 0;
    }

    // ------------------------------------------------------------------ cycle de vie

    public final void start(StartMode mode) {
        onStart(mode);
        bossManager.registerActiveBoss(this);
        this.runTaskTimer(plugin, 0L, 1L);
    }

    @Override
    public void run() {
        if (!boss.isValid() || boss.isDead()) {
            this.cancel();
            return;
        }

        tickCounter++;

        if (tickCounter % 20 == 0) {
            updateTarget();
            updateBossBar();
            onSecond();
        }

        onTick();

        this.lastKnownLocation = boss.getLocation();
        this.lastKnownHealth = boss.getHealth();
    }

    @Override
    public synchronized void cancel() throws IllegalStateException {
        super.cancel();
        bossBar.removeAll();
        onCancel();
    }

    // ------------------------------------------------------------------ cible et barre de boss

    public void setExternalTarget(Player player) {
        this.externalTarget = player;
    }

    protected void updateTarget() {
        // Une île peut couvrir plusieurs dimensions : seule la cible du même monde que le boss est mesurable
        World bossWorld = boss.getWorld();
        List<Player> islandMembers = island.getIslandMembers(true).stream()
            .map(SuperiorPlayer::asPlayer)
            .filter(p -> p != null && p.isOnline() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR && p.getWorld().equals(bossWorld) && island.isInside(p.getLocation()))
            .collect(Collectors.toList());
        Player validExternalTarget = (externalTarget != null && externalTarget.isOnline() && !externalTarget.isDead() && externalTarget.getWorld().equals(bossWorld)) ? externalTarget : null;
        this.target = Stream.concat(islandMembers.stream(), Stream.ofNullable(validExternalTarget))
            .filter(Objects::nonNull).distinct()
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation())))
            .orElse(null);
    }

    /** Les joueurs qui voient la barre de boss : membres de l'île en ligne, plus l'agresseur extérieur. */
    protected Set<Player> viewers() {
        Set<Player> viewers = island.getIslandMembers(true).stream().filter(SuperiorPlayer::isOnline).map(SuperiorPlayer::asPlayer).filter(Objects::nonNull).collect(Collectors.toSet());
        if (externalTarget != null && externalTarget.isOnline()) viewers.add(externalTarget);
        return viewers;
    }

    protected void updateBossBar() {
        double currentHealth = boss.getHealth();
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, currentHealth / maxHealth)));
        bossBar.setTitle(barTitle());
        BarColor color = barColor();
        if (color != shownColor) {
            bossBar.setColor(color);
            shownColor = color;
        }
        BarStyle style = barStyle();
        if (style != shownStyle) {
            bossBar.setStyle(style);
            shownStyle = style;
        }
        Set<Player> viewers = new HashSet<>(viewers());
        new ArrayList<>(bossBar.getPlayers()).stream().filter(p -> !viewers.contains(p)).forEach(bossBar::removePlayer);
        viewers.stream().filter(p -> !bossBar.getPlayers().contains(p)).forEach(bossBar::addPlayer);
    }

    /** Envoie un message aux joueurs qui suivent le combat ; un message vide n'est pas envoyé. */
    protected void tellViewers(String message) {
        if (message == null || message.isEmpty()) return;
        for (Player viewer : viewers()) viewer.sendMessage(message);
    }

    // ------------------------------------------------------------------ accès et sauvegarde

    public LivingEntity getBoss() {
        return boss;
    }

    public Island getIsland() {
        return island;
    }

    public UUID getUniqueId() {
        return boss.getUniqueId();
    }

    public BossDataManager.BossData getBossData() {
        return new BossDataManager.BossData(island.getUniqueId(), type(), lastKnownLocation, lastKnownHealth, maxHealth, savedPhase(), savedHearts());
    }

    protected String islandLabel() {
        return (island != null && island.getOwner() != null) ? "l'île de " + island.getOwner().getName() : "une île inconnue";
    }

    // SSB2 2026 : getCenter prend une Dimension et non plus un World.Environment
    protected static Dimension dimension(World.Environment env) {
        for (Dimension d : Dimension.values()) {
            if (d.getEnvironment() == env) return d;
        }
        return Dimension.getByName("NORMAL");
    }
}
