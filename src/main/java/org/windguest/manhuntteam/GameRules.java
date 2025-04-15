package org.windguest.manhuntteam;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Random;

public class GameRules implements Listener {
    private final JavaPlugin plugin;
    private final Random random = new Random();

    public GameRules(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onLootGenerate(LootGenerateEvent event) {
        World world = event.getWorld();
        List<ItemStack> loot = event.getLoot();
        for (ItemStack item : loot) {
            int multiplier = random.nextInt(3) + 1;
            item.setAmount(item.getAmount() * multiplier);
        }
        if (world.getEnvironment() == World.Environment.THE_END) {
            addRandomItems(loot);
        }
    }

    private void addRandomItems(List<ItemStack> loot) {
        if (random.nextDouble() < 0.30) {
            ItemStack elytra = new ItemStack(Material.ELYTRA);
            setRandomDurability(elytra, 2, 20);
            loot.add(elytra);
        }
        if (random.nextDouble() < 0.40) {
            int amount = 1 + random.nextInt(8);
            ItemStack fireworks = new ItemStack(Material.FIREWORK_ROCKET, amount);
            loot.add(fireworks);
        }
        if (random.nextDouble() < 0.20) {
            ItemStack diamondSword = new ItemStack(Material.DIAMOND_SWORD);
            setRandomDurability(diamondSword, 20, 100);
            int sharpnessLevel = random.nextInt(6);
            int knockbacklevel = random.nextInt(3);
            if (sharpnessLevel > 0) {
                diamondSword.addUnsafeEnchantment(Enchantment.SHARPNESS, sharpnessLevel);
            }
            if (knockbacklevel > 0) {
                diamondSword.addUnsafeEnchantment(Enchantment.KNOCKBACK, sharpnessLevel);
            }
            loot.add(diamondSword);
        }
        if (random.nextDouble() < 0.20) {
            ItemStack diamondAxe = new ItemStack(Material.DIAMOND_AXE);
            setRandomDurability(diamondAxe, 20, 100);
            int sharpnessLevel = random.nextInt(6);
            int efficiencyLevel = random.nextInt(6);
            if (sharpnessLevel > 0) {
                diamondAxe.addUnsafeEnchantment(Enchantment.SHARPNESS, sharpnessLevel);
            }
            if (efficiencyLevel > 0) {
                diamondAxe.addUnsafeEnchantment(Enchantment.EFFICIENCY, efficiencyLevel);
            }
            loot.add(diamondAxe);
        }
        if (random.nextDouble() < 0.20) {
            ItemStack diamondPickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
            setRandomDurability(diamondPickaxe, 20, 100);
            int efficiencyLevel = random.nextInt(6); // 0~5
            if (efficiencyLevel > 0) {
                diamondPickaxe.addUnsafeEnchantment(Enchantment.EFFICIENCY, efficiencyLevel);
            }
            loot.add(diamondPickaxe);
        }
        if (random.nextDouble() < 0.20) {
            ItemStack helmet = new ItemStack(Material.DIAMOND_HELMET);
            setRandomDurability(helmet, 20, 100);
            int protectionLevel = random.nextInt(5); // 0~4
            if (protectionLevel > 0) {
                helmet.addUnsafeEnchantment(Enchantment.PROTECTION, protectionLevel);
            }
            loot.add(helmet);
        }
        if (random.nextDouble() < 0.20) {
            ItemStack chestplate = new ItemStack(Material.DIAMOND_CHESTPLATE);
            setRandomDurability(chestplate, 20, 100);
            int protectionLevel = random.nextInt(5); // 0~4
            if (protectionLevel > 0) {
                chestplate.addUnsafeEnchantment(Enchantment.PROTECTION, protectionLevel);
            }
            loot.add(chestplate);
        }
        if (random.nextDouble() < 0.20) {
            ItemStack leggings = new ItemStack(Material.DIAMOND_LEGGINGS);
            setRandomDurability(leggings, 20, 100);
            int protectionLevel = random.nextInt(5); // 0~4
            if (protectionLevel > 0) {
                leggings.addUnsafeEnchantment(Enchantment.PROTECTION, protectionLevel);
            }
            loot.add(leggings);
        }
        if (random.nextDouble() < 0.20) {
            ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
            setRandomDurability(boots, 20, 100);
            int protectionLevel = random.nextInt(5); // 0~4
            if (protectionLevel > 0) {
                boots.addUnsafeEnchantment(Enchantment.PROTECTION, protectionLevel);
            }
            loot.add(boots);
        }
    }

    private void setRandomDurability(ItemStack item, int minPercent, int maxPercent) {
        int maxDurability = item.getType().getMaxDurability();
        int percent = minPercent + random.nextInt(maxPercent - minPercent + 1);
        short durability = (short) ((percent / 100.0) * maxDurability);
        durability = (short) Math.min(durability, maxDurability);
        item.setDurability((short) (item.getType().getMaxDurability() - durability));
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntityType() == EntityType.ENDERMAN) {
            event.getDrops().clear();
            event.getDrops().add(new ItemStack(Material.ENDER_PEARL, random.nextInt(2) + 1));
        }
        if (event.getEntityType() == EntityType.BLAZE) {
            event.getDrops().clear();
            event.getDrops().add(new ItemStack(Material.BLAZE_ROD, random.nextInt(2) + 1));
        }
        if (event.getEntityType() == EntityType.WITHER_SKELETON) {
            event.getDrops().clear();
            event.getDrops().add(new ItemStack(Material.WITHER_SKELETON_SKULL, 1));
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material blockType = event.getBlock().getType();
        ItemStack tool = player.getInventory().getItemInMainHand();
        int fortuneLevel = tool.getEnchantmentLevel(Enchantment.FORTUNE);

        ItemStack drop;
        Random random = new Random();
        switch (blockType) {
            case IRON_ORE:
            case DEEPSLATE_IRON_ORE: {
                int amount = 1 + random.nextInt(3);
                amount = applyFortune(amount, fortuneLevel, random);
                drop = new ItemStack(Material.IRON_INGOT, amount);
                break;
            }
            case RAW_IRON_BLOCK: {
                drop = new ItemStack(Material.IRON_BLOCK, 1);
                break;
            }
            case GOLD_ORE:
            case DEEPSLATE_GOLD_ORE: {
                int amount = 1 + random.nextInt(3);
                amount = applyFortune(amount, fortuneLevel, random);
                drop = new ItemStack(Material.GOLD_INGOT, amount);
                break;
            }
            case RAW_GOLD_BLOCK: {
                drop = new ItemStack(Material.GOLD_BLOCK, 1);
                break;
            }
            case ANCIENT_DEBRIS: {
                int amount = 1 + random.nextInt(3);
                drop = new ItemStack(Material.NETHERITE_SCRAP, amount);
                break;
            }
            case COPPER_ORE:
            case DEEPSLATE_COPPER_ORE: {
                int amount = 1 + random.nextInt(3);
                amount = applyFortune(amount, fortuneLevel, random);
                drop = new ItemStack(Material.COPPER_INGOT, amount);
                break;
            }
            case DIAMOND_ORE:
            case DEEPSLATE_DIAMOND_ORE: {
                int amount = 1 + random.nextInt(3);
                amount = applyFortune(amount, fortuneLevel, random);
                drop = new ItemStack(Material.DIAMOND, amount);
                break;
            }
            case EMERALD_ORE:
            case DEEPSLATE_EMERALD_ORE: {
                int amount = 1 + random.nextInt(3);
                amount = applyFortune(amount, fortuneLevel, random);
                drop = new ItemStack(Material.EMERALD, amount);
                break;
            }
            case LAPIS_ORE:
            case DEEPSLATE_LAPIS_ORE: {
                int amount = 4 + random.nextInt(8);
                amount = applyFortune(amount, fortuneLevel, random);
                drop = new ItemStack(Material.LAPIS_LAZULI, amount);
                break;
            }
            case REDSTONE_ORE:
            case DEEPSLATE_REDSTONE_ORE: {
                int amount = 4 + random.nextInt(8);
                amount = applyFortune(amount, fortuneLevel, random);
                drop = new ItemStack(Material.REDSTONE, amount);
                break;
            }
            case COAL_ORE:
            case DEEPSLATE_COAL_ORE: {
                int amount = 1 + random.nextInt(3);
                amount = applyFortune(amount, fortuneLevel, random);
                drop = new ItemStack(Material.COAL, amount);
                break;
            }
            default: {
                return;
            }
        }
        event.setDropItems(false);
        event.getBlock().getWorld().dropItem(event.getBlock().getLocation().add(0.5, 0.2, 0.5), drop);
    }

    private int applyFortune(int baseAmount, int fortuneLevel, Random random) {
        if (fortuneLevel > 0) {
            switch (fortuneLevel) {
                case 1: {
                    if (!(random.nextDouble() < 0.33)) break;
                    return baseAmount * 2;
                }
                case 2: {
                    double chance = random.nextDouble();
                    if (chance < 0.25) {
                        return baseAmount * 2;
                    }
                    if (!(chance < 0.5)) break;
                    return baseAmount * 3;
                }
                case 3: {
                    double chance = random.nextDouble();
                    if (chance < 0.2) {
                        return baseAmount * 2;
                    }
                    if (chance < 0.4) {
                        return baseAmount * 3;
                    }
                    if (!(chance < 0.6)) break;
                    return baseAmount * 4;
                }
            }
        }
        return baseAmount;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.NETHER_STAR && event.getAction() == Action.RIGHT_CLICK_AIR) {
            PersistentDataContainer data = player.getPersistentDataContainer();
            NamespacedKey key = new NamespacedKey(this.plugin, "nether_star_effect_stage");
            int stage = data.getOrDefault(key, PersistentDataType.INTEGER, 0);
            switch (stage) {
                case 0: {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 1, true, false));
                    data.set(key, PersistentDataType.INTEGER, 1);
                    break;
                }
                case 1: {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, true, false));
                    data.set(key, PersistentDataType.INTEGER, 2);
                    break;
                }
                case 2: {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, Integer.MAX_VALUE, 1, true, false));
                    data.set(key, PersistentDataType.INTEGER, 3);
                    break;
                }
                case 3: {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0, true, false));
                    data.set(key, PersistentDataType.INTEGER, 4);
                    break;
                }
                case 4: {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 1, true, false));
                    data.set(key, PersistentDataType.INTEGER, 5);
                    break;
                }
                default: {
                    player.sendMessage("你已经获得所有效果！");
                    return;
                }
            }
            item.setAmount(item.getAmount() - 1);
        }
    }

    @EventHandler
    public void onPiglinBarter(PiglinBarterEvent event) {
        Random random = new Random();
        event.getOutcome().clear();
        int pearlCount = 2 + random.nextInt(3);
        ItemStack pearls = new ItemStack(Material.ENDER_PEARL, pearlCount);
        int obsidianCount = 1 + random.nextInt(2);
        ItemStack obsidian = new ItemStack(Material.OBSIDIAN, obsidianCount);
        ItemStack splashFireResist = new ItemStack(Material.SPLASH_POTION);
        PotionMeta splashMeta = (PotionMeta) splashFireResist.getItemMeta();
        splashMeta.clearCustomEffects();
        splashMeta.addCustomEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 3600, 0), true);
        splashMeta.setDisplayName("§f喷溅型抗火药水");
        splashMeta.setColor(Color.fromRGB(225, 186, 128));
        splashFireResist.setItemMeta(splashMeta);
        ItemStack fireResist = new ItemStack(Material.POTION);
        PotionMeta fireMeta = (PotionMeta) fireResist.getItemMeta();
        fireMeta.clearCustomEffects();
        fireMeta.addCustomEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 3600, 0), true);
        fireMeta.setDisplayName("§f抗火药水");
        splashMeta.setColor(Color.fromRGB(225, 186, 128));
        fireResist.setItemMeta(fireMeta);
        int spectralArrowCount = 6 + random.nextInt(7);
        ItemStack spectralArrows = new ItemStack(Material.SPECTRAL_ARROW, spectralArrowCount);
        int dropChoice = random.nextInt(5);
        switch (dropChoice) {
            case 0: {
                event.getOutcome().add(pearls);
                break;
            }
            case 1: {
                event.getOutcome().add(obsidian);
                break;
            }
            case 2: {
                event.getOutcome().add(splashFireResist);
                break;
            }
            case 3: {
                event.getOutcome().add(fireResist);
                break;
            }
            case 4: {
                event.getOutcome().add(spectralArrows);
            }
        }
    }

    @EventHandler
    public void onPlayerPickupExperience(PlayerPickupExperienceEvent event) {
        int originalExp = event.getExperienceOrb().getExperience();
        int multipliedExp = originalExp * 10;
        event.getExperienceOrb().setExperience(multipliedExp);
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        ItemStack pickedItem = event.getItem().getItemStack();
        if (isExplosiveArrow(pickedItem)) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getCurrentItem() != null && isExplosiveArrow(event.getCurrentItem())) {
            event.setCancelled(true);
            event.getWhoClicked().getInventory().remove(event.getCurrentItem());
        }
    }

    private boolean isExplosiveArrow(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName().equals("Explosive Arrow");
        }
        return false;
    }
}