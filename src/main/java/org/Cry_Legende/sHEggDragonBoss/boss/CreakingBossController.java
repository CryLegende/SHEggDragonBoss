package org.Cry_Legende.sHEggDragonBoss.boss;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.CreakingSettings;
import org.Cry_Legende.sHEggDragonBoss.config.Fx;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.HeartManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boss n°2 : le Creaking. L'IA vanilla reste active (poursuite, attaque, gel quand un joueur le regarde : rien de cela
 * n'est réimplémenté). Le plugin joue sur ce regard et ajoute :
 * <ul>
 *   <li><b>le Noir</b> : un éclair d'obscurité de moins d'une seconde, sans annonce, qui revient souvent. Il aveugle les joueurs
 *       proches (et cache le nom du boss) ; un joueur aveuglé ne le fige plus, alors le boss se téléporte derrière eux, à leur
 *       hauteur s'ils volent, et plante autant de coups que de combattants. Chaque coup tombe après un court délai (esquivable) ;</li>
 *   <li><b>le Sursaut</b> : le regard fige le boss, mais frappé sur place sans répit il finit par se retourner contre celui
 *       qui le frappe le plus : même téléportation et même frappe esquivable que le Noir ;</li>
 *   <li>des phases d'invulnérabilité à certains seuils de PV, protégées par des cœurs que les joueurs frappent à la main
 *       (titre, barre d'action, repères lumineux et boussole les guident) ;</li>
 *   <li>des dégâts réels (un pourcentage des PV max du joueur, que l'armure et l'absorption n'arrêtent pas) et un plafond
 *       de PV perdus par coup, pour que le combat compte face à un équipement complet ;</li>
 *   <li>l'onde de choc et la résine collante ;</li>
 *   <li>un garde-fou d'arène : le boss ne quitte jamais l'île et ne tombe jamais dans le vide.</li>
 * </ul>
 */
public class CreakingBossController extends BossController implements HeartOwner {

    private enum BlackoutState {
        IDLE,
        ACTIVE
    }

    /** Un coup du Noir déjà plantée : le boss est arrivé en {@code origin}, le coup tombe au tick {@code tick}. */
    private record PendingStrike(UUID victim, Location origin, long tick) {
    }

    private enum SursautState {
        IDLE,
        /** Le boss va se téléporter sur sa cible : craquement et avertissement. */
        WARNING,
        /** Le boss est arrivé : la frappe part à la fin du délai. */
        STRIKING
    }

    /** Ce qui rend une frappe annoncée dangereuse : portée d'esquive, dégâts normaux, part des PV max, recul. */
    private record StrikeProfile(double reach, double damage, double truePercent, double knockback) {
    }

    /** Flèches de la boussole des cœurs, de « droit devant » dans le sens des aiguilles d'une montre. */
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private CreakingSettings settings;
    private final HeartManager heartManager;
    private final Creaking creaking;

    /** Nombre de phases déjà déclenchées (0 au départ). */
    private int phasesStarted;
    /** Cœurs qu'il reste à détruire dans la phase en cours ; le boss est invulnérable tant que ce nombre est positif. */
    private int heartsLeft;
    /** Parmi les cœurs restants, ceux qui n'ont pas encore pu être posés (emplacement ou chunk indisponible). */
    private int heartsToPlace;
    private int placementRetries;

    private long nextShockwaveTick;
    private long nextResinTick;

    private BlackoutState blackoutState = BlackoutState.IDLE;
    private long nextBlackoutTick;
    /** Fin de la fenêtre du Noir en cours. */
    private long blackoutStateEnd;
    private long nextBlinkTick;
    /** Vrai quand le noir est levé (les coups déjà plantés tombent encore un instant). */
    private boolean blindnessLifted;
    /** Joueurs que le boss va viser pendant le Noir en cours, dans l'ordre. */
    private final List<UUID> blinkQueue = new ArrayList<>();
    /** Coups plantés par le Noir en cours, qui tomberont à leur tick. */
    private final List<PendingStrike> pendingStrikes = new ArrayList<>();
    /** Joueurs aveuglés par le Noir en cours. */
    private final Set<UUID> blinded = new HashSet<>();

    private SursautState sursautState = SursautState.IDLE;
    /** Fin de l'avertissement du Sursaut. */
    private long sursautWarningEnd;
    private long nextSursautTick;
    private long sursautStrikeTick;
    private UUID sursautTarget;
    /** Ticks passés sur place en recevant des coups ; le Sursaut part quand ils atteignent le seuil. */
    private int pinnedTicks;
    /** Où le boss se trouvait quand il s'est immobilisé : s'il s'en éloigne, il n'est plus « sur place ». */
    private Location pinnedAnchor;
    private long lastHitTick = Long.MIN_VALUE / 2;
    /** Dégâts reçus de chaque joueur depuis le dernier Sursaut : le plus gros contributeur devient la cible. */
    private final Map<UUID, Double> damageDealt = new ConcurrentHashMap<>();
    /** Vrai tant que le boss flotte, sans gravité, à la hauteur d'une proie qui vole, en attendant sa frappe. */
    private boolean hovering;
    private boolean nameHidden;

    private CreakingBossController(SHEggDragonBoss plugin, BossManager bossManager, Island island, Creaking creaking,
                                   double maxHealth, int phasesStarted, int heartsLeft) {
        super(plugin, bossManager, island, creaking, maxHealth,
            plugin.getConfigManager().creaking().name(),
            plugin.getConfigManager().creaking().bar().color(),
            plugin.getConfigManager().creaking().bar().style());
        this.settings = plugin.getConfigManager().creaking();
        this.heartManager = plugin.getHeartManager();
        this.creaking = creaking;
        this.phasesStarted = Math.max(0, Math.min(phasesStarted, settings.phases().size()));
        this.heartsLeft = Math.max(0, heartsLeft);
        this.heartsToPlace = this.heartsLeft;
        this.nextShockwaveTick = settings.shockwave().cooldownTicks();
        this.nextResinTick = settings.resin().cooldownTicks();
        this.nextBlackoutTick = settings.blackout().cooldownTicks();
        this.nextSursautTick = settings.sursaut().cooldownTicks();
        // Un boss repris après un redémarrage a pu être sauvegardé en plein Noir (nom caché) ou en plein vol stationnaire (sans gravité)
        creaking.setGravity(true);
        creaking.setAI(true);
        showName(true);
        persistState();
    }

    /** Fait apparaître un nouveau boss, ou réveille un boss dormant (avec sa phase et ses cœurs restants). */
    public static CreakingBossController spawn(SHEggDragonBoss plugin, BossManager bossManager, Island island, Location location,
                                               double maxHealth, double currentHealth, int phase, int heartsLeft, StartMode mode) {
        Creaking entity = createEntity(plugin.getConfigManager().creaking(), location, maxHealth, currentHealth);
        // Le serveur peut plafonner les PV maximum : c'est la valeur réellement appliquée qui compte pour la barre et la sauvegarde
        AttributeInstance appliedMax = entity.getAttribute(Attribute.MAX_HEALTH);
        double effectiveMax = appliedMax != null ? appliedMax.getBaseValue() : maxHealth;
        CreakingBossController controller = new CreakingBossController(plugin, bossManager, island, entity, effectiveMax, phase, heartsLeft);
        controller.start(mode);
        return controller;
    }

    /** Reprend un Creaking déjà présent dans le monde ; sa phase et ses cœurs restants sont lus sur l'entité. */
    public static CreakingBossController adopt(SHEggDragonBoss plugin, BossManager bossManager, Island island, Creaking existing, double maxHealth) {
        PersistentDataContainer container = existing.getPersistentDataContainer();
        int phase = container.getOrDefault(SHEggDragonBoss.CREAKING_PHASE_KEY, PersistentDataType.INTEGER, 0);
        int hearts = container.getOrDefault(SHEggDragonBoss.CREAKING_HEARTS_KEY, PersistentDataType.INTEGER, 0);
        CreakingBossController controller = new CreakingBossController(plugin, bossManager, island, existing, maxHealth, phase, hearts);
        controller.start(StartMode.RESTORED);
        return controller;
    }

    @Override
    public BossType type() {
        return BossType.CREAKING;
    }

    @Override
    public UUID bossId() {
        return boss.getUniqueId();
    }

    // ------------------------------------------------------------------ démarrage et arrêt

    @Override
    protected void onStart(StartMode mode) {
        String islandName = islandLabel();
        switch (mode) {
            case FIRST_SPAWN -> {
                Location at = boss.getLocation();
                settings.spawnFx().spawn(at.clone().add(0, boss.getHeight() / 2, 0));
                settings.spawnSound().play(at);
                plugin.getLogManager().log(journal("creaking-spawned", islandName));
                tellViewers(format(settings.texts().spawnHint()));
            }
            case WOKEN_FROM_DORMANT ->
                plugin.getLogManager().log(phaseJournal("creaking-woken", islandName));
            case RESTORED ->
                plugin.getLogManager().log(phaseJournal("creaking-restored", islandName));
        }
        // Boss repris en pleine phase d'invulnérabilité : ses cœurs avaient été retirés du monde, on les repose
        if (heartsToPlace > 0) tryPlaceHearts();
    }

    @Override
    protected void onCancel() {
        // Mort, perte de l'entité ou arrêt du plugin : aucun cœur ne doit rester dans le monde, aucun joueur ne reste aveuglé par le boss
        heartManager.removeAll(boss.getUniqueId());
        clearBlindness();
        // Le plugin peut s'arrêter en plein Noir ou en plein Sursaut : le boss ne doit rester ni sans nom ni sans gravité
        boss.setGravity(true);
        showName(true);
    }

    @Override
    protected boolean usesVanillaAi() {
        return true;
    }

    /** La mort commence : ni Noir ni Sursaut en cours, plus de cœurs, plus personne d'aveuglé. */
    @Override
    protected void onDeathStart() {
        blackoutState = BlackoutState.IDLE;
        blinkQueue.clear();
        pendingStrikes.clear();
        sursautState = SursautState.IDLE;
        hovering = false;
        clearBlindness();
        heartManager.removeAll(boss.getUniqueId());
        heartsLeft = 0;
        heartsToPlace = 0;
        showName(true);
    }

    @Override
    protected int savedPhase() {
        return phasesStarted;
    }

    @Override
    protected int savedHearts() {
        return heartsLeft;
    }

    // ------------------------------------------------------------------ tick

    @Override
    protected void onTick() {
        // Réglages relus à chaud (/sheggdragonboss reload) : un boss en cours applique les nouveaux réglages au tick suivant
        settings = plugin.getConfigManager().creaking();
        checkPhaseTrigger();
        tickBlackout();
        tickSursaut();
        if (tickCounter % settings.checkIntervalTicks() == 0) {
            enforceArena();
            processAttacks();
        }
        if (heartsLeft > 0) {
            if (tickCounter % settings.shieldAuraIntervalTicks() == 0) {
                settings.shieldAuraFx().spawn(boss.getLocation().add(0, boss.getHeight() / 2, 0),
                    settings.shieldAuraFx().count(), boss.getWidth() / 2, boss.getHeight() / 2, boss.getWidth() / 2);
            }
            heartMarkers();
            heartGuide();
        }
    }

    @Override
    protected void onSecond() {
        // La vitesse est relue à chaud comme les autres réglages
        setBase(creaking, Attribute.MOVEMENT_SPEED, settings.movementSpeed());
        // Aide à l'IA vanilla : un Creaking sans cœur lié peut rester « endormi » ; on l'éveille vers la cible la plus proche
        if (settings.assistActivation() && target != null && !creaking.isActive()) {
            creaking.activate(target);
        }
        maintainShield();
    }

    // ------------------------------------------------------------------ arène

    /** Hors de l'île ou trop bas (vide) : retour au centre de l'île. */
    private void enforceArena() {
        Location location = boss.getLocation();
        Location center = island.getCenter(dimension(location.getWorld().getEnvironment()));
        boolean outside = !island.isInside(location);
        boolean tooLow = center != null && location.getY() < center.getY() - settings.arena().maxDepthBelowCenter();
        if ((!outside && !tooLow) || center == null) return;
        Location destination = center.clone().add(0.5, settings.arena().recenterOffsetY(), 0.5);
        boss.teleport(destination);
        boss.setFallDistance(0.0f);
        boss.setVelocity(new Vector(0, 0, 0));
    }

    // ------------------------------------------------------------------ le Noir

    /**
     * Le Noir : un éclair d'obscurité, sans annonce. Les joueurs proches sont aveuglés moins d'une seconde ; le temps que
     * dure le noir, le boss se téléporte derrière eux et plante un coup par combattant ({@code hits-per-player} par joueur).
     * Rien ne se passe sans joueur à portée.
     */
    private void tickBlackout() {
        CreakingSettings.Blackout cfg = settings.blackout();
        if (!cfg.enabled()) {
            // Désactivé à chaud en plein Noir : on referme proprement
            if (blackoutState != BlackoutState.IDLE) endWindow();
            return;
        }
        switch (blackoutState) {
            case IDLE -> {
                // Le Noir attend la fin d'un Sursaut en cours : le boss ne mène qu'une téléportation à la fois
                if (tickCounter < nextBlackoutTick || tickCounter % settings.checkIntervalTicks() != 0
                    || sursautState != SursautState.IDLE) return;
                List<Player> victims = playersWithin(cfg.radius());
                if (!victims.isEmpty()) startWindow(cfg, victims);
            }
            case ACTIVE -> blackoutStep(cfg);
        }
    }

    private void startWindow(CreakingSettings.Blackout cfg, List<Player> victims) {
        blinded.clear();
        pendingStrikes.clear();
        blinkQueue.clear();
        blindnessLifted = false;
        // Un effet qui a moins d'une seconde à vivre s'estompe déjà : le noir est posé pour plus longtemps que la fenêtre
        // et levé à la main quand elle se termine
        int effectTicks = Math.max(20, cfg.durationTicks()) + 20;
        for (Player victim : victims) {
            for (CreakingSettings.BlindEffect effect : cfg.effects()) {
                victim.addPotionEffect(new PotionEffect(effect.type(), effectTicks, effect.amplifier(), false, false, true));
            }
            blinded.add(victim.getUniqueId());
        }
        // Même aveuglés, les joueurs verraient son nom à travers le noir : il disparaît le temps de la fenêtre
        if (cfg.hideName()) showName(false);

        // Autant de coups que de combattants (hits-per-player par joueur), chacun sur un joueur différent tant que c'est possible
        int hits = (int) Math.max(1, Math.min(cfg.maxHits(), Math.round(victims.size() * cfg.hitsPerPlayer())));
        List<UUID> order = new ArrayList<>(blinded);
        Collections.shuffle(order);
        for (int i = 0; i < hits; i++) blinkQueue.add(order.get(i % order.size()));

        blackoutState = BlackoutState.ACTIVE;
        blackoutStateEnd = tickCounter + cfg.durationTicks();
        nextBlinkTick = tickCounter + cfg.firstBlinkDelayTicks();
    }

    private void blackoutStep(CreakingSettings.Blackout cfg) {
        // Le noir se lève quand la fenêtre est écoulée et que le boss a fini de se téléporter
        if (!blindnessLifted && tickCounter >= blackoutStateEnd && blinkQueue.isEmpty()) liftBlindness();
        if (!blinkQueue.isEmpty() && tickCounter >= nextBlinkTick) blink(cfg);
        resolveDueStrikes(cfg);
        if (blinkQueue.isEmpty() && pendingStrikes.isEmpty() && tickCounter >= blackoutStateEnd) endWindow();
    }

    /** Le noir se lève : plus personne n'est aveuglé, le boss retrouve son nom. Les frappes déjà plantées tombent quand même. */
    private void liftBlindness() {
        blindnessLifted = true;
        clearBlindness();
        if (nameHidden) showName(true);
    }

    private void endWindow() {
        if (!blindnessLifted) liftBlindness();
        blackoutState = BlackoutState.IDLE;
        pendingStrikes.clear();
        blinkQueue.clear();
        nextBlackoutTick = tickCounter + blackoutCooldown(settings.blackout());
        land();
    }

    /** Délai avant le prochain Noir : raccourci à chaque phase déclenchée, et tiré au hasard autour de sa valeur (pas de rythme à apprendre). */
    private long blackoutCooldown(CreakingSettings.Blackout cfg) {
        double base = cfg.cooldownTicks() * Math.pow(cfg.phaseCooldownFactor(), phasesStarted);
        long jitter = cfg.cooldownJitterTicks() > 0
            ? ThreadLocalRandom.current().nextLong(-cfg.cooldownJitterTicks(), cfg.cooldownJitterTicks() + 1L) : 0L;
        return Math.max(20L, Math.round(base) + jitter);
    }

    /** Un joueur aveuglé qui peut encore être pris pour cible : en vie, sur l'île, et toujours dans le noir (lait, soin...). */
    private boolean isBlindTarget(Player player, CreakingSettings.Blackout cfg) {
        return isTargetable(player) && (cfg.effects().isEmpty() || player.hasPotionEffect(cfg.effects().get(0).type()));
    }

    /** Un joueur que le boss peut viser : en ligne, en vie, ni spectateur, ni créatif, ni invisible (vanish), dans le monde du boss et sur l'île. */
    private boolean isTargetable(Player player) {
        return isFightable(player) && player.getWorld().equals(boss.getWorld()) && island.isInside(player.getLocation());
    }

    /** Le boss se téléporte derrière le joueur suivant de la file et y plante un coup, qui tombera {@code strike-delay-ticks} plus tard. */
    private void blink(CreakingSettings.Blackout cfg) {
        UUID id = blinkQueue.remove(0);
        nextBlinkTick = tickCounter + cfg.blinkIntervalTicks();
        Player victim = Bukkit.getPlayer(id);
        // Un joueur qui a quitté l'île, est mort ou a retiré son aveuglement (lait...) perd ce coup
        if (victim == null || !isBlindTarget(victim, cfg)) return;
        // Le craquement à l'arrivée est le seul indice : le joueur est aveugle
        Location origin = blinkTo(victim, cfg.blinkDistance(), cfg);
        if (origin == null) return;
        pendingStrikes.add(new PendingStrike(id, origin, tickCounter + cfg.strikeDelayTicks()));
    }

    private void resolveDueStrikes(CreakingSettings.Blackout cfg) {
        if (pendingStrikes.isEmpty()) return;
        StrikeProfile profile = new StrikeProfile(cfg.strikeReach(), cfg.strikeDamage(), cfg.strikeTruePercent(), cfg.strikeKnockback());
        Iterator<PendingStrike> due = pendingStrikes.iterator();
        while (due.hasNext()) {
            PendingStrike pending = due.next();
            if (tickCounter < pending.tick()) continue;
            due.remove();
            strike(pending.victim(), pending.origin(), cfg, profile);
        }
    }

    /**
     * Téléporte le boss sur sa proie (effets et craquement positionnel compris). Une proie en l'air n'a pas de sol : le boss
     * la rejoint à sa hauteur, sans gravité, jusqu'à sa frappe.
     *
     * @return l'endroit où le boss est arrivé, ou nul si aucun emplacement libre n'a été trouvé autour de la proie
     */
    private Location blinkTo(Player victim, double distance, CreakingSettings.Blackout fx) {
        Location spot = blinkSpot(victim, distance);
        if (spot == null) return null;
        fx.blinkFx().spawn(boss.getLocation().add(0, boss.getHeight() / 2, 0));
        boss.teleport(spot);
        boss.setVelocity(new Vector(0, 0, 0));
        boss.setFallDistance(0.0f);
        boolean supported = spot.clone().subtract(0, 1, 0).getBlock().getType().isSolid();
        hovering = !supported;
        boss.setGravity(supported);
        fx.blinkFx().spawn(spot.clone().add(0, boss.getHeight() / 2, 0));
        fx.blinkSound().play(spot);
        return spot;
    }

    /** Fin du vol stationnaire : le boss retrouve sa gravité et redescend au sol sous lui. */
    private void land() {
        if (!hovering) return;
        hovering = false;
        boss.setGravity(true);
        boss.setFallDistance(0.0f);
        Location ground = groundBelow(boss.getLocation());
        if (ground != null) boss.teleport(ground);
        boss.setVelocity(new Vector(0, 0, 0));
    }

    /** Le premier sol où le boss tient debout sous {@code from}, ou le centre de l'île s'il n'y en a pas. */
    private Location groundBelow(Location from) {
        World world = from.getWorld();
        Location center = island.getCenter(dimension(world.getEnvironment()));
        int bottom = world.getMinHeight();
        if (center != null) bottom = Math.max(bottom, (int) Math.floor(center.getY() - settings.arena().maxDepthBelowCenter()));
        int blockX = from.getBlockX();
        int blockZ = from.getBlockZ();
        int height = (int) Math.ceil(boss.getHeight());
        if (world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            for (int feet = from.getBlockY(); feet > bottom; feet--) {
                if (stands(world, blockX, feet, blockZ, height)) {
                    return new Location(world, blockX + 0.5, feet, blockZ + 0.5, from.getYaw(), from.getPitch());
                }
            }
        }
        return center == null ? null : center.clone().add(0.5, settings.arena().recenterOffsetY(), 0.5);
    }

    /** Remplace le nom du boss (ou le rend) : sans nom du tout, même le regard posé sur lui ne l'affiche pas. */
    private void showName(boolean shown) {
        nameHidden = !shown;
        boss.setCustomName(shown ? settings.name() : null);
        boss.setCustomNameVisible(shown);
    }

    /** Un joueur est « en l'air » (vol, élytres, grand saut) quand rien de solide ne se trouve sous ses pieds sur airborne-clearance blocs. */
    private boolean isAirborne(Player player) {
        if (player.isGliding()) return true;
        Location at = player.getLocation();
        World world = at.getWorld();
        int blockX = at.getBlockX();
        int blockZ = at.getBlockZ();
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return false;
        for (int i = 1; i <= settings.arena().airborneClearance(); i++) {
            if (world.getBlockAt(blockX, at.getBlockY() - i, blockZ).getType().isSolid()) return false;
        }
        return true;
    }

    /** Le point de chute du boss : à {@code distance} blocs du joueur, derrière lui d'abord, puis sur les côtés, enfin devant. */
    private Location blinkSpot(Player victim, double distance) {
        Location at = victim.getLocation();
        World world = at.getWorld();
        boolean airborne = isAirborne(victim);
        Vector facing = at.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-4) facing = new Vector(0, 0, 1);
        facing.normalize();
        for (double degrees : new double[]{180, 150, -150, 110, -110, 70, -70, 0}) {
            Vector offset = facing.clone().rotateAroundY(Math.toRadians(degrees)).multiply(distance);
            double x = at.getX() + offset.getX();
            double z = at.getZ() + offset.getZ();
            Location ground = airborne ? floating(world, x, at.getY(), z) : standable(world, x, at.getY(), z);
            if (ground == null || !island.isInside(ground)) continue;
            // Il fait face à sa proie
            ground.setDirection(at.toVector().subtract(ground.toVector()));
            return ground;
        }
        return null;
    }

    /** Un emplacement où le boss tient debout (sol solide dessous, assez de blocs libres au-dessus), à ±2 blocs de la hauteur donnée. */
    private Location standable(World world, double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return null;
        int baseY = (int) Math.floor(y);
        int height = (int) Math.ceil(boss.getHeight());
        for (int offset : new int[]{0, 1, -1, 2, -2}) {
            int feet = baseY + offset;
            if (stands(world, blockX, feet, blockZ, height)) return new Location(world, blockX + 0.5, feet, blockZ + 0.5);
        }
        return null;
    }

    /** Vrai si le boss tient debout les pieds à {@code feet} : sol solide dessous, assez de blocs libres au-dessus. */
    private boolean stands(World world, int blockX, int feet, int blockZ, int height) {
        if (feet <= world.getMinHeight() || feet + height >= world.getMaxHeight()) return false;
        if (!world.getBlockAt(blockX, feet - 1, blockZ).getType().isSolid()) return false;
        return columnFree(world, blockX, feet, blockZ, height);
    }

    private boolean columnFree(World world, int blockX, int feet, int blockZ, int height) {
        for (int i = 0; i < height; i++) {
            Block block = world.getBlockAt(blockX, feet + i, blockZ);
            if (!block.isPassable() || block.isLiquid()) return false;
        }
        return true;
    }

    /** Un emplacement en plein air, à la hauteur donnée, assez libre pour le boss (proie qui vole). */
    private Location floating(World world, double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return null;
        int feet = (int) Math.floor(y);
        int height = (int) Math.ceil(boss.getHeight());
        if (feet <= world.getMinHeight() || feet + height >= world.getMaxHeight()) return null;
        return columnFree(world, blockX, feet, blockZ, height) ? new Location(world, blockX + 0.5, feet, blockZ + 0.5) : null;
    }

    /**
     * Une frappe annoncée (Noir ou Sursaut) : elle porte si le joueur est resté à portée de l'endroit d'où elle part
     * ({@code origin}, là où le boss est arrivé), sinon il l'a esquivée. Les sons et particules sont ceux du Noir.
     */
    private void strike(UUID id, Location origin, CreakingSettings.Blackout fx, StrikeProfile profile) {
        Player victim = id == null ? null : Bukkit.getPlayer(id);
        if (victim == null || !isFightable(victim) || !victim.getWorld().equals(origin.getWorld())) return;

        if (victim.getLocation().distanceSquared(origin) > profile.reach() * profile.reach()) {
            fx.dodgeSound().playFor(victim);
            PlayerText.actionBar(victim, settings.texts().dodge());
            return;
        }
        fx.strikeSound().play(origin);
        fx.strikeFx().spawn(victim.getLocation().add(0, 1.0, 0));
        hurt(victim, profile.damage(), profile.truePercent());
        Vector push = victim.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if (!victim.isDead() && push.lengthSquared() > 1.0E-4) {
            victim.setVelocity(push.normalize().multiply(profile.knockback()).setY(profile.knockback() * 0.4));
        }
    }

    /** Retire l'aveuglement du Noir aux joueurs qui le portent encore (mort ou disparition du boss en pleine fenêtre). */
    private void clearBlindness() {
        for (UUID id : blinded) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            for (CreakingSettings.BlindEffect effect : settings.blackout().effects()) player.removePotionEffect(effect.type());
        }
        blinded.clear();
    }

    // ------------------------------------------------------------------ dégâts infligés aux joueurs

    /**
     * Coup de mêlée vanille du boss sur un joueur (appelé depuis l'événement de dégâts) : le bonus de dégâts réels part
     * au tick suivant, pour ne pas imbriquer deux dégâts dans le même événement.
     */
    public void onMeleeHit(Player victim) {
        if (dealingDamage || settings.meleeTruePercent() <= 0.0) return;
        Bukkit.getScheduler().runTask(plugin, () -> trueDamage(victim, settings.meleeTruePercent()));
    }

    // ------------------------------------------------------------------ le Sursaut

    /** Retient un coup accepté : il alimente le compteur du Sursaut et désigne celui qui frappe le plus. */
    private void recordHit(EntityDamageEvent event) {
        lastHitTick = tickCounter;
        Player attacker = attackerOf(event);
        if (attacker != null) damageDealt.merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    private void tickSursaut() {
        CreakingSettings.Sursaut cfg = settings.sursaut();
        if (!cfg.enabled()) {
            // Désactivé à chaud en plein Sursaut : on referme proprement
            if (sursautState != SursautState.IDLE) endSursaut(cfg);
            return;
        }
        switch (sursautState) {
            case IDLE -> trackPinned(cfg);
            case WARNING -> {
                if (tickCounter >= sursautWarningEnd) {
                    sursautBlink(cfg);
                } else if (tickCounter % 4 == 0) {
                    settings.blackout().blinkFx().spawn(boss.getLocation().add(0, boss.getHeight() / 2, 0));
                }
            }
            case STRIKING -> {
                if (tickCounter >= sursautStrikeTick) {
                    strike(sursautTarget, boss.getLocation(), settings.blackout(),
                        new StrikeProfile(cfg.strikeReach(), cfg.strikeDamage(), cfg.strikeTruePercent(), cfg.strikeKnockback()));
                    endSursaut(cfg);
                }
            }
        }
    }

    /**
     * Compte le temps passé sur place en recevant des coups. Le compteur monte tant que le boss est frappé sans bouger, redescend
     * quand les coups cessent et repart de zéro dès qu'il s'éloigne. Rien ne compte pendant le Noir ni pendant une phase.
     */
    private void trackPinned(CreakingSettings.Sursaut cfg) {
        int step = settings.checkIntervalTicks();
        if (tickCounter % step != 0) return;
        if (tickCounter - lastHitTick > 200) damageDealt.clear();
        if (blackoutState != BlackoutState.IDLE || isShielded()) {
            pinnedTicks = 0;
            pinnedAnchor = null;
            return;
        }
        Location here = boss.getLocation();
        if (pinnedAnchor == null || !here.getWorld().equals(pinnedAnchor.getWorld())
            || here.distanceSquared(pinnedAnchor) > cfg.stillDistance() * cfg.stillDistance()) {
            pinnedAnchor = here;
            pinnedTicks = 0;
            return;
        }
        boolean beingHit = tickCounter - lastHitTick <= cfg.hitWindowTicks();
        pinnedTicks = beingHit ? pinnedTicks + step : Math.max(0, pinnedTicks - step);
        if (pinnedTicks >= cfg.pinnedTicks() && tickCounter >= nextSursautTick) startSursaut(cfg);
    }

    private void startSursaut(CreakingSettings.Sursaut cfg) {
        Player victim = sursautVictim(cfg);
        if (victim == null) {
            pinnedTicks = 0;
            return;
        }
        sursautState = SursautState.WARNING;
        sursautWarningEnd = tickCounter + cfg.warningTicks();
        sursautTarget = victim.getUniqueId();
        cfg.warningSound().play(boss.getLocation());
        cfg.warningSound().playFor(victim);
        PlayerText.actionBar(victim, format(settings.texts().sursautWarning()));
        plugin.getLogManager().log(journal("creaking-sursaut", islandLabel(), "player", victim.getName(),
            "seconds", String.valueOf(pinnedTicks / 20)));
    }

    /** Celui qui a le plus frappé le boss depuis le dernier Sursaut, sinon le joueur le plus proche. */
    private Player sursautVictim(CreakingSettings.Sursaut cfg) {
        Location origin = boss.getLocation();
        double radiusSquared = cfg.radius() * cfg.radius();
        Player best = null;
        double bestDamage = -1.0;
        for (Map.Entry<UUID, Double> entry : damageDealt.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !isTargetable(player) || player.getLocation().distanceSquared(origin) > radiusSquared) continue;
            if (entry.getValue() > bestDamage) {
                best = player;
                bestDamage = entry.getValue();
            }
        }
        if (best != null) return best;
        return playersWithin(cfg.radius()).stream()
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(origin)))
            .orElse(null);
    }

    private void sursautBlink(CreakingSettings.Sursaut cfg) {
        Player victim = sursautTarget == null ? null : Bukkit.getPlayer(sursautTarget);
        if (victim == null || !isTargetable(victim) || blinkTo(victim, cfg.blinkDistance(), settings.blackout()) == null) {
            endSursaut(cfg);
            return;
        }
        sursautState = SursautState.STRIKING;
        sursautStrikeTick = tickCounter + cfg.strikeDelayTicks();
    }

    private void endSursaut(CreakingSettings.Sursaut cfg) {
        sursautState = SursautState.IDLE;
        sursautTarget = null;
        pinnedTicks = 0;
        pinnedAnchor = null;
        damageDealt.clear();
        nextSursautTick = tickCounter + cfg.cooldownTicks();
        land();
    }

    // ------------------------------------------------------------------ phases d'invulnérabilité

    private boolean isShielded() {
        return heartsLeft > 0;
    }

    private void checkPhaseTrigger() {
        if (isShielded() || phasesStarted >= settings.phases().size()) return;
        CreakingSettings.Phase next = settings.phases().get(phasesStarted);
        if (boss.getHealth() <= maxHealth * next.healthPercent() / 100.0) startPhase();
    }

    private void startPhase() {
        CreakingSettings.Phase phase = settings.phases().get(phasesStarted);
        phasesStarted++;
        // Plus il y a de combattants, plus il y a de cœurs à briser (au plus le double de ceux de la phase)
        int fighters = playersWithin(settings.hearts().guideRange()).size();
        int extra = (int) Math.round(Math.max(0, fighters - 1) * settings.heartsPerExtraPlayer());
        heartsLeft = phase.hearts() + Math.min(extra, phase.hearts());
        heartsToPlace = heartsLeft;
        placementRetries = 0;
        tryPlaceHearts();
        persistState();
        // L'onde de choc accélère dès le début de la phase, sans attendre la fin du délai normal
        nextShockwaveTick = Math.min(nextShockwaveTick, tickCounter + shockwaveCooldown(true));
        // Le temps de lire le titre et de repérer les cœurs avant que le Noir ne tombe
        if (blackoutState == BlackoutState.IDLE) {
            nextBlackoutTick = Math.max(nextBlackoutTick, tickCounter + settings.blackout().afterPhaseDelayTicks());
        }

        plugin.getLogManager().log(phaseJournal("creaking-phase-start", islandLabel()));
        settings.shieldStartSound().play(boss.getLocation());
        tellViewers(format(settings.texts().shieldStart()));
        for (Player viewer : viewers()) {
            PlayerText.title(viewer, format(settings.texts().shieldTitle()), format(settings.texts().shieldSubtitle()));
        }
        updateBossBar();
    }

    /** Pose les cœurs qui manquent ; ceux qu'on n'arrive pas à poser seront retentés chaque seconde. */
    private void tryPlaceHearts() {
        if (heartsToPlace <= 0) return;
        int placed = heartManager.place(this, island, boss.getLocation(), heartsToPlace, settings.hearts());
        heartsToPlace -= placed;
    }

    /** Vérifie chaque seconde que la phase en cours tient toujours (cœurs retirés par un admin, emplacements à retenter). */
    private void maintainShield() {
        if (!isShielded()) return;
        int alive = heartManager.countAlive(boss.getUniqueId());
        int expected = heartsLeft - heartsToPlace;
        if (alive < expected) {
            // Cœurs disparus sans avoir été brisés (retirés par un administrateur) : ils ne comptent plus
            heartsLeft -= expected - alive;
            persistState();
        }
        if (heartsToPlace > 0 && heartsLeft > 0) {
            if (placementRetries++ < settings.hearts().placementRetries()) {
                tryPlaceHearts();
            } else {
                plugin.getLogger().warning("Creaking : " + heartsToPlace + " cœur(s) n'ont pas pu être posés sur " + islandLabel() + " ; ils ne comptent pas dans la phase.");
                heartsLeft -= heartsToPlace;
                heartsToPlace = 0;
                persistState();
            }
        }
        if (heartsLeft <= 0) endPhase();
    }

    @Override
    public void onHeartDestroyed(Player player) {
        // Un cœur brisé compte comme des dégâts infligés au boss : pendant une phase on ne peut pas le blesser autrement
        addContribution(player.getUniqueId(), maxHealth * settings.hearts().creditPercent() / 100.0);
        if (heartsLeft > 0) heartsLeft--;
        persistState();
        if (heartsLeft > 0) {
            tellViewers(format(settings.texts().heartDestroyed()).replace("%player%", player.getName()));
            updateBossBar();
        } else {
            endPhase();
        }
    }

    private void endPhase() {
        heartsLeft = 0;
        heartsToPlace = 0;
        persistState();
        plugin.getLogManager().log(phaseJournal("creaking-phase-end", islandLabel()));
        settings.shieldEndSound().play(boss.getLocation());
        tellViewers(format(settings.texts().shieldEnd()));
        updateBossBar();
    }

    private String format(String message) {
        return message
            .replace("%hearts%", String.valueOf(heartsLeft))
            .replace("%phase%", String.valueOf(phasesStarted))
            .replace("%phases%", String.valueOf(settings.phases().size()))
            .replace("%name%", settings.name());
    }

    /** Ligne du journal avec la phase en cours : %phase%, %phases% et %hearts%. */
    private String phaseJournal(String key, String island) {
        return journal(key, island, "phase", String.valueOf(phasesStarted), "phases", String.valueOf(settings.phases().size()),
            "hearts", String.valueOf(heartsLeft));
    }

    /** Écrit la phase et les cœurs restants sur l'entité : un redémarrage reprend le combat là où il en était. */
    private void persistState() {
        PersistentDataContainer container = boss.getPersistentDataContainer();
        container.set(SHEggDragonBoss.CREAKING_PHASE_KEY, PersistentDataType.INTEGER, phasesStarted);
        container.set(SHEggDragonBoss.CREAKING_HEARTS_KEY, PersistentDataType.INTEGER, heartsLeft);
    }

    // ------------------------------------------------------------------ repères des cœurs

    /** Colonne de particules au-dessus de chaque cœur : on les voit de loin, même derrière le boss. */
    private void heartMarkers() {
        CreakingSettings.Hearts cfg = settings.hearts();
        Fx fx = cfg.markerFx();
        if (fx.particle() == null || fx.count() <= 0 || tickCounter % cfg.markerIntervalTicks() != 0) return;
        for (Location heart : heartManager.locations(boss.getUniqueId())) {
            World world = heart.getWorld();
            for (int i = 0; i < fx.count(); i++) {
                double y = heart.getY() + 0.6 + cfg.markerHeight() * i / Math.max(1, fx.count() - 1);
                world.spawnParticle(fx.particle(), heart.getX(), y, heart.getZ(), 1, 0.0, 0.0, 0.0, fx.speed());
            }
        }
    }

    /** Boussole : distance et direction du cœur le plus proche, dans la barre d'action (lisible même dans le noir). */
    private void heartGuide() {
        CreakingSettings.Hearts cfg = settings.hearts();
        if (cfg.guideActionbar().isEmpty() || tickCounter % cfg.guideIntervalTicks() != 0) return;
        List<Location> hearts = heartManager.locations(boss.getUniqueId());
        if (hearts.isEmpty()) return;
        for (Player viewer : playersWithin(cfg.guideRange())) {
            Location from = viewer.getLocation();
            Location nearest = null;
            double best = Double.MAX_VALUE;
            for (Location heart : hearts) {
                if (!from.getWorld().equals(heart.getWorld())) continue;
                double distance = heart.distanceSquared(from);
                if (distance < best) {
                    best = distance;
                    nearest = heart;
                }
            }
            if (nearest == null) continue;
            // Cap absolu vers le cœur (yaw Minecraft : 0 = sud, 90 = ouest), puis écart avec le regard du joueur
            double targetYaw = Math.toDegrees(Math.atan2(-(nearest.getX() - from.getX()), nearest.getZ() - from.getZ()));
            double relative = ((targetYaw - from.getYaw()) % 360.0 + 540.0) % 360.0 - 180.0;
            String arrow = ARROWS[Math.floorMod((int) Math.round(relative / 45.0), 8)];
            PlayerText.actionBar(viewer, format(cfg.guideActionbar())
                .replace("%distance%", String.valueOf((int) Math.round(Math.sqrt(best))))
                .replace("%arrow%", arrow));
        }
    }

    // ------------------------------------------------------------------ dégâts reçus

    /**
     * Filtre un coup reçu par le boss (appelé avant les autres effets, sur le thread principal).
     * Pendant une phase, tout est annulé. Aucun coup ne retire plus de {@code max-hit-percent} % des PV max : un équipement
     * complet ne peut pas faire sauter le combat d'un seul coup. Un coup qui ferait franchir le prochain seuil de PV est
     * ramené pile au seuil pour que la phase démarre au lieu que le boss traverse ses phases ou meure d'un coup.
     *
     * @return vrai si le coup doit être annulé
     */
    @Override
    public boolean absorbDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        // Le vide et /kill passent toujours : un boss ne doit jamais devenir impossible à retirer
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return false;

        if (isShielded()) {
            settings.shieldBlockedSound().play(boss.getLocation());
            return true;
        }

        limitHit(event, settings.maxHitPercent(), 1.0);

        if (phasesStarted < settings.phases().size()) {
            double thresholdHealth = maxHealth * settings.phases().get(phasesStarted).healthPercent() / 100.0;
            double health = boss.getHealth();
            if (health > thresholdHealth && health - event.getFinalDamage() <= thresholdHealth) {
                boss.setHealth(thresholdHealth);
                startPhase();
                settings.hurtSound().play(boss.getLocation());
                return true;
            }
        }
        recordHit(event);
        return false;
    }

    // ------------------------------------------------------------------ attaques

    private void processAttacks() {
        boolean shielded = isShielded();

        CreakingSettings.Shockwave shockwave = settings.shockwave();
        if (shockwave.enabled() && tickCounter >= nextShockwaveTick && shockwave(shockwave)) {
            nextShockwaveTick = tickCounter + shockwaveCooldown(shielded);
        }

        CreakingSettings.Resin resin = settings.resin();
        if (resin.enabled() && (!shielded || resin.duringShield()) && tickCounter >= nextResinTick && resin(resin)) {
            nextResinTick = tickCounter + resin.cooldownTicks();
        }
    }

    /** Délai entre deux ondes de choc ; pendant une phase d'invulnérabilité, il est divisé par le facteur de fréquence. */
    private long shockwaveCooldown(boolean shielded) {
        CreakingSettings.Shockwave shockwave = settings.shockwave();
        if (!shielded) return shockwave.cooldownTicks();
        return Math.max(1L, Math.round(shockwave.cooldownTicks() / shockwave.shieldFrequencyFactor()));
    }

    /** Onde de choc : dégâts et recul pour les joueurs proches. Renvoie faux (sans consommer le délai) si personne n'est à portée. */
    private boolean shockwave(CreakingSettings.Shockwave config) {
        List<Player> victims = playersWithin(config.radius());
        if (victims.isEmpty()) return false;
        Location origin = boss.getLocation();
        for (Player victim : victims) {
            hurt(victim, config.damage(), config.truePercent());
            Vector push = victim.getLocation().toVector().subtract(origin.toVector());
            push.setY(0);
            if (push.lengthSquared() < 1.0E-4) {
                // Pile sur le boss : une direction au hasard plutôt qu'un vecteur nul
                double angle = Math.random() * Math.PI * 2.0;
                push = new Vector(Math.cos(angle), 0, Math.sin(angle));
            }
            push.normalize().multiply(config.knockbackHorizontal()).setY(config.knockbackVertical());
            victim.setVelocity(push);
        }
        config.sound().play(origin);
        // Anneau de particules à la limite de l'onde, au niveau du sol du boss
        int points = config.ringFx().count();
        if (config.ringFx().particle() != null && points > 0) {
            World world = origin.getWorld();
            for (int i = 0; i < points; i++) {
                double angle = 2.0 * Math.PI * i / points;
                Location ring = origin.clone().add(Math.cos(angle) * config.radius(), 0.2, Math.sin(angle) * config.radius());
                world.spawnParticle(config.ringFx().particle(), ring, 1, 0.0, 0.0, 0.0, config.ringFx().speed());
            }
        }
        return true;
    }

    /** Résine collante : effets de potion pour les joueurs proches. Renvoie faux si personne n'est à portée. */
    private boolean resin(CreakingSettings.Resin config) {
        List<Player> victims = playersWithin(config.radius());
        if (victims.isEmpty()) return false;
        for (Player victim : victims) {
            for (CreakingSettings.Effect effect : config.effects()) {
                victim.addPotionEffect(new PotionEffect(effect.type(), effect.durationTicks(), effect.amplifier()));
            }
            config.fx().spawn(victim.getLocation().add(0, 1.0, 0));
        }
        config.sound().play(boss.getLocation());
        return true;
    }

    // ------------------------------------------------------------------ barre de boss

    @Override
    protected String barTitle() {
        String template = isShielded() ? settings.bar().shieldTitle() : settings.bar().title();
        return format(template)
            .replace("%current_health%", String.valueOf((int) Math.round(boss.getHealth())))
            .replace("%max_health%", String.valueOf((int) Math.round(maxHealth)));
    }

    @Override
    protected BarColor barColor() {
        return isShielded() ? settings.bar().shieldColor() : settings.bar().color();
    }

    @Override
    protected BarStyle barStyle() {
        return isShielded() ? settings.bar().shieldStyle() : settings.bar().style();
    }

    // ------------------------------------------------------------------ création de l'entité

    private static Creaking createEntity(CreakingSettings settings, Location location, double maxHealth, double currentHealth) {
        // Un Creaking créé par le plugin n'a aucun cœur vanilla lié ; son IA vanilla reste active
        Creaking spawned = (Creaking) location.getWorld().spawnEntity(location, EntityType.CREAKING);
        spawned.setSilent(false);
        spawned.setCustomName(settings.name());
        spawned.setCustomNameVisible(true);
        spawned.setPersistent(true);
        spawned.setRemoveWhenFarAway(false);
        spawned.getPersistentDataContainer().set(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE, (byte) 1);
        spawned.getPersistentDataContainer().set(SHEggDragonBoss.BOSS_TYPE_KEY, PersistentDataType.STRING, BossType.CREAKING.id());
        // Les PV maximum d'abord : setHealth refuse une valeur supérieure au maximum du moment
        AttributeInstance maxHealthAttribute = spawned.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttribute != null) {
            try {
                maxHealthAttribute.setBaseValue(maxHealth);
            } catch (IllegalArgumentException e) {
                SHEggDragonBoss.getInstance().getLogger().warning("Creaking : le serveur refuse " + maxHealth + " PV maximum (" + e.getMessage()
                    + "). Les PV maximum par défaut de l'entité sont conservés.");
            }
        }
        double appliedMaxHealth = maxHealthAttribute != null ? maxHealthAttribute.getBaseValue() : maxHealth;
        spawned.setHealth(Math.min(currentHealth, appliedMaxHealth));
        setBase(spawned, Attribute.ATTACK_DAMAGE, settings.attackDamage());
        setBase(spawned, Attribute.MOVEMENT_SPEED, settings.movementSpeed());
        setBase(spawned, Attribute.ARMOR, settings.armor());
        setBase(spawned, Attribute.ARMOR_TOUGHNESS, settings.armorToughness());
        setBase(spawned, Attribute.KNOCKBACK_RESISTANCE, settings.knockbackResistance());
        setBase(spawned, Attribute.FOLLOW_RANGE, settings.followRange());
        return spawned;
    }

    private static void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }
}
