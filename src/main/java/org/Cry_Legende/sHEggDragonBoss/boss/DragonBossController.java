package org.Cry_Legende.sHEggDragonBoss.boss;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.DragonSettings;
import org.Cry_Legende.sHEggDragonBoss.config.Fx;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.model.BossModel;
import org.Cry_Legende.sHEggDragonBoss.model.ModelManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EntityType;
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
 * Avec son modèle 3D (model.enabled), le Phantom est remplacé par une entité porteuse invisible (un Ghast, qui n'a pas
 * d'yeux lumineux à cacher) habillée d'un dragon fait de têtes de joueur : il plane, crache, se cabre, plonge, s'écrase
 * étourdi, redécolle, rugit et meurt avec ses propres animations ; son corps monte à chaque coup d'aile par sa position.
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
        /** Décollage du modèle après l'étourdissement : poussée sur les pattes, puis le battement le soulève. */
        TAKEOFF,
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

    // ------------------------------------------------------------------ modèle 3D
    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
    /** Instants (ticks) où partent la salve et la morsure dans leurs animations : l'animation démarre autant de ticks avant. */
    private static final int CRACHE_IMPACT = 12;
    private static final int MORSURE_IMPACT = 7;
    private BossModel model;
    /** Fin de l'éclosion (tick) : le dragon reste sur place le temps de son animation d'apparition. */
    private long apparitionEndTick;
    /** Hauteur (blocs) ajoutée à sa position par la montée et la descente du vol plané, déjà appliquée. */
    private double heaveApplied;
    /** L'animation de la prochaine salve est déjà partie. */
    private boolean volleyAnnounced;

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
        LivingEntity entity = createEntity(plugin.getConfigManager().dragon(), location, maxHealth, currentHealth, wantsCarrier(plugin));
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
        // L'entité porteuse ne se montre jamais, même le temps que le modèle soit prêt
        if (isCarrier(plugin, boss) && !sameType(plugin)) boss.setInvisible(true);
        attachModel(mode == StartMode.FIRST_SPAWN);
    }

    @Override
    protected void onDeathStart() {
        // Tué pendant qu'il est étourdi au sol : il s'effondre là où il est étendu ; en vol, il pique et tombe en vrille
        boolean auSol = state == State.STUNNED;
        state = State.ORBIT;
        lockedPoint = null;
        diveTarget = null;
        apparitionEndTick = 0;
        if (model != null) model.play(auSol ? "mort_sol" : "mort", 0, 0, true);
    }

    @Override
    protected void onDeathTick() {
        // La mort du modèle continue pendant la mise en scène
        if (model != null) model.tick();
    }

    @Override
    protected void onFinale() {
        if (model != null) model.vanish();
    }

    @Override
    protected boolean shakesOnDeath() {
        return model == null;
    }

    @Override
    protected void onCancel() {
        if (model != null) {
            model = null;
            ModelManager models = plugin.getModelManager();
            boolean carrier = isCarrier(plugin, boss) && !sameType(plugin);
            // Mort : le modèle finit de disparaître (il rétrécit au coup de grâce) avant d'être retiré
            if (boss.isDead()) Bukkit.getScheduler().runTaskLater(plugin, () -> models.detach(boss, carrier), 16L);
            else models.detach(boss, carrier);
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    protected void onTick() {
        // Réglages relus à chaud (/sheggdragonboss reload) : un boss en cours applique les nouveaux réglages au tick suivant
        settings = plugin.getConfigManager().dragon();
        if (!modelCheck()) return;
        // Éclosion : il se déplie sur place, ni vol ni attaque le temps de son animation
        if (apparitionEndTick > 0 && tickCounter < apparitionEndTick) {
            tickModel();
            updateAmbientSound();
            return;
        }
        apparitionEndTick = 0;
        checkPhaseTrigger();
        switch (state) {
            case ORBIT -> tickOrbit();
            case DIVE_WARNING -> tickDiveWarning();
            case DIVING -> tickDiving();
            case STUNNED -> tickStunned();
            case TAKEOFF -> tickTakeoff();
            case ROAR -> tickRoar();
        }
        updateAmbientParticles();
        updateAmbientSound();
        tickModel();
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
                if (model != null) model.playOver("morsure", 0, MORSURE_IMPACT - 2);
            });
    }

    private Player randomFighter(double range) {
        List<Player> fighters = playersWithin(range);
        return fighters.isEmpty() ? null : fighters.get(ThreadLocalRandom.current().nextInt(fighters.size()));
    }

    // ------------------------------------------------------------------ boules de feu

    private void fireVolley(Player victim) {
        Location eyes = model != null ? mouth() : boss.getEyeLocation();
        Vector main = victim.getEyeLocation().toVector().subtract(eyes.toVector());
        if (main.lengthSquared() < 1.0E-4) return;
        main.normalize();
        Location spawn = eyes.clone().add(main.clone().multiply(model != null ? 0.6 : 1.5));
        // Salve tirée sans que l'animation soit partie (cible apparue au dernier moment) : la gueule s'ouvre au moment du tir
        if (model != null && !volleyAnnounced) model.playOver("crache", 0, CRACHE_IMPACT - 2);
        volleyAnnounced = false;
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
        // Il se cabre : l'animation finit (bascule vers la cible) quand le plongeon part
        if (model != null) model.play("cabre", 0, Math.max(0, model.length("cabre") - dive.warningTicks()), false);
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
            if (model != null) model.cut();
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
        if (model != null) {
            // Le modèle s'écrase à plat ventre : le boss est posé pour que l'origine du modèle soit à sa hauteur au-dessus
            // du sol (la hitbox, centrée sur le corps, couvre alors le dragon étendu, là où les joueurs le voient)
            anchor = groundAnchor(at);
            boss.teleport(anchor);
            heaveApplied = 0.0;
            model.play("impact");
        }
    }

    /** Pixels du modèle (échelle 1) entre son origine et le sol dans sa pose au sol (SOL dans l'atelier du modèle). */
    private static final double GROUND_ORIGIN_PX = 13.3;

    /** Position du boss étourdi au sol : le premier bloc plein sous lui (8 blocs au plus), sinon sa position telle quelle. */
    private Location groundAnchor(Location at) {
        World world = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ();
        for (int y = (int) Math.floor(at.getY() + 1.0); y >= Math.floor(at.getY()) - 8 && y > world.getMinHeight(); y--) {
            if (world.getBlockAt(x, y, z).isSolid()) {
                double ground = y + 1.0;
                double originAbove = GROUND_ORIGIN_PX / 16.0 * model.scale();
                Location anchor = at.clone();
                anchor.setY(ground + originAbove - boss.getHeight() * 0.5);
                return anchor;
            }
        }
        return at.clone();
    }

    private void tickStunned() {
        DragonSettings.Dive dive = settings.dive();
        // Étourdi : des étincelles lui tournent autour de la tête. Le Phantom tourne aussi sur lui-même ; le modèle, couché,
        // montre l'étourdissement par son animation (tête qui roule) et ne tourne pas : un dragon de 10 blocs qui pivote à
        // plat ferait une toupie, et chaque rotation coûte un paquet par pièce
        Location here = anchor.clone();
        here.setYaw(boss.getLocation().getYaw() + (model == null ? 9.0f : 0.0f));
        boss.teleport(here);
        if (tickCounter % 4 == 0) dive.stunFx().spawn(boss.getLocation().add(0, boss.getHeight(), 0));
        if (tickCounter >= stateEnd) {
            nextDiveTick = tickCounter + Math.max(1L, Math.round(dive.cooldownTicks() * cooldownFactor()));
            nextVolleyTick = Math.max(nextVolleyTick, tickCounter + 20L);
            if (model == null) {
                state = State.ORBIT;
                return;
            }
            // Décollage du modèle : le plugin porte la montée du corps (aucun paquet de pièce), l'animation fait le reste
            state = State.TAKEOFF;
            takeoffStart = tickCounter;
            stateEnd = tickCounter + Math.max(1, model.length("decolle"));
            model.play("decolle");
        }
    }

    /** Début du décollage (tick), pour la courbe de montée. */
    private long takeoffStart;

    /**
     * Décollage : le boss reste à sa place au sol et monte selon la courbe de l'animation « decolle » (leveDecolle dans
     * l'atelier du modèle) : 21,2 pixels du modèle pendant la poussée des pattes (0 à 0,1 s), 18,8 pendant le battement
     * (0,3 à 0,9 s), puis il reprend son vol.
     */
    private void tickTakeoff() {
        double t = (tickCounter - takeoffStart) / 20.0;
        double pixels = 21.2 * smooth(t, 0.0, 0.1) + 18.8 * smooth(t, 0.3, 0.9);
        Location here = anchor.clone().add(0, model == null ? 0.0 : pixels / 16.0 * model.scale(), 0);
        here.setYaw(boss.getLocation().getYaw());
        here.setPitch(0.0f);
        boss.teleport(here);
        if (tickCounter >= stateEnd) state = State.ORBIT;
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
        // L'animation finit sur le souffle (fin du rugissement)
        if (model != null) model.play("rugissement", 0, Math.max(0, model.length("rugissement") - roar.durationTicks()), false);
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
        // Son propre souffle (les nuages de ses boules de feu) ne le blesse pas : le Phantom, mort-vivant, n'y perdait rien,
        // l'entité porteuse du modèle (un Ghast) y perdrait des PV
        if (cause == EntityDamageEvent.DamageCause.DRAGON_BREATH
            || (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof org.bukkit.entity.AreaEffectCloud cloud && boss.equals(cloud.getSource()))) {
            return true;
        }

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
        if (model != null && !model.busy()) model.playOver("degats");
        return false;
    }

    // ------------------------------------------------------------------ déplacement

    /**
     * Avance le dragon vers {@code desired} d'au plus {@code speed} blocs, en se tournant vers son déplacement (ou vers
     * {@code lookAt} quand il ne bouge presque plus). Il ne sort jamais de l'île : hors de l'île il revient vers le centre.
     */
    private void moveToward(Location desired, double speed, Location lookAt) {
        // Le trajet se calcule sans la montée du vol plané, ajoutée ensuite : elle ne freine ni ne dévie le dragon
        Location from = boss.getLocation().subtract(0, heaveApplied, 0);
        Vector delta = desired.toVector().subtract(from.toVector());
        double distance = delta.length();
        Vector step = distance <= speed ? delta : delta.normalize().multiply(speed);
        Location next = from.clone().add(step);

        Vector heading = step.lengthSquared() > 0.01 ? step : lookAt.toVector().subtract(from.toVector());
        if (heading.lengthSquared() > 1.0E-6) {
            Location oriented = from.clone();
            oriented.setDirection(heading);
            next.setYaw(approachAngle(from.getYaw(), oriented.getYaw(), model != null ? 15.0f : 30.0f));
            next.setPitch(Math.max(-60.0f, Math.min(60.0f, oriented.getPitch())));
        }

        if (!island.isInside(next)) {
            // Il ne bouge pas ce tick-ci ; s'il est déjà hors de l'île (téléporté, repoussé), il revient au centre
            if (island.isInside(from)) return;
            Location center = island.getCenter(dimension(boss.getWorld().getEnvironment()));
            if (center == null) return;
            next = center.clone().add(0.5, settings.flight().orbitHeight(), 0.5);
        }
        double heave = heave();
        next.add(0, heave, 0);
        heaveApplied = heave;
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

    // ------------------------------------------------------------------ modèle 3D

    /** Le modèle doit-il habiller le dragon ? (activé, packetevents présent, fichier lu) */
    private static boolean wantsCarrier(SHEggDragonBoss plugin) {
        DragonSettings.Model cfg = plugin.getConfigManager().dragon().model();
        ModelManager models = plugin.getModelManager();
        return cfg.enabled() && models != null && models.ready(cfg.file());
    }

    /** Ce boss est-il l'entité porteuse du modèle ? */
    private static boolean isCarrier(SHEggDragonBoss plugin, LivingEntity entity) {
        return entity.getType() == plugin.getConfigManager().dragon().model().carrier();
    }

    /** Entité porteuse et entité vanilla du même type : rien à échanger (le modèle habille directement le boss). */
    private static boolean sameType(SHEggDragonBoss plugin) {
        DragonSettings settings = plugin.getConfigManager().dragon();
        return settings.model().carrier() == settings.entityType();
    }

    /**
     * L'entité doit-elle changer ? Modèle prêt sur un Phantom (boss d'avant le modèle) : il passe sur l'entité porteuse ;
     * modèle coupé (model.enabled: false) sur une entité porteuse : il redevient un Phantom. Le remplacement garde PV,
     * phase et contributions ({@link BossManager#replaceDragon}). Rend faux si le contrôleur vient d'être remplacé.
     */
    private boolean modelCheck() {
        if (tickCounter % 20 != 0 || isDying() || sameType(plugin)) return true;
        boolean carrier = isCarrier(plugin, boss);
        boolean want = wantsCarrier(plugin);
        DragonSettings.Model cfg = settings.model();
        if (want != carrier && (want || !cfg.enabled())) {
            bossManager.replaceDragon(this);
            return false;
        }
        if (carrier && !boss.isInvisible()) boss.setInvisible(true);
        return true;
    }

    private BossModel.Options modelOptions() {
        return new BossModel.Options(0.5f, true, Math.max(1, settings.model().turnTicks()), true);
    }

    /** Habille le boss de son modèle 3D ; à son éclosion, il se déplie sur place avant de s'envoler. */
    private void attachModel(boolean appearing) {
        DragonSettings.Model cfg = settings.model();
        ModelManager models = plugin.getModelManager();
        if (!cfg.enabled() || models == null || !isCarrier(plugin, boss)) return;
        models.markCarrier(boss, !sameType(plugin));
        model = models.attach(boss, cfg.file(), cfg.scale(), cfg.entityScale(), (float) cfg.facing(), modelOptions());
        if (model == null) return;
        model.label(LEGACY.deserialize(settings.name()));
        if (appearing) {
            model.play("apparition");
            apparitionEndTick = tickCounter + Math.max(1, model.length("apparition"));
        }
    }

    /**
     * Un tick du modèle : état de fond selon ce que fait le dragon (vol plané, plongeon, étourdi), animation de la salve
     * lancée CRACHE_IMPACT ticks avant le tir prévu, puis les images clés dues.
     */
    private void tickModel() {
        if (model == null) {
            // Modèle pas encore lu au démarrage du boss (lecture en arrière-plan) : nouvel essai chaque seconde
            if (settings.model().enabled() && tickCounter % 20 == 0 && !isDying() && isCarrier(plugin, boss)) attachModel(false);
            return;
        }
        String loop = switch (state) {
            case DIVING -> "plonge";
            case STUNNED -> "etourdi";
            default -> "vol";
        };
        model.loop(loop);
        DragonSettings.RangedAttack ranged = settings.rangedAttack();
        if (state == State.ORBIT && ranged.enabled() && !volleyAnnounced && !model.busy()
            && nextVolleyTick - tickCounter == CRACHE_IMPACT && !playersWithin(ranged.maxRange()).isEmpty()) {
            model.playOver("crache");
            volleyAnnounced = true;
        }
        if (volleyAnnounced && tickCounter > nextVolleyTick + 20) volleyAnnounced = false;
        model.tick();
    }

    /**
     * Montée du corps en vol plané (blocs), calée sur l'animation « vol » du modèle : il monte au coup d'aile puis
     * redescend en planant (la même courbe que heave() dans l'atelier du modèle). 0 hors du vol plané.
     */
    private double heave() {
        double amplitude = settings.model().heave();
        if (model == null || amplitude <= 0.0) return 0.0;
        int tick = model.loopTick("vol");
        if (tick < 0) return 0.0;
        double t = tick / 20.0, h;
        if (t < 0.48) h = -0.12 * Math.cos(Math.PI * t / 0.48);
        else if (t < 1.2) h = 0.12 - 0.08 * smooth(t, 0.48, 1.2);
        else h = 0.04 - 0.16 * smooth(t, 1.2, 6.4);
        return h * amplitude * model.scale();
    }

    private static double smooth(double t, double a, double b) {
        double s = Math.max(0.0, Math.min(1.0, (t - a) / (b - a)));
        return s * s * (3 - 2 * s);
    }

    /** La gueule du modèle : centre de la hitbox, puis la bouche (droite, haut, avant) tournée comme le dragon. */
    private Location mouth() {
        double[] m = settings.model().mouth();
        double k = model.scale();
        Location at = boss.getLocation().add(0, boss.getHeight() / 2.0, 0);
        double yaw = Math.toRadians(at.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        // droite du dragon : avant tourné d'un quart de tour (x = -fz, z = fx)
        at.add(fx * m[2] * k - fz * m[0] * k, m[1] * k, fz * m[2] * k + fx * m[0] * k);
        return at;
    }

    public double healthNow() {
        return boss.getHealth();
    }

    public int phasesStarted() {
        return phasesStarted;
    }

    // ------------------------------------------------------------------ création de l'entité

    private static LivingEntity createEntity(DragonSettings settings, Location location, double maxHealth, double currentHealth, boolean carrier) {
        EntityType type = carrier ? settings.model().carrier() : settings.entityType();
        LivingEntity spawned = (LivingEntity) location.getWorld().spawnEntity(location, type);
        if (carrier) {
            // Entité porteuse : jamais visible, elle ne sert qu'à la hitbox et aux PV ; le modèle l'habille
            spawned.setInvisible(true);
            spawned.setGravity(false);
        }
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
