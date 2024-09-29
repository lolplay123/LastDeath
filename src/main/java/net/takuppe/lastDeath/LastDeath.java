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

import java.sql.*;
import java.util.Objects;
import java.util.UUID;

public class LastDeath extends JavaPlugin implements Listener {

    private Connection connection;

    @Override
    public void onEnable() {
        // config.ymlの生成
        saveDefaultConfig();

        getLogger().info("LastDeath is now enabled!");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("lastdeath")).setExecutor(new Commands(this));

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

        try (Connection connection = getConnection()) {
            if (connection == null || connection.isClosed()) {
                getLogger().severe("Database connection is not available.");
                return;
            }

            // データベース内のMCIDを取得して比較し、変更があった場合に更新
            String query = "SELECT mcid FROM players WHERE uuid = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, uuid.toString());
                ResultSet resultSet = preparedStatement.executeQuery();

                if (resultSet.next()) {
                    String currentMCID = resultSet.getString("mcid");
                    if (!currentMCID.equals(newMCID)) {
                        // MCIDが変更されている場合のみデータベースを更新
                        String updateQuery = "UPDATE players SET mcid = ? WHERE uuid = ?";
                        try (PreparedStatement updateStatement = connection.prepareStatement(updateQuery)) {
                            updateStatement.setString(1, newMCID);
                            updateStatement.setString(2, uuid.toString());
                            updateStatement.executeUpdate();

                            getLogger().info("Player data updated: UUID=" + uuid + ", MCID=" + newMCID);
                        }
                    }
                } else {
                    // データベースにプレイヤーが存在しない場合、新規追加
                    String insertQuery = "INSERT INTO players (uuid, mcid) VALUES (?, ?)";
                    try (PreparedStatement insertStatement = connection.prepareStatement(insertQuery)) {
                        insertStatement.setString(1, uuid.toString());
                        insertStatement.setString(2, newMCID);
                        insertStatement.executeUpdate();

                        getLogger().info("Inserted new player into database: UUID=" + uuid + ", MCID=" + newMCID);
                    }
                }
            }
        } catch (SQLException e) {
            getLogger().severe("An error occurred while updating player MCID : " + e.getMessage());
            e.fillInStackTrace();
        }
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
                    insertStatement.setString(2, Objects.requireNonNull(loc.getWorld()).getName());
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
                getLogger().severe("An error occurred while saving death data : " + e.getMessage());
                Bukkit.getScheduler().runTask(this, () -> player.sendMessage("§7[§4ERROR§7] §rAn error occurred while saving your death data."));
            }
        });
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling LastDeath...");

        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.fillInStackTrace();
        }

        getLogger().info("LastDeath is now disabled!");
    }

    public Connection getConnection() {
        try {
            return getDatabaseConnection();
        } catch (SQLException e) {
            getLogger().severe("Failed to get database connection : " + e.getMessage());
            return null;
        }
    }
}
