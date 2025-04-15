package org.windguest.manhuntteam;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.StructureType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class Placeholder extends PlaceholderExpansion {
    private final ManHuntTeam plugin;

    public Placeholder(ManHuntTeam plugin) {
        this.plugin = plugin;
    }

    public boolean persist() {
        return true;
    }

    public boolean canRegister() {
        return true;
    }

    public @NotNull String getIdentifier() {
        return "manhunt";
    }

    public @NotNull String getAuthor() {
        return this.plugin.getDescription().getAuthors().toString();
    }

    public @NotNull String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    public String onPlaceholderRequest(Player player, String identifier) {
        switch (identifier) {
            case "countdown":
                if (this.plugin.isCountdownStarted()) {
                    return "将在 §a" + this.plugin.getTimeLeft() + " §f秒后开始";
                }
                return "等待玩家";
            case "team":
                if (this.plugin.blue.contains(player)) {
                    return "§9蓝队";
                }
                if (this.plugin.red.contains(player)) {
                    return "§c红队";
                }
                return "§7旁观者";
            case "icon":
                return this.plugin.getPlayerTeamIcon(player);
            case "red_alive":
                return String.valueOf(this.plugin.getRedCount());
            case "blue_alive":
                return String.valueOf(this.plugin.getBlueCount());
            case "nearest_stronghold":
                Location strongholdLocation = this.plugin.getNearestStructure(StructureType.STRONGHOLD, player);
                if (strongholdLocation != null) {
                    return "[" + strongholdLocation.getBlockX() + ", " + strongholdLocation.getBlockZ() + "]";
                }
                return "未找到";
            case "nearest_nether_fortress":
                Location fortressLocation = this.plugin.getNearestStructure(StructureType.NETHER_FORTRESS, player);
                if (fortressLocation != null) {
                    return "[" + fortressLocation.getBlockX() + ", " + fortressLocation.getBlockZ() + "]";
                }
                return "未找到";
            case "nearest_bastion_remnant":
                Location bastionLocation = this.plugin.getNearestStructure(StructureType.BASTION_REMNANT, player);
                if (bastionLocation != null) {
                    return "[" + bastionLocation.getBlockX() + ", " + bastionLocation.getBlockZ() + "]";
                }
                return "未找到";
            case "wins":
                return String.valueOf(this.plugin.getPlayerConfig(player, "wins"));
            case "games":
                return String.valueOf(this.plugin.getPlayerConfig(player, "games"));
            case "wr":
                double wr = this.plugin.getPlayerWR(player);
                return String.format("%.2f", wr);
            case "kills":
                return String.valueOf(this.plugin.getPlayerConfig(player, "kills"));
            case "deaths":
                return String.valueOf(this.plugin.getPlayerConfig(player, "deaths"));
            case "red_dragon_damage":
                double redDamage = this.plugin.getDragonDamage("red");
                return String.format("%.1f", redDamage);
            case "blue_dragon_damage":
                double blueDamage = this.plugin.getDragonDamage("blue");
                return String.format("%.1f", blueDamage);
            case "score":
                double score = this.plugin.calculatePlayerScore(player);
                return String.format("%.1f", score);
            case "elapsed_time":
                long elapsedTime = this.plugin.getGameElapsedTime();
                long minutes = elapsedTime / 60L;
                long seconds = elapsedTime % 60L;
                return String.format("%d分%d秒", minutes, seconds);
        }
        return null;
    }
}