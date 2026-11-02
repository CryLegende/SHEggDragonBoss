package org.Cry_Legende.sHEggDragonBoss.boss;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.CreakingSettings;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.HeartManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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

import java.util.List;
import java.util.UUID;

/**
 * Boss n°2 : le Creaking. L'IA vanilla reste active (poursuite, attaque, gel quand un joueur le regarde : rien de cela
 * n'est réimplémenté). Le plugin ajoute :
 * <ul>
 *   <li>des phases d'invulnérabilité à certains seuils de PV, protégées par des cœurs que les joueurs détruisent à la main ;</li>
 *   <li>l'onde de choc et la résine collante ;</li>
 *   <li>un garde-fou d'arène : le boss ne quitte jamais l'île et ne tombe jamais dans le vide.</li>
 * </ul>
 */
public class CreakingBossController extends BossController implements HeartOwner {

    private final CreakingSettings settings;
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
                plugin.getLogManager().log(String.format("Un boss Creaking est apparu sur %s (Vie: %.1f/%.1f).", islandName, boss.getHealth(), maxHealth));
            }
            case WOKEN_FROM_DORMANT ->
                plugin.getLogManager().log(String.format("Un boss Creaking dormant a été réveillé sur %s (Vie: %.1f/%.1f, phase %d/%d, %d cœur(s) à détruire).",
                    islandName, boss.getHealth(), maxHealth, phasesStarted, settings.phases().size(), heartsLeft));
            case RESTORED ->
                plugin.getLogManager().log(String.format("Un boss Creaking a été restauré sur %s après un redémarrage (phase %d/%d, %d cœur(s) à détruire).",
                    islandName, phasesStarted, settings.phases().size(), heartsLeft));
        }
        // Boss repris en pleine phase d'invulnérabilité : ses cœurs avaient été retirés du monde, on les repose
        if (heartsToPlace > 0) tryPlaceHearts();
    }

    @Override
    protected void onCancel() {
        // Mort, perte de l'entité ou arrêt du plugin : aucun cœur ne doit rester dans le monde
        heartManager.removeAll(boss.getUniqueId());
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
        checkPhaseTrigger();
        if (tickCounter % settings.checkIntervalTicks() == 0) {
            enforceArena();
            processAttacks();
        }
        if (heartsLeft > 0 && tickCounter % settings.shieldAuraIntervalTicks() == 0) {
            settings.shieldAuraFx().spawn(boss.getLocation().add(0, boss.getHeight() / 2, 0),
                settings.shieldAuraFx().count(), boss.getWidth() / 2, boss.getHeight() / 2, boss.getWidth() / 2);
        }
    }

    @Override
    protected void onSecond() {
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

        plugin.getLogManager().log(String.format("Boss Creaking sur %s : phase %d/%d, %d cœur(s) à détruire (Vie: %.1f/%.1f).",
            islandLabel(), phasesStarted, settings.phases().size(), heartsLeft, boss.getHealth(), maxHealth));
        settings.shieldStartSound().play(boss.getLocation());
        tellViewers(format(settings.messages().shieldStart()));
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
            tellViewers(format(settings.messages().heartDestroyed()).replace("{player}", player.getName()));
            updateBossBar();
        } else {
            endPhase();
        }
    }

    private void endPhase() {
        heartsLeft = 0;
        heartsToPlace = 0;
        persistState();
        plugin.getLogManager().log(String.format("Boss Creaking sur %s : phase %d/%d terminée, le boss est de nouveau vulnérable (Vie: %.1f/%.1f).",
            islandLabel(), phasesStarted, settings.phases().size(), boss.getHealth(), maxHealth));
        settings.shieldEndSound().play(boss.getLocation());
        tellViewers(format(settings.messages().shieldEnd()));
        updateBossBar();
    }

    private String format(String message) {
        return message
            .replace("{hearts}", String.valueOf(heartsLeft))
            .replace("{phase}", String.valueOf(phasesStarted))
            .replace("{phases}", String.valueOf(settings.phases().size()))
            .replace("{name}", settings.name());
    }

    /** Écrit la phase et les cœurs restants sur l'entité : un redémarrage reprend le combat là où il en était. */
    private void persistState() {
        PersistentDataContainer container = boss.getPersistentDataContainer();
        container.set(SHEggDragonBoss.CREAKING_PHASE_KEY, PersistentDataType.INTEGER, phasesStarted);
        container.set(SHEggDragonBoss.CREAKING_HEARTS_KEY, PersistentDataType.INTEGER, heartsLeft);
    }

    // ------------------------------------------------------------------ dégâts reçus

    /**
     * Filtre un coup reçu par le boss (appelé avant les autres effets, sur le thread principal).
     * Pendant une phase, tout est annulé. Un coup qui ferait franchir le prochain seuil de PV est ramené pile au
     * seuil pour que la phase démarre au lieu que le boss traverse ses phases ou meure d'un coup.
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
        if (phasesStarted >= settings.phases().size()) return false;

        double thresholdHealth = maxHealth * settings.phases().get(phasesStarted).healthPercent() / 100.0;
        double health = boss.getHealth();
        if (health > thresholdHealth && health - event.getFinalDamage() <= thresholdHealth) {
            boss.setHealth(thresholdHealth);
            startPhase();
            settings.hurtSound().play(boss.getLocation());
            return true;
        }
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
            victim.damage(config.damage(), boss);
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
            .replace("{current_health}", String.valueOf((int) Math.round(boss.getHealth())))
            .replace("{max_health}", String.valueOf((int) Math.round(maxHealth)));
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
