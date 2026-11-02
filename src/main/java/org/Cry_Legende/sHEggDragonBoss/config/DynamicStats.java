package org.Cry_Legende.sHEggDragonBoss.config;

/**
 * PV dynamiques d'un boss : {@code base + parNiveauDIle × niveau + parMembre × membres}.
 */
public record DynamicStats(boolean enabled, double healthPerIslandLevel, double healthPerIslandMember) {
}
