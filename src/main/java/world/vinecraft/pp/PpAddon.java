package world.vinecraft.pp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChatTabCompleteEvent;
import org.bukkit.scheduler.BukkitTask;

import net.md_5.bungee.api.ChatColor;
import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.api.addons.Addon;
import world.bentobox.bentobox.api.user.User;
import world.bentobox.bentobox.util.Util;

public class PpAddon extends Addon implements Listener {
    private Deque<ChatLine> chatBuffer;
    private List<Challenge> challenges;
    private ChatGPTService gpt;
    private BukkitTask pollingTask;
    private Map<UUID, Map<String, LocalDate>> completedToday;
    private boolean checking;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        chatBuffer = new ConcurrentLinkedDeque<>();
        completedToday = new HashMap<>();

        // Load config
        int interval = getConfig().getInt("poll-interval", 60);
        String key  = getConfig().getString("openai-api-key");
        if (key.equals("your-secret-key-here")) {
            this.logError("Set the ChatGPT key in config.yml and then restart the server!");
            this.setState(State.DISABLED);
            return;
        }
        gpt = new ChatGPTService(this, key);

        // Load challenges
        challenges = new ArrayList<>();
        getConfig().getConfigurationSection("challenges").getKeys(false)
                .forEach(id -> {
                    String prompt = getConfig().getString("challenges." + id + ".prompt");
                    List<String> cmds = getConfig().getStringList("challenges." + id + ".commands");
                    challenges.add(new Challenge(id, prompt, cmds));
                });

        // Register chat listener
        this.registerListener(this);

        // Schedule polling task
        pollingTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                getPlugin(),
                this::checkChallenges, interval * 20L, // delay
                interval * 20L // period
        );
    }

    @Override
    public void onDisable() {
        if (pollingTask != null) pollingTask.cancel();
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        // Take over chat completely
        e.setCancelled(true);

        // See if first char is an @
        if (e.getMessage().startsWith("@all ")) {
            // Handle broadcast
            e.setMessage(e.getMessage().substring(5, e.getMessage().length()));
            e.setCancelled(false);
            return;
        } else if (e.getMessage().startsWith("@")) {
            // DM
            // Find if this is a known player
            int index = e.getMessage().indexOf(" ");
            String target = e.getMessage().substring(1, index > 0 ? index : e.getMessage().length());
            if (Util.getOnlinePlayerList(User.getInstance(e.getPlayer())).stream()
                    .anyMatch(n -> n.equalsIgnoreCase(target))) {
                BentoBox.getInstance().getPlayers().getUser(target)
                        .sendRawMessage(e.getPlayer().getDisplayName() + ": " + e.getMessage().substring(index));
            } else {
                User.getInstance(e.getPlayer()).sendMessage("general.errors.unknown-player");
            }
            return;
        }
        // General range limited chat
        // Check if there are any players online that the player can see
        User user = User.getInstance(e.getPlayer());
        if (Util.getOnlinePlayerList(user).isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            // Find out who might be around
            boolean alone = true;
            double size = getConfig().getDouble("chat-distance", 10d);
            for (Entity en : e.getPlayer().getNearbyEntities(size, size, size)) {
                if (en instanceof Player p && user.getPlayer().canSee(p)) {
                    p.sendMessage(ChatColor.WHITE + "<" + e.getPlayer().getDisplayName() + ChatColor.WHITE + "> "
                            + e.getMessage());
                    alone = false;
                }
            }
            if (!alone) {
                user.getPlayer().sendMessage(ChatColor.WHITE + "<" + e.getPlayer().getDisplayName() + ChatColor.WHITE
                        + "> " + e.getMessage());
            } else {
                user.getPlayer().sendMessage(ChatColor.WHITE + "<" + e.getPlayer().getDisplayName() + ChatColor.WHITE
                        + "> " + ChatColor.GRAY + e.getMessage());
            }

            // keep only last N
            chatBuffer.addLast(new ChatLine(e.getPlayer().getUniqueId(), e.getPlayer().getName(), e.getMessage()));
            while (chatBuffer.size() > getConfig().getInt("window-size", 100)) {
                chatBuffer.pollFirst();
            }
        });
    }

    @EventHandler
    public void onChatTabComplete(@SuppressWarnings("deprecation") PlayerChatTabCompleteEvent event) {
        String message = event.getChatMessage();

        // Only modify if the player typed something starting with "@"
        if (message.startsWith("@")) {
            String partial = message.substring(1).toLowerCase(); // Remove '@' for matching
            Set<String> completions = new HashSet<>();

            // Add matching player names
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(partial)) {
                    completions.add("@" + online.getName());
                }
            }

            // Add "@all" if it matches
            if ("all".startsWith(partial)) {
                completions.add("@all");
            }

            // Override suggestions
            event.getTabCompletions().clear();
            event.getTabCompletions().addAll(completions);
        }
    }

    private void checkChallenges() {
        if (chatBuffer.isEmpty() || checking)
            return; // no new chat

        checking = true;

        // snapshot current buffer
        List<ChatLine> window = new ArrayList<>(chatBuffer);

        // build payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat", window);
        payload.put("challenges", challenges);

        // ask ChatGPT
        Map<String, List<String>> results = gpt.evaluateChallenges(payload);
        // results: map<challengeId, list of player names>
        ConsoleCommandSender console = getServer().getConsoleSender();
        LocalDate today = LocalDate.now();

        results.forEach((id, winners) -> {
            Challenge c = challenges.stream()
                    .filter(ch -> ch.id().equals(id)).findFirst().orElse(null);
            if (c == null) return;

            for (String playerName : winners) {
                UUID uuid = this.getPlayers().getUUID(playerName);
                if (uuid == null) {
                    this.logError("Player not found: " + playerName);
                    continue;
                }
                completedToday.putIfAbsent(uuid, new HashMap<>());
                Map<String, LocalDate> doneMap = completedToday.get(uuid);
                if (doneMap.getOrDefault(id, LocalDate.MIN).isEqual(today)) continue;

                // run reward commands sync
                Bukkit.getScheduler().runTask(getPlugin(), () ->
                c.commands().forEach(cmd -> Bukkit.dispatchCommand(console, cmd.replace("%player%", playerName))));

                doneMap.put(id, today);
            }
        });
        // clear buffer so we don't double-count
        chatBuffer.clear();
        checking = false;
    }
}
