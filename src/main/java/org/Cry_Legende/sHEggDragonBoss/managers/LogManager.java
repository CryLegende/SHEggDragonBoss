package org.Cry_Legende.sHEggDragonBoss.managers;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LogManager {

    private final SHEggDragonBoss plugin;
    private File logFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public LogManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        setupLogFile();
    }

    private void setupLogFile() {
        File logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        String fileName = "boss-log-" + dateFormat.format(new Date()) + ".log";
        this.logFile = new File(logDir, fileName);
    }

    public void log(String message) {
        // S'assure que nous utilisons le bon fichier de log pour la date actuelle
        String currentFileName = "boss-log-" + dateFormat.format(new Date()) + ".log";
        if (!logFile.getName().equals(currentFileName)) {
            setupLogFile(); // La date a changé, on crée un nouveau fichier
        }

        String timestamp = timeFormat.format(new Date());
        String logMessage = "[" + timestamp + "] " + message;

        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(logMessage);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible d'écrire dans le fichier de log : " + logFile.getName());
            e.printStackTrace();
        }
    }
}