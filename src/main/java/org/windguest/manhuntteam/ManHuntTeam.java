package org.windguest.manhuntteam;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.CopyOnWriteArraySet;

import org.bukkit.util.Vector;

public class ManHuntTeam extends JavaPlugin implements Listener, CommandExecutor {
    private final List<Location> glassBlocks = new ArrayList<>();
    public final Set<Player> red = new CopyOnWriteArraySet<>();
    public final Set<Player> blue = new CopyOnWriteArraySet<>();
    private final Set<Player> spectators = new CopyOnWriteArraySet<>();
    private final Map<UUID, String> diedPlayers = new ConcurrentHashMap<>();
    private final Map<StructureType, Set<Location>> nearestStructureCache = new ConcurrentHashMap<>();
    private final Map<StructureType, Map<Player, Location>> playerNearestStructure = new ConcurrentHashMap<>();
    private final Map<Player, Integer> playerPageIndex = new ConcurrentHashMap<>();
    private final Map<UUID, String> quitTeams = new ConcurrentHashMap<>();
    private final Inventory redSharedChest = Bukkit.createInventory(null, 54, "红队共享背包");
    private final Inventory blueSharedChest = Bukkit.createInventory(null, 54, "蓝队共享背包");
    private boolean countdownStarted = false;
    private boolean gameStarted = false;
    private boolean frozenStarted = false;
    private boolean gameEnded = false;
    private long gameStartTime = -1L;
    private double redDamage = 0;
    private double blueDamage = 0;
    private int countdown = 0;
    private boolean enderPortalOpenedRed = false;
    private boolean enderPortalOpenedBlue = false;
    private Location endLocation;
    private List<String> messages;
    private int intervalMinutes;
    private int currentMessageIndex = 0;
    private int glassFrozenTime = 0;
    private int waitingTime = 0;
    private int spawnDistance = 0;
    private boolean dragonDamaged = false;
    private Location spawnLocation = null;
    Random random = new Random();

    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        new Placeholder(this).register();
        new GameRules(this);
        loadWorld();
        createUsersFolder();
        saveDefaultConfig();
        loadConfiguration();
        startScheduledMessages();
        initializeCaches();
        getNearestNonOceanBiomeLocation();
        prepopulateStrongholdCache();
    }

    private List<Location> generatePredefinedLocations(World world) {
        List<Location> locations = new ArrayList<>();
        for (int n = 0; n <= 5; n++) {
            if (n == 0) {
                locations.add(spawnLocation.clone());
                continue;
            }
            for (int xOffset = -n; xOffset <= n; xOffset++) {
                for (int zOffset = -n; zOffset <= n; zOffset++) {
                    if (Math.abs(xOffset) != n && Math.abs(zOffset) != n) {
                        continue;
                    }
                    double x = spawnLocation.getX() + xOffset * 1000;
                    double z = spawnLocation.getZ() + zOffset * 1000;
                    double y = spawnLocation.getY();
                    Location loc = new Location(world, x, y, z);
                    locations.add(loc);
                }
            }
        }
        return locations;
    }

    private void prepopulateStrongholdCache() {
        new BukkitRunnable() {
            @Override
            public void run() {
                World overworld = Bukkit.getWorld("world");
                if (overworld == null) {
                    getLogger().severe("主世界 'world' 未找到！");
                    return;
                }
                List<Location> generatedLocations = generatePredefinedLocations(overworld);
                for (Location loc : generatedLocations) {
                    Location searchLocation = loc.clone();
                    Location foundStructure = overworld.locateNearestStructure(
                            searchLocation,
                            StructureType.STRONGHOLD,
                            1500,
                            false
                    );
                    if (foundStructure != null) {
                        nearestStructureCache.get(StructureType.STRONGHOLD).add(foundStructure);
                        getLogger().info(String.format("已缓存要塞位置：查找位置 (%.0f, %.0f, %.0f) -> 要塞位置 (%.0f, %.0f, %.0f)",
                                searchLocation.getX(), searchLocation.getY(), searchLocation.getZ(),
                                foundStructure.getX(), foundStructure.getY(), foundStructure.getZ()));
                    } else {
                        getLogger().warning(String.format("在位置 (%.0f, %.0f, %.0f) 未找到要塞。",
                                searchLocation.getX(), searchLocation.getY(), searchLocation.getZ()));
                    }
                }

                getLogger().info("预先查找要塞并缓存完成。");
            }
        }.runTask(this);
    }

    private void loadConfiguration() {
        FileConfiguration config = getConfig();
        intervalMinutes = config.getInt("scheduled-messages.interval-minutes", 3);
        messages = config.getStringList("scheduled-messages.messages");
        glassFrozenTime = config.getInt("glass-frozen-time", 30);
        waitingTime = config.getInt("waiting-time", 60);
        spawnDistance = config.getInt("spawn-distance", 200);
    }

    public void getNearestNonOceanBiomeLocation() {
        World world = Bukkit.getWorld("world");
        int maxRadius = 10000;
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    if (x * x + z * z > radius * radius) {
                        continue;
                    }
                    int y = world.getHighestBlockYAt(x, z);
                    Biome biome = world.getBiome(x, y + 1, z);
                    if (isOceanBiome(biome)) {
                        continue;
                    }
                    getLogger().info("查找到了非海洋生物群系: " + biome.name());
                    spawnLocation = new Location(world, x, y, z);
                    return;
                }
            }
        }
        int y = world.getHighestBlockYAt(0, 0);
        spawnLocation = new Location(world, 0, y, 0);
    }

    private boolean isOceanBiome(Biome biome) {
        return switch (biome) {
            case OCEAN, DEEP_OCEAN, FROZEN_OCEAN, COLD_OCEAN, LUKEWARM_OCEAN, WARM_OCEAN, DEEP_FROZEN_OCEAN,
                 DEEP_COLD_OCEAN, DEEP_LUKEWARM_OCEAN -> true;
            default -> false;
        };
    }

    private void startScheduledMessages() {
        long delay = 0L;
        long period = intervalMinutes * 60 * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () ->
        {
            if (messages.isEmpty()) return;
            String message = messages.get(currentMessageIndex);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(message);
            }
            currentMessageIndex = (currentMessageIndex + 1) % messages.size();
        }, delay, period);
    }

    private void checkAndTeleport(Player player) {
        if (player.getFlySpeed() > 0.1f) {
            player.setFlySpeed(0.1f);
        }
        if (player.getWalkSpeed() > 0.2f) {
            player.setWalkSpeed(0.2f);
        }
        Location playerLocation = player.getLocation();
        boolean hasNearbyPlayer = false;
        Player nearestPlayer = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Player p : player.getWorld().getPlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR) {
                double distance = playerLocation.distance(p.getLocation());
                if (distance <= 48) {
                    hasNearbyPlayer = true;
                    break;
                }
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestPlayer = p;
                }
            }
        }
        if (!hasNearbyPlayer && nearestPlayer != null) {
            player.teleport(nearestPlayer.getLocation());
            player.sendMessage("§c你不能距离玩家太远！");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = (Player) sender;
        if (!red.contains(player) && !blue.contains(player)) {
            player.sendMessage(ChatColor.RED + "只有游戏玩家才可以喊话！");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "使用方法: /s <消息内容>");
            return true;
        }
        String message = String.join(" ", args);
        String playerIcon = getPlayerTeamIcon(player);
        String formattedMessage = ChatColor.YELLOW + "[喊话] " + playerIcon + " " + player.getName() + ChatColor.WHITE + ": " + message;
        Bukkit.broadcastMessage(formattedMessage);
        return true;
    }

    private void loadWorld() {
        if (Bukkit.getWorld("hub") == null) {
            WorldCreator wc = new WorldCreator("hub");
            Bukkit.createWorld(wc);
        }
    }

    private void createUsersFolder() {
        File usersFolder = new File(getDataFolder(), "users");
        if (!usersFolder.exists()) {
            usersFolder.mkdirs();
        }
    }

    public long getGameElapsedTime() {
        if (gameStartTime == -1L) {
            return 0L;
        }
        return (System.currentTimeMillis() - gameStartTime) / 1000L;
    }

    private void createPlayerFileIfNotExists(Player player) {
        UUID playerUUID = player.getUniqueId();
        File playerFile = new File(getDataFolder() + "/users", playerUUID + ".yml");
        if (!playerFile.exists()) {
            try {
                playerFile.createNewFile();
                YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
                config.set("wins", 0);
                config.set("games", 0);
                config.set("kills", 0);
                config.set("deaths", 0);
                config.save(playerFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void updatePlayerConfig(Player player, String key) {
        UUID playerUUID = player.getUniqueId();
        File playerFile = new File(getDataFolder() + "/users", playerUUID + ".yml");
        if (playerFile.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
            int value = config.getInt(key);
            config.set(key, (value + 1));
            try {
                config.save(playerFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        createPlayerFileIfNotExists(player);
        event.joinMessage(Component.text("[+] ", NamedTextColor.GREEN)
                .append(Component.text(playerName, NamedTextColor.GREEN)));
        if (!gameStarted) {
            Bukkit.getScheduler().runTaskLater(this, () -> openRulesMenu(player), 20L);
            World hub = Bukkit.getWorld("hub");
            if (hub != null) {
                Location hubLocation = new Location(hub, 0.5, 81.0, 0.5);
                player.teleport(hubLocation);
                player.setGameMode(GameMode.ADVENTURE);
                giveRuleCompass(player);
                player.setInvulnerable(true);
            }
            if (Bukkit.getOnlinePlayers().size() >= 2 && !countdownStarted) {
                startCountdown();
            }
        } else {
            if (quitTeams.containsKey(playerUUID)) {
                String team = quitTeams.get(playerUUID);
                if (team.equals("blue")) {
                    blue.add(player);
                    player.sendMessage("§9[⚔] 你已恢复到蓝队！");
                    quitTeams.remove(playerUUID, "blue");
                } else {
                    red.add(player);
                    player.sendMessage("§c[🏹] 你已恢复到红队！");
                    quitTeams.remove(playerUUID, "red");
                }
                if (frozenStarted) {
                    player.setGameMode(GameMode.ADVENTURE);
                    player.setInvisible(true);
                    player.setInvulnerable(true);
                    if (team.equals("blue")) {
                        teleportToRandomPlayer(player, "blue");
                    } else if (team.equals("red")) {
                        teleportToRandomPlayer(player, "red");
                    }
                } else {
                    player.setGameMode(GameMode.SURVIVAL);
                    giveGameCompass(player);
                    player.setInvisible(false);
                    player.setInvulnerable(false);
                }
            } else if (!diedPlayers.containsKey(player.getUniqueId())) {
                if (getGameElapsedTime() <= 1800) {
                    Bukkit.getScheduler().runTaskLater(this, () ->
                    {
                        player.setInvisible(true);
                        player.setInvulnerable(true);
                        openPlaySelectionMenu(player);
                    }, 5L);
                } else {
                    spectators.add(player);
                    player.sendMessage("§7[🚫] 游戏已经进行了超过30分钟！你现在是旁观者");
                    player.setInvisible(false);
                    player.setInvulnerable(false);
                    player.setGameMode(GameMode.SPECTATOR);
                    teleportToRandomPlayer(player, "all");
                }
            } else {
                spectators.add(player);
                player.sendMessage("§7[🚫] 你已经死亡！你现在是旁观者");
                player.setInvisible(false);
                player.setInvulnerable(false);
                player.setGameMode(GameMode.SPECTATOR);
                teleportToRandomPlayer(player, "all");
            }
        }
    }

    private void openPlaySelectionMenu(Player player) {
        Inventory hunterMenu = Bukkit.createInventory(null, 27, "中途加入");
        ItemStack hunterItem = new ItemStack(Material.BOW);
        ItemMeta hunterMeta = hunterItem.getItemMeta();
        hunterMeta.setDisplayName("§a我想作为玩家中途加入游戏");
        hunterItem.setItemMeta(hunterMeta);
        ItemStack spectatorItem = new ItemStack(Material.ENDER_EYE);
        ItemMeta spectatorMeta = spectatorItem.getItemMeta();
        spectatorMeta.setDisplayName("§7我想作为旁观者观战");
        spectatorItem.setItemMeta(spectatorMeta);
        hunterMenu.setItem(11, hunterItem);
        hunterMenu.setItem(15, spectatorItem);
        player.openInventory(hunterMenu);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        event.quitMessage(Component.text("[-] ", NamedTextColor.RED)
                .append(Component.text(playerName, NamedTextColor.RED)));
        if (blue.contains(player)) {
            quitTeams.put(player.getUniqueId(), "blue");
            blue.remove(player);
        } else if (red.contains(player)) {
            quitTeams.put(player.getUniqueId(), "red");
            red.remove(player);
        } else {
            spectators.remove(player);
        }
        if (blue.isEmpty() && gameStarted) {
            endLocation = player.getLocation();
            endGame("red");
        }
        if (red.isEmpty() && gameStarted) {
            endLocation = player.getLocation();
            endGame("blue");
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (gameEnded) {
            return;
        }
        Player player = event.getEntity();
        event.setDeathMessage(null);
        Player killer = player.getKiller();
        if (killer != null) {
            updatePlayerConfig(killer, "kills");
            String killerIcon = getPlayerTeamIcon(killer);
            String playerIcon = getPlayerTeamIcon(player);
            killer.sendTitle("", "§c§l🗡 §r" + playerIcon + " " + player.getName(), 10, 70, 20);
            Bukkit.broadcastMessage("§f[☠] " + killerIcon + " " + killer.getName() + " §7击杀了 " + playerIcon + " " + player.getName());
        } else {
            String playerIcon = getPlayerTeamIcon(player);
            String deathCause = getDeathCause(player.getLastDamageCause().getCause());
            Bukkit.broadcastMessage("§f[☠] " + playerIcon + " " + player.getName() + " §7因为" + deathCause + "死亡了");
        }
        updatePlayerConfig(player, "deaths");
        Location deathLocation = player.getLocation();
        Inventory inventory = player.getInventory();
        World world = player.getWorld();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null
                    && item.getType() != Material.AIR
                    && item.getType() != Material.COMPASS
                    && !item.containsEnchantment(Enchantment.VANISHING_CURSE)) {
                world.dropItemNaturally(deathLocation, item);
            }
        }
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null
                    && armor.getType() != Material.AIR
                    && armor.getType() != Material.COMPASS
                    && !armor.containsEnchantment(Enchantment.VANISHING_CURSE)) {
                world.dropItemNaturally(deathLocation, armor);
            }
        }
        inventory.clear();
        player.setGameMode(GameMode.SPECTATOR);
        if (blue.contains(player)) {
            spawnFirework(player, Color.BLUE);
            blue.remove(player);
            diedPlayers.put(player.getUniqueId(), "blue");
            if (blue.isEmpty() && gameStarted) {
                endLocation = player.getLocation();
                endGame("red");
            }
        } else {
            spawnFirework(player, Color.RED);
            red.remove(player);
            diedPlayers.put(player.getUniqueId(), "red");
            if (red.isEmpty() && gameStarted) {
                endLocation = player.getLocation();
                endGame("blue");
            }
        }
        event.setCancelled(true);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, true, false));
    }

    private void teleportToRandomPlayer(Player player, String team) {
        Set<Player> teamPlayers = new HashSet<>();
        if (team.equals("red")) {
            teamPlayers.addAll(red);
        } else if (team.equals("blue")) {
            teamPlayers.addAll(blue);
        } else {
            teamPlayers.addAll(red);
            teamPlayers.addAll(blue);
        }
        teamPlayers.remove(player);
        if (teamPlayers.isEmpty()) {
            return;
        }
        teamPlayers.stream()
                .skip(random.nextInt(teamPlayers.size()))
                .findFirst()
                .ifPresent(randomPlayer -> player.teleport(randomPlayer.getLocation()));
    }

    private void endGame(String team) {
        if (gameEnded) {
            return;
        }
        gameEnded = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(endLocation);
            if (blue.contains(player) && team.equals("blue")) {
                updatePlayerConfig(player, "wins");
            } else if (red.contains(player) && team.equals("red")) {
                updatePlayerConfig(player, "wins");
            }
            if (diedPlayers.containsKey(player.getUniqueId())) {
                String diedTeam = diedPlayers.get(player.getUniqueId());
                if (diedTeam.equals("blue") && team.equals("blue")) {
                    updatePlayerConfig(player, "wins");
                } else if (diedTeam.equals("red") && team.equals("red")) {
                    updatePlayerConfig(player, "wins");
                }
            }
            if (team.equals("blue")) {
                player.sendTitle("", "§9⚔ 蓝队获胜！", 10, 70, 20);
            } else if (team.equals("red")) {
                player.sendTitle("", "§c🏹 红队获胜！", 10, 70, 20);
            } else {
                player.sendTitle("", "§c7? 旁观获胜！", 10, 70, 20);
            }
        }
        new BukkitRunnable() {
            public void run() {
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    cancel();
                    return;
                }
                for (int i = 0; i < 5; i++) {
                    double angle = Math.random() * 2 * Math.PI;
                    double radius = 5 + Math.random() * 5;
                    double offsetX = radius * Math.cos(angle);
                    double offsetZ = radius * Math.sin(angle);
                    Location randomLocation = endLocation.clone().add(offsetX, 0, offsetZ);
                    spawnFireworkAtLocation(randomLocation);
                }
            }
        }.runTaskTimer(this, 0L, 20L);
        Bukkit.getScheduler().runTask(this, () ->
        {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), Sound.MUSIC_DISC_CHIRP, 2.0f, 1.0f);
            }
        });
        Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 300L);
    }

    private void openRulesMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 36, "游戏规则");
        ItemStack hunterItem = new ItemStack(Material.DRAGON_EGG);
        ItemMeta hunterMeta = hunterItem.getItemMeta();
        if (hunterMeta != null) {
            hunterMeta.setDisplayName("§a获胜条件");
            ArrayList<String> hunterLore = new ArrayList<>();
            hunterLore.add("§7方式① 击杀全部敌方玩家即可获胜");
            hunterLore.add("§7方式② 末影龙死亡，并且你的队伍对末影龙造");
            hunterLore.add("§7造成的伤害 > 另一个队伍");
            hunterMeta.setLore(hunterLore);
            hunterItem.setItemMeta(hunterMeta);
        }
        menu.setItem(11, hunterItem);
        ItemStack runnerItem = new ItemStack(Material.BOW);
        ItemMeta runnerMeta = runnerItem.getItemMeta();
        if (runnerMeta != null) {
            runnerMeta.setDisplayName("§a规则");
            ArrayList<String> runnerLore = new ArrayList<>();
            runnerLore.add("§7游戏开始后同队伍玩家出生在一起");
            runnerLore.add("§7两个队伍初始相隔300格左右");
            runnerLore.add("§7玩家死亡后不可以复活，变为旁观者");
            runnerMeta.setLore(runnerLore);
            runnerItem.setItemMeta(runnerMeta);
        }
        menu.setItem(12, runnerItem);
        ItemStack teleportItem = new ItemStack(Material.COMPASS);
        ItemMeta teleportMeta = teleportItem.getItemMeta();
        if (teleportMeta != null) {
            teleportMeta.setDisplayName("§a传送");
            ArrayList<String> teleportLore = new ArrayList<>();
            teleportLore.add("§7左键指南针打开传送菜单");
            teleportLore.add("§7消耗19点血量进行传送");
            teleportLore.add("§7传送后的玩家短暂获得DEBUFF");
            teleportLore.add("§7若有队友进入过末地，则可以直接传送至末地");
            teleportMeta.setLore(teleportLore);
            teleportItem.setItemMeta(teleportMeta);
        }
        menu.setItem(13, teleportItem);
        ItemStack sharedChestItem = new ItemStack(Material.ENDER_CHEST);
        ItemMeta sharedChestMeta = sharedChestItem.getItemMeta();
        if (sharedChestMeta != null) {
            sharedChestMeta.setDisplayName("§a共享背包");
            ArrayList<String> sharedChestLore = new ArrayList<>();
            sharedChestLore.add("§7右键指南针打开共享背包");
            sharedChestLore.add("§7同队伍的玩家共用一个共享背包");
            sharedChestLore.add("§7若附近50格有玩家不能打开");
            sharedChestMeta.setLore(sharedChestLore);
            sharedChestItem.setItemMeta(sharedChestMeta);
        }
        menu.setItem(14, sharedChestItem);
        ItemStack netherStarItem = new ItemStack(Material.NETHER_STAR);
        ItemMeta netherStarMeta = netherStarItem.getItemMeta();
        if (netherStarMeta != null) {
            netherStarMeta.setDisplayName("§a末地和凋灵");
            ArrayList<String> netherStarLore = new ArrayList<>();
            netherStarLore.add("§7玩家进入末地出生在外岛");
            netherStarLore.add("§7并获得低耐久鞘翅和烟花火箭");
            netherStarLore.add("§7右键下界之星可以获得永久药效");
            netherStarLore.add("§7依次为：生命恢复Ⅱ,速度Ⅱ,急迫Ⅱ,抗性提升Ⅰ,力量Ⅱ");
            netherStarMeta.setLore(netherStarLore);
            netherStarItem.setItemMeta(netherStarMeta);
        }
        menu.setItem(15, netherStarItem);
        ItemStack datapackItem = new ItemStack(Material.OAK_SAPLING);
        ItemMeta datapackMeta = datapackItem.getItemMeta();
        if (datapackMeta != null) {
            datapackMeta.setDisplayName("§a数据包");
            ArrayList<String> datapackLore = new ArrayList<>();
            datapackLore.add("§7服务器添加了大量自定义数据包");
            datapackLore.add("§7修改了地形的生成和结构");
            datapackLore.add("§7新增了大约500种新结构");
            datapackMeta.setLore(datapackLore);
            datapackItem.setItemMeta(datapackMeta);
        }
        menu.setItem(16, datapackItem);
        ItemStack dataModItem = new ItemStack(Material.BOOK);
        ItemMeta dataModMeta = dataModItem.getItemMeta();
        if (dataModMeta != null) {
            dataModMeta.setDisplayName("§a数据修改");
            ArrayList<String> dataModLore = new ArrayList<>();
            dataModLore.add("§71. 末影龙血量修改为 500");
            dataModLore.add("§72. 猪灵交易仅可以获得黑曜石、抗火药水和末影珍珠");
            dataModLore.add("§73. 击杀凋零骷髅 100% 掉落头颅");
            dataModLore.add("§74. 击杀烈焰人 100% 掉落烈焰棒");
            dataModLore.add("§75. 击杀末影人 100% 掉落末影珍珠");
            dataModLore.add("§76. 结构中的宝箱物品爆率大幅增大");
            dataModLore.add("§77. 玩家不允许在特定世界使用床、末影水晶和重生锚");
            dataModMeta.setLore(dataModLore);
            dataModItem.setItemMeta(dataModMeta);
        }
        menu.setItem(10, dataModItem);
        ItemStack closeItem = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeItem.getItemMeta();
        if (closeMeta != null) {
            closeMeta.setDisplayName("§c关闭");
            closeItem.setItemMeta(closeMeta);
        }
        menu.setItem(31, closeItem);
        player.openInventory(menu);
    }

    private void giveGameCompass(Player player) {
        if (!hasCompass(player)) {
            ItemStack compass = new ItemStack(Material.COMPASS);
            ItemMeta meta = compass.getItemMeta();
            if (meta != null) {
                String displayName = "§a敌人指南针§7（左键或右键点击）";
                meta.setDisplayName(displayName);
                List<String> lore = Arrays.asList("§7按下§f[左键]§7打开传送菜单", "§7按下§f[右键]§7打开共享背包");
                meta.setLore(lore);
                compass.setItemMeta(meta);
            }
            player.getInventory().addItem(compass);
        }
    }

    private boolean hasCompass(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.COMPASS || !item.getItemMeta().getDisplayName().equals("§a敌人指南针§7（左键或右键点击）"))
                continue;
            return true;
        }
        return false;
    }

    private void giveRuleCompass(Player player) {
        ItemStack teamSelector = new ItemStack(Material.COMPASS);
        ItemMeta meta = teamSelector.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a大厅指南针§7（左键或右键点击）");
            List<String> lore = Arrays.asList("§7按下§f[左键]§7打开游戏规则", "§7按下§f[右键]§7选择胜利之声");
            meta.setLore(lore);
            teamSelector.setItemMeta(meta);
        }
        player.getInventory().setItem(0, teamSelector);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        Inventory closedInventory = event.getInventory();
        if (closedInventory.equals(redSharedChest) || closedInventory.equals(blueSharedChest)) {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.3f, 1.0f);
        }
        if (event.getView().getTitle().equals("中途加入") && !red.contains(player) && !blue.contains(player) && !spectators.contains(player)) {
            Bukkit.getScheduler().runTaskLater(this, () -> player.openInventory(event.getInventory()), 5L);
        }
        if (event.getView().getTitle().equals("选择职业") && !player.hasMetadata("professionChosen")) {
            Bukkit.getScheduler().runTaskLater(this, () -> openProfessionSelector(player), 5L);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Inventory openInventory = player.getOpenInventory().getTopInventory();
        if (openInventory.equals(redSharedChest) || openInventory.equals(blueSharedChest)) {
            return;
        }
        if (event.getItem() != null && event.getItem().getType() == Material.COMPASS) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (!gameStarted && player.getWorld().getName().equals("hub")) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                    openRulesMenu(player);
                } else if (gameStarted) {
                    Player nearestPlayer = findNearestEnemy(player);
                    if (nearestPlayer != null && player.getLocation().distance(nearestPlayer.getLocation()) < 50.0) {
                        player.sendMessage("§c[❌] 附近50格内有敌人，无法打开共享背包！");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    } else if (red.contains(player)) {
                        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.3f, 1.0f);
                        player.openInventory(redSharedChest);
                    } else if (blue.contains(player)) {
                        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.3f, 1.0f);
                        player.openInventory(blueSharedChest);
                    }
                }
            } else if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
                if (!gameStarted && player.getWorld().getName().equals("hub")) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                    openRulesMenu(player);
                } else if (gameStarted && !player.hasMetadata("teleporting")) {
                    Player nearestPlayer = findNearestEnemy(player);
                    if (nearestPlayer != null && player.getLocation().distance(nearestPlayer.getLocation()) < 50.0) {
                        player.sendMessage("§c[❌] 附近50格内有敌人，无法打开传送菜单！");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    } else {
                        openTeleportMenu(player, 0);
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                    }
                }
            }
        }
    }

    private void openTeleportMenu(Player player, int pageIndex) {
        Inventory teleportMenu = Bukkit.createInventory(null, 54, ("传送 - 第 " + (pageIndex + 1) + " 页"));
        ItemStack previousPage = new ItemStack(Material.ARROW);
        ItemMeta previousPageMeta = previousPage.getItemMeta();
        if (previousPageMeta != null) {
            previousPageMeta.setDisplayName("§e上一页");
            previousPage.setItemMeta(previousPageMeta);
        }
        teleportMenu.setItem(48, previousPage);
        ItemStack closeItem = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeItem.getItemMeta();
        if (closeMeta != null) {
            closeMeta.setDisplayName("§c关闭");
            closeItem.setItemMeta(closeMeta);
        }
        teleportMenu.setItem(49, closeItem);
        ItemStack nextPage = new ItemStack(Material.ARROW);
        ItemMeta nextPageMeta = nextPage.getItemMeta();
        if (nextPageMeta != null) {
            nextPageMeta.setDisplayName("§e下一页");
            nextPage.setItemMeta(nextPageMeta);
        }
        teleportMenu.setItem(50, nextPage);
        ItemStack endPortalItem = new ItemStack(Material.END_PORTAL_FRAME);
        ItemMeta endPortalMeta = endPortalItem.getItemMeta();
        if (endPortalMeta != null) {
            endPortalMeta.setDisplayName("§a传送至末地");
            List<String> endPortalLore = List.of("§7仅在有队友进入过末地后开启");
            endPortalMeta.setLore(endPortalLore);
            endPortalItem.setItemMeta(endPortalMeta);
        }
        teleportMenu.setItem(45, endPortalItem);
        ItemStack rulesItem = new ItemStack(Material.CRAFTING_TABLE);
        ItemMeta rulesMeta = rulesItem.getItemMeta();
        if (rulesMeta != null) {
            rulesMeta.setDisplayName("§a游戏规则");
            rulesItem.setItemMeta(rulesMeta);
        }
        teleportMenu.setItem(53, rulesItem);
        ArrayList<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(blue);
        allPlayers.addAll(red);
        allPlayers.remove(player);
        int totalPages = (int) Math.ceil((double) allPlayers.size() / 28.0);
        if (pageIndex < 0) {
            pageIndex = 0;
        }
        if (pageIndex >= totalPages) {
            pageIndex = totalPages - 1;
        }
        int startIndex = pageIndex * 28;
        int endIndex = Math.min(startIndex + 28, allPlayers.size());
        int[] slots = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        for (int i = startIndex; i < endIndex; ++i) {
            Player p = allPlayers.get(i);
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            if (meta != null) {
                String icon = blue.contains(p) ? "§9⚔" : "§c🏹";
                meta.setDisplayName(icon + " " + p.getName());
                meta.setOwningPlayer(p);
                ArrayList<String> lore = new ArrayList<>();
                lore.add("");
                if (player.getWorld().equals(p.getWorld())) {
                    int distance = (int) player.getLocation().distance(p.getLocation());
                    if (red.contains(p)) {
                        lore.add("§c" + getWorldName(p.getWorld()) + " [" + p.getLocation().getBlockX() + ", " + p.getLocation().getBlockY() + ", " + p.getLocation().getBlockZ() + "]");
                        lore.add("§c距离: " + distance + "格");
                    } else {
                        lore.add("§9" + getWorldName(p.getWorld()) + " [" + p.getLocation().getBlockX() + ", " + p.getLocation().getBlockY() + ", " + p.getLocation().getBlockZ() + "]");
                        lore.add("§9距离: " + distance + "格");
                    }
                } else {
                    lore.add("§c" + getWorldName(p.getWorld()));
                    lore.add("§c不同世界");
                }
                if ((blue.contains(p) && blue.contains((player)) || (red.contains(p) && red.contains((player))))) {
                    lore.add("");
                    lore.add("§e点击消耗 §f9.5 §c❤");
                    lore.add("§e传送到他的位置");
                } else {
                    lore.add("");
                    lore.add("§e点击消耗 §f9.5 §c❤");
                    lore.add("§e传送到他附近100格");
                }
                meta.setLore(lore);
                skull.setItemMeta(meta);
            }
            teleportMenu.setItem(slots[i - startIndex], skull);
        }
        playerPageIndex.put(player, pageIndex);
        player.openInventory(teleportMenu);
    }

    private Location findRandomLocationNear(Location location, double y) {
        World world = location.getWorld();
        double radius = 100.0;
        double angle = random.nextDouble() * 2.0 * Math.PI;
        double xOffset = Math.cos(angle) * radius;
        double zOffset = Math.sin(angle) * radius;
        Location newLocation = location.clone().add(xOffset, 0.0, zOffset);
        if (y == -100.0) {
            y = world.getHighestBlockYAt(newLocation) + 1.0;
        }
        newLocation.setY(y);
        return newLocation;
    }

    private String getWorldName(World world) {
        return switch (world.getEnvironment()) {
            case NORMAL -> "主世界";
            case NETHER -> "下界";
            case THE_END -> "末地";
            default -> "未知世界";
        };
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack currentItem;
        ItemStack clickedItem;
        Player player = (Player) event.getWhoClicked();
        if (event.getView().getTitle().equals("选择职业")) {
            clickedItem = event.getCurrentItem();
            event.setCancelled(true);
            if (clickedItem != null) {
                String profession = null;
                if (clickedItem.getType() == Material.ENDER_PEARL) {
                    profession = "游侠";
                } else if (clickedItem.getType() == Material.BOW) {
                    profession = "弓箭手";
                } else if (clickedItem.getType() == Material.IRON_INGOT) {
                    profession = "铁匠";
                } else if (clickedItem.getType() == Material.BARRIER) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                    player.closeInventory();
                }
                if (profession != null) {
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
                    player.setMetadata("professionChosen", new FixedMetadataValue(this, profession));
                    player.closeInventory();
                    Bukkit.broadcastMessage(("§a[✔] " + player.getName() + " 选择了职业：" + profession));
                }
            }
        } else if (event.getView().getTitle().startsWith("传送")) {
            event.setCancelled(true);
            clickedItem = event.getCurrentItem();
            if (clickedItem != null) {
                Player targetPlayer;
                OfflinePlayer target;
                SkullMeta meta;
                if (clickedItem.getType() == Material.END_PORTAL_FRAME) {
                    player.closeInventory();
                    if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
                        player.sendMessage("§c[❌] 你已经在末地，无法再次传送！");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        return;
                    }
                    if (!enderPortalOpenedBlue && blue.contains(player)) {
                        player.sendMessage("§c[❌] 还没有队友进入末地，你无法传送！");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        return;
                    }
                    if (!enderPortalOpenedRed && red.contains(player)) {
                        player.sendMessage("§c[❌] 还没有队友进入末地，你无法传送！");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        return;
                    }
                    double healthCost = 19.0;
                    double absorptionHealth = player.getAbsorptionAmount();
                    double remainingHealthCost = healthCost;
                    if (absorptionHealth > 0.0) {
                        if (absorptionHealth >= remainingHealthCost) {
                            player.setAbsorptionAmount(absorptionHealth - remainingHealthCost);
                            remainingHealthCost = 0.0;
                        } else {
                            remainingHealthCost -= absorptionHealth;
                            player.setAbsorptionAmount(0.0);
                        }
                    }
                    if (player.getHealth() > remainingHealthCost) {
                        player.setHealth(player.getHealth() - remainingHealthCost);
                        double radius = 1000.0;
                        double angle = Math.random() * 2.0 * Math.PI;
                        double x = Math.cos(angle) * radius;
                        double z = Math.sin(angle) * radius;
                        double y = 192.0;
                        player.teleport(new Location(Bukkit.getWorld("world_the_end"), x, y, z));
                        endDown(player);
                        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                        String playerIcon = getPlayerTeamIcon(player);
                        Bukkit.broadcastMessage((playerIcon + " " + player.getName() + " §7传送到了末地"));
                    } else {
                        player.sendMessage("§c[❌] 你的血量不足以进行传送！");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    }
                } else if (clickedItem.getType() == Material.BARRIER) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                    player.closeInventory();
                } else if (clickedItem.getType() == Material.ARROW) {
                    int currentPage = playerPageIndex.getOrDefault(player, 0);
                    if (clickedItem.getItemMeta().getDisplayName().equals("§e上一页")) {
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                        openTeleportMenu(player, currentPage - 1);
                    } else if (clickedItem.getItemMeta().getDisplayName().equals("§e下一页")) {
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                        openTeleportMenu(player, currentPage + 1);
                    }
                } else if (clickedItem.getType() == Material.CRAFTING_TABLE) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                    openRulesMenu(player);
                } else if (clickedItem.getType() == Material.PLAYER_HEAD && (meta = (SkullMeta) clickedItem.getItemMeta()) != null && (target = meta.getOwningPlayer()) != null && target.isOnline() && (targetPlayer = target.getPlayer()) != null) {
                    if (targetPlayer.getWorld().getEnvironment() == World.Environment.THE_END) {
                        if (red.contains(player) && !enderPortalOpenedRed) {
                            player.sendMessage("§c[❌] 你的队伍还没有进入过末地，你不能传送到末地的敌人！");
                            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                            return;
                        }
                        if (blue.contains(player) && !enderPortalOpenedBlue) {
                            player.sendMessage("§c[❌] 你的队伍还没有进入过末地，你不能传送到末地的敌人！");
                            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                            return;
                        }
                    }
                    int cost = 19;
                    double totalHealth = player.getHealth() + player.getAbsorptionAmount();
                    if (totalHealth > (double) cost) {
                        player.setMetadata("teleporting", new FixedMetadataValue(this, true));
                        startTeleportCountdown(player, targetPlayer, cost);
                        player.closeInventory();
                    } else {
                        player.sendMessage("§c[❌] 你的血量不足以进行传送！");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    }
                }
            }
        } else if (event.getView().getTitle().startsWith("游戏规则")) {
            clickedItem = event.getCurrentItem();
            event.setCancelled(true);
            if (clickedItem != null && clickedItem.getType() == Material.BARRIER) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f);
                player.closeInventory();
            }
        } else if (event.getView().getTitle().equals("中途加入")) {
            clickedItem = event.getCurrentItem();
            event.setCancelled(true);
            if (clickedItem != null) {
                if (clickedItem.getType() == Material.BOW) {
                    boolean joinRed = red.size() < blue.size();
                    if (red.size() == blue.size()) {
                        joinRed = new Random().nextBoolean();
                    }
                    if (joinRed) {
                        player.sendMessage("§c[🏹] 你加入了红队继续游戏！");
                        red.add(player);
                        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                        teleportToRandomPlayer(player, "red");
                    } else {
                        player.sendMessage("§9[⚔] 你加入了蓝队继续游戏！");
                        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                        blue.add(player);
                        teleportToRandomPlayer(player, "blue");
                    }
                    player.getInventory().remove(Material.COMPASS);
                    if (frozenStarted) {
                        player.setInvisible(true);
                        player.setInvulnerable(true);
                        player.setGameMode(GameMode.ADVENTURE);
                    } else {
                        player.setInvisible(false);
                        player.setInvulnerable(false);
                        player.setGameMode(GameMode.SURVIVAL);
                        giveGameCompass(player);
                    }
                    updatePlayerConfig(player, "games");
                    player.closeInventory();
                } else if (clickedItem.getType() == Material.ENDER_EYE) {
                    player.sendMessage("§7[🚫] 你选择了作为旁观者观战");
                    spectators.add(player);
                    player.setGameMode(GameMode.SPECTATOR);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, true, false));
                    teleportToRandomPlayer(player, "all");
                    player.closeInventory();
                }
            }
        }
        if ((event.getSlotType() == InventoryType.SlotType.CONTAINER || event.getSlotType() == InventoryType.SlotType.QUICKBAR) && (currentItem = event.getCurrentItem()) != null && currentItem.getType() == Material.COMPASS && currentItem.getItemMeta().getDisplayName().equals("§a大厅指南针§7（左键或右键点击）")) {
            event.setCancelled(true);
        }
        Inventory clickedInventory = event.getInventory();
        ItemStack currentItem2 = event.getCurrentItem();
        if ((clickedInventory.equals(redSharedChest) || clickedInventory.equals(blueSharedChest)) && (event.getAction() == InventoryAction.PLACE_ALL || event.getAction() == InventoryAction.PLACE_ONE || event.getAction() == InventoryAction.PLACE_SOME || event.getAction() == InventoryAction.SWAP_WITH_CURSOR) && currentItem2 != null && currentItem2.getType() == Material.COMPASS) {
            event.setCancelled(true);
        }
    }

    private void startTeleportCountdown(Player player, Player targetPlayer, int cost) {
        player.closeInventory();
        new BukkitRunnable() {
            int countdown = 5;

            public void run() {
                ChatColor color;
                if (countdown <= 0) {
                    teleportPlayer(player, targetPlayer, cost);
                    player.removeMetadata("teleporting", ManHuntTeam.this);
                    cancel();
                    return;
                }
                switch (countdown) {
                    case 3:
                    case 4:
                    case 5: {
                        color = ChatColor.RED;
                        break;
                    }
                    case 2: {
                        color = ChatColor.YELLOW;
                        break;
                    }
                    default: {
                        color = ChatColor.GREEN;
                    }
                }
                player.sendTitle("", color + String.valueOf(countdown), 0, 20, 0);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f);
                targetPlayer.playSound(targetPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
                --countdown;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void teleportPlayer(Player player, Player targetPlayer, int cost) {
        double regularHealth = player.getHealth();
        double absorptionHealth = player.getAbsorptionAmount();
        double currentHealth = absorptionHealth + (regularHealth);
        if (currentHealth > (double) cost) {
            if (absorptionHealth >= (double) cost) {
                player.setAbsorptionAmount(absorptionHealth - (double) cost);
            } else {
                player.setAbsorptionAmount(0.0);
                player.setHealth(regularHealth - ((double) cost - absorptionHealth));
            }
        } else {
            player.sendMessage("§c[❌] 你的血量不足以进行传送！");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        Location targetLocation = targetPlayer.getLocation();
        World.Environment targetEnvironment = targetPlayer.getWorld().getEnvironment();
        String locationMessage = null;
        boolean sameTeam = blue.contains(player) && blue.contains(targetPlayer) || red.contains(player) && red.contains(targetPlayer);
        if (sameTeam) {
            locationMessage = "§a[✔] 你已传送到 " + targetPlayer.getName() + " 的位置";
        } else if (targetEnvironment == World.Environment.NORMAL) {
            targetLocation = findRandomLocationNear(targetPlayer.getLocation(), -100.0);
            locationMessage = "§a[✔] 你已传送到 " + targetPlayer.getName() + " 附近100格的随机位置";
        } else if (targetEnvironment == World.Environment.NETHER) {
            targetLocation = findRandomLocationNear(targetPlayer.getLocation(), targetPlayer.getLocation().getY());
            locationMessage = "§a[✔] 你已传送到 " + targetPlayer.getName() + " 附近100格的随机位置";
        } else if (targetEnvironment == World.Environment.THE_END) {
            targetLocation = findRandomLocationNear(targetPlayer.getLocation(), 192.0);
            locationMessage = "§a[✔] 你已传送到 " + targetPlayer.getName() + " 附近100格的随机位置";
            endDown(player);
        }
        player.teleport(targetLocation);
        if (locationMessage != null) {
            player.sendMessage(locationMessage);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
            targetPlayer.playSound(targetPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 300, 2));
            player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 300, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 300, 2));
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 10));
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 900, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 900, 0));
            if (locationMessage.contains("随机位置")) {
                Bukkit.broadcastMessage((getPlayerTeamIcon(player) + " " + player.getName() + " §7传送到了 " + getPlayerTeamIcon(targetPlayer) + " " + targetPlayer.getName() + " §7附近100格的随机位置"));
            } else {
                Bukkit.broadcastMessage((getPlayerTeamIcon(player) + " " + player.getName() + " §7传送到了 " + getPlayerTeamIcon(targetPlayer) + " " + targetPlayer.getName() + " §7的位置"));
            }
        } else {
            player.sendMessage("§c[❌] 传送失败，未知原因！");
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        ItemStack draggedItem = event.getOldCursor();
        if (draggedItem.getType() == Material.COMPASS && draggedItem.getItemMeta().getDisplayName().equals("§a大厅指南针§7（左键或右键点击）")) {
            event.setCancelled(true);
        }
        if (draggedItem.getType() == Material.COMPASS && (event.getInventory().equals(redSharedChest) || event.getInventory().equals(blueSharedChest))) {
            event.setCancelled(true);
        }
        if (event.getView().getTitle().startsWith("游戏规则")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.getType() == Material.COMPASS) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() && meta.getDisplayName().equals("§a选择阵营§7（左键或右键点击）") || meta.getDisplayName().equals("§a敌人指南针§7（左键或右键点击）")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (gameEnded) {
            Player player = event.getPlayer();
            player.teleport(endLocation);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.getInventory().getChestplate() != null && player.getInventory().getChestplate().getType() == Material.ELYTRA) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 40, 9, true, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 40, 0, true, false));
        }
        if (player.getWorld() == Bukkit.getWorld("hub") && player.getY() < 70.0) {
            World hub = Bukkit.getWorld("hub");
            Location hubLocation = new Location(hub, 0.5, 81.0, 0.5);
            player.teleport(hubLocation);
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            checkAndTeleport(player);
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Player player;
        ItemStack chestplate;
        ProjectileSource shooter;
        if (event.getEntity() instanceof Arrow && (shooter = event.getEntity().getShooter()) instanceof Player && (chestplate = (player = (Player) shooter).getInventory().getChestplate()) != null && chestplate.getType() == Material.ELYTRA) {
            event.setCancelled(true);
            player.sendMessage("§c[❌] 你不能在穿着鞘翅时射箭！");
        }
    }

    private void openProfessionSelector(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, "选择职业");
        ItemStack rangerItem = new ItemStack(Material.ENDER_PEARL);
        ItemMeta rangerMeta = rangerItem.getItemMeta();
        if (rangerMeta != null) {
            rangerMeta.setDisplayName("§a游侠");
            ArrayList<String> rangerLore = new ArrayList<>();
            rangerLore.add("");
            rangerLore.add("§7开局获得10分钟的速度Ⅰ");
            rangerLore.add("§725分钟的急迫Ⅰ");
            rangerLore.add("§750分钟的生命恢复Ⅰ");
            rangerLore.add("§7开局获得4个末影珍珠");
            rangerMeta.setLore(rangerLore);
            rangerItem.setItemMeta(rangerMeta);
        }
        menu.setItem(11, rangerItem);
        ItemStack archerItem = new ItemStack(Material.BOW);
        ItemMeta archerMeta = archerItem.getItemMeta();
        if (archerMeta != null) {
            archerMeta.setDisplayName("§a弓箭手");
            ArrayList<String> archerLore = new ArrayList<>();
            archerLore.add("");
            archerLore.add("§7开局后获得力量3 冲击 火焰附件的弓");
            archerLore.add("§71组燧石和皮革头盔");
            archerMeta.setLore(archerLore);
            archerItem.setItemMeta(archerMeta);
        }
        menu.setItem(13, archerItem);
        ItemStack blacksmithItem = new ItemStack(Material.IRON_INGOT);
        ItemMeta blacksmithMeta = blacksmithItem.getItemMeta();
        if (blacksmithMeta != null) {
            blacksmithMeta.setDisplayName("§a铁匠");
            ArrayList<String> blacksmithLore = new ArrayList<>();
            blacksmithLore.add("");
            blacksmithLore.add("§7开局后获得16个铁、16个熟牛肉");
            blacksmithLore.add("§7铁胸甲，铁镐");
            blacksmithMeta.setLore(blacksmithLore);
            blacksmithItem.setItemMeta(blacksmithMeta);
        }
        menu.setItem(15, blacksmithItem);
        player.openInventory(menu);
    }

    private void startCountdown() {
        countdownStarted = true;
        countdown = waitingTime;
        new BukkitRunnable() {

            public void run() {
                if (countdown <= 0) {
                    Bukkit.getWorld("world").setGameRule(GameRule.SPECTATORS_GENERATE_CHUNKS, false);
                    teleportPlayersToTeamBases();
                    countdownStarted = false;
                    startGameCountdown();
                    cancel();
                    return;
                }
                if (Bukkit.getOnlinePlayers().size() < 2) {
                    countdownStarted = false;
                    countdown = waitingTime;
                    cancel();
                    return;
                }
                countdown--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void teleportPlayersToTeamBases() {
        World world = Bukkit.getWorld("world");
        int redX = spawnLocation.getBlockX() + spawnDistance / 2;
        int redZ = spawnLocation.getBlockZ() + spawnDistance / 2;
        int blueX = spawnLocation.getBlockX() - spawnDistance / 2;
        int blueZ = spawnLocation.getBlockZ() - spawnDistance / 2;
        if (world == null) {
            return;
        }
        int[][] coordinates = {
                {redX, redZ},
                {blueX, blueZ}
        };
        int cubeHeight = 5;
        int cubeSize = 5;
        for (int[] coordinate : coordinates) {
            int x = coordinate[0];
            int z = coordinate[1];
            int y = world.getHighestBlockYAt(x, z) + 10;
            Location center = new Location(world, x, y, z);
            createHollowGlassCube(center, cubeSize, cubeHeight);
        }
        assignTeams();
        startNearestStructureUpdater();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inventory = player.getInventory();
            inventory.clear();
            if (blue.contains(player)) {
                updatePlayerConfig(player, "games");
                int y = world.getHighestBlockYAt(redX, redZ) - 3;
                Location teleportLocation = new Location(world, redX, y, redZ);
                player.teleport(teleportLocation);
            } else if (red.contains(player)) {
                updatePlayerConfig(player, "games");
                int y = world.getHighestBlockYAt(blueX, blueZ) - 3;
                Location teleportLocation = new Location(world, blueX, y, blueZ);
                player.teleport(teleportLocation);
            }
        }
    }

    private void createHollowGlassCube(Location center, int size, int height) {
        World world = center.getWorld();
        if (world == null) return;
        int halfSize = size / 2;
        for (int x = -halfSize; x <= halfSize; x++) {
            for (int z = -halfSize; z <= halfSize; z++) {
                for (int y = 0; y < height; y++) {
                    Location loc = center.clone().add(x, y, z);
                    Block block = loc.getBlock();
                    boolean isEdge = (x == -halfSize || x == halfSize) ||
                            (y == 0 || y == height - 1) ||
                            (z == -halfSize || z == halfSize);
                    if (isEdge) {
                        block.setType(Material.GLASS);
                        glassBlocks.add(loc);
                    }
                }
            }
        }
    }

    private void startGameCountdown() {
        gameStarted = true;
        frozenStarted = true;
        gameStartTime = System.currentTimeMillis();
        countdown = glassFrozenTime;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasMetadata("professionChosen")) {
                openProfessionSelector(player);
            }
        }
        new BukkitRunnable() {
            public void run() {
                if (countdown <= 0) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        World world = Bukkit.getWorld("world");
                        if (player.getWorld() != world && world != null) {
                            Location worldSpawn = world.getSpawnLocation();
                            player.teleport(worldSpawn);
                        }
                        player.sendTitle("§a游戏开始！", "§c击杀末影龙或者杀死对手！", 10, 70, 20);
                        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);
                        player.setGameMode(GameMode.SURVIVAL);
                        player.setInvulnerable(false);
                        player.setInvisible(false);
                        frozenStarted = false;
                        giveGameCompass(player);
                        String profession;
                        if (player.hasMetadata("professionChosen")) {
                            profession = player.getMetadata("professionChosen").getFirst().asString();
                        } else {
                            String[] professions = new String[]{"游侠", "弓箭手", "铁匠"};
                            profession = professions[new Random().nextInt(professions.length)];
                            player.setMetadata("professionChosen", new FixedMetadataValue(ManHuntTeam.this, profession));
                        }
                        switch (profession) {
                            case "游侠": {
                                giveRangerKit(player);
                                break;
                            }
                            case "弓箭手": {
                                giveArcherKit(player);
                                break;
                            }
                            case "铁匠": {
                                giveBlacksmithKit(player);
                                break;
                            }
                        }
                        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 4));
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 2));
                        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 1200, 2));
                        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 1200, 0));
                        player.getInventory().addItem(new ItemStack(Material.BREAD, 16));
                    }
                    for (Location loc : glassBlocks) {
                        World world = loc.getWorld();
                        if (world != null) {
                            world.spawnParticle(Particle.BLOCK, loc, 30, 0.5, 0.5, 0.5, 0.1, Material.GLASS.createBlockData());
                            world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.0f);
                            Block block = loc.getBlock();
                            if (block.getType() == Material.GLASS) {
                                block.setType(Material.AIR);
                            }
                        }
                    }
                    glassBlocks.clear();
                    new BukkitRunnable() {
                        public void run() {
                            if (!gameStarted) {
                                cancel();
                                return;
                            }
                            updateCompass();
                        }
                    }.runTaskTimer(ManHuntTeam.this, 0L, 20L);
                    cancel();
                    return;
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    ChatColor color = countdown > 5 ? ChatColor.RED : (countdown > 2 ? ChatColor.YELLOW : ChatColor.GREEN);
                    player.sendTitle(color + String.valueOf(countdown), "观察四周！", 10, 20, 10);
                    if (countdown > 5) continue;
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f);
                }
                --countdown;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void giveRangerKit(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 12000, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 30000, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60000, 0));
        player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, 4));
    }

    private void giveArcherKit(Player player) {
        ItemStack bow = new ItemStack(Material.BOW);
        bow.addEnchantment(Enchantment.POWER, 3);
        bow.addEnchantment(Enchantment.PUNCH, 1);
        bow.addEnchantment(Enchantment.FLAME, 1);
        bow.addEnchantment(Enchantment.VANISHING_CURSE, 1);
        player.getInventory().addItem(bow);
        player.getInventory().addItem(new ItemStack(Material.FLINT, 64));
        player.getInventory().addItem(new ItemStack(Material.LEATHER_HELMET));
    }

    private void giveBlacksmithKit(Player player) {
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 16));
        player.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
        player.getInventory().addItem(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().addItem(new ItemStack(Material.IRON_PICKAXE));
    }

    public double calculatePlayerScore(Player player) {
        double kdr = getPlayerKDR(player);
        double wr = getPlayerWR(player);
        double games = getPlayerConfig(player, "games");
        if (games == 0) games = 1;
        return Math.min(Math.log(games) / Math.log(2), 20) + Math.min(kdr * 30, 45) + Math.min(wr * 50, 35);
    }

    private void assignTeams() {
        List<Player> allPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        allPlayers.sort((p1, p2) -> Double.compare(calculatePlayerScore(p2), calculatePlayerScore(p1)));
        double redScore = 0;
        double blueScore = 0;
        for (Player player : allPlayers) {
            double playerScore = calculatePlayerScore(player);
            if (red.size() < blue.size()) {
                red.add(player);
                redScore += playerScore;
            } else if (blue.size() < red.size()) {
                blue.add(player);
                blueScore += playerScore;
            } else {
                if (redScore <= blueScore) {
                    red.add(player);
                    redScore += playerScore;
                } else {
                    blue.add(player);
                    blueScore += playerScore;
                }
            }
        }
    }

    public int getPlayerConfig(Player player, String key) {
        File playerFile = new File(getDataFolder() + "/users", player.getUniqueId() + ".yml");
        if (playerFile.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
            return config.getInt(key);
        }
        return 0;
    }

    public double getPlayerKDR(Player player) {
        int kills = getPlayerConfig(player, "kills");
        int deaths = getPlayerConfig(player, "deaths");
        if (deaths == 0) deaths = 1;
        return (double) kills / deaths;
    }

    public double getPlayerWR(Player player) {
        int wins = getPlayerConfig(player, "wins");
        int games = getPlayerConfig(player, "games");
        if (games == 0) return 0.0;
        return (double) wins / games;
    }

    private void updateCompass() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!red.contains(player) && !blue.contains(player)) continue;
            Player nearestEnemy = findNearestEnemy(player);
            if (nearestEnemy != null) {
                int distance = (int) player.getLocation().distance(nearestEnemy.getLocation());
                String direction = getHorizontalDirectionToTarget(player, nearestEnemy);
                if (red.contains(player)) {
                    player.sendActionBar("§9最近的敌人: " + direction + " §r§9" + nearestEnemy.getName() + "（" + distance + "格）");
                } else {
                    player.sendActionBar("§c最近的敌人: " + direction + " §r§c" + nearestEnemy.getName() + "（" + distance + "格）");
                }
                player.setCompassTarget(nearestEnemy.getLocation());
                continue;
            }
            player.sendActionBar("§a没有敌人和你在一个世界！");
        }
    }

    private String getHorizontalDirectionToTarget(Player player, Player target) {
        Vector playerDirection = player.getLocation().getDirection().setY(0).normalize();
        Vector toTarget = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0).normalize();
        double angle = Math.toDegrees(Math.atan2(toTarget.getZ(), toTarget.getX()) - Math.atan2(playerDirection.getZ(), playerDirection.getX()));
        if ((angle = (angle + 360.0) % 360.0) >= 337.5 || angle < 22.5) {
            return "↑";
        }
        if (angle >= 22.5 && angle < 67.5) {
            return "§l↗";
        }
        if (angle >= 67.5 && angle < 112.5) {
            return "→";
        }
        if (angle >= 112.5 && angle < 157.5) {
            return "§l↘";
        }
        if (angle >= 157.5 && angle < 202.5) {
            return "↓";
        }
        if (angle >= 202.5 && angle < 247.5) {
            return "§l↙";
        }
        if (angle >= 247.5 && angle < 292.5) {
            return "←";
        }
        if (angle >= 292.5) {
            return "§l↖";
        }
        return "？";
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntityType() == EntityType.WITHER) {
            event.getDrops().clear();
            ItemStack customNetherStar = new ItemStack(Material.NETHER_STAR);
            ItemMeta meta = customNetherStar.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§b随身信标§7（右键点击获得永久BUFF）");
                customNetherStar.setItemMeta(meta);
            }
            event.getDrops().add(customNetherStar);
        }
        if (event.getEntityType() == EntityType.ENDER_DRAGON) {
            if (redDamage > blueDamage) {
                endLocation = event.getEntity().getLocation();
                endGame("red");
            } else if (redDamage < blueDamage) {
                endLocation = event.getEntity().getLocation();
                endGame("blue");
            } else {
                endLocation = event.getEntity().getLocation();
                endGame("gray");
            }
        }
    }

    @EventHandler
    private void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework && event.getDamager().hasMetadata("noDamage")) {
            event.setCancelled(true);
            return;
        }
        Entity damaged = event.getEntity();
        Entity damager = event.getDamager();
        double damage = event.getFinalDamage();
        if (damaged instanceof Player victim) {
            Player attacker = null;
            if (damager instanceof Player) {
                attacker = (Player) damager;
                Material attackerItem = attacker.getInventory().getItemInMainHand().getType();
                if (isAxe(attackerItem)) {
                    if (victim.isBlocking() && victim.isHandRaised() && damage == 0.0) {
                        attacker.playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 1.0f);
                        return;
                    }
                }
            }
            if (damager instanceof Arrow arrow) {
                victim.playSound(victim.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0f, 1.0f);
                if (arrow.getShooter() instanceof Player) {
                    attacker = (Player) arrow.getShooter();
                }
            }
            if (victim.isBlocking() && victim.isHandRaised() && damage == 0.0) {
                victim.playSound(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);
                if (attacker != null) {
                    attacker.playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);
                }
            }
            if (damage > 0) {
                victim.getWorld().spawnParticle(Particle.BLOCK, victim.getLocation().add(0.0, 1.0, 0.0), 30, 0.5, 0.5, 0.5, 0.0, Material.RED_WOOL.createBlockData());
            }
        }

        if (damaged instanceof EnderDragon dragon) {
            if (!dragonDamaged) {
                dragonDamaged = true;
                dragon.setMaxHealth(500.0);
                dragon.setHealth(500.0);
            }
            Player player = null;
            if (damager instanceof Player) {
                player = (Player) damager;
            }
            if (damager instanceof Projectile projectile) {
                if (projectile.getShooter() instanceof Player) {
                    player = (Player) projectile.getShooter();
                }
            }
            if (player != null) {
                if (red.contains(player)) {
                    redDamage += damage;
                } else if (blue.contains(player)) {
                    blueDamage += damage;
                }
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damagerEntity = event.getDamager();
        Entity damagedEntity = event.getEntity();

        Player damager = null;
        Player damaged = null;

        if (damagerEntity instanceof Player) {
            damager = (Player) damagerEntity;
        } else if (damagerEntity instanceof Projectile) {
            ProjectileSource source = ((Projectile) damagerEntity).getShooter();
            if (source instanceof Player) {
                damager = (Player) source;
            }
        }

        if (damagedEntity instanceof Player) {
            damaged = (Player) damagedEntity;
        }

        if (damager != null && damaged != null) {
            if (areOnSameTeam(damager, damaged)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean areOnSameTeam(Player player1, Player player2) {
        if (red.contains(player1) && red.contains(player2)) {
            return true;
        }
        if (blue.contains(player1) && blue.contains(player2)) {
            return true;
        }
        return false;
    }

    private boolean isAxe(Material material) {
        return switch (material) {
            case WOODEN_AXE, STONE_AXE, IRON_AXE, GOLDEN_AXE, DIAMOND_AXE, NETHERITE_AXE -> true;
            default -> false;
        };
    }

    private void spawnFireworkAtLocation(Location location) {
        Firework firework = location.getWorld().spawn(location, Firework.class);
        FireworkMeta fireworkMeta = firework.getFireworkMeta();
        FireworkEffect.Type type = FireworkEffect.Type.values()[random.nextInt(FireworkEffect.Type.values().length)];
        Color color1 = Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256));
        Color color2 = Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256));
        FireworkEffect effect = FireworkEffect.builder().with(type).withColor(color1).withFade(color2).flicker(random.nextBoolean()).trail(random.nextBoolean()).build();
        fireworkMeta.addEffect(effect);
        fireworkMeta.setPower(1);
        firework.setFireworkMeta(fireworkMeta);
    }

    private String getDeathCause(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case CONTACT -> "接触";
            case ENTITY_ATTACK -> "实体攻击";
            case PROJECTILE -> "投射物";
            case SUFFOCATION -> "窒息";
            case FALL -> "摔落";
            case FIRE -> "火焰";
            case FIRE_TICK -> "火焰灼烧";
            case MELTING -> "融化";
            case LAVA -> "熔岩";
            case DROWNING -> "溺水";
            case BLOCK_EXPLOSION -> "方块爆炸";
            case ENTITY_EXPLOSION -> "实体爆炸";
            case VOID -> "虚空";
            case LIGHTNING -> "闪电";
            case SUICIDE -> "自杀";
            case STARVATION -> "饥饿";
            case POISON -> "中毒";
            case MAGIC -> "魔法";
            case WITHER -> "凋零";
            case FALLING_BLOCK -> "落下的方块";
            case THORNS -> "荆棘";
            case DRAGON_BREATH -> "龙息";
            case FLY_INTO_WALL -> "撞墙";
            case HOT_FLOOR -> "热地板";
            case CRAMMING -> "挤压";
            case DRYOUT -> "干涸";
            case FREEZE -> "冻结";
            default -> "未知原因";
        };
    }

    public String getPlayerTeamIcon(Player player) {
        if (blue.contains(player)) {
            return "§9⚔";
        }
        if (red.contains(player)) {
            return "§c🏹";
        }
        return "§7🚫";
    }

    public Double getDragonDamage(String team) {
        if (team.equals("red")) {
            return redDamage;
        }
        if (team.equals("blue")) {
            return blueDamage;
        }
        return 0.0;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Material block = event.getBlock().getType();
        World.Environment environment = player.getWorld().getEnvironment();
        if (block == Material.END_CRYSTAL) {
            event.setCancelled(true);
            player.sendMessage("§c[❌] 你不能放置末影水晶！");
            return;
        }
        if (block.toString().endsWith("_BED") && environment != World.Environment.NORMAL) {
            event.setCancelled(true);
            player.sendMessage("§c[❌] 你不能在非主世界放置床！");
            return;
        }
        if (block == Material.RESPAWN_ANCHOR && environment != World.Environment.NETHER) {
            event.setCancelled(true);
            player.sendMessage("§c[❌] 你不能在非下界放置重生锚！");
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        String playerIcon = getPlayerTeamIcon(sender);
        String message = event.getMessage();
        event.setFormat(playerIcon + " " + sender.getName() + "§f: " + message);
        Set<Player> recipients = new HashSet<>(Bukkit.getOnlinePlayers());
        if (red.contains(sender)) {
            recipients.removeAll(blue);
        } else if (blue.contains(sender)) {
            recipients.removeAll(red);
        }
        event.getRecipients().clear();
        event.getRecipients().addAll(recipients);
    }

    private void spawnFirework(Player player, Color color) {
        Firework firework = player.getWorld().spawn(player.getLocation().add(0.0, 2.0, 0.0), Firework.class);
        FireworkMeta fireworkMeta = firework.getFireworkMeta();
        fireworkMeta.addEffect(FireworkEffect.builder().withColor(color).with(FireworkEffect.Type.BALL).trail(false).flicker(false).build());
        fireworkMeta.setPower(0);
        firework.setFireworkMeta(fireworkMeta);
        firework.setMetadata("noDamage", new FixedMetadataValue(this, true));
        Bukkit.getScheduler().runTaskLater(this, () ->
        {
            if (!firework.isDead()) {
                firework.detonate();
            }
        }, 1L);
    }

    private Player findNearestEnemy(Player player) {
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (player == p) continue;
            if ((red.contains(player) && blue.contains(p)) || (blue.contains(player) && red.contains(p))) {
                double distance;
                if (!player.getWorld().equals(p.getWorld()) || !((distance = player.getLocation().distance(p.getLocation())) < nearestDistance))
                    continue;
                nearestDistance = distance;
                nearest = p;
            }
        }
        return nearest;
    }

    public int getBlueCount() {
        return blue.size();
    }

    public int getRedCount() {
        return red.size();
    }

    public int getTimeLeft() {
        return countdown;
    }

    public boolean isCountdownStarted() {
        return countdownStarted;
    }

    private void initializeCaches() {
        for (StructureType type : StructureType.getStructureTypes().values()) {
            nearestStructureCache.put(type, new HashSet<>());
            playerNearestStructure.put(type, new ConcurrentHashMap<>());
        }
    }

    private void startNearestStructureUpdater() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    World world = player.getWorld();
                    World.Environment environment = world.getEnvironment();
                    switch (environment) {
                        case NORMAL:
                            handleStructureUpdate(player, StructureType.STRONGHOLD, 1500);
                            break;
                        case NETHER:
                            handleStructureUpdate(player, StructureType.NETHER_FORTRESS, 200);
                            handleStructureUpdate(player, StructureType.BASTION_REMNANT, 200);
                            break;
                        default:
                            break;
                    }
                }
            }
        }.runTaskTimer(this, 0L, 6000L);
    }

    private double calculateXZDistanceSquared(Location loc1, Location loc2) {
        double deltaX = loc1.getX() - loc2.getX();
        double deltaZ = loc1.getZ() - loc2.getZ();
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    private void handleStructureUpdate(Player player, StructureType structureType, double distanceThreshold) {
        Location playerLocation = player.getLocation();
        Set<Location> sharedCache = nearestStructureCache.get(structureType);
        Map<Player, Location> playerCache = playerNearestStructure.get(structureType);
        Location cachedStructureLocation = null;
        double thresholdSquared = distanceThreshold * distanceThreshold;
        for (Location loc : sharedCache) {
            double distanceSquared = calculateXZDistanceSquared(playerLocation, loc);
            if (distanceSquared <= thresholdSquared) {
                cachedStructureLocation = loc;
                break;
            }
        }
        if (cachedStructureLocation != null) {
            playerCache.put(player, cachedStructureLocation);
        } else {
            Location foundStructure = player.getWorld().locateNearestStructure(playerLocation, structureType, 100, false);
            if (foundStructure != null) {
                sharedCache.add(foundStructure);
                playerCache.put(player, foundStructure);
            } else {
                playerCache.remove(player);
            }
        }
    }

    public Location getNearestStructure(StructureType type, Player player) {
        return playerNearestStructure.get(type).get(player);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        World.Environment environment = world.getEnvironment();
        switch (environment) {
            case NORMAL:
                handleStructureUpdate(player, StructureType.STRONGHOLD, 1500);
                break;
            case NETHER:
                handleStructureUpdate(player, StructureType.NETHER_FORTRESS, 200);
                handleStructureUpdate(player, StructureType.BASTION_REMNANT, 200);
                break;
            default:
                break;
        }
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getTo().getWorld().getEnvironment() == World.Environment.THE_END) {
            Player player = event.getPlayer();
            World endWorld = event.getTo().getWorld();
            Advancement advancement = Bukkit.getAdvancement(NamespacedKey.minecraft("end/root"));
            if (advancement != null && !player.getAdvancementProgress(advancement).isDone()) {
                ItemStack elytra = new ItemStack(Material.ELYTRA);
                elytra.setDurability((short) (elytra.getType().getMaxDurability() - 90));
                player.getInventory().addItem(elytra);
                ItemStack fireworks = new ItemStack(Material.FIREWORK_ROCKET, 16);
                player.getInventory().addItem(fireworks);

                if (red.contains(player) && !enderPortalOpenedRed) {
                    Location portalLocation = event.getFrom();
                    String coordinates = String.format("X=%d Y=%d Z=%d",
                            portalLocation.getBlockX(),
                            portalLocation.getBlockY(),
                            portalLocation.getBlockZ());
                    String mainTitle = ChatColor.RED + "红队首次进入末地！";
                    String subTitle = ChatColor.RED + coordinates;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendTitle(mainTitle, subTitle, 10, 70, 20);
                    }
                    String chatMessage = ChatColor.RED + "[🏹] 红队首次进入末地！位置: " + coordinates;
                    Bukkit.broadcastMessage(chatMessage);
                }
                if (blue.contains(player) && !enderPortalOpenedBlue) {
                    Location portalLocation = event.getFrom();
                    String coordinates = String.format("X=%d Y=%d Z=%d",
                            portalLocation.getBlockX(),
                            portalLocation.getBlockY(),
                            portalLocation.getBlockZ());
                    String mainTitle = ChatColor.BLUE + "蓝队首次进入末地！";
                    String subTitle = ChatColor.BLUE + coordinates;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendTitle(mainTitle, subTitle, 10, 70, 20);
                    }
                    String chatMessage = ChatColor.BLUE + "[⚔] 蓝队首次进入末地！位置: " + coordinates;
                    Bukkit.broadcastMessage(chatMessage);
                    for (Entity entity : endWorld.getEntities()) {
                        if (entity instanceof EnderDragon dragon) {
                            double newMaxHealth = 1000.0;
                            dragon.setMaxHealth(newMaxHealth);
                        }
                    }
                }
            }
            if (red.contains(player)) {
                enderPortalOpenedRed = true;
            } else if (blue.contains(player)) {
                enderPortalOpenedBlue = true;
            }
            double angle = Math.random() * 2.0 * Math.PI;
            double x = 1000.0 * Math.cos(angle);
            double z = 1000.0 * Math.sin(angle);
            double y = 192.0;
            Location targetLocation = new Location(endWorld, x, y, z);
            event.setTo(targetLocation);
            endDown(player);
        }
    }

    private void endDown(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 2400, 0));
        BukkitRunnable task = new BukkitRunnable() {

            public void run() {
                if (player.getLocation().getY() < 32.0) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 200, 4));
                }
            }
        };
        task.runTaskTimer(this, 0L, 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> task.cancel(), 2400L);
    }
}