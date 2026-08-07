package dev.smspectate;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bukkit/Spigot build. No Paper API imports anywhere in this file, so it links and
 * verifies cleanly on a plain CraftBukkit/Spigot server that never loads paper-api
 * classes. Compiled against Spigot API 1.19.4's public surface, which Bukkit keeps
 * source- and ABI-compatible going forward, so this single jar keeps running unmodified
 * from 1.19.4 through the latest snapshot. Runtime Minecraft-version detection below is
 * a compatibility guard and diagnostic, not a recompile point.
 */
public final class SMPSpectate extends JavaPlugin {

    public enum ServerSoftware {
        BUKKIT, SPIGOT
    }

    private static final int[] MIN_SUPPORTED_VERSION = {1, 19, 4};

    private final Map<UUID, Location> origins = new HashMap<>();
    private final Map<UUID, Collection<PotionEffect>> savedEffects = new HashMap<>();

    private ServerSoftware serverSoftware;
    private int[] minecraftVersion = {0, 0, 0};
    private boolean isPluginChangingGamemode = false;

    private List<String> blockedWorlds = new ArrayList<>();
    private double maxDistance = 200.0;
    private double maxY = 320.0;
    private boolean enforceWorldBorder = true;
    private String msgSpectateEnter = "&7You are now in spectator mode. Type /s again to return.";
    private String msgSpectateExit = "&aWelcome back!";
    private String msgRestrictDistance = "&cYou strayed too far ({distance} blocks) from where you started spectating, {player}. Returning you.";
    private String msgRestrictYLimit = "&cYou reached the spectator height limit, {player}. Returning you.";
    private String msgRestrictBorder = "&cYou reached the world border while spectating, {player}. Returning you.";

    private BukkitRunnable restrictionTask;

    @Override
    public void onEnable() {
        this.serverSoftware = detectServerSoftware();
        this.minecraftVersion = detectMinecraftVersion();
        getLogger().info("Detected server software: " + serverSoftware.name()
                + " running Minecraft " + versionString(minecraftVersion));

        if (!isAtLeast(minecraftVersion, MIN_SUPPORTED_VERSION)) {
            getLogger().warning("This server is running an older Minecraft version than the "
                    + versionString(MIN_SUPPORTED_VERSION) + " floor SMPSpectate targets. "
                    + "Some behavior may not be guaranteed.");
        }

        saveDefaultConfig();
        loadConfigValues();

        getCommand("s").setExecutor(new SpectateCommand());
        AdminCommand adminCommand = new AdminCommand();
        getCommand("smspectate").setExecutor(adminCommand);
        getCommand("smspectate").setTabCompleter(adminCommand);

        getServer().getPluginManager().registerEvents(new BukkitChatListener(), this);
        getServer().getPluginManager().registerEvents(new Listeners(), this);

        restrictionTask = new BukkitRunnable() {
            @Override
            public void run() {
                enforceRestrictions();
            }
        };
        restrictionTask.runTaskTimer(this, 10L, 10L);
    }

    @Override
    public void onDisable() {
        if (restrictionTask != null) {
            restrictionTask.cancel();
            restrictionTask = null;
        }

        for (UUID uuid : new ArrayList<>(origins.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                restorePlayerState(player);
            }
        }
        origins.clear();
        savedEffects.clear();
    }

    private void loadConfigValues() {
        reloadConfig();
        blockedWorlds = getConfig().getStringList("blocked-worlds");
        maxDistance = getConfig().getDouble("max-distance", 200.0);
        maxY = getConfig().getDouble("max-y", 320.0);
        enforceWorldBorder = getConfig().getBoolean("enforce-world-border", true);
        msgSpectateEnter = getConfig().getString("messages.spectate-enter", msgSpectateEnter);
        msgSpectateExit = getConfig().getString("messages.spectate-exit", msgSpectateExit);
        msgRestrictDistance = getConfig().getString("messages.restrict-distance", msgRestrictDistance);
        msgRestrictYLimit = getConfig().getString("messages.restrict-ylimit", msgRestrictYLimit);
        msgRestrictBorder = getConfig().getString("messages.restrict-border", msgRestrictBorder);
    }

    private ServerSoftware detectServerSoftware() {
        if (classExists("org.spigotmc.SpigotConfig")) {
            return ServerSoftware.SPIGOT;
        }
        return ServerSoftware.BUKKIT;
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Parses the running server's Minecraft version (e.g. "1.20.4" from
     * "1.20.4-R0.1-SNAPSHOT") purely for logging/diagnostics and the floor-version
     * warning above; no behavior branches on it because the Bukkit/Spigot API surface
     * this plugin uses has been stable across the whole 1.19.4+ range.
     */
    private static int[] detectMinecraftVersion() {
        String raw = Bukkit.getBukkitVersion();
        String versionPart = raw.split("-")[0];
        String[] parts = versionPart.split("\\.");
        int major = parts.length > 0 ? parseIntSafe(parts[0]) : 0;
        int minor = parts.length > 1 ? parseIntSafe(parts[1]) : 0;
        int patch = parts.length > 2 ? parseIntSafe(parts[2]) : 0;
        return new int[]{major, minor, patch};
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isAtLeast(int[] version, int[] floor) {
        for (int i = 0; i < floor.length; i++) {
            if (version[i] != floor[i]) {
                return version[i] > floor[i];
            }
        }
        return true;
    }

    private static String versionString(int[] version) {
        return version[0] + "." + version[1] + "." + version[2];
    }

    private void sendPluginMessage(Player player, String rawMessage) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', rawMessage));
    }

    private String formatMessage(String template, Player player, double distance) {
        return template
                .replace("{player}", player.getName())
                .replace("{distance}", String.format(Locale.ROOT, "%.1f", distance));
    }

    private boolean enterSpectatorMode(Player player) {
        if (!player.hasPermission("smspectate.use")) {
            sendPluginMessage(player, "&cYou do not have permission to use spectator mode.");
            return false;
        }

        if (blockedWorlds.contains(player.getWorld().getName())) {
            sendPluginMessage(player, "&cSpectator mode is disabled in this world.");
            return false;
        }

        UUID uuid = player.getUniqueId();
        origins.put(uuid, player.getLocation().clone());
        savedEffects.put(uuid, new ArrayList<>(player.getActivePotionEffects()));

        isPluginChangingGamemode = true;
        try {
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
            player.setGameMode(GameMode.SPECTATOR);
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, true, false));
        } finally {
            isPluginChangingGamemode = false;
        }

        sendPluginMessage(player, msgSpectateEnter);
        return true;
    }

    private void exitSpectatorMode(Player player, boolean teleportBack) {
        UUID uuid = player.getUniqueId();
        Location origin = origins.remove(uuid);
        Collection<PotionEffect> effects = savedEffects.remove(uuid);

        if (teleportBack && origin != null && player.isOnline()) {
            player.teleport(origin);
        }

        isPluginChangingGamemode = true;
        try {
            player.setGameMode(GameMode.SURVIVAL);
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            if (effects != null) {
                for (PotionEffect effect : effects) {
                    player.addPotionEffect(effect);
                }
            }
        } finally {
            isPluginChangingGamemode = false;
        }
    }

    private void restorePlayerState(Player player) {
        UUID uuid = player.getUniqueId();
        Collection<PotionEffect> effects = savedEffects.remove(uuid);
        origins.remove(uuid);

        isPluginChangingGamemode = true;
        try {
            player.setGameMode(GameMode.SURVIVAL);
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            if (effects != null) {
                for (PotionEffect effect : effects) {
                    player.addPotionEffect(effect);
                }
            }
        } finally {
            isPluginChangingGamemode = false;
        }
    }

    private void enforceRestrictions() {
        if (origins.isEmpty()) {
            return;
        }

        for (UUID uuid : new ArrayList<>(origins.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }

            Location origin = origins.get(uuid);
            if (origin == null) {
                continue;
            }

            Location current = player.getLocation();
            World originWorld = origin.getWorld();
            World currentWorld = current.getWorld();

            if (originWorld == null || currentWorld == null || !currentWorld.equals(originWorld)) {
                exitSpectatorMode(player, true);
                sendPluginMessage(player, formatMessage(msgRestrictBorder, player, 0.0));
                continue;
            }

            double distance = current.distance(origin);
            if (distance > maxDistance && !player.hasPermission("smspectate.bypass.distance")) {
                exitSpectatorMode(player, true);
                sendPluginMessage(player, formatMessage(msgRestrictDistance, player, distance));
                continue;
            }

            if (current.getY() > maxY && !player.hasPermission("smspectate.bypass.ylimit")) {
                exitSpectatorMode(player, true);
                sendPluginMessage(player, formatMessage(msgRestrictYLimit, player, distance));
                continue;
            }

            if (enforceWorldBorder && !player.hasPermission("smspectate.bypass.worldborder")) {
                WorldBorder border = currentWorld.getWorldBorder();
                if (!border.isInside(current)) {
                    exitSpectatorMode(player, true);
                    sendPluginMessage(player, formatMessage(msgRestrictBorder, player, distance));
                }
            }
        }
    }

    private final class SpectateCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }

            Player player = (Player) sender;
            if (origins.containsKey(player.getUniqueId())) {
                exitSpectatorMode(player, true);
                sendPluginMessage(player, msgSpectateExit);
            } else {
                enterSpectatorMode(player);
            }
            return true;
        }
    }

    private final class AdminCommand implements CommandExecutor, TabCompleter {
        private final List<String> subcommands = List.of("reload", "list", "pull", "version");

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("smspectate.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /smspectate <reload|list|pull|version>");
                return true;
            }

            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload":
                    loadConfigValues();
                    sender.sendMessage(ChatColor.GREEN + "SMPSpectate config reloaded.");
                    return true;

                case "list":
                    if (origins.isEmpty()) {
                        sender.sendMessage(ChatColor.YELLOW + "No players are currently spectating.");
                        return true;
                    }
                    for (UUID uuid : origins.keySet()) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player == null) {
                            continue;
                        }
                        Location origin = origins.get(uuid);
                        double distance = origin != null && origin.getWorld() != null
                                && origin.getWorld().equals(player.getWorld())
                                ? player.getLocation().distance(origin)
                                : -1.0;
                        sender.sendMessage(ChatColor.AQUA + player.getName() + ChatColor.GRAY + " - "
                                + (distance >= 0 ? String.format(Locale.ROOT, "%.1f blocks from origin", distance)
                                        : "different world from origin"));
                    }
                    return true;

                case "pull":
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "Usage: /smspectate pull <player>");
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null || !origins.containsKey(target.getUniqueId())) {
                        sender.sendMessage(ChatColor.RED + "That player is not currently spectating.");
                        return true;
                    }
                    exitSpectatorMode(target, true);
                    sendPluginMessage(target, msgSpectateExit);
                    sender.sendMessage(ChatColor.GREEN + "Pulled " + target.getName() + " out of spectator mode.");
                    return true;

                case "version":
                    sender.sendMessage(ChatColor.GRAY + "SMPSpectate v" + getDescription().getVersion()
                            + " running on " + serverSoftware.name() + " " + versionString(minecraftVersion));
                    return true;

                default:
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /smspectate <reload|list|pull|version>");
                    return true;
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                String partial = args[0].toLowerCase(Locale.ROOT);
                return subcommands.stream()
                        .filter(s -> s.startsWith(partial))
                        .collect(Collectors.toList());
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("pull")) {
                String partial = args[1].toLowerCase(Locale.ROOT);
                List<String> names = new ArrayList<>();
                for (UUID uuid : origins.keySet()) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                        names.add(player.getName());
                    }
                }
                return names;
            }

            return new ArrayList<>();
        }
    }

    private final class Listeners implements Listener {

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            Player player = event.getPlayer();
            if (origins.containsKey(player.getUniqueId())) {
                restorePlayerState(player);
            }
        }

        @EventHandler
        public void onPlayerKick(PlayerKickEvent event) {
            Player player = event.getPlayer();
            if (origins.containsKey(player.getUniqueId())) {
                restorePlayerState(player);
            }
        }

        @EventHandler
        public void onPlayerDeath(PlayerDeathEvent event) {
            Player player = event.getEntity();
            if (origins.containsKey(player.getUniqueId())) {
                restorePlayerState(player);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onGameModeChange(PlayerGameModeChangeEvent event) {
            if (isPluginChangingGamemode) {
                return;
            }
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            if (origins.containsKey(uuid)) {
                origins.remove(uuid);
                savedEffects.remove(uuid);
            }
        }
    }

    private final class BukkitChatListener implements Listener {
        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            if (origins.containsKey(player.getUniqueId())) {
                event.setCancelled(true);
                sendPluginMessage(player, "&cYou cannot chat while spectating.");
            }
        }
    }
}
