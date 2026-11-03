package org.Cry_Legende.sHEggDragonBoss.boss;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.CreakingSettings;
import org.Cry_Legende.sHEggDragonBoss.config.Fx;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.HeartManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boss n°2 : le Creaking. L'IA vanilla reste active (poursuite, attaque, gel quand un joueur le regarde : rien de cela
 * n'est réimplémenté). Le plugin joue sur ce regard et ajoute :
 * <ul>
 *   <li><b>le Noir</b> : il aveugle les joueurs proches (et cache son nom) ; un joueur aveuglé ne le fige plus, alors le boss
 *       se téléporte derrière lui, à sa hauteur s'il vole, et le frappe après un court délai (esquivable) ;</li>
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
        TELEGRAPH,
        ACTIVE
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
    /** Fin de l'état en cours (annonce ou fenêtre du Noir). */
    private long blackoutStateEnd;
    private long nextBlinkTick;
    /** Tick de la frappe annoncée, ou -1 s'il n'y en a pas. */
    private long strikeTick = -1;
    private UUID strikeTarget;
    private UUID lastBlinkTarget;
    /** Joueurs aveuglés par le Noir en cours. */
    private final Set<UUID> blinded = new HashSet<>();
    /** Vrai pendant un coup donné par le plugin lui-même : le bonus de mêlée ne s'y ajoute pas une seconde fois. */
    private boolean dealingDamage;

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
    private final Map<UUID, Double> damageDealt = new HashMap<>();
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
     * Le Noir : annonce (son, titre, particules), puis fenêtre où les joueurs proches sont aveuglés. Pendant la fenêtre
     * le boss se téléporte derrière un joueur aveuglé, puis le frappe. Rien ne se passe sans joueur à portée.
     */
    private void tickBlackout() {
        CreakingSettings.Blackout cfg = settings.blackout();
        if (!cfg.enabled()) return;
        switch (blackoutState) {
            case IDLE -> {
                // Le Noir attend la fin d'un Sursaut en cours : le boss ne mène qu'une téléportation à la fois
                if (tickCounter < nextBlackoutTick || tickCounter % settings.checkIntervalTicks() != 0
                    || sursautState != SursautState.IDLE) return;
                List<Player> victims = playersWithin(cfg.radius());
                if (!victims.isEmpty()) startTelegraph(cfg, victims);
            }
            case TELEGRAPH -> {
                if (tickCounter >= blackoutStateEnd) {
                    startWindow(cfg);
                } else if (tickCounter % 5 == 0) {
                    for (Player player : playersWithin(cfg.radius())) cfg.telegraphFx().spawn(player.getLocation().add(0, 1.0, 0));
                }
            }
            case ACTIVE -> blackoutStep(cfg);
        }
    }

    private void startTelegraph(CreakingSettings.Blackout cfg, List<Player> victims) {
        blackoutState = BlackoutState.TELEGRAPH;
        blackoutStateEnd = tickCounter + cfg.telegraphTicks();
        for (Player victim : victims) {
            // Joué pour chaque joueur : le son doit s'entendre aussi loin que le rayon du Noir
            cfg.telegraphSound().playFor(victim);
            PlayerText.title(victim, format(settings.texts().blackoutTitle()), format(settings.texts().blackoutSubtitle()));
        }
        plugin.getLogManager().log(journal("creaking-blackout", islandLabel(), "players", String.valueOf(victims.size())));
    }

    private void startWindow(CreakingSettings.Blackout cfg) {
        List<Player> victims = playersWithin(cfg.radius());
        blinded.clear();
        if (victims.isEmpty()) {
            endWindow(cfg);
            return;
        }
        // Un peu plus longtemps que la fenêtre : une frappe annoncée en fin de fenêtre va toujours au bout
        int duration = cfg.durationTicks() + 20;
        for (Player victim : victims) {
            for (CreakingSettings.BlindEffect effect : cfg.effects()) {
                victim.addPotionEffect(new PotionEffect(effect.type(), duration, effect.amplifier(), false, false, true));
            }
            blinded.add(victim.getUniqueId());
        }
        // Même aveuglés, les joueurs verraient son nom à travers le noir : il disparaît le temps de la fenêtre
        if (cfg.hideName()) showName(false);
        blackoutState = BlackoutState.ACTIVE;
        blackoutStateEnd = tickCounter + cfg.durationTicks();
        nextBlinkTick = tickCounter + cfg.firstBlinkDelayTicks();
        strikeTick = -1;
        strikeTarget = null;
    }

    private void blackoutStep(CreakingSettings.Blackout cfg) {
        if (strikeTick >= 0) {
            if (tickCounter >= strikeTick) resolveStrike(cfg);
            return;
        }
        if (tickCounter >= blackoutStateEnd) {
            endWindow(cfg);
            return;
        }
        if (tickCounter >= nextBlinkTick) blink(cfg);
    }

    private void endWindow(CreakingSettings.Blackout cfg) {
        blackoutState = BlackoutState.IDLE;
        blinded.clear();
        strikeTick = -1;
        strikeTarget = null;
        nextBlackoutTick = tickCounter + blackoutCooldown(cfg);
        land();
        if (nameHidden) showName(true);
    }

    /** Délai avant le prochain Noir : raccourci à chaque phase déclenchée. */
    private long blackoutCooldown(CreakingSettings.Blackout cfg) {
        return Math.max(20L, Math.round(cfg.cooldownTicks() * Math.pow(cfg.phaseCooldownFactor(), phasesStarted)));
    }

    /** Un joueur aveuglé qui peut encore être pris pour cible : en vie, sur l'île, et toujours dans le noir (lait, soin...). */
    private boolean isBlindTarget(Player player, CreakingSettings.Blackout cfg) {
        return isTargetable(player) && (cfg.effects().isEmpty() || player.hasPotionEffect(cfg.effects().get(0).type()));
    }

    /** Un joueur que le boss peut viser : en ligne, en vie, pas spectateur, dans le monde du boss et sur l'île. */
    private boolean isTargetable(Player player) {
        if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) return false;
        return player.getWorld().equals(boss.getWorld()) && island.isInside(player.getLocation());
    }

    private void blink(CreakingSettings.Blackout cfg) {
        List<Player> candidates = new ArrayList<>();
        for (UUID id : blinded) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && isBlindTarget(player, cfg)) candidates.add(player);
        }
        if (candidates.isEmpty()) {
            // Plus personne dans le noir : le Noir se termine plus tôt
            endWindow(cfg);
            return;
        }
        if (candidates.size() > 1) candidates.removeIf(p -> p.getUniqueId().equals(lastBlinkTarget));
        Player victim = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        nextBlinkTick = tickCounter + cfg.blinkIntervalTicks();

        // Le craquement à l'arrivée est le seul indice : le joueur est aveugle
        if (!blinkTo(victim, cfg.blinkDistance(), cfg)) return;
        lastBlinkTarget = victim.getUniqueId();
        strikeTarget = victim.getUniqueId();
        strikeTick = tickCounter + cfg.strikeDelayTicks();
    }

    /**
     * Téléporte le boss sur sa proie (effets et craquement positionnel compris). Une proie en l'air n'a pas de sol : le boss
     * la rejoint à sa hauteur, sans gravité, jusqu'à sa frappe.
     *
     * @return faux si aucun emplacement libre n'a été trouvé autour de la proie
     */
    private boolean blinkTo(Player victim, double distance, CreakingSettings.Blackout fx) {
        Location spot = blinkSpot(victim, distance);
        if (spot == null) return false;
        fx.blinkFx().spawn(boss.getLocation().add(0, boss.getHeight() / 2, 0));
        boss.teleport(spot);
        boss.setVelocity(new Vector(0, 0, 0));
        boss.setFallDistance(0.0f);
        boolean supported = spot.clone().subtract(0, 1, 0).getBlock().getType().isSolid();
        hovering = !supported;
        boss.setGravity(supported);
        fx.blinkFx().spawn(spot.clone().add(0, boss.getHeight() / 2, 0));
        fx.blinkSound().play(spot);
        return true;
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

    /** La frappe annoncée du Noir. */
    private void resolveStrike(CreakingSettings.Blackout cfg) {
        UUID id = strikeTarget;
        strikeTick = -1;
        strikeTarget = null;
        strike(id, cfg, new StrikeProfile(cfg.strikeReach(), cfg.strikeDamage(), cfg.strikeTruePercent(), cfg.strikeKnockback()));
    }

    /**
     * Une frappe annoncée (Noir ou Sursaut) : elle porte si le joueur est resté à portée, sinon il l'a esquivée. Si le boss
     * flottait à la hauteur de sa proie, il redescend ensuite au sol. Les sons et particules sont ceux du Noir.
     */
    private void strike(UUID id, CreakingSettings.Blackout fx, StrikeProfile profile) {
        try {
            Player victim = id == null ? null : Bukkit.getPlayer(id);
            if (victim == null || !victim.isOnline() || victim.isDead() || !victim.getWorld().equals(boss.getWorld())) return;

            Location origin = boss.getLocation();
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
        } finally {
            land();
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
     * Frappe un joueur : d'abord un coup normal (armure, protections, totem... s'appliquent), puis une part de ses PV max
     * retirée directement. L'équipement complet du serveur arrête presque tout coup normal ; cette part-là ne dépend ni de
     * l'armure ni des cœurs d'absorption, elle suit les PV du joueur. Si elle suffit à le tuer, elle passe par un vrai coup
     * (totem d'immortalité, mort et règles de l'île se déclenchent normalement).
     */
    private void hurt(Player victim, double baseDamage, double truePercent) {
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

    private void trueDamage(Player victim, double percent) {
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

    private static Player attackerOf(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return null;
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) return player;
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
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
                    settings.blackout().telegraphFx().spawn(boss.getLocation().add(0, boss.getHeight() / 2, 0));
                }
            }
            case STRIKING -> {
                if (tickCounter >= sursautStrikeTick) {
                    strike(sursautTarget, settings.blackout(),
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
        if (victim == null || !isTargetable(victim) || !blinkTo(victim, cfg.blinkDistance(), settings.blackout())) {
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
        heartsLeft = phase.hearts();
        heartsToPlace = phase.hearts();
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
    public boolean absorbDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        // Le vide et /kill passent toujours : un boss ne doit jamais devenir impossible à retirer
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return false;

        if (isShielded()) {
            settings.shieldBlockedSound().play(boss.getLocation());
            return true;
        }

        double cap = maxHealth * settings.maxHitPercent() / 100.0;
        double finalDamage = event.getFinalDamage();
        if (cap > 0.0 && finalDamage > cap) {
            // Chaque modificateur (base, armure, résistance...) est mis à l'échelle : setDamage(double) ne recalcule pas l'armure
            // proportionnellement et laisserait un total négatif, c'est-à-dire un soin
            double scale = cap / finalDamage;
            for (EntityDamageEvent.DamageModifier modifier : EntityDamageEvent.DamageModifier.values()) {
                if (event.isApplicable(modifier)) event.setDamage(modifier, event.getDamage(modifier) * scale);
            }
        }

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

    /** Joueurs vivants de l'île dans un rayon autour du boss. */
    private List<Player> playersWithin(double radius) {
        Location origin = boss.getLocation();
        double radiusSquared = radius * radius;
        return boss.getWorld().getNearbyPlayers(origin, radius,
                p -> p.getGameMode() != GameMode.SPECTATOR && !p.isDead() && island.isInside(p.getLocation()))
            .stream()
            .filter(p -> p.getLocation().distanceSquared(origin) <= radiusSquared)
            .toList();
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
