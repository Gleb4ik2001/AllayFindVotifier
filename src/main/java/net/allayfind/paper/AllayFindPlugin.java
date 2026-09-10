package net.allayfind.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.channels.*;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class AllayFindPlugin extends JavaPlugin {
    private ScheduledExecutorService worker;
    private ApiClient api;
    private Inbox inbox;
    private List<String> commands;
    private volatile boolean stopping;
    private FileChannel lockChannel;
    private FileLock fileLock;
    private long nextRequest;
    private int failures;
    private String networkStatus = "Not polled yet";

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            commands = List.copyOf(getConfig().getStringList("reward-commands"));
            if (commands.isEmpty() || commands.size() > 20 || commands.stream().anyMatch(command ->
                    command.isBlank() || command.startsWith("/") || command.length() > 512 ||
                    command.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Configure 1-20 single-line reward commands without a leading slash");
            api = new ApiClient(getConfig().getString("site-url", ""), getConfig().getLong("server-id"),
                    getConfig().getString("api-token", ""), getConfig().getString("edition", "java"),
                    getConfig().getBoolean("allow-local-http"));
            lockChannel = FileChannel.open(getDataFolder().toPath().resolve("receiver.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
            if (fileLock == null) throw new IllegalStateException("Another receiver is using this data folder");
            inbox = new Inbox(getDataFolder().toPath().resolve("votes.db"),
                    api.scope() + "#" + getConfig().getString("edition", "java"));
            worker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "AllayFind-worker"); thread.setDaemon(true); return thread;
            });
            long seconds = Math.max(10, Math.min(300, getConfig().getLong("poll-seconds", 30)));
            worker.scheduleWithFixedDelay(this::cycle, 1, seconds, TimeUnit.SECONDS);
            getLogger().info("AllayFind enabled. Rewards and API status: /allayfind status");
        } catch (Exception e) {
            getLogger().severe("Cannot start AllayFind: " + e.getClass().getSimpleName() +
                    ". Check config.yml, database access and whether another receiver uses this folder.");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private <T> T onMain(Supplier<T> action) throws Exception {
        if (stopping) throw new InterruptedException();
        CompletableFuture<T> result = new CompletableFuture<>();
        var task = Bukkit.getScheduler().runTask(this, () -> {
            if (stopping || result.isCancelled()) { result.cancel(false); return; }
            try { result.complete(action.get()); }
            catch (Throwable e) { result.completeExceptionally(e); }
        });
        try { return result.get(10, TimeUnit.SECONDS); }
        catch (Exception e) { result.cancel(false); task.cancel(); throw e; }
    }

    private void cycle() {
        if (stopping) return;
        if (System.currentTimeMillis() >= nextRequest) {
            try {
                List<VoteEvent> votes = api.pending();
                inbox.save(votes, commands);
                api.acknowledge(votes);
                failures = 0;
                networkStatus = "API OK at " + Instant.now();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (Exception e) {
                failures = Math.min(failures + 1, 6);
                nextRequest = System.currentTimeMillis() + Math.min(300, 10L << failures) * 1000;
                networkStatus = (e instanceof ApiClient.HttpStatusException ? e.getMessage() :
                        "API/storage error (" + e.getClass().getSimpleName() + ")") + ", retry scheduled";
                getLogger().warning(networkStatus + ". Check token, API mode, HTTPS and local database.");
            }
        }
        try {
            List<String> online = onMain(() -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            for (String name : online) {
                if (stopping) return;
                if (!name.matches("[A-Za-z0-9_.]{1,32}")) continue;
                for (Inbox.Reward reward : inbox.pending(name)) reward(reward, name);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { getLogger().warning("Local rewards paused: " + e.getClass().getSimpleName()); }
    }

    private void reward(Inbox.Reward reward, String name) throws Exception {
        if (!inbox.transition(reward.id(), "pending", "executing")) return;
        try {
            String outcome = onMain(() -> {
                Player player = Bukkit.getPlayerExact(name);
                if (player == null || !player.isOnline()) return "pending";
                return RewardCommands.execute(reward, player.getName(), player.getUniqueId(),
                        command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            });
            inbox.transition(reward.id(), "executing", outcome);
            if (outcome.equals("uncertain")) getLogger().warning("Check reward " + reward.id() + " before retrying.");
        } catch (Exception e) {
            inbox.transition(reward.id(), "executing", "uncertain");
            throw e;
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("allayfind.admin")) { sender.sendMessage("Нет прав."); return true; }
        if (args.length == 0) return false;
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!Set.of("status", "queue", "poll", "retry", "confirm").contains(action)) return false;
        String voteId = null;
        if (action.equals("retry") || action.equals("confirm")) {
            if (args.length != 2) return false;
            try { voteId = UUID.fromString(args[1]).toString(); }
            catch (IllegalArgumentException e) { sender.sendMessage("Некорректный UUID голоса."); return true; }
        }
        final String id = voteId;
        final String administrator = sender.getName();
        worker.execute(() -> {
            try {
                List<String> lines = new ArrayList<>();
                switch (action) {
                    case "poll" -> { nextRequest = 0; cycle(); lines.add(networkStatus); }
                    case "queue" -> { lines.addAll(inbox.unresolved()); if (lines.isEmpty()) lines.add("Очередь пуста."); }
                    case "retry", "confirm" -> {
                        boolean changed = inbox.transition(id, "uncertain", action.equals("retry") ? "pending" : "done");
                        lines.add(changed ? "Статус изменён. Повтор может повторно выдать награду." : "Нет события со статусом uncertain.");
                        if (changed) getLogger().warning("Admin " + administrator + " used " + action + " for " + id);
                    }
                    default -> { lines.add(networkStatus); lines.add(inbox.status()); }
                }
                onMain(() -> { lines.forEach(sender::sendMessage); return null; });
            } catch (Exception e) { getLogger().warning("Diagnostic failed: " + e.getClass().getSimpleName()); }
        });
        return true;
    }

    @Override public void onDisable() {
        stopping = true;
        if (worker != null) {
            worker.shutdownNow();
            try {
                if (!worker.awaitTermination(20, TimeUnit.SECONDS)) {
                    getLogger().severe("Worker still stopping; retaining database lock until process exits."); return;
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        try {
            if (api != null) api.close();
            if (inbox != null) inbox.close();
            if (fileLock != null) fileLock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (Exception e) { getLogger().warning("Database shutdown: " + e.getClass().getSimpleName()); }
    }
}
