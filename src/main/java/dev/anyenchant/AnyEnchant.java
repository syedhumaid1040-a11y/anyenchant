package dev.anyenchant;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class AnyEnchant extends JavaPlugin implements Listener {

    /**
     * Enchants rolled onto ANY held item. They either already work on any held item in
     * vanilla (sharpness, knockback, looting...) or are emulated by this plugin
     * (density, breach, channeling, riptide, wind burst, sweeping edge).
     */
    private static final String[] ANY_HELD = {
            "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect",
            "looting", "impaling", "density", "breach", "channeling", "riptide",
            "wind_burst", "sweeping_edge", "efficiency", "fortune", "silk_touch"
    };

    private boolean inSweep = false;
    private final Set<Item> inFlight = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        long minutes = Math.max(1, getConfig().getLong("roll-interval-minutes", 15));
        long ticks = minutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::rollAll, ticks, ticks);
        getLogger().info("AnyEnchant enabled. Random roll every " + minutes + " minute(s).");
    }

    @Override
    public void onDisable() {
        // Never leave a thrown Loyalty item stuck in the world
        for (Item drop : inFlight) {
            if (drop.isValid()) drop.setPickupDelay(0);
        }
        inFlight.clear();
    }

    // ---------------------------------------------------------------- helpers

    private Registry<Enchantment> registry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
    }

    private Enchantment ench(String name) {
        return registry().get(NamespacedKey.minecraft(name));
    }

    private boolean isCurse(Enchantment e) {
        return e.getKey().getKey().endsWith("_curse");
    }

    private boolean has(ItemStack item, String name) {
        Enchantment e = ench(name);
        return e != null && item.getEnchantmentLevel(e) > 0;
    }

    /** Items whose right-click already does something, so we don't turn them into throwables. */
    private boolean canThrow(Material m) {
        if (m == Material.AIR || m == Material.TRIDENT || m == Material.BOW
                || m == Material.CROSSBOW || m == Material.SHIELD
                || m == Material.FISHING_ROD) return false;
        return !m.isBlock() && !m.isEdible();
    }

    // ------------------------------------------------------------ random roll

    private void rollAll() {
        for (Player p : Bukkit.getOnlinePlayers()) rollFor(p, false);
    }

    private List<Enchantment> pool(ItemStack item) {
        Set<Enchantment> set = new LinkedHashSet<>();
        boolean curses = getConfig().getBoolean("allow-curses", false);
        Material type = item.getType();

        // Everything vanilla already supports on this item
        for (Enchantment e : registry()) {
            if (!curses && isCurse(e)) continue;
            if (e.canEnchantItem(item)) set.add(e);
        }

        // Extras that work (natively or emulated) on any held item
        for (String name : ANY_HELD) {
            Enchantment e = ench(name);
            if (e != null) set.add(e);
        }
        if (type.getMaxDurability() > 0) {
            Enchantment u = ench("unbreaking");
            Enchantment m = ench("mending");
            if (u != null) set.add(u);
            if (m != null) set.add(m);
        }
        if (type.isEdible()) {
            Enchantment inf = ench("infinity");
            if (inf != null) set.add(inf);
        }
        if (canThrow(type)) {
            Enchantment loyalty = ench("loyalty");
            if (loyalty != null) set.add(loyalty);
        }
        if (type == Material.BOW) {
            Enchantment ms = ench("multishot");
            if (ms != null) set.add(ms);
        }

        // Skip enchants already at their max level
        List<Enchantment> out = new ArrayList<>();
        for (Enchantment e : set) {
            if (item.getEnchantmentLevel(e) < Math.max(1, e.getMaxLevel())) out.add(e);
        }
        return out;
    }

    private void rollFor(Player p, boolean manual) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            if (manual) p.sendMessage(Component.text("Hold an item first.", NamedTextColor.RED));
            return;
        }
        List<Enchantment> pool = pool(held);
        if (pool.isEmpty()) return;

        Enchantment e = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        int cur = held.getEnchantmentLevel(e);
        int max = Math.max(1, e.getMaxLevel());
        int level = ThreadLocalRandom.current().nextInt(cur + 1, max + 1);

        ItemStack updated = held.clone();
        updated.addUnsafeEnchantment(e, level);
        p.getInventory().setItemInMainHand(updated);

        if (getConfig().getBoolean("announce", true)) {
            p.sendMessage(Component.text("Enchant roll: ", NamedTextColor.GOLD)
                    .append(e.displayName(level)));
        }
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
    }

    // ------------------------------------------------------------------ anvil

    /** Anvil: item + enchanted book = item with the book's enchants, no restrictions. */
    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getFirstItem();
        ItemStack right = inv.getSecondItem();

        if (left == null || right == null) return;
        if (left.getType() == Material.ENCHANTED_BOOK || left.getType() == Material.BOOK) return;
        if (right.getType() != Material.ENCHANTED_BOOK) return;
        if (!(right.getItemMeta() instanceof EnchantmentStorageMeta bookMeta)) return;
        if (!event.getView().getPlayer().hasPermission("anyenchant.anvil")) return;

        ItemStack result = left.clone();
        for (Map.Entry<Enchantment, Integer> e : bookMeta.getStoredEnchants().entrySet()) {
            int current = result.getEnchantmentLevel(e.getKey());
            int level = e.getValue();
            // Same level twice -> +1 like vanilla; otherwise keep the higher one
            int newLevel = (current == level) ? level + 1 : Math.max(current, level);
            result.addUnsafeEnchantment(e.getKey(), newLevel);
        }

        event.setResult(result);
        event.getView().setRepairCost(5);
    }

    // ------------------------------------------------- emulated enchant effects

    /** Infinity on food (enchanted golden apple etc.): the item comes back after eating. */
    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!has(item, "infinity")) return;

        Player p = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        ItemStack saved = item.clone();
        Bukkit.getScheduler().runTask(this, () -> p.getInventory().setItem(hand, saved));
    }

    /** Melee emulations: Density, Breach, Channeling, Wind Burst, Sweeping Edge. */
    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (inSweep) return;
        if (!(event.getDamager() instanceof Player p)) return;
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return;
        Material type = item.getType();

        // Density: +0.5 damage per block fallen per level (mace already does this itself)
        Enchantment density = ench("density");
        if (density != null && type != Material.MACE) {
            int lvl = item.getEnchantmentLevel(density);
            if (lvl > 0 && p.getFallDistance() > 1.5f) {
                event.setDamage(event.getDamage() + 0.5 * lvl * p.getFallDistance());
            }
        }

        // Breach: each level cuts the target's armor effectiveness by 15%
        Enchantment breach = ench("breach");
        if (breach != null && type != Material.MACE
                && event.getEntity() instanceof LivingEntity target) {
            int lvl = item.getEnchantmentLevel(breach);
            AttributeInstance armorAttr = target.getAttribute(Attribute.ARMOR);
            AttributeInstance toughAttr = target.getAttribute(Attribute.ARMOR_TOUGHNESS);
            if (lvl > 0 && armorAttr != null) {
                double armor = armorAttr.getValue();
                double tough = toughAttr == null ? 0 : toughAttr.getValue();
                double dmg = event.getDamage();
                double reduced = Math.max(0, 1 - 0.15 * lvl) * armor;
                double before = armorMultiplier(armor, tough, dmg);
                double after = armorMultiplier(reduced, tough, dmg);
                if (before > 0) event.setDamage(dmg * (after / before));
            }
        }

        // Channeling: lightning on hit during a thunderstorm (trident already does this itself)
        Enchantment channeling = ench("channeling");
        if (channeling != null && type != Material.TRIDENT
                && item.getEnchantmentLevel(channeling) > 0) {
            Entity victim = event.getEntity();
            if (victim.getWorld().isThundering()
                    && victim.getLocation().getBlock().getLightFromSky() == 15) {
                victim.getWorld().strikeLightning(victim.getLocation());
            }
        }

        // Sweeping Edge: damages entities next to the target (swords already do this natively)
        Enchantment sweep = ench("sweeping_edge");
        if (sweep != null && !type.name().endsWith("_SWORD")
                && event.getEntity() instanceof LivingEntity primary
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            int lvl = item.getEnchantmentLevel(sweep);
            if (lvl > 0 && p.getCooledAttackStrength(0f) >= 0.9f && !p.isSprinting()) {
                double sweepDamage = 1 + (lvl / (lvl + 1.0)) * event.getDamage();
                inSweep = true;
                try {
                    for (Entity near : primary.getNearbyEntities(1.0, 0.25, 1.0)) {
                        if (near.equals(p) || near instanceof ArmorStand) continue;
                        if (!(near instanceof LivingEntity living)) continue;
                        if (living instanceof Tameable pet && p.equals(pet.getOwner())) continue;
                        living.damage(sweepDamage, p);
                    }
                } finally {
                    inSweep = false;
                }
            }
        }

        // Wind Burst: a smash attack launches you upward (mace already does this itself)
        Enchantment windBurst = ench("wind_burst");
        if (windBurst != null && type != Material.MACE) {
            int lvl = item.getEnchantmentLevel(windBurst);
            if (lvl > 0 && p.getFallDistance() > 1.5f) {
                p.setVelocity(new Vector(0, 0.8 + 0.4 * lvl, 0));
                p.setFallDistance(0f);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f, 1f);
            }
        }
    }

    /** Vanilla armor formula: fraction of damage that gets through. */
    private double armorMultiplier(double armor, double toughness, double damage) {
        double reduction = Math.min(20, Math.max(armor / 5.0, armor - damage / (2 + toughness / 4.0)));
        return 1 - reduction / 25.0;
    }

    /** Riptide on non-tridents: right-click in water or rain to launch yourself. */
    @EventHandler
    public void onRiptide(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.TRIDENT) return;

        Enchantment riptide = ench("riptide");
        if (riptide == null) return;
        int lvl = item.getEnchantmentLevel(riptide);
        if (lvl <= 0) return;

        Player p = event.getPlayer();
        boolean wet = p.isInWater()
                || (p.getWorld().hasStorm() && p.getLocation().getBlock().getLightFromSky() == 15);
        if (!wet || p.hasCooldown(item.getType())) return;

        p.setCooldown(item.getType(), 30);
        p.setVelocity(p.getLocation().getDirection().multiply(0.7 + 0.6 * lvl));
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 1f, 1f);
    }

    /** Loyalty on non-tridents: right-click throws the item, it flies back to you after hitting. */
    @EventHandler
    public void onLoyaltyThrow(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || !canThrow(item.getType())) return;

        Enchantment loyalty = ench("loyalty");
        if (loyalty == null) return;
        int lvl = item.getEnchantmentLevel(loyalty);
        if (lvl <= 0) return;

        event.setCancelled(true);
        Player p = event.getPlayer();

        ItemStack thrown = item.clone();
        thrown.setAmount(1);
        ItemStack rest = item.clone();
        rest.setAmount(item.getAmount() - 1);
        p.getInventory().setItemInMainHand(rest.getAmount() <= 0 ? new ItemStack(Material.AIR) : rest);

        Item drop = p.getWorld().dropItem(p.getEyeLocation(), thrown);
        drop.setPickupDelay(32767);
        drop.setCanMobPickup(false);
        drop.setUnlimitedLifetime(true);
        drop.setVelocity(p.getLocation().getDirection().multiply(1.6));
        inFlight.add(drop);
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 1f);

        final int returnDelay = Math.max(2, 14 - 4 * lvl);
        new BukkitRunnable() {
            int ticks = 0;
            int returnAt = -1;

            @Override
            public void run() {
                ticks++;

                // Item got destroyed (lava, void...): give it back instead of losing it
                if (!drop.isValid()) {
                    inFlight.remove(drop);
                    giveBack(p, thrown);
                    cancel();
                    return;
                }

                if (returnAt < 0) {
                    for (Entity e : drop.getNearbyEntities(0.7, 0.7, 0.7)) {
                        if (e instanceof LivingEntity target && !e.equals(p)
                                && !(e instanceof ArmorStand)) {
                            target.damage(8.0, p);
                            returnAt = ticks + returnDelay;
                            break;
                        }
                    }
                }
                if (returnAt < 0 && (drop.isOnGround() || ticks > 40)) {
                    returnAt = ticks + returnDelay;
                }
                if (returnAt >= 0 && ticks >= returnAt) {
                    drop.remove();
                    inFlight.remove(drop);
                    giveBack(p, thrown);
                    if (p.isOnline()) p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1f);
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void giveBack(Player p, ItemStack item) {
        if (!p.isOnline()) {
            p.getWorld().dropItemNaturally(p.getLocation(), item);
            return;
        }
        for (ItemStack left : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    /** Multishot on bows (crossbows already do this natively): two extra arrows at +/-10 degrees. */
    @EventHandler
    public void onShoot(EntityShootBowEvent event) {
        ItemStack bow = event.getBow();
        if (bow == null || bow.getType() == Material.CROSSBOW) return;
        if (!has(bow, "multishot")) return;
        if (!(event.getProjectile() instanceof AbstractArrow base)) return;

        Vector v = base.getVelocity();
        for (double deg : new double[]{-10, 10}) {
            Vector nv = v.clone().rotateAroundY(Math.toRadians(deg));
            Arrow extra = event.getEntity().getWorld().spawnArrow(
                    base.getLocation(), nv.clone().normalize(), (float) nv.length(), 0f);
            extra.setShooter(event.getEntity());
            extra.setCritical(base.isCritical());
            extra.setDamage(base.getDamage());
            extra.setFireTicks(base.getFireTicks());
            extra.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
        }
    }

    private boolean isWeapon(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR")
                || m == Material.MACE || m == Material.TRIDENT
                || m == Material.BOW || m == Material.CROSSBOW;
    }

    /**
     * On death, every enchant on a weapon drops as its own enchanted book and the weapon
     * drops plain. Works for any enchant. Set death-split-any-item: true in config.yml
     * to do this for every enchanted item instead of just weapons.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        boolean anyItem = getConfig().getBoolean("death-split-any-item", false);
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> books = new ArrayList<>();

        for (ItemStack drop : drops) {
            if (drop == null || drop.getType() == Material.AIR) continue;
            if (drop.getType() == Material.ENCHANTED_BOOK) continue;
            if (!anyItem && !isWeapon(drop.getType())) continue;

            Map<Enchantment, Integer> enchants = new java.util.HashMap<>(drop.getEnchantments());
            if (enchants.isEmpty()) continue;

            for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                drop.removeEnchantment(e.getKey());

                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
                meta.addStoredEnchant(e.getKey(), e.getValue(), true);
                book.setItemMeta(meta);
                books.add(book);
            }
        }
        drops.addAll(books);
    }

    // ---------------------------------------------------------------- command

    /** /anyenchant roll   or   /anyenchant <name> <level> */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("roll")) {
            rollFor(player, true);
            return true;
        }
        if (args.length != 2) {
            player.sendMessage("Usage: /anyenchant roll  |  /anyenchant <enchantment> <level>");
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            player.sendMessage("Hold an item first.");
            return true;
        }

        Enchantment e = ench(args[0].toLowerCase());
        if (e == null) {
            player.sendMessage("Unknown enchantment: " + args[0]);
            return true;
        }

        int level;
        try {
            level = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            player.sendMessage("Level must be a number.");
            return true;
        }

        ItemStack updated = item.clone();
        updated.addUnsafeEnchantment(e, Math.max(1, Math.min(level, 255)));
        player.getInventory().setItemInMainHand(updated);
        player.sendMessage("Enchanted your item with " + args[0] + " " + level + ".");
        return true;
    }
}
