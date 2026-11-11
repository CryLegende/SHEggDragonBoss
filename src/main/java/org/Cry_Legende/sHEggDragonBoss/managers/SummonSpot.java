package org.Cry_Legende.sHEggDragonBoss.managers;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.boss.BossType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Point d'apparition d'un boss invoqué par commande : la colonne du joueur, déplacée vers le haut ou le bas jusqu'à un
 * sol plein avec assez de vide au-dessus pour le boss. Le décalage {@code spawn-offset-y} du rituel s'ajoute ensuite
 * au sol, comme il s'ajoute au bloc du rituel.
 */
public final class SummonSpot {

    /** Hauteur de l'entité (Creaking 2,7 blocs, Phantom 0,5 bloc). */
    private static double height(BossType type) {
        return switch (type) {
            case CREAKING -> 2.7;
            case DRAGON -> 0.5;
        };
    }

    private SummonSpot() {
    }

    /**
     * @param origin    position du joueur (ses pieds)
     * @param offsetY   décalage {@code spawn-offset-y} du rituel du boss
     * @param maxSearch distance verticale maximale, en blocs, entre les pieds du joueur et le sol retenu
     * @param island    île du joueur : le point doit en faire partie
     * @return le point d'apparition (centre du bloc, au sol plus le décalage), ou null s'il n'y a aucun emplacement sûr
     */
    public static Location find(Location origin, BossType type, double offsetY, int maxSearch, Island island) {
        World world = origin.getWorld();
        if (world == null) return null;
        int x = origin.getBlockX();
        int z = origin.getBlockZ();
        int feet = origin.getBlockY();
        // Cases libres exigées au-dessus du sol : le décalage du rituel, puis le corps du boss
        int clearance = (int) Math.ceil(Math.max(0.0, offsetY) + height(type));
        for (int distance = 0; distance <= maxSearch; distance++) {
            for (int side = 0; side < (distance == 0 ? 1 : 2); side++) {
                int y = feet + (side == 0 ? distance : -distance);
                if (!fits(world, x, y, z, clearance)) continue;
                Location spot = new Location(world, x + 0.5, y + offsetY, z + 0.5, origin.getYaw(), 0.0f);
                if (island.isInside(spot)) return spot;
            }
        }
        return null;
    }

    /** Un sol plein sous {@code y} et {@code clearance} cases ni pleines ni liquides à partir de {@code y}. */
    private static boolean fits(World world, int x, int y, int z, int clearance) {
        if (y - 1 < world.getMinHeight() || y + clearance > world.getMaxHeight()) return false;
        Block ground = world.getBlockAt(x, y - 1, z);
        if (ground.isPassable() || ground.isLiquid()) return false;
        for (int dy = 0; dy < clearance; dy++) {
            Block block = world.getBlockAt(x, y + dy, z);
            if (!block.isPassable() || block.isLiquid()) return false;
        }
        return true;
    }
}
