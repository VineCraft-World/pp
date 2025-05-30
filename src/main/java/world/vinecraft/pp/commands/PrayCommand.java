package world.vinecraft.pp.commands;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.api.commands.CompositeCommand;
import world.bentobox.bentobox.api.user.User;
import world.vinecraft.pp.Challenge;
import world.vinecraft.pp.ChatGPTService;
import world.vinecraft.pp.ChatGPTService.Winner;
import world.vinecraft.pp.ChatLine;
import world.vinecraft.pp.PpAddon;

/**
 * Handles /pray and /amen commands
 */
public class PrayCommand extends CompositeCommand implements Listener {

    private final PpAddon addon;
    private final List<Challenge> challenges;
    private final ChatGPTService gpt;
    private final Deque<ChatLine> chatBuffer;
    private final Map<UUID, Map<String, LocalDate>> completedToday;
    private boolean checking;


    public PrayCommand(PpAddon addon, String label, String string) throws IOException {
        super(addon, label, string);
        this.addon = addon;
        chatBuffer = new ConcurrentLinkedDeque<>();
        completedToday = new HashMap<>();

        // Load config
        int interval = addon.getConfig().getInt("poll-interval", 60);
        String key = addon.getConfig().getString("openai-api-key");
        if (key.equals("your-secret-key-here")) {
            throw new IOException("Set the ChatGPT key in config.yml and then restart the server!");
        }
        gpt = new ChatGPTService(addon, key);

        // Load challenges
        challenges = new ArrayList<>();
        addon.getConfig().getConfigurationSection("challenges").getKeys(false).forEach(id -> {
            String prompt = addon.getConfig().getString("challenges." + id + ".prompt");
            List<String> cmds = addon.getConfig().getStringList("challenges." + id + ".commands");
            challenges.add(new Challenge(id, prompt, cmds));
        });

        // Register chat listener
        addon.registerListener(this);

        // Schedule polling task
        Bukkit.getScheduler().runTaskTimerAsynchronously(getPlugin(), this::checkChallenges,
                interval * 20L, // delay
                interval * 20L // period
        );
    }

    @Override
    public void setup() {
        this.setPermission("pray");
        this.setOnlyPlayer(true);
        this.setParametersHelp("pp.help.parameters");
        this.setDescription("pp.help.description");
    }

    @Override
    public boolean execute(User user, String label, List<String> args) {
        if (!label.toLowerCase().equals("amen") && args.isEmpty()) {
            this.showHelp(this, user);
            return false;
        }
        String message = "";
        if (label.toLowerCase().equals("amen")) {
            message = "amen";
        }
        if (label.toLowerCase().equals("pray")) {
            message = String.join(" ", args);
        }
        if (!message.isBlank()) {
            // keep only last N
            chatBuffer.addLast(new ChatLine(user.getUniqueId(), user.getName(), message));
            while (chatBuffer.size() > addon.getConfig().getInt("window-size", 100)) {
                chatBuffer.pollFirst();
            }
            sayAloud(user, message);
            user.getPlayer().giveExp(new Random().nextInt(10));
            return true;
        }
        return false;
    }

    private void sayAloud(User user, String message) {
        // Find out who might be around
        boolean alone = true;
        double size = addon.getConfig().getDouble("chat-distance", 10d);
        for (Entity en : user.getPlayer().getNearbyEntities(size, size, size)) {
            if (en instanceof Player p && user.getPlayer().canSee(p)) {
                p.sendMessage(ChatColor.WHITE + "<" + user.getDisplayName() + ChatColor.WHITE + "> " + message);
                alone = false;
            }
        }
        if (!alone) {
            user.getPlayer()
                    .sendMessage(ChatColor.WHITE + "<" + user.getDisplayName() + ChatColor.WHITE + "> " + message);
        } else {
            user.getPlayer().sendMessage(
                    ChatColor.WHITE + "<" + user.getDisplayName() + ChatColor.WHITE + "> " + ChatColor.GRAY + message);
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
        Map<String, List<Winner>> results = gpt.evaluateChallenges(payload);
        // results: map<challengeId, list of player names>
        LocalDate today = LocalDate.now();

        results.forEach((id, winners) -> {
            Challenge c = challenges.stream().filter(ch -> ch.id().equals(id)).findFirst().orElse(null);
            if (c == null)
                return;

            for (Winner playerName : winners) {
                UUID uuid = this.getPlayers().getUUID(playerName.playerName());
                if (uuid == null) {
                    addon.logError("Player not found: " + playerName);
                    continue;
                }
                /*
                completedToday.putIfAbsent(uuid, new HashMap<>());
                Map<String, LocalDate> doneMap = completedToday.get(uuid);
                if (doneMap.getOrDefault(id, LocalDate.MIN).isEqual(today))
                    continue;
                doneMap.put(id, today);
                */
                c.commands().forEach(cmd -> BentoBox.getInstance().logDebug(cmd));
                // run reward commands sync
                Bukkit.getScheduler().runTask(getPlugin(), () -> c.commands()
                        .forEach(cmd -> runCommand(playerName, cmd)));
            }
        });
        // clear buffer so we don't double-count
        chatBuffer.clear();
        checking = false;
    }

    private void runCommand(Winner winner, String cmdTemplate) {
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        List<String> guidanceList = addon.getConfig().getStringList("guidance");

        String guidance = guidanceList.isEmpty() ? "" : guidanceList.get(new Random().nextInt(guidanceList.size()));
        String encodedGuidance = guidance.isEmpty() ? "" : URLEncoder.encode(guidance, StandardCharsets.UTF_8);

        String command = cmdTemplate.replace("%player%", winner.playerName()).replace("%reward%", winner.reward())
                .replace("%qty%", winner.qty().toString()).replace("%guidance%", guidance)
                .replace("%encoded-guidance%", encodedGuidance);
        BentoBox.getInstance().logDebug(command);
        Bukkit.dispatchCommand(console, command);
    }
}
