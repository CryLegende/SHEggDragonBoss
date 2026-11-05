package org.Cry_Legende.sHEggDragonBoss.managers;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;

/** Écrit le journal des boss dans le fichier « boss » de SHLogs. */
public class LogManager {

    private static final String LOG_FILE = "boss";

    private final SHEggDragonBoss plugin;

    public LogManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
    }

    public void log(String message) {
        ActionLog.write(plugin, LOG_FILE, message);
    }
}
