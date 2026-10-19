package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.ChatX;
import cn.jason31416.chatx.util.Config;
import com.velocitypowered.api.proxy.Player;

import javax.annotation.Nonnull;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record DiscordTabSnapshot(
        @Nonnull String routeName,
        @Nonnull String backend,
        @Nonnull String networkName,
        boolean local,
        int count,
        int networkOnline,
        int hidden,
        int maxPlayers,
        @Nonnull String generatedAt,
        @Nonnull String source,
        @Nonnull List<Entry> players
) {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}|%([a-zA-Z0-9_]+)%");
    private static final Pattern UNRESOLVED_TAB = Pattern.compile("%[a-zA-Z0-9_:-]+%");
    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z");
    private static final long TAB_PLACEHOLDER_TIMEOUT_MILLIS = 1_200;
    static DiscordTabSnapshot capture(
            @Nonnull DiscordRoute route,
            @Nonnull DiscordConfig.PlayerListImage config,
            @Nonnull String source
    ) {
        List<Player> routePlayers = route.type() == DiscordRoute.Type.GLOBAL
                ? List.copyOf(ChatX.getProxy().getAllPlayers())
                : ChatX.getProxy().getServer(route.backend())
                        .<List<Player>>map(server -> List.copyOf(server.getPlayersConnected()))
                        .orElseGet(List::of);
        boolean tabAvailable = config.useTab()
                && ChatX.getProxy().getPluginManager().isLoaded("tab");
        List<CompletableFuture<DiscordTabValues>> tabValues = new ArrayList<>(routePlayers.size());
        for(Player player : routePlayers){
            if(!tabAvailable){
                tabValues.add(CompletableFuture.completedFuture(DiscordTabValues.EMPTY));
                continue;
            }
            try{
                tabValues.add(DiscordTabIntegration.values(player));
            }catch (RuntimeException | LinkageError ignored){
                tabValues.add(CompletableFuture.completedFuture(DiscordTabValues.EMPTY));
            }
        }
        try{
            // The bridge request is bounded by its own timeout and all players resolve in parallel.
            CompletableFuture.allOf(tabValues.toArray(CompletableFuture[]::new))
                    .get(TAB_PLACEHOLDER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }catch (InterruptedException error){
            Thread.currentThread().interrupt();
        }catch (ExecutionException | TimeoutException ignored){
            // Keep values already available from TAB if the backend bridge is slow.
        }
        List<Entry> entries = new ArrayList<>(routePlayers.size());
        for(int index = 0; index < routePlayers.size(); index++){
            Player player = routePlayers.get(index);
            String backend = player.getCurrentServer()
                    .map(connection -> connection.getServer().getServerInfo().getName())
                    .orElse("");
            DiscordTabValues tab = tabValues.get(index).getNow(DiscordTabValues.EMPTY);
            entries.add(new Entry(
                    player.getUniqueId(), player.getUsername(), backend,
                    Config.getServerDisplayName(backend),
                    backend.isBlank() ? 0 : ChatX.getProxy().getServer(backend)
                            .map(server -> server.getPlayersConnected().size()).orElse(0),
                    player.getPing(),
                    tab.prefix(), tab.name().isBlank() ? player.getUsername() : tab.name(),
                    tab.suffix(), tab.group(), tab.sortKey(), tab.world(),
                    tab.originalPrefix(), tab.originalName(), tab.originalSuffix()
            ));
        }
        Comparator<Entry> names = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::name);
        if(tabAvailable && entries.stream().anyMatch(entry -> !entry.sortKey().isBlank())){
            entries.sort(Comparator.comparing(
                            (Entry entry) -> entry.sortKey().isBlank() ? "\uffff" : entry.sortKey(),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(names));
        }else{
            entries.sort(names);
        }
        int count = entries.size();
        int shown = Math.min(count, config.maxPlayers());
        String backend = route.type() == DiscordRoute.Type.GLOBAL ? "" : route.backend();
        String routeName = backend.isBlank() ? config.networkName() : Config.getServerDisplayName(backend);
        return new DiscordTabSnapshot(routeName, backend, config.networkName(), route.type() == DiscordRoute.Type.LOCAL, count,
                ChatX.getProxy().getPlayerCount(), count - shown, config.maxPlayers(), GENERATED_AT.format(ZonedDateTime.now()), source,
                List.copyOf(entries.subList(0, shown)));
    }

    Map<String, String> placeholders() {
        Map<String, String> values = new HashMap<>();
        values.put("route_name", routeName);
        values.put("route", routeName);
        values.put("network_name", networkName);
        values.put("backend", backend);
        values.put("server_id", backend);
        values.put("server", routeName);
        values.put("count", Integer.toString(count));
        values.put("online", Integer.toString(count));
        values.put("server_online", Integer.toString(count));
        values.put("network_online", Integer.toString(networkOnline));
        values.put("hidden", Integer.toString(hidden));
        values.put("shown", Integer.toString(players.size()));
        values.put("max", Integer.toString(maxPlayers));
        values.put("generated_at", generatedAt);
        values.put("source", source);
        for(var server : ChatX.getProxy().getAllServers()){
            String id = server.getServerInfo().getName();
            values.put("online_" + id, Integer.toString(server.getPlayersConnected().size()));
        }
        return values;
    }

    static String apply(@Nonnull String template, @Nonnull Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while(matcher.find()){
            String key = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement(values.getOrDefault(key, matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    record Entry(
            UUID uuid,
            String name,
            String backend,
            String server,
            int serverOnline,
            long ping,
            String prefix,
            String displayName,
            String suffix,
            String group,
            String sortKey,
            String world,
            String originalPrefix,
            String originalName,
            String originalSuffix
    ) {
        Map<String, String> placeholders(DiscordTabSnapshot snapshot) {
            Map<String, String> values = new java.util.HashMap<>(snapshot.placeholders());
            values.put("name", name);
            values.put("player", name);
            values.put("player_name", name);
            values.put("display_name", displayName);
            values.put("tab_name", displayName);
            values.put("tabname", displayName);
            values.put("prefix", prefix);
            values.put("tabprefix", prefix);
            values.put("suffix", suffix);
            values.put("tabsuffix", suffix);
            values.put("group", group);
            values.put("sort_key", sortKey);
            values.put("ping", Long.toString(ping));
            values.put("player_ping", Long.toString(ping));
            values.put("uuid", uuid.toString());
            values.put("world", world);
            values.put("player_world", world);
            values.put("backend", backend);
            values.put("server", server);
            values.put("server_id", backend);
            values.put("server_online", Integer.toString(serverOnline));
            values.put("prefix", resolvedTab(prefix, originalPrefix));
            values.put("display_name", resolvedTab(displayName, originalName));
            values.put("tab_name", values.get("display_name"));
            values.put("tabname", values.get("display_name"));
            values.put("suffix", resolvedTab(suffix, originalSuffix));
            values.put("tabprefix", values.get("prefix"));
            values.put("tabsuffix", values.get("suffix"));
            return values;
        }

        private static String resolvedTab(String value, String original) {
            if(value == null) return "";
            // TAB's original-replaced value has already passed through its own
            // placeholders and replacements. Do not reinterpret %world% as a
            // literal world name here: it may be a color in TAB's config.
            if(!UNRESOLVED_TAB.matcher(value).find()) return value;
            String source = original != null && !original.isBlank() ? original : value;
            return UNRESOLVED_TAB.matcher(source).replaceAll("");
        }
    }
}
