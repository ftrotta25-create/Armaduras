package net.floatmc.armors;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class FloatArmors extends JavaPlugin implements Listener, TabExecutor {

    private record Piece(String id, String set, Material mat, String name, String lore,
                         double armor, double tough, double kb, EquipmentSlot group) {}

    private static final String TEMPEST = "tempestad";
    private static final String VOID = "regalia";
    private static final String CORE = "nucleo_vacio";

    private final Map<String, Piece> pieces = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> hits = new HashMap<>();
    private final Set<UUID> discharging = new HashSet<>();
    private final Map<UUID, Integer> charges = new HashMap<>();
    private final Map<UUID, Long> lastSneak = new HashMap<>();
    private final Random random = new Random();
    private static final double BLINK_RANGE = 12.0;
    /** Velocidad vertical que lleva a ~15 bloques de altura. */
    private static final double LAUNCH_VELOCITY = 1.7;
    private NamespacedKey keyId;

    @Override
    public void onEnable() {
        keyId = new NamespacedKey(this, "id");
        registerSet(TEMPEST, "&b&lTempestad", "CHAINMAIL", new double[]{3, 6, 6, 3}, 2.0, 0.05,
                "&7El poder de las tormentas\n \n&b⚡ Sobrecarga Eléctrica &8- cada 4 golpes\n&b⚡ Pararrayos &8- cada 3 golpes recibidos");
        registerSet(VOID, "&5&lRegalia del Vacío", "GOLDEN", new double[]{4, 8, 7, 3}, 3.5, 0.125,
                "&7Superior a la Netherite\n \n&5✦ Paso del Vacío &8- doble Shift\n&5✦ Singularidad &8- con el Núcleo del Vacío\n&5✦ Dominio del Vacío &8- -80% daño por caída");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("floatarmors");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
    }

    private void registerSet(String set, String display, String mat, double[] armor, double tough, double kb, String lore) {
        String[] ids = {"casco", "pechera", "pantalones", "botas"};
        String[] names = {"Casco", "Pechera", "Pantalones", "Botas"};
        String[] suffix = {"_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS"};
        EquipmentSlot[] groups = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            String id = set + "_" + ids[i];
            pieces.put(id, new Piece(id, set, Material.valueOf(mat + suffix[i]),
                    display + " &7" + names[i], lore, armor[i], tough, kb, groups[i]));
        }
    }

    // ---------------------------------------------------------------- items

    private Component c(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy)
                .decoration(TextDecoration.ITALIC, false);
    }

    private UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("floatarmors:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ItemStack buildPiece(Piece p) {
        ItemStack item = new ItemStack(p.mat());
        ItemMeta m = item.getItemMeta();
        m.displayName(c(p.name()));
        List<Component> lore = new ArrayList<>();
        for (String line : p.lore().split("\\n")) lore.add(c(line));
        lore.add(Component.empty());
        lore.add(c("&7Armadura: &f" + (int) p.armor() + "  &7Dureza: &f" + p.tough()));
        lore.add(c("&8Las habilidades requieren las 4 piezas puestas"));
        m.lore(lore);
        m.setUnbreakable(true);
        boolean tempest = p.set().equals(TEMPEST);
        m.setCustomModelData(tempest ? 1 : 2); // 1 = Tempestad, 2 = Regalia (icono)
        m.addEnchant(Enchantment.DURABILITY, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        // Estos modificadores REEMPLAZAN los valores vanilla de la pieza.
        m.addAttributeModifier(Attribute.GENERIC_ARMOR, new AttributeModifier(
                uuid(p.id() + "_armor"), "armor", p.armor(),
                AttributeModifier.Operation.ADD_NUMBER, p.group()));
        m.addAttributeModifier(Attribute.GENERIC_ARMOR_TOUGHNESS, new AttributeModifier(
                uuid(p.id() + "_tough"), "tough", p.tough(),
                AttributeModifier.Operation.ADD_NUMBER, p.group()));
        m.addAttributeModifier(Attribute.GENERIC_KNOCKBACK_RESISTANCE, new AttributeModifier(
                uuid(p.id() + "_kb"), "kb", p.kb(),
                AttributeModifier.Operation.ADD_NUMBER, p.group()));
        m.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, p.id());
        item.setItemMeta(m);
        return item;
    }

    private ItemStack buildCore() {
        ItemStack item = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta m = item.getItemMeta();
        m.displayName(c("&5&lNúcleo del Vacío"));
        m.lore(List.of(c("&7Con la Regalia completa genera una onda"),
                c("&7que lanza al cielo a todos los cercanos. &8(Clic derecho)")));
        m.addEnchant(Enchantment.DURABILITY, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.setCustomModelData(1);
        m.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, CORE);
        item.setItemMeta(m);
        return item;
    }

    private String idOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    private int countSet(Player p, String set) {
        int n = 0;
        for (ItemStack it : p.getInventory().getArmorContents()) {
            String id = idOf(it);
            Piece pc = id == null ? null : pieces.get(id);
            if (pc != null && pc.set().equals(set)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ cooldowns

    private long remainingMs(Player p, String ability) {
        Long until = cooldowns.getOrDefault(p.getUniqueId(), Map.of()).get(ability);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    private void startCooldown(Player p, String ability, long ms) {
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                .put(ability, System.currentTimeMillis() + ms);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        cooldowns.remove(id); hits.remove(id); charges.remove(id); lastSneak.remove(id); discharging.remove(id);
    }

    // ------------------------------------------------------------ abilities

    /** Sobrecarga Eléctrica: cada 4 golpes cuerpo a cuerpo, descarga en área. CD 12 s. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target) || e.getEntity() instanceof ArmorStand) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (discharging.contains(p.getUniqueId())) return;
        if (countSet(p, TEMPEST) < 4) return;
        if (remainingMs(p, "sobrecarga") > 0) return;

        int n = hits.merge(p.getUniqueId(), 1, Integer::sum);
        if (n < 4) return;
        hits.put(p.getUniqueId(), 0);
        startCooldown(p, "sobrecarga", 12_000);

        discharging.add(p.getUniqueId());
        try {
            Set<LivingEntity> victims = new HashSet<>();
            victims.add(target);
            for (var near : target.getNearbyEntities(4, 4, 4)) {
                if (near instanceof LivingEntity le && !(le instanceof ArmorStand) && !le.equals(p)) victims.add(le);
            }
            for (LivingEntity v : victims) {
                v.damage(3.0, p); // 3 de daño = 1,5 corazones
                v.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 40, 0)); // Lentitud I, 2 s
                v.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, v.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.1);
            }
        } finally {
            discharging.remove(p.getUniqueId());
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.6f, 1.6f);
        p.sendActionBar(c("&b⚡ Sobrecarga Eléctrica"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        // Dominio del Vacío: -80% daño por caída
        if (e.getCause() == DamageCause.FALL && countSet(p, VOID) == 4) {
            e.setDamage(e.getDamage() * 0.2);
        }

        // Pararrayos: cada golpe enemigo que recibís carga estática; a la 3ª carga la
        // Tempestad devuelve una cadena de relámpagos a los 3 enemigos más cercanos. CD 15 s.
        DamageCause cause = e.getCause();
        boolean enemyHit = cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.PROJECTILE
                || cause == DamageCause.ENTITY_SWEEP_ATTACK || cause == DamageCause.ENTITY_EXPLOSION;
        if (enemyHit && countSet(p, TEMPEST) == 4 && remainingMs(p, "pararrayos") == 0
                && !discharging.contains(p.getUniqueId())) {
            int n = charges.merge(p.getUniqueId(), 1, Integer::sum);
            if (n < 3) {
                p.sendActionBar(c("&b⚡ Carga estática " + n + "/3"));
            } else {
                List<LivingEntity> targets = p.getNearbyEntities(8, 8, 8).stream()
                        .filter(en -> en instanceof LivingEntity le && validTarget(le, p))
                        .map(en -> (LivingEntity) en)
                        .sorted(Comparator.comparingDouble(le -> le.getLocation().distanceSquared(p.getLocation())))
                        .limit(3).toList();
                if (!targets.isEmpty()) {
                    charges.put(p.getUniqueId(), 0);
                    startCooldown(p, "pararrayos", 15_000);
                    getServer().getScheduler().runTask(this, () -> chainLightning(p, targets));
                }
            }
        }
    }

    private boolean validTarget(LivingEntity le, Player self) {
        if (le.equals(self) || le instanceof ArmorStand || le.isDead()) return false;
        if (le instanceof Player op) {
            return op.getGameMode() != GameMode.CREATIVE && op.getGameMode() != GameMode.SPECTATOR;
        }
        return true;
    }

    private void chainLightning(Player p, List<LivingEntity> targets) {
        if (!p.isOnline() || p.isDead()) return;
        discharging.add(p.getUniqueId());
        try {
            for (LivingEntity le : targets) {
                if (le.isDead()) continue;
                le.getWorld().strikeLightningEffect(le.getLocation()); // solo efecto, sin fuego
                le.damage(5.0, p);
                le.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, le.getLocation().add(0, 1, 0), 40, 0.4, 0.7, 0.4, 0.15);
            }
        } finally {
            discharging.remove(p.getUniqueId());
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1)); // Velocidad II, 3 s
        p.sendActionBar(c("&b⚡ Pararrayos"));
    }

    /** Paso del Vacío: doble toque de Shift = teletransporte de hasta 12 bloques. CD 10 s. */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastSneak.put(p.getUniqueId(), now);
        if (last == null || now - last > 350) return;
        lastSneak.remove(p.getUniqueId());
        if (countSet(p, VOID) < 4) return;
        long left = remainingMs(p, "paso");
        if (left > 0) { p.sendActionBar(c("&cPaso del Vacío en enfriamiento: " + (left / 1000 + 1) + " s")); return; }

        Location from = p.getLocation();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, BLINK_RANGE, FluidCollisionMode.NEVER, true);
        double max = hit == null ? BLINK_RANGE : Math.max(0, hit.getHitPosition().distance(eye.toVector()) - 0.8);
        Location dest = null;
        for (double d = max; d >= 2.0; d -= 0.5) {
            Location t = from.clone().add(dir.clone().multiply(d));
            if (isFree(t) && isFree(t.clone().add(0, 1, 0)) && isFree(t.clone().add(0, 1.8, 0))) { dest = t; break; }
        }
        if (dest == null) { p.sendActionBar(c("&cNo hay espacio para el Paso del Vacío")); return; }
        startCooldown(p, "paso", 10_000);

        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, from.clone().add(0, 1, 0), 80, 0.4, 0.8, 0.4, 0.2);
        p.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 0.7f);
        p.teleport(dest);
        p.setFallDistance(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1));              // Velocidad II, 3 s
        p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 60, 0));  // Resistencia I, 3 s
        p.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 80, 0.4, 0.8, 0.4, 0.5);
        p.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 1.2f);
        p.sendActionBar(c("&5✦ Paso del Vacío"));
        spawnRift(p, from);
    }

    private boolean isFree(Location l) {
        var b = l.getBlock();
        return b.isPassable() && b.getType() != Material.LAVA && b.getType() != Material.FIRE;
    }

    /** Grieta que queda en el punto de partida 3 s: 2 de daño/s y Lentitud I a quien esté dentro (3 bloques). */
    private void spawnRift(Player owner, Location center) {
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= 60 || !owner.isOnline()) { cancel(); return; }
                center.getWorld().spawnParticle(Particle.SQUID_INK, center.clone().add(0, 0.6, 0), 6, 1.2, 0.5, 1.2, 0.01);
                center.getWorld().spawnParticle(Particle.PORTAL, center.clone().add(0, 0.6, 0), 14, 1.5, 0.6, 1.5, 0.3);
                if (ticks % 20 == 0) {
                    discharging.add(owner.getUniqueId());
                    try {
                        for (var en : center.getWorld().getNearbyEntities(center, 3, 3, 3)) {
                            if (!(en instanceof LivingEntity le) || !validTarget(le, owner)) continue;
                            le.damage(2.0, owner); // 1 corazón por segundo
                            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 30, 0));
                        }
                    } finally {
                        discharging.remove(owner.getUniqueId());
                    }
                }
                ticks += 5;
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    /** Singularidad: lanza al cielo (jugadores y mobs, 6 bloques) con daño + Lentitud II. CD 55 s. */
    @EventHandler
    public void onUseCore(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!CORE.equals(idOf(e.getItem()))) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (countSet(p, VOID) < 4) { p.sendActionBar(c("&cNecesitás la Regalia del Vacío completa")); return; }
        long left = remainingMs(p, "singularidad");
        if (left > 0) { p.sendActionBar(c("&cSingularidad en enfriamiento: " + (left / 1000 + 1) + " s")); return; }
        startCooldown(p, "singularidad", 55_000);

        discharging.add(p.getUniqueId());
        try {
            for (var near : p.getNearbyEntities(6, 6, 6)) {
                if (!(near instanceof LivingEntity le) || !validTarget(le, p)) continue;
                if (le.getLocation().distanceSquared(p.getLocation()) > 36) continue;
                le.damage(4.0, p); // 4 de daño = 2 corazones
                le.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 60, 1)); // Lentitud II, 3 s
                le.setVelocity(new Vector(0, LAUNCH_VELOCITY, 0)); // lanzado ~15 bloques al cielo
                le.getWorld().spawnParticle(Particle.PORTAL, le.getLocation().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.5);
            }
        } finally {
            discharging.remove(p.getUniqueId());
        }
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 200, 3, 1.5, 3, 0.5);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.4f, 1.5f);
        p.sendActionBar(c("&5✦ Singularidad"));
    }

    // -------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("floatarmors.admin")) { s.sendMessage(c("&cSin permiso.")); return true; }
        if (a.length < 3 || !a[0].equalsIgnoreCase("give")) {
            s.sendMessage(c("&7Uso: /floatarmors give <jugador> <id>")); return true;
        }
        Player target = getServer().getPlayerExact(a[1]);
        if (target == null) { s.sendMessage(c("&cJugador no encontrado.")); return true; }
        String id = a[2].toLowerCase();
        List<ItemStack> give = new ArrayList<>();
        if (id.equals(TEMPEST) || id.equals(VOID)) {
            pieces.values().stream().filter(p -> p.set().equals(id)).forEach(p -> give.add(buildPiece(p)));
        } else if (id.equals(CORE)) {
            give.add(buildCore());
        } else if (pieces.containsKey(id)) {
            give.add(buildPiece(pieces.get(id)));
        } else { s.sendMessage(c("&cID inválido.")); return true; }
        for (ItemStack it : give) {
            target.getInventory().addItem(it).values()
                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        }
        s.sendMessage(c("&aEntregado: " + id + " a " + target.getName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) return List.of("give");
        if (a.length == 2) return getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        if (a.length == 3) {
            List<String> ids = new ArrayList<>(pieces.keySet());
            ids.addAll(List.of(TEMPEST, VOID, CORE));
            return ids.stream().filter(i -> i.startsWith(a[2].toLowerCase())).toList();
        }
        return List.of();
    }
}
