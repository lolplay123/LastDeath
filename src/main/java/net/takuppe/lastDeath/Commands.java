package net.takuppe.lastDeath;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

public class Commands implements CommandExecutor, TabCompleter {

    private final LastDeath plugin;

    public Commands(LastDeath plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lastdeath")) {
            return false;
        }

        // OPまたは * 権限を持つ場合、すべてのコマンドを実行できる
        boolean hasAllPermissions = sender.isOp() || sender.hasPermission("*");

        if (args.length == 0) {
            sender.sendMessage("§7[§cNG§7] §rInvalid command usage.");
            sender.sendMessage("§c/lastdeath show <or> /lastdeath show <player>");
            sender.sendMessage("§c/lastdeath reload");
            sender.sendMessage("§c/lastdeath deletelog <player>");
            return true;
        }

        if (args[0].equalsIgnoreCase("show")) {
            if (args.length == 1 && sender instanceof Player player) {
                if (hasAllPermissions || sender.hasPermission("lastdeath.show.self")) {
                    showLastDeathInfo(sender, player.getUniqueId());
                } else {
                    sender.sendMessage("§7[§cNG§7] §rYou do not have permission to view your own death info.");
                }
            } else if (args.length == 2) {
                String targetName = args[1];
                Player player = (sender instanceof Player) ? (Player) sender : null;

                if (player != null && targetName.equalsIgnoreCase(player.getName())) {
                    if (hasAllPermissions || sender.hasPermission("lastdeath.show.self")) {
                        showLastDeathInfo(sender, player.getUniqueId());
                    } else {
                        sender.sendMessage("§7[§cNG§7] §rYou do not have permission to view your own death info.");
                    }
                } else {
                    if (hasAllPermissions || sender.hasPermission("lastdeath.show.other")) {
                        Player target = Bukkit.getPlayer(targetName);
                        if (target != null) {
                            showLastDeathInfo(sender, target.getUniqueId());
                        } else {
                            getOfflinePlayerInfo(sender, targetName);
                        }
                    } else {
                        sender.sendMessage("§7[§cNG§7] §rYou do not have permission to view other players' death data.");
                    }
                }
            } else {
                sender.sendMessage("§7[§cNG§7] §rToo many arguments.");
                sender.sendMessage("§cUsage: /lastdeath show <or> /lastdeath show <player>");
            }
        } else if (args[0].equalsIgnoreCase("reload")) {
            if (args.length > 1) {
                sender.sendMessage("§7[§cNG§7] §rToo many arguments.");
                sender.sendMessage("§cUsage: /lastdeath reload");
                return true;
            }
        } else if (args[0].equalsIgnoreCase("deletelog")) {
            if (args.length == 2) {
                String targetName = args[1];
                if (hasAllPermissions || sender.hasPermission("lastdeath.deletelog")) {
                    deletePlayerDeathLog(sender, targetName);
                } else {
                    sender.sendMessage("§7[§cNG§7] §rYou do not have permission to delete death logs.");
                }
            } else {
                sender.sendMessage("§7[§cNG§7] §rToo many arguments.");
                sender.sendMessage("§cUsage: /lastdeath deletelog <player>");
            }
        } else {
            sender.sendMessage("§7[§cNG§7] §rInvalid command usage.");
            sender.sendMessage("§c/lastdeath show <or> /lastdeath show <player>");
            sender.sendMessage("§c/lastdeath reload");
            sender.sendMessage("§c/lastdeath deletelog <player>");
        }

        return true;
    }

    private void deletePlayerDeathLog(CommandSender sender, String mcid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = plugin.getConnection()) {
                if (connection == null || connection.isClosed()) {
                    throw new SQLException("Database connection is not available.");
                }

                String uuidQuery = "SELECT uuid FROM players WHERE mcid = ?";
                try (PreparedStatement uuidStatement = connection.prepareStatement(uuidQuery)) {
                    uuidStatement.setString(1, mcid);
                    ResultSet resultSet = uuidStatement.executeQuery();

                    if (!resultSet.next()) {
                        // プレイヤーのデータが見つからない場合の処理
                        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7[§cNG§7] §rThe specified player §b" + mcid + "§r's data was not found in the database. It's possible that you've never logged into the server."));
                        return;
                    }

                    UUID uuid = UUID.fromString(resultSet.getString("uuid"));

                    String deleteQuery = "DELETE FROM deaths WHERE uuid = ?";
                    try (PreparedStatement deleteStatement = connection.prepareStatement(deleteQuery)) {
                        deleteStatement.setString(1, uuid.toString());
                        int rowsAffected = deleteStatement.executeUpdate();

                        if (rowsAffected > 0) {
                            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7[§aOK§7] §rSuccessfully deleted all death logs for player §7: §b" + mcid));
                        } else {
                            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7[§cNG§7] §rNo death data found for specified player §7: §b" + mcid));
                        }
                    }
                }
            } catch (SQLException e) {
                // 重大なエラーの場合の処理
                plugin.getLogger().severe("An error occurred while deleting death logs for player: " + mcid + " - " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7[§4ERROR§7] §rAn error occurred while deleting death logs. Please try again later."));
            }
        });
    }

    private void showLastDeathInfo(CommandSender sender, UUID uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = plugin.getConnection()) {
                if (connection == null || connection.isClosed()) {
                    throw new SQLException("Database connection is not available.");
                }

                String playerQuery = "SELECT mcid FROM players WHERE uuid = ?";
                try (PreparedStatement playerStatement = connection.prepareStatement(playerQuery)) {
                    playerStatement.setString(1, uuid.toString());
                    ResultSet playerResultSet = playerStatement.executeQuery();

                    if (!playerResultSet.next()) {
                        throw new SQLException("The player's data does not exist in the database for UUID : " + uuid);
                    }

                    String mcid = playerResultSet.getString("mcid");

                    String query = "SELECT world, x, y, z, time FROM deaths WHERE uuid = ? ORDER BY time DESC";
                    try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                        preparedStatement.setString(1, uuid.toString());
                        ResultSet resultSet = preparedStatement.executeQuery();

                        if (resultSet.next()) {
                            String title = "&r&m-=-=-&r &7[&rDeath log(s) for &b" + mcid + "&7] &r&m-=-=-";
                            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.translateAlternateColorCodes('&', title)));

                            String header = "&r*  &eWorld  X  Y  Z  Time";
                            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.translateAlternateColorCodes('&', header)));

                            Map<String, Object> aliasMapRaw = Objects.requireNonNull(plugin.getConfig().getConfigurationSection("Alias")).getValues(false);
                            Map<String, String> aliasMap = new HashMap<>();
                            for (Map.Entry<String, Object> entry : aliasMapRaw.entrySet()) {
                                if (entry.getValue() instanceof String) {
                                    aliasMap.put(entry.getKey(), (String) entry.getValue());
                                }
                            }

                            int index = 0;
                            do {
                                String world = resultSet.getString("world");
                                if (aliasMap.containsKey(world)) {
                                    world = ChatColor.translateAlternateColorCodes('&', aliasMap.get(world));
                                }

                                double x = resultSet.getDouble("x");
                                double y = resultSet.getDouble("y");
                                double z = resultSet.getDouble("z");
                                long time = resultSet.getLong("time");
                                long timeSinceDeath = (Instant.now().toEpochMilli() - time) / 1000;

                                String formattedTime = formatTime(timeSinceDeath);
                                String message = "&7" + (index + 1) + " &b" + world + " &r" + String.format("%.1f", x) + " &r"
                                        + String.format("%.1f", y) + " &r" + String.format("%.1f", z) + " &a" + formattedTime;

                                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message)));
                                index++;
                            } while (resultSet.next() && index < 5);
                            sender.sendMessage("");
                        } else {
                            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7[§cNG§7] §rNo death data found for specified player §7: §b" + mcid));
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("An error occurred while retrieving death data for UUID : " + uuid + " - " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7[§4ERROR§7] §rAn error occurred while retrieving death data. Please try again later."));
            }
        });
    }

    private String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + "s Ago";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m Ago";
        } else {
            return (seconds / 3600) + "h Ago";
        }
    }

    private void getOfflinePlayerInfo(CommandSender sender, String mcid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = plugin.getConnection()) {
                if (connection == null || connection.isClosed()) {
                    throw new SQLException("Database connection is not available.");
                }

                String query = "SELECT uuid FROM players WHERE mcid = ?";
                try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                    preparedStatement.setString(1, mcid);
                    ResultSet resultSet = preparedStatement.executeQuery();

                    if (resultSet.next()) {
                        UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                        showLastDeathInfo(sender, uuid);
                    } else {
                        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7[§cNG§7] §rThe specified player §b" + mcid + "§r's data was not found in the database. It's possible that you've never logged into the server."));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("An error occurred while retrieving player data for MCID : " + mcid + " - " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7[§4ERROR§7] §rAn error occurred while retrieving player data. Please try again later."));
            }
        });
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("lastdeath")) {
            if (args.length == 1) {
                if ("show".startsWith(args[0].toLowerCase())) {
                    completions.add("show");
                }
                if ("reload".startsWith(args[0].toLowerCase())) {
                    completions.add("reload");
                }
                if ("deletelog".startsWith(args[0].toLowerCase())) {
                    completions.add("deletelog");
                }
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("show") || args[0].equalsIgnoreCase("deletelog")) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                            completions.add(player.getName());
                        }
                    }

                    try (Connection connection = plugin.getConnection()) {
                        String query = "SELECT mcid FROM players";
                        try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                            ResultSet resultSet = preparedStatement.executeQuery();
                            while (resultSet.next()) {
                                String mcid = resultSet.getString("mcid");
                                if (mcid.toLowerCase().startsWith(args[1].toLowerCase())) {
                                    completions.add(mcid);
                                }
                            }
                        }
                    } catch (SQLException e) {
                        plugin.getLogger().severe("An error occurred while retrieving player data for tab completion : " + e.getMessage());
                    }
                }
            }
        }
        return completions;
    }
}
