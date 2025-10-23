package net.rainbowcreation.langcategory;

import me.icegames.iglanguages.IGLanguages;
import me.icegames.iglanguages.api.IGLanguagesAPI;
import net.rainbowcreation.storage.api.StorageClient;
import net.rainbowcreation.storage.api.StorageGateway;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangCategory: lets players (and admins) configure which translation categories (by filename)
 * are enabled/disabled. Policies are stored in StorageGateway (no local files).
 *
 * Commands (all default-usable; add [player] at the end if you have langcat.admin):
 *   /lcat enable <category> [player]
 *   /lcat disable <category> [player]
 *   /lcat list [player]
 *   /lcat show [player]
 *   /lcat enable all [player]
 *   /lcat disable all [player]
 *   /lcat enable only <category> [player]
 *   /lcat disable only <category> [player]
 */
public final class LangCategory extends JavaPlugin implements CommandExecutor {

    /* ========= Required services ========= */
    private StorageClient client;
    private IGLanguagesAPI langAPI;

    /* ========= Storage keys ========= */
    private String policyNs;                 // e.g. "lcatpolicy"
    private String policyKeyPrefix;          // e.g. "lcat:"

    /* ========= Policy model ========= */
    public enum Mode { ALL, ONLY, EXCEPT, NONE } // NONE = deny all
    public static final class Policy {
        public Mode mode = Mode.ALL;
        public final Set<String> cats = new HashSet<>();
    }

    // In-memory per-player cache; lazy-loaded from StorageGateway
    private final Map<UUID, Policy> perPlayer = new ConcurrentHashMap<>();
    private Policy globalDefault;

    // Hook registered only if IGLanguages is present (it is required by plugin.yml)
    private IGLangExt hook;

    @Override public void onEnable() {
        // --- require dependencies (plugin.yml depend ensures load order, but double-check) ---
        if (!Bukkit.getPluginManager().isPluginEnabled("IGLanguages")) {
            getLogger().severe("IGLanguages is required but not found. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (Bukkit.getServicesManager().load(StorageGateway.class) == null) {
            getLogger().severe("StorageGatewayAPI is required but not found. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig(); // only for connection + defaults (no player data here)

        // --- read config (connection + defaults) ---
        String db      = getConfig().getString("db", "main");
        String secret  = getConfig().getString("secret", "");
        policyNs       = getConfig().getString("translationPolicy.storage.namespace", "lcatpolicy");
        policyKeyPrefix= getConfig().getString("translationPolicy.storage.keyPrefix", "lcat:");

        globalDefault = new Policy();
        String defMode = getConfig().getString("translationPolicy.default.mode", "ALL").toUpperCase(Locale.ROOT);
        try { globalDefault.mode = Mode.valueOf(defMode); } catch (IllegalArgumentException ignore) { globalDefault.mode = Mode.ALL; }
        for (String c : getConfig().getStringList("translationPolicy.default.categories"))
            globalDefault.cats.add(c.toLowerCase(Locale.ROOT));

        // --- open StorageGateway ---
        ServicesManager sm = Bukkit.getServicesManager();
        StorageGateway gw = sm.load(StorageGateway.class);
        try {
            client = gw.open(db, secret);
            getLogger().info("LangCategory connected to StorageGateway db=" + db);
        } catch (SecurityException se) {
            getLogger().severe("Invalid secret for db " + db + ": " + se.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // --- IGLanguages API (required) ---
        langAPI = IGLanguages.getInstance().getAPI();
        if (langAPI == null) {
            getLogger().severe("IGLanguages API unavailable. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // --- register TranslationExtension hook ---
        hook = new IGLangExt(this);
        hook.register();
        getLogger().info("Registered TranslationExtension (LangCategory).");

        // --- command ---
        Optional.ofNullable(getCommand("lcat")).ifPresent(c -> {
            c.setExecutor(this);
            c.setTabCompleter(this);
        });
    }

    @Override public void onDisable() {
        if (hook != null) { hook.unregister(); hook = null; }
    }

    /* ================== Commands ================== */

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!"lcat".equalsIgnoreCase(cmd.getName())) return false;

        // optional trailing [player] (admin required)
        UUID targetId = parseOptionalTargetAtEnd(sender, args);
        if (targetId != null) args = Arrays.copyOf(args, args.length - 1);
        if (targetId == null) {
            if (!(sender instanceof Player)) { sender.sendMessage("Players only (or specify a player name)."); return true; }
            targetId = ((Player) sender).getUniqueId();
        }

        if (args.length == 0) { help(sender); return true; }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "enable":  return handleEnable(sender, targetId, slice(args,1));
            case "disable": return handleDisable(sender, targetId, slice(args,1));
            case "toggle":  return handleToggle(sender, targetId, slice(args,1));
            case "list":    return handleList(sender, targetId);
            case "show":    return handleShow(sender, targetId);
            default:        help(sender); return true;
        }
    }

    private boolean handleEnable(CommandSender sender, UUID target, String[] tail) {
        if (tail.length == 0) { sender.sendMessage(msg("&cUsage: /lcat enable <category|all|only> [category] [player]")); return true; }
        String what = tail[0].toLowerCase(Locale.ROOT);

        if ("all".equals(what)) {
            getPolicy(target).thenAccept(pol -> {
                pol.mode = Mode.ALL; pol.cats.clear();
                savePolicy(target, pol);
                sendDone(sender, target, "Enabled all categories (mode=ALL).");
            });
            return true;
        }
        if ("only".equals(what)) {
            if (tail.length < 2) { sender.sendMessage(msg("&cUsage: /lcat enable only <category> [player]")); return true; }
            String cat = normCat(tail[1]);
            getPolicy(target).thenAccept(pol -> {
                pol.mode = Mode.ONLY; pol.cats.clear(); pol.cats.add(cat);
                savePolicy(target, pol);
                sendDone(sender, target, "Enabled only: " + cat + " (mode=ONLY).");
            });
            return true;
        }

        // enable <category>
        String cat = normCat(what);
        getPolicy(target).thenAccept(pol -> {
            switch (pol.mode) {
                case NONE:  pol.mode = Mode.ONLY; pol.cats.clear(); pol.cats.add(cat); break;
                case ONLY:  pol.cats.add(cat); break;
                case EXCEPT:pol.cats.remove(cat); break;
                case ALL: default: /* already allowed */ break;
            }
            savePolicy(target, pol);
            sendDone(sender, target, "Enabled category: " + cat + " (mode=" + pol.mode + ").");
        });
        return true;
    }

    private boolean handleDisable(CommandSender sender, UUID target, String[] tail) {
        if (tail.length == 0) { sender.sendMessage(msg("&cUsage: /lcat disable <category|all|only> [category] [player]")); return true; }
        String what = tail[0].toLowerCase(Locale.ROOT);

        if ("all".equals(what)) {
            getPolicy(target).thenAccept(pol -> {
                pol.mode = Mode.NONE; pol.cats.clear();
                savePolicy(target, pol);
                sendDone(sender, target, "Disabled all categories (mode=NONE).");
            });
            return true;
        }
        if ("only".equals(what)) {
            if (tail.length < 2) { sender.sendMessage(msg("&cUsage: /lcat disable only <category> [player]")); return true; }
            String cat = normCat(tail[1]);
            getPolicy(target).thenAccept(pol -> {
                pol.mode = Mode.EXCEPT; pol.cats.clear(); pol.cats.add(cat);
                savePolicy(target, pol);
                sendDone(sender, target, "Disabled only: " + cat + " (mode=EXCEPT, all others enabled).");
            });
            return true;
        }

        // disable <category>
        String cat = normCat(what);
        getPolicy(target).thenAccept(pol -> {
            switch (pol.mode) {
                case NONE:  /* already denied */ break;
                case ONLY:  pol.cats.remove(cat); if (pol.cats.isEmpty()) pol.mode = Mode.NONE; break;
                case EXCEPT:pol.cats.add(cat); break;
                case ALL: default: pol.mode = Mode.EXCEPT; pol.cats.add(cat); break;
            }
            savePolicy(target, pol);
            sendDone(sender, target, "Disabled category: " + cat + " (mode=" + pol.mode + ").");
        });
        return true;
    }

    private boolean handleToggle(CommandSender sender, UUID target, String[] tail) {
        if (tail.length == 0) { sender.sendMessage(msg("&cUsage: /lcat toggle <category> [player]")); return true; }
        String cat = normCat(tail[0]);

        getPolicy(target).thenAccept(pol -> {
            switch (pol.mode) {
                case NONE:
                    // Nothing allowed → toggling a cat should allow just that one
                    pol.mode = Mode.ONLY;
                    pol.cats.clear();
                    pol.cats.add(cat);
                    break;

                case ALL:
                    // Everything allowed → toggling a cat should block just that one
                    pol.mode = Mode.EXCEPT;
                    pol.cats.clear();
                    pol.cats.add(cat);
                    break;

                case ONLY:
                    // Allowed-set semantics: flip membership
                    if (pol.cats.remove(cat)) {
                        if (pol.cats.isEmpty()) pol.mode = Mode.NONE; // no cats left → deny-all
                    } else {
                        pol.cats.add(cat);
                    }
                    break;

                case EXCEPT:
                    // Blocked-set semantics: flip membership
                    if (pol.cats.remove(cat)) {
                        if (pol.cats.isEmpty()) pol.mode = Mode.ALL; // no blocks left → allow-all
                    } else {
                        pol.cats.add(cat);
                    }
                    break;
            }
            savePolicy(target, pol);
            sendDone(sender, target, "Toggled category: " + cat + " (mode=" + pol.mode + ", set=" + pol.cats + ")");
        });
        return true;
    }


    private boolean handleList(CommandSender sender, UUID target) {
        // use target's current language if online; otherwise default language
        String lang = Optional.ofNullable(Bukkit.getPlayer(target))
                .map(langAPI::getPlayerLang)
                .orElseGet(() -> IGLanguages.getInstance().getLangManager().getDefaultLang());
        List<String> cats = langAPI.getAvailableCategories(lang);
        sender.sendMessage(msg("&eCategories (" + lang + "): &f" + cats));
        return true;
    }

    private boolean handleShow(CommandSender sender, UUID target) {
        getPolicy(target).thenAccept(pol -> {
            sender.sendMessage(msg("&bMode: &f" + pol.mode));
            sender.sendMessage(msg("&bSet:  &f" + pol.cats));
        });
        return true;
    }

    private void help(CommandSender s) {
        boolean admin = s.hasPermission("langcat.admin");
        s.sendMessage(msg("&e/lcat enable <category>"));
        s.sendMessage(msg("&e/lcat disable <category>"));
        s.sendMessage(msg("&e/lcat toggle <category>"));
        s.sendMessage(msg("&e/lcat list"));
        s.sendMessage(msg("&e/lcat show"));
        s.sendMessage(msg("&e/lcat enable all"));
        s.sendMessage(msg("&e/lcat disable all"));
        s.sendMessage(msg("&e/lcat enable only <category>"));
        s.sendMessage(msg("&e/lcat disable only <category>"));
        if (admin) {
            s.sendMessage(msg("&7(Admin) Append a player name to target someone else, e.g.:"));
            s.sendMessage(msg("&7/lcat enable only <category> <player>"));
        }
    }


    /* ================== Gate (used by hook) ================== */

    public boolean decideGate(Player player, String category) {
        if (player == null) return true;
        if (category == null || category.isEmpty()) category = "uncategorized";
        category = category.toLowerCase(Locale.ROOT);

        Policy pol = perPlayer.get(player.getUniqueId());
        if (pol == null) { getPolicy(player.getUniqueId()); pol = globalDefault; } // async load
        switch (pol.mode) {
            case ALL:   return true;
            case NONE:  return false;
            case ONLY:  return pol.cats.contains(category);
            case EXCEPT:return !pol.cats.contains(category);
            default:    return true;
        }
    }

    /* ================== StorageGateway IO ================== */

    private CompletableFuture<Policy> getPolicy(UUID id) {
        Policy cached = perPlayer.get(id);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        String key = policyKeyPrefix + id.toString();
        return client.get(policyNs, key, String.class).thenApply(opt -> {
            Policy p = opt.map(this::decodePolicy).orElseGet(this::copyDefault);
            perPlayer.put(id, p);
            return p;
        }).exceptionally(t -> {
            getLogger().warning("Failed to load policy for " + id + ": " + t.getMessage());
            Policy p = copyDefault();
            perPlayer.put(id, p);
            return p;
        });
    }

    private void savePolicy(UUID id, Policy p) {
        String key = policyKeyPrefix + id.toString();
        client.set(policyNs, key, encodePolicy(p)).exceptionally(t -> {
            getLogger().warning("Failed to save policy for " + id + ": " + t.getMessage());
            return null;
        });
    }

    // MODE|cat1,cat2,...
    private String encodePolicy(Policy p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.mode.name()).append('|');
        boolean first = true;
        for (String c : p.cats) {
            if (!first) sb.append(',');
            sb.append(c.toLowerCase(Locale.ROOT));
            first = false;
        }
        return sb.toString();
    }

    private Policy decodePolicy(String s) {
        Policy p = new Policy();
        try {
            String[] parts = s.split("\\|", 2);
            p.mode = Mode.valueOf(parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                for (String c : parts[1].split(",")) if (!c.isEmpty()) p.cats.add(c.toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignore) {
            p = copyDefault();
        }
        return p;
    }

    private Policy copyDefault() {
        Policy p = new Policy();
        p.mode = globalDefault.mode;
        p.cats.addAll(globalDefault.cats);
        return p;
    }

    /* ================ Tab Complete ================*/
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("lcat")) return Collections.emptyList();
        boolean admin = sender.hasPermission("langcat.admin");

        if (args.length == 1) {
            return filter(args[0], Arrays.asList("enable","disable", "toggle", "list","show"));
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (args.length == 2) {
            if (sub.equals("enable") || sub.equals("disable") || sub.equals("toggle")) {
                List<String> base = new ArrayList<>(Arrays.asList("all","only"));
                base.addAll(currentCategories(sender));
                return filter(args[1], base);
            }
            return Collections.emptyList();
        }

        if (args.length == 3) {
            if ((sub.equals("enable") || sub.equals("disable")) && args[1].equalsIgnoreCase("only")) {
                return filter(args[2], currentCategories(sender));
            }
            if (admin) return filter(args[2], onlinePlayerNames());
            return Collections.emptyList();
        }

        // 4th arg or more → only admins can target players at the end
        if (admin) return filter(args[args.length-1], onlinePlayerNames());
        return Collections.emptyList();
    }

    private List<String> currentCategories(CommandSender sender) {
        try {
            String lang = (sender instanceof Player)
                    ? langAPI.getPlayerLang((Player) sender)
                    : IGLanguages.getInstance().getLangManager().getDefaultLang();
            return langAPI.getAvailableCategories(lang);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    private List<String> filter(String prefix, List<String> options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }


    /* ================== Helpers ================== */

    private UUID parseOptionalTargetAtEnd(CommandSender sender, String[] args) {
        if (args.length == 0) return null;
        String last = args[args.length - 1];
        // Only admins can target others
        if (!sender.hasPermission("langcat.admin")) return null;
        if (!isPlausiblePlayerName(last)) return null;
        OfflinePlayer op = Bukkit.getOfflinePlayer(last);
        return (op != null && op.getUniqueId() != null) ? op.getUniqueId() : null;
    }

    private static boolean isPlausiblePlayerName(String s) {
        return s != null && s.length() >= 3 && s.length() <= 16;
    }

    private static String[] slice(String[] src, int from) {
        if (from >= src.length) return new String[0];
        String[] out = new String[src.length - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static String normCat(String s) { return s.toLowerCase(Locale.ROOT); }

    private static String msg(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void sendDone(CommandSender sender, UUID target, String message) {
        String who = Optional.ofNullable(Bukkit.getOfflinePlayer(target))
                .map(OfflinePlayer::getName).orElse(target.toString());
        sender.sendMessage(ChatColor.GREEN + "[LangCategory] " + ChatColor.YELLOW + who + ChatColor.GRAY + ": " + ChatColor.WHITE + message);
    }
}