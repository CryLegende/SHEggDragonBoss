package org.Cry_Legende.sHEggDragonBoss.boss;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.BossSettings;
import org.Cry_Legende.sHEggDragonBoss.config.DeathSettings;
import org.Cry_Legende.sHEggDragonBoss.config.Fx;
import org.Cry_Legende.sHEggDragonBoss.managers.BossDataManager;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ce que partagent les deux boss : le tick, la cible, la barre de boss, la sauvegarde « dormant », le journal, la
 * contribution de chaque joueur au combat et la mort en plusieurs temps.
 * Chaque type de boss ajoute son comportement dans {@link #onTick()} et {@link #onSecond()}.
 *
 * <p><b>La mort</b> : un coup qui ferait tomber le boss à 0 PV est annulé ({@link #interceptLethal}). Le boss est alors
 * retenu pendant la durée réglée dans {@code death.duration-ticks} (il tremble, des particules l'entourent), son final
 * éclate, puis le plugin porte lui-même le coup de grâce : la mort suit son cours normal (récompenses comprises).
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
    /** Vrai pendant un coup donné par le plugin lui-même : le bonus de mêlée ne s'y ajoute pas une seconde fois. */
    protected boolean dealingDamage;

    private Location lastKnownLocation;
    private double lastKnownHealth;
    private BarColor shownColor;
    private BarStyle shownStyle;

    /** Ce que chaque joueur a fait au boss (dégâts réellement infligés, cœurs brisés), en PV du boss. */
    private final Map<UUID, Double> contributions = new HashMap<>();

    private boolean dying;
    private boolean finishing;
    private boolean finaleDone;
    private int deathTick;
    private Location deathOrigin;
    private UUID deathKiller;

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

    /** Appelé quand la mort commence : le boss ne mène plus aucune attaque, ses états en cours doivent être refermés. */
    protected void onDeathStart() {
    }

    /** Vrai si le boss garde son IA vanilla (le Creaking) ; faux s'il est déplacé à la main (le dragon). */
    protected boolean usesVanillaAi() {
        return false;
    }

    /**
     * Filtre un coup reçu par le boss (avant les autres effets, thread principal) : phases, plafond par coup...
     *
     * @return vrai si le coup doit être annulé
     */
    public boolean absorbDamage(EntityDamageEvent event) {
        return false;
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

        if (dying) {
            tickDeath();
            return;
        }

        if (tickCounter % 20 == 0) {
            persistHealth();
            updateTarget();
            updateBossBar();
            onSecond();
        }

        onTick();

        this.lastKnownLocation = boss.getLocation();
        this.lastKnownHealth = boss.getHealth();
    }

    /** Copie les PV max et les PV sur l'entité : voir {@link SHEggDragonBoss#BOSS_MAX_HEALTH_KEY}. */
    private void persistHealth() {
        var container = boss.getPersistentDataContainer();
        container.set(SHEggDragonBoss.BOSS_MAX_HEALTH_KEY, org.bukkit.persistence.PersistentDataType.DOUBLE, maxHealth);
        container.set(SHEggDragonBoss.BOSS_HEALTH_KEY, org.bukkit.persistence.PersistentDataType.DOUBLE, boss.getHealth());
    }

    @Override
    public synchronized void cancel() throws IllegalStateException {
        super.cancel();
        bossBar.removeAll();
        // Arrêt du plugin en pleine mort : le boss (encore en vie) retrouve son IA et sa gravité
        if (dying && usesVanillaAi() && boss.isValid() && !boss.isDead()) {
            boss.setAI(true);
            boss.setGravity(true);
        }
        onCancel();
    }

    // ------------------------------------------------------------------ joueurs que le boss peut viser

    /** Un joueur que le boss peut viser : en ligne, en vie, ni spectateur, ni créatif, ni invisible (vanish). */
    public static boolean isFightable(Player player) {
        if (!player.isOnline() || player.isDead()) return false;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) return false;
        return !isVanished(player);
    }

    /** Méthode VanishAPI.isInvisible(Player) de PremiumVanish, résolue une seule fois (nulle sans PremiumVanish). */
    private static java.lang.reflect.Method premiumVanishCheck;
    private static boolean premiumVanishResolved;

    /**
     * Un joueur invisible : PremiumVanish, SuperVanish et EssentialsX posent la métadonnée « vanished » ; PremiumVanish
     * répond aussi par son API (interrogée par réflexion, le plugin n'en dépend pas).
     */
    public static boolean isVanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) return true;
        }
        if (!premiumVanishResolved) {
            premiumVanishResolved = true;
            try {
                premiumVanishCheck = Class.forName("de.myzelyam.api.vanish.VanishAPI").getMethod("isInvisible", Player.class);
            } catch (ReflectiveOperationException ignored) {
                // Pas de PremiumVanish : seule la métadonnée compte
            }
        }
        if (premiumVanishCheck == null) return false;
        try {
            return Boolean.TRUE.equals(premiumVanishCheck.invoke(null, player));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Joueurs de l'île que le boss peut viser, dans un rayon autour de lui. */
    protected List<Player> playersWithin(double radius) {
        Location origin = boss.getLocation();
        double radiusSquared = radius * radius;
        return boss.getWorld().getNearbyPlayers(origin, radius,
                p -> isFightable(p) && island.isInside(p.getLocation()))
            .stream()
            .filter(p -> p.getLocation().distanceSquared(origin) <= radiusSquared)
            .toList();
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
            .filter(p -> p != null && isFightable(p) && p.getWorld().equals(bossWorld) && island.isInside(p.getLocation()))
            .collect(Collectors.toList());
        Player validExternalTarget = (externalTarget != null && isFightable(externalTarget) && externalTarget.getWorld().equals(bossWorld)) ? externalTarget : null;
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

    /** Les joueurs qui suivent le combat : membres de l'île en ligne, plus l'agresseur extérieur. */
    public Set<Player> audience() {
        return viewers();
    }

    protected void updateBossBar() {
        double currentHealth = boss.getHealth();
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, currentHealth / maxHealth)));
        bossBar.setTitle(dying ? dyingBarTitle() : barTitle());
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

    /** Titre de la barre de boss pendant la mort (messages.yml, bosses.&lt;boss&gt;.bossbar-dying-title). */
    private String dyingBarTitle() {
        return plugin.getMessages().format("bosses." + type().id() + ".bossbar-dying-title",
            "name", configManager.bossSettings(type()).name());
    }

    /** Envoie un message aux joueurs qui suivent le combat ; un message vide n'est pas envoyé. */
    protected void tellViewers(String message) {
        if (message == null || message.isEmpty()) return;
        for (Player viewer : viewers()) viewer.sendMessage(message);
    }

    // ------------------------------------------------------------------ dégâts : contribution, plafond, coups donnés aux joueurs

    /** Le joueur à l'origine d'un coup (directement ou par un projectile), sinon nul. */
    public static Player attackerOf(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return null;
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) return player;
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
    }

    /** Retient un coup accepté : les PV réellement retirés au boss sont comptés à son auteur. */
    public void recordDamage(EntityDamageEvent event) {
        Player attacker = attackerOf(event);
        if (attacker == null) return;
        addContribution(attacker.getUniqueId(), Math.min(event.getFinalDamage(), boss.getHealth()));
    }

    /** Compte {@code amount} PV du boss à la contribution du joueur (un cœur brisé, un coup...). */
    public void addContribution(UUID player, double amount) {
        if (amount > 0.0) contributions.merge(player, amount, Double::sum);
    }

    /** Contribution de chaque joueur depuis le début du combat, en PV du boss. */
    public Map<UUID, Double> contributions() {
        return Map.copyOf(contributions);
    }

    public double maxHealth() {
        return maxHealth;
    }

    /**
     * Ramène un coup à {@code maxHitPercent} % des PV max du boss au plus, puis le multiplie par {@code bonus}. Chaque
     * modificateur (base, armure, résistance...) est mis à l'échelle : {@code setDamage(double)} ne recalcule pas l'armure
     * proportionnellement et laisserait un total négatif, c'est-à-dire un soin.
     */
    protected void limitHit(EntityDamageEvent event, double maxHitPercent, double bonus) {
        double finalDamage = event.getFinalDamage();
        if (finalDamage <= 0.0) return;
        double cap = maxHitPercent > 0.0 ? maxHealth * maxHitPercent / 100.0 : Double.MAX_VALUE;
        double wanted = Math.min(finalDamage, cap) * bonus;
        if (Math.abs(wanted - finalDamage) < 1.0E-9) return;
        double scale = wanted / finalDamage;
        for (EntityDamageEvent.DamageModifier modifier : EntityDamageEvent.DamageModifier.values()) {
            if (event.isApplicable(modifier)) event.setDamage(modifier, event.getDamage(modifier) * scale);
        }
    }

    /**
     * Frappe un joueur : d'abord un coup normal (armure, protections, totem... s'appliquent), puis une part de ses PV max
     * retirée directement. L'équipement complet du serveur arrête presque tout coup normal ; cette part-là ne dépend ni de
     * l'armure ni des cœurs d'absorption, elle suit les PV du joueur. Si elle suffit à le tuer, elle passe par un vrai coup
     * (totem d'immortalité, mort et règles de l'île se déclenchent normalement).
     */
    protected void hurt(Player victim, double baseDamage, double truePercent) {
        if (baseDamage > 0.0) {
            dealingDamage = true;
            try {
                victim.damage(baseDamage, boss);
            } finally {
                dealingDamage = false;
            }
        }
        trueDamage(victim, truePercent);
    }

    protected void trueDamage(Player victim, double percent) {
        if (percent <= 0.0 || !victim.isOnline() || victim.isDead()) return;
        GameMode mode = victim.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return;
        AttributeInstance maxHealthAttribute = victim.getAttribute(Attribute.MAX_HEALTH);
        double amount = (maxHealthAttribute != null ? maxHealthAttribute.getValue() : 20.0) * percent / 100.0;
        double health = victim.getHealth();
        if (amount < health) {
            victim.setHealth(health - amount);
            return;
        }
        dealingDamage = true;
        try {
            victim.damage(1_000_000.0, boss);
        } finally {
            dealingDamage = false;
        }
    }

    // ------------------------------------------------------------------ la mort en plusieurs temps

    public final boolean isDying() {
        return dying;
    }

    /** Vrai pendant le coup de grâce du plugin : aucun filtre ne doit le toucher. */
    public final boolean isFinishing() {
        return finishing;
    }

    /** Vrai si le final de la mort a déjà éclaté (inutile de le rejouer à l'évènement de mort). */
    public final boolean finaleDone() {
        return finaleDone;
    }

    /** Le joueur qui a porté le coup fatal, s'il est en ligne. */
    public final Player deathKiller() {
        return deathKiller == null ? null : Bukkit.getPlayer(deathKiller);
    }

    /**
     * Un coup fatal est retenu : le boss meurt en plusieurs temps. Les coups venus du vide ou de {@code /kill} passent
     * toujours, comme quand la mort en scène est désactivée.
     *
     * @return vrai si le coup doit être annulé
     */
    public final boolean interceptLethal(EntityDamageEvent event) {
        if (finishing) return false;
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean forced = cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL;
        if (dying) return !forced;
        if (forced || !deathSettings().enabled()) return false;
        if (event.getFinalDamage() < boss.getHealth()) return false;
        Player killer = attackerOf(event);
        if (killer != null) addContribution(killer.getUniqueId(), boss.getHealth());
        beginDeath(killer);
        return true;
    }

    protected final DeathSettings deathSettings() {
        return configManager.bossSettings(type()).death();
    }

    private void beginDeath(Player killer) {
        dying = true;
        deathTick = 0;
        deathKiller = killer == null ? null : killer.getUniqueId();
        deathOrigin = boss.getLocation().clone();
        boss.setVelocity(new Vector(0, 0, 0));
        boss.setGravity(false);
        boss.setAI(false);
        onDeathStart();
        deathSettings().startSound().play(deathOrigin);
        updateBossBar();
    }

    private void tickDeath() {
        DeathSettings d = deathSettings();
        deathTick++;
        int duration = Math.max(1, d.durationTicks());
        double progress = Math.min(1.0, deathTick / (double) duration);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Il tremble de plus en plus fort, s'élève (ou s'affaisse) et tourne sur lui-même de plus en plus vite
        double shake = d.shake() * (0.3 + 0.7 * progress);
        Location at = deathOrigin.clone().add((random.nextDouble() - 0.5) * 2.0 * shake,
            d.lift() * progress * progress,
            (random.nextDouble() - 0.5) * 2.0 * shake);
        at.setYaw((float) (deathOrigin.getYaw() + d.spinDegrees() * deathTick * progress / 2.0));
        at.setPitch(deathOrigin.getPitch());
        boss.teleport(at);
        boss.setVelocity(new Vector(0, 0, 0));
        if (deathTick % 3 == 0) boss.playHurtAnimation(random.nextFloat() * 360.0f);

        if (d.pulseIntervalTicks() > 0 && deathTick % d.pulseIntervalTicks() == 0) {
            d.pulseSound().play(at, (float) (d.pulsePitchGrowth() * progress));
        }
        spiral(d.auraFx(), at.clone().add(0, boss.getHeight() / 2.0, 0), Math.max(0.8, boss.getWidth()) * (1.6 - 0.9 * progress), deathTick * 0.45);

        // La barre se vide pendant que le boss s'éteint
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, boss.getHealth() / maxHealth * (1.0 - progress))));

        if (deathTick >= duration) finishDeath();
    }

    /** Particules en hélice autour d'un point : {@code fx.count()} points répartis sur le cercle, qui tourne avec {@code phase}. */
    private static void spiral(Fx fx, Location center, double radius, double phase) {
        if (fx.particle() == null || fx.count() <= 0) return;
        for (int i = 0; i < fx.count(); i++) {
            double angle = phase + i * 2.0 * Math.PI / fx.count();
            double height = Math.sin(phase * 0.7 + i) * radius * 0.6;
            fx.spawn(center.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius), 1, 0.0, 0.0, 0.0);
        }
    }

    private void finishDeath() {
        Location center = boss.getLocation().add(0, boss.getHeight() / 2.0, 0);
        finaleDone = true;
        DeathEffects.finale(plugin, deathSettings(), center, viewers());
        Player killer = deathKiller();
        finishing = true;
        try {
            if (killer != null) boss.damage(1_000_000.0, killer);
            else boss.damage(1_000_000.0);
            // Un autre plugin a pu annuler le coup : la mort ne doit pas rester en suspens
            if (!boss.isDead()) boss.setHealth(0.0);
        } finally {
            finishing = false;
        }
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

    /** Réglages communs du boss (nom, récompenses, mort...). */
    protected BossSettings baseSettings() {
        return configManager.bossSettings(type());
    }

    /** Ligne du journal : %island%, %health% et %max% sont fournis ; {@code extra} = nom, valeur, nom, valeur... */
    protected String journal(String key, String island, String... extra) {
        String[] base = {"island", island, "health", String.format("%.1f", boss.getHealth()), "max", String.format("%.1f", maxHealth)};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return plugin.getMessages().journal(key, all);
    }

    protected String islandLabel() {
        return (island != null && island.getOwner() != null)
            ? plugin.getMessages().journal("island-of", "owner", island.getOwner().getName())
            : plugin.getMessages().journal("island-unknown");
    }

    // SSB2 2026 : getCenter prend une Dimension et non plus un World.Environment
    protected static Dimension dimension(World.Environment env) {
        for (Dimension d : Dimension.values()) {
            if (d.getEnvironment() == env) return d;
        }
        return Dimension.getByName("NORMAL");
    }
}
