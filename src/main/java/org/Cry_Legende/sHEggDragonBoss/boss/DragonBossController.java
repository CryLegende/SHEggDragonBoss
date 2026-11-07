package org.Cry_Legende.sHEggDragonBoss.boss;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.DragonSettings;
import org.Cry_Legende.sHEggDragonBoss.config.Fx;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boss n°1 : le « Jeune Ender Dragon ». Un Phantom sans IA que le plugin fait voler :
 * <ul>
 *   <li>il <b>tourne autour</b> d'un joueur et lui crache une salve de boules de feu (une de plus à chaque phase) ;</li>
 *   <li>il <b>plonge</b> sur un joueur tiré au hasard : il se cabre au-dessus de lui, fixe l'endroit visé (un cercle de
 *       particules le montre), puis fond dessus. L'impact blesse tout ce qui est autour ; le dragon reste ensuite étourdi, et
 *       plus vulnérable ;</li>
 *   <li>à chaque seuil de PV il <b>rugit</b> : invulnérable un instant, puis un souffle repousse tout le monde. Il est alors plus
 *       rapide et attaque plus souvent ;</li>
 *   <li>aucun coup ne lui retire plus de {@code max-hit-percent} % de ses PV : un équipement complet ne le fait pas tomber en
 *       quelques secondes.</li>
 * </ul>
 */
public class DragonBossController extends BossController {

    private enum State {
        /** Vol en cercle autour de la cible : boules de feu, morsure. */
        ORBIT,
        /** Il se cabre au-dessus de sa proie, puis fixe l'endroit visé. */
        DIVE_WARNING,
        /** Il fond sur l'endroit fixé. */
        DIVING,
        /** Étourdi après l'impact : plus vulnérable. */
        STUNNED,
        /** Rugissement d'un changement de phase : invulnérable. */
        ROAR
    }

    /** Durée maximale de la descente, en ticks : au-delà, l'impact a lieu là où il est arrivé. */
    private static final int DIVE_TIMEOUT_TICKS = 60;
    /** Hauteur, en blocs, à laquelle il se cabre au-dessus de sa proie avant de plonger. */
    private static final double DIVE_HEIGHT = 9.0;

    private DragonSettings settings;
    private State state = State.ORBIT;

    /** Nombre de phases (rugissements) déjà déclenchées. */
    private int phasesStarted;
    private double orbitAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
    private long nextVolleyTick;
    private long nextDiveTick;
    private long lastMeleeTick;
    private long stateEnd;
    private UUID diveTarget;
    private Location lockedPoint;
    private Location anchor;

    private DragonBossController(SHEggDragonBoss plugin, BossManager bossManager, Island island, LivingEntity boss, double maxHealth, int phase) {
        super(plugin, bossManager, island, boss, maxHealth,
            plugin.getConfigManager().dragon().barTitle(),
            plugin.getConfigManager().dragon().barColor(),
            plugin.getConfigManager().dragon().barStyle());
        this.settings = plugin.getConfigManager().dragon();
        this.phasesStarted = Math.max(0, Math.min(phase, settings.phases().size()));
        this.nextVolleyTick = settings.rangedAttack().cooldownTicks();
        this.nextDiveTick = settings.dive().cooldownTicks();
        persistState();
    }

    /** Fait apparaître un nouveau boss (éclosion) ou réveille un boss dormant à {@code location}. */
    public static DragonBossController spawn(SHEggDragonBoss plugin, BossManager bossManager, Island island, Location location,
                                             double maxHealth, double currentHealth, int phase, StartMode mode) {
        LivingEntity entity = createEntity(plugin.getConfigManager().dragon(), location, maxHealth, currentHealth);
        DragonBossController controller = new DragonBossController(plugin, bossManager, island, entity, maxHealth, phase);
        controller.start(mode);
        return controller;
    }

    /** Reprend un boss déjà présent dans le monde (redémarrage, chunk rechargé) ; sa phase est lue sur l'entité. */
    public static DragonBossController adopt(SHEggDragonBoss plugin, BossManager bossManager, Island island, LivingEntity existing, double maxHealth) {
        int phase = existing.getPersistentDataContainer().getOrDefault(SHEggDragonBoss.DRAGON_PHASE_KEY, PersistentDataType.INTEGER, 0);
        DragonBossController controller = new DragonBossController(plugin, bossManager, island, existing, maxHealth, phase);
        controller.start(StartMode.RESTORED);
        return controller;
    }

    @Override
    public BossType type() {
        return BossType.DRAGON;
    }

    @Override
    protected int savedPhase() {
        return phasesStarted;
    }

    @Override
    protected void onStart(StartMode mode) {
        String islandName = islandLabel();
        switch (mode) {
            case FIRST_SPAWN -> {
                playSpawnEffects();
                plugin.getLogManager().log(journal("dragon-spawned", islandName));
            }
            case WOKEN_FROM_DORMANT ->
                plugin.getLogManager().log(phaseJournal("dragon-woken", islandName));
            case RESTORED ->
                plugin.getLogManager().log(phaseJournal("dragon-restored", islandName));
        }
    }

    @Override
    protected void onDeathStart() {
        state = State.ORBIT;
        lockedPoint = null;
        diveTarget = null;
    }

    // ------------------------------------------------------------------ tick

    @Override
    protected void onTick() {
        // Réglages relus à chaud (/sheggdragonboss reload) : un boss en cours applique les nouveaux réglages au tick suivant
        settings = plugin.getConfigManager().dragon();
        checkPhaseTrigger();
        switch (state) {
            case ORBIT -> tickOrbit();
            case DIVE_WARNING -> tickDiveWarning();
            case DIVING -> tickDiving();
            case STUNNED -> tickStunned();
            case ROAR -> tickRoar();
        }
        updateAmbientParticles();
        updateAmbientSound();
    }

    // ------------------------------------------------------------------ rythme : chaque phase accélère le dragon

    /** Délais multipliés par ce facteur : {@code phase-speedup} à la puissance du nombre de phases franchies. */
    private double cooldownFactor() {
        return Math.pow(settings.phaseSpeedup(), phasesStarted);
    }

    /** Vitesse de déplacement, en blocs par tick. */
    private double maxStep() {
        return settings.speedMultiplier() * 0.1 * (1.0 + settings.phaseSpeedBonus() * phasesStarted);
    }

    // ------------------------------------------------------------------ vol en cercle

    private void tickOrbit() {
        // La cible est choisie une fois par seconde : elle a pu changer de monde ou se déconnecter depuis
        if (target == null || !target.isOnline() || !target.getWorld().equals(boss.getWorld())) return;
        DragonSettings.Flight flight = settings.flight();
        Location eye = target.getEyeLocation();
        orbitAngle += Math.toRadians(flight.orbitDegreesPerTick());
        Location desired = eye.clone().add(Math.cos(orbitAngle) * flight.orbitRadius(), flight.orbitHeight(), Math.sin(orbitAngle) * flight.orbitRadius());
        moveToward(desired, maxStep(), eye);

        double distanceSquared = boss.getLocation().distanceSquared(eye);
        DragonSettings.RangedAttack ranged = settings.rangedAttack();
        if (ranged.enabled() && tickCounter >= nextVolleyTick && distanceSquared > ranged.minRangeSquared()) {
            Player victim = randomFighter(ranged.maxRange());
            if (victim != null) {
                fireVolley(victim);
                nextVolleyTick = tickCounter + Math.max(1L, Math.round(ranged.cooldownTicks() * cooldownFactor()));
            }
        }
        bite();
        DragonSettings.Dive dive = settings.dive();
        if (dive.enabled() && tickCounter >= nextDiveTick) {
            Player victim = randomFighter(dive.range());
            if (victim != null) startDive(victim);
        }
    }

    /** Morsure : le joueur le plus proche, s'il est à portée de la gueule. */
    private void bite() {
        if (tickCounter < lastMeleeTick + settings.attackCooldownTicks()) return;
        double range = Math.sqrt(settings.meleeRangeSquared());
        playersWithin(range).stream()
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation())))
            .ifPresent(victim -> {
                hurt(victim, settings.attackDamage(), settings.meleeTruePercent());
                lastMeleeTick = tickCounter;
            });
    }

    private Player randomFighter(double range) {
        List<Player> fighters = playersWithin(range);
        return fighters.isEmpty() ? null : fighters.get(ThreadLocalRandom.current().nextInt(fighters.size()));
    }

    // ------------------------------------------------------------------ boules de feu

    private void fireVolley(Player victim) {
        Location eyes = boss.getEyeLocation();
        Vector main = victim.getEyeLocation().toVector().subtract(eyes.toVector());
        if (main.lengthSquared() < 1.0E-4) return;
        main.normalize();
        Location spawn = eyes.clone().add(main.clone().multiply(1.5));
        Vector side = main.clone().crossProduct(new Vector(0, 1, 0));
        // Tir à la verticale : le produit vectoriel est nul, on étale la salve sur un axe quelconque
        side = side.lengthSquared() < 1.0E-4 ? new Vector(1, 0, 0) : side.normalize();
        side.multiply(0.35);

        DragonSettings.RangedAttack ranged = settings.rangedAttack();
        int count = Math.max(1, Math.min(9, ranged.fireballs() + ranged.fireballsPerPhase() * phasesStarted));
        World world = boss.getWorld();
        for (int i = 0; i < count; i++) {
            double offset = i - (count - 1) / 2.0;
            DragonFireball ball = world.spawn(spawn, DragonFireball.class);
            ball.setDirection(main.clone().add(side.clone().multiply(offset)).normalize());
            ball.setShooter(boss);
        }
        ranged.sound().play(boss.getLocation());
    }

    // ------------------------------------------------------------------ plongeon

    private void startDive(Player victim) {
        DragonSettings.Dive dive = settings.dive();
        state = State.DIVE_WARNING;
        diveTarget = victim.getUniqueId();
        lockedPoint = null;
        stateEnd = tickCounter + dive.warningTicks();
        dive.warningSound().play(boss.getLocation());
        dive.warningSound().playFor(victim);
        PlayerText.actionBar(victim, format(settings.texts().diveWarning()));
    }

    private void tickDiveWarning() {
        DragonSettings.Dive dive = settings.dive();
        Player victim = diveTarget == null ? null : Bukkit.getPlayer(diveTarget);
        if (victim == null || !isFightable(victim) || !victim.getWorld().equals(boss.getWorld()) || !island.isInside(victim.getLocation())) {
            abortDive();
            return;
        }
        if (stateEnd - tickCounter > dive.lockTicks()) {
            // Il se cabre au-dessus de sa proie, qu'il suit encore
            moveToward(victim.getLocation().add(0, DIVE_HEIGHT, 0), maxStep() * 1.5, victim.getLocation());
        } else {
            // L'endroit visé est fixé : un cercle de particules au sol le montre, le dragon ne bouge plus
            if (lockedPoint == null) lockedPoint = victim.getLocation().clone();
            face(lockedPoint);
            if (tickCounter % 2 == 0) ring(dive.warningFx(), lockedPoint.clone().add(0, 0.15, 0), dive.hitRadius());
        }
        if (tickCounter >= stateEnd) {
            if (lockedPoint == null) lockedPoint = victim.getLocation().clone();
            state = State.DIVING;
            stateEnd = tickCounter + DIVE_TIMEOUT_TICKS;
        }
    }

    private void tickDiving() {
        DragonSettings.Dive dive = settings.dive();
        Location aim = lockedPoint.clone().add(0, 1.2, 0);
        double speed = dive.speed();
        if (boss.getLocation().distance(aim) <= speed + 0.5 || tickCounter >= stateEnd) {
            impact();
            return;
        }
        moveToward(aim, speed, aim);
    }

    /** L'impact : tout ce qui est autour est blessé et projeté, puis le dragon reste étourdi. */
    private void impact() {
        DragonSettings.Dive dive = settings.dive();
        Location at = boss.getLocation();
        dive.impactSound().play(at);
        dive.impactFx().spawn(at);
        for (Player victim : playersWithin(dive.hitRadius())) {
            hurt(victim, dive.damage(), dive.truePercent());
            push(victim, at, dive.knockback(), dive.knockback() * 0.5);
        }
        state = State.STUNNED;
        stateEnd = tickCounter + dive.stunTicks();
        anchor = at.clone();
        lockedPoint = null;
    }

    private void tickStunned() {
        DragonSettings.Dive dive = settings.dive();
        // Étourdi : il tourne lentement sur place, des étincelles lui tournent autour de la tête
        Location here = anchor.clone();
        here.setYaw(boss.getLocation().getYaw() + 9.0f);
        boss.teleport(here);
        if (tickCounter % 4 == 0) dive.stunFx().spawn(boss.getLocation().add(0, boss.getHeight(), 0));
        if (tickCounter >= stateEnd) {
            state = State.ORBIT;
            nextDiveTick = tickCounter + Math.max(1L, Math.round(dive.cooldownTicks() * cooldownFactor()));
            nextVolleyTick = Math.max(nextVolleyTick, tickCounter + 20L);
        }
    }

    private void abortDive() {
        state = State.ORBIT;
        diveTarget = null;
        lockedPoint = null;
        nextDiveTick = tickCounter + 40L;
    }

    // ------------------------------------------------------------------ phases et rugissement

    private void checkPhaseTrigger() {
        if (state == State.ROAR || phasesStarted >= settings.phases().size()) return;
        if (boss.getHealth() <= maxHealth * settings.phases().get(phasesStarted) / 100.0) startRoar();
    }

    private void startRoar() {
        phasesStarted++;
        persistState();
        DragonSettings.Roar roar = settings.roar();
        // Sans rugissement la phase passe quand même : le dragon est simplement plus rapide
        if (!roar.enabled()) return;
        state = State.ROAR;
        stateEnd = tickCounter + roar.durationTicks();
        anchor = boss.getLocation().clone();
        lockedPoint = null;
        diveTarget = null;
        roar.startSound().play(anchor);
        plugin.getLogManager().log(phaseJournal("dragon-phase-start", islandLabel()));
        tellViewers(format(settings.texts().roarChat()));
        for (Player viewer : viewers()) {
            PlayerText.title(viewer, format(settings.texts().roarTitle()), format(settings.texts().roarSubtitle()));
        }
        updateBossBar();
    }

    private void tickRoar() {
        DragonSettings.Roar roar = settings.roar();
        // Il se cabre sur place et tremble de plus en plus fort
        int duration = Math.max(1, roar.durationTicks());
        double progress = 1.0 - Math.max(0L, stateEnd - tickCounter) / (double) duration;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double shake = 0.05 + 0.25 * progress;
        Location at = anchor.clone().add((random.nextDouble() - 0.5) * shake, 1.5 * progress, (random.nextDouble() - 0.5) * shake);
        at.setPitch(-25.0f * (float) progress);
        boss.teleport(at);
        if (tickCounter % 2 == 0) roar.auraFx().spawn(at.clone().add(0, boss.getHeight() / 2.0, 0));
        if (tickCounter >= stateEnd) endRoar();
    }

    /** Le souffle du rugissement : tout le monde autour est blessé et repoussé. */
    private void endRoar() {
        DragonSettings.Roar roar = settings.roar();
        Location at = boss.getLocation();
        roar.blastSound().play(at);
        ring(roar.blastFx(), at.clone().add(0, -boss.getHeight() / 2.0, 0), roar.radius());
        for (Player victim : playersWithin(roar.radius())) {
            hurt(victim, roar.damage(), roar.truePercent());
            push(victim, at, roar.knockbackHorizontal(), roar.knockbackVertical());
        }
        state = State.ORBIT;
        nextVolleyTick = Math.max(nextVolleyTick, tickCounter + 20L);
        nextDiveTick = Math.max(nextDiveTick, tickCounter + 60L);
        updateBossBar();
    }

    // ------------------------------------------------------------------ dégâts reçus

    @Override
    public boolean absorbDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        // Le vide et /kill passent toujours : un boss ne doit jamais devenir impossible à retirer
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return false;

        if (state == State.ROAR) {
            settings.blockedSound().play(boss.getLocation());
            return true;
        }
        // Étourdi, il encaisse plus : le bonus s'applique au coup déjà plafonné
        double bonus = state == State.STUNNED ? settings.dive().stunDamageMultiplier() : 1.0;
        limitHit(event, settings.maxHitPercent(), bonus);

        // Un coup qui ferait franchir le prochain seuil est ramené pile au seuil : le rugissement a lieu au lieu que le dragon
        // traverse ses phases ou meure d'un coup
        if (phasesStarted < settings.phases().size()) {
            double threshold = maxHealth * settings.phases().get(phasesStarted) / 100.0;
            double health = boss.getHealth();
            if (health > threshold && health - event.getFinalDamage() <= threshold) {
                Player attacker = attackerOf(event);
                if (attacker != null) addContribution(attacker.getUniqueId(), health - threshold);
                boss.setHealth(threshold);
                startRoar();
                settings.hurtSound().play(boss.getLocation());
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ déplacement

    /**
     * Avance le dragon vers {@code desired} d'au plus {@code speed} blocs, en se tournant vers son déplacement (ou vers
     * {@code lookAt} quand il ne bouge presque plus). Il ne sort jamais de l'île : hors de l'île il revient vers le centre.
     */
    private void moveToward(Location desired, double speed, Location lookAt) {
        Location from = boss.getLocation();
        Vector delta = desired.toVector().subtract(from.toVector());
        double distance = delta.length();
        Vector step = distance <= speed ? delta : delta.normalize().multiply(speed);
        Location next = from.clone().add(step);

        Vector heading = step.lengthSquared() > 0.01 ? step : lookAt.toVector().subtract(from.toVector());
        if (heading.lengthSquared() > 1.0E-6) {
            Location oriented = from.clone();
            oriented.setDirection(heading);
            next.setYaw(approachAngle(from.getYaw(), oriented.getYaw(), 30.0f));
            next.setPitch(Math.max(-60.0f, Math.min(60.0f, oriented.getPitch())));
        }

        if (!island.isInside(next)) {
            // Il ne bouge pas ce tick-ci ; s'il est déjà hors de l'île (téléporté, repoussé), il revient au centre
            if (island.isInside(from)) return;
            Location center = island.getCenter(dimension(boss.getWorld().getEnvironment()));
            if (center == null) return;
            next = center.clone().add(0.5, settings.flight().orbitHeight(), 0.5);
        }
        boss.teleport(next);
    }

    /** Se tourne vers un point sans bouger. */
    private void face(Location point) {
        Location here = boss.getLocation();
        Vector heading = point.toVector().subtract(here.toVector());
        if (heading.lengthSquared() < 1.0E-6) return;
        here.setDirection(heading);
        boss.teleport(here);
    }

    /** L'angle le plus proche de {@code target} atteignable depuis {@code current} en tournant d'au plus {@code maxDelta} degrés. */
    private static float approachAngle(float current, float target, float maxDelta) {
        float diff = ((target - current + 540.0f) % 360.0f) - 180.0f;
        return current + Math.max(-maxDelta, Math.min(maxDelta, diff));
    }

    /** Repousse un joueur loin de {@code origin}. */
    private static void push(Player victim, Location origin, double horizontal, double vertical) {
        Vector away = victim.getLocation().toVector().subtract(origin.toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            // Pile sous le dragon : une direction au hasard plutôt qu'un vecteur nul
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
            away = new Vector(Math.cos(angle), 0, Math.sin(angle));
        }
        victim.setVelocity(away.normalize().multiply(horizontal).setY(vertical));
    }

    /** Anneau horizontal de {@code fx.count()} points. */
    private static void ring(Fx fx, Location center, double radius) {
        if (fx.particle() == null || fx.count() <= 0) return;
        for (int i = 0; i < fx.count(); i++) {
            double angle = 2.0 * Math.PI * i / fx.count();
            fx.spawn(center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), 1, 0.0, 0.0, 0.0);
        }
    }

    // ------------------------------------------------------------------ ambiance

    private void updateAmbientParticles() {
        DragonSettings.AmbientParticles ambient = settings.ambientParticles();
        if (ambient.enabled() && ambient.particle() != null && tickCounter % ambient.intervalTicks() == 0) {
            boss.getWorld().spawnParticle(ambient.particle(), boss.getLocation().add(0, boss.getHeight() / 2, 0), ambient.count(), boss.getWidth() / 2, boss.getHeight() / 2, boss.getWidth() / 2, ambient.speed());
        }
    }

    private void updateAmbientSound() {
        if (tickCounter % (settings.ambientSoundDelaySeconds() * 20) == 0) {
            settings.ambientSound().play(boss.getLocation());
        }
    }

    private void playSpawnEffects() {
        Location loc = boss.getLocation();
        DragonSettings.SpawnExplosion explosion = settings.spawnExplosion();
        if (explosion.enabled())
            loc.getWorld().createExplosion(loc, explosion.power(), explosion.setFire(), explosion.breakBlocks(), boss);
        settings.spawnSound().play(loc);
    }

    // ------------------------------------------------------------------ barre de boss, journal, sauvegarde

    private String format(String message) {
        return message
            .replace("%phase%", String.valueOf(phasesStarted))
            .replace("%phases%", String.valueOf(settings.phases().size()))
            .replace("%name%", settings.name());
    }

    @Override
    protected String barTitle() {
        String template = state == State.ROAR ? settings.roarBarTitle() : settings.barTitle();
        return format(template)
            .replace("%current_health%", String.valueOf((int) Math.round(boss.getHealth())))
            .replace("%max_health%", String.valueOf((int) Math.round(maxHealth)));
    }

    @Override
    protected BarColor barColor() {
        // La barre rougit quand le dragon est enragé (deux phases franchies)
        return phasesStarted >= 2 ? BarColor.RED : settings.barColor();
    }

    @Override
    protected BarStyle barStyle() {
        return settings.barStyle();
    }

    /** Ligne du journal avec la phase en cours : %phase% et %phases%. */
    private String phaseJournal(String key, String island) {
        return journal(key, island, "phase", String.valueOf(phasesStarted), "phases", String.valueOf(settings.phases().size()));
    }

    /** Écrit la phase sur l'entité : un redémarrage reprend le combat là où il en était. */
    private void persistState() {
        boss.getPersistentDataContainer().set(SHEggDragonBoss.DRAGON_PHASE_KEY, PersistentDataType.INTEGER, phasesStarted);
    }

    // ------------------------------------------------------------------ création de l'entité

    private static LivingEntity createEntity(DragonSettings settings, Location location, double maxHealth, double currentHealth) {
        LivingEntity spawned = (LivingEntity) location.getWorld().spawnEntity(location, settings.entityType());
        spawned.setAI(false);
        spawned.setCollidable(false);
        spawned.setSilent(true);
        spawned.setCustomName(settings.name());
        spawned.setCustomNameVisible(true);
        spawned.setPersistent(true);
        spawned.getPersistentDataContainer().set(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE, (byte) 1);
        spawned.getPersistentDataContainer().set(SHEggDragonBoss.BOSS_TYPE_KEY, PersistentDataType.STRING, BossType.DRAGON.id());
        if (spawned instanceof Phantom) ((Phantom) spawned).setSize(settings.phantomSize());
        AttributeInstance maxHealthAttr = spawned.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) maxHealthAttr.setBaseValue(maxHealth);
        spawned.setHealth(Math.min(currentHealth, maxHealth));
        return spawned;
    }
}
