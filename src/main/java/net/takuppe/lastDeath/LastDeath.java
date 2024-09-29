package net.takuppe.lastDeath;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public class LastDeath extends JavaPlugin implements Listener {

    private Connection connection;

    @Override
    public void onEnable() {
        // config.ymlの生成
        saveDefaultConfig();

        getLogger().info("LastDeath is now enabled!");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("lastdeath").setExecutor(new Commands(this));

        // データベース接続設定
        try {
            getDatabaseConnection();
            createTables();
        } catch (SQLException e) {
            getLogger().severe("Could not connect to the SQLite database." + e.getMessage());
        }
    }

    private Connection getDatabaseConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            String dbPath = getDataFolder().getAbsolutePath() + "/deaths.db";
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        }
        return connection;
    }

    private void createTables() throws SQLException {
        String createDeathsTableQuery = "CREATE TABLE IF NOT EXISTS deaths (uuid TEXT, world TEXT, x REAL, y REAL, z REAL, time LONG);";
        String createPlayersTableQuery = "CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, mcid TEXT NOT NULL);";

        try (Statement statement = getDatabaseConnection().createStatement()) {
            statement.execute(createDeathsTableQuery);
            statement.execute(createPlayersTableQuery);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String newMCID = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Connection connection = getDatabaseConnection()) {
                String query = "INSERT OR REPLACE INTO players (uuid, mcid) VALUES (?, ?)";
                try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                    preparedStatement.setString(1, uuid.toString());
                    preparedStatement.setString(2, newMCID);
                    preparedStatement.executeUpdate();
                }
                getLogger().info("Player data updated: UUID=" + uuid + ", MCID=" + newMCID);
            } catch (SQLException e) {
                getLogger().severe("An error occurred while updating player data: " + e.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location loc = player.getLocation();
        long deathTime = System.currentTimeMillis();

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Connection connection = getDatabaseConnection()) {
                // プレイヤー情報の更新
                String updatePlayerInfoQuery = "INSERT OR REPLACE INTO players (uuid, mcid) VALUES (?, ?)";
                try (PreparedStatement updatePlayerInfoStatement = connection.prepareStatement(updatePlayerInfoQuery)) {
                    updatePlayerInfoStatement.setString(1, player.getUniqueId().toString());
                    updatePlayerInfoStatement.setString(2, player.getName());
                    updatePlayerInfoStatement.executeUpdate();
                }

                // デス情報の追加
                String insertQuery = "INSERT INTO deaths (uuid, world, x, y, z, time) VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement insertStatement = connection.prepareStatement(insertQuery)) {
                    insertStatement.setString(1, player.getUniqueId().toString());
                    insertStatement.setString(2, loc.getWorld().getName());
                    insertStatement.setDouble(3, loc.getX());
                    insertStatement.setDouble(4, loc.getY());
                    insertStatement.setDouble(5, loc.getZ());
                    insertStatement.setLong(6, deathTime);
                    insertStatement.executeUpdate();
                }

                // 古いデス情報の削除
                String deleteOldRecordsQuery = "DELETE FROM deaths WHERE uuid = ? AND time NOT IN (SELECT time FROM deaths WHERE uuid = ? ORDER BY time DESC LIMIT 5)";
                try (PreparedStatement deleteOldRecordsStatement = connection.prepareStatement(deleteOldRecordsQuery)) {
                    deleteOldRecordsStatement.setString(1, player.getUniqueId().toString());
                    deleteOldRecordsStatement.setString(2, player.getUniqueId().toString());
                    deleteOldRecordsStatement.executeUpdate();
                }

                // 成功メッセージの送信
                Bukkit.getScheduler().runTask(this, () -> {
                    String deathMessage = getConfig().getString("Death", "You died at &7<&b$world &6$x &6$y &6$z&7> &r☠");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', deathMessage
                            .replace("$world", loc.getWorld().getName())
                            .replace("$x", String.format("%.1f", loc.getX()))
                            .replace("$y", String.format("%.1f", loc.getY()))
                            .replace("$z", String.format("%.1f", loc.getZ()))));
                });
            } catch (SQLException e) {
                getLogger().severe("An error occurred while saving death data: " + e.getMessage());
                Bukkit.getScheduler().runTask(this, () -> player.sendMessage(ChatColor.COLOR_CHAR + "§7[§cErr§7] §rAn error occurred while saving your death data."));
            }
        });
    }

    @Override
    public void onDisable() {
        getLogger().info("death.db saving...");

        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                getLogger().info("Saved death.db!");
            }
        } catch (SQLException e) {
            e.getMessage();
        }

        getLogger().info("LastDeath is now disabled!");
    }

    public Connection getConnection() {
        try {
            return getDatabaseConnection();
        } catch (SQLException e) {
            getLogger().severe("Failed to get database connection: " + e.getMessage());
            return null;
        }
    }
}
