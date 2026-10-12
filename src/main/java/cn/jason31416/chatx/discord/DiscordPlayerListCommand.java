package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.ChatX;
import com.velocitypowered.api.proxy.Player;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class DiscordPlayerListCommand {
    private static final int MAX_MESSAGE_LENGTH = Message.MAX_CONTENT_LENGTH;

    private final DiscordManager manager;
    private final Map<String, Long> userCooldowns = new HashMap<>();
    private final Map<String, Long> channelCooldowns = new HashMap<>();

    DiscordPlayerListCommand(@Nonnull DiscordManager manager) {
        this.manager = manager;
    }

    boolean consume(@Nonnull MessageReceivedEvent event, @Nonnull DiscordRoute route) {
        DiscordConfig.PlayerListCommand command = manager.getConfig().getPlayerListCommand();
        if(!command.enabled() || !event.getMessage().getContentRaw().equals(command.trigger())) return false;

        // A recognized command is never forwarded to Minecraft, including ignored bot/webhook invocations.
        if(event.isWebhookMessage() || event.getAuthor().isBot()) return true;
        if(!acquireCooldown(event.getAuthor().getId(), event.getChannel().getId(), command.cooldownSeconds())){
            return true;
        }

        List<String> players = players(route);
        String response = render(players, command.formats());
        manager.publishCommandResponse(event.getChannel().getId(), response, command.deleteAfterSeconds());
        return true;
    }

    @Nonnull
    private static List<String> players(@Nonnull DiscordRoute route) {
        var stream = route.type() == DiscordRoute.Type.GLOBAL
                ? ChatX.getProxy().getAllPlayers().stream()
                : ChatX.getProxy().getServer(route.backend())
                        .stream()
                        .flatMap(server -> server.getPlayersConnected().stream());
        return stream
                .map(Player::getUsername)
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo))
                .toList();
    }

    private synchronized boolean acquireCooldown(
            @Nonnull String userId,
            @Nonnull String channelId,
            int cooldownSeconds
    ) {
        if(cooldownSeconds <= 0) return true;
        long now = System.currentTimeMillis();
        userCooldowns.values().removeIf(expiresAt -> expiresAt <= now);
        channelCooldowns.values().removeIf(expiresAt -> expiresAt <= now);
        if(userCooldowns.getOrDefault(userId, 0L) > now
                || channelCooldowns.getOrDefault(channelId, 0L) > now){
            return false;
        }
        long expiresAt = now + TimeUnit.SECONDS.toMillis(cooldownSeconds);
        userCooldowns.put(userId, expiresAt);
        channelCooldowns.put(channelId, expiresAt);
        return true;
    }

    @Nonnull
    private static String render(
            @Nonnull List<String> players,
            @Nonnull DiscordConfig.PlayerListFormats formats
    ) {
        int count = players.size();
        if(players.isEmpty()){
            return truncate(formats.empty().replace("{count}", "0"), MAX_MESSAGE_LENGTH);
        }

        String header = formats.header().replace("{count}", Integer.toString(count));
        List<String> rows = players.stream()
                .map(name -> formats.player()
                        .replace("{name}", name)
                        .replace("{count}", Integer.toString(count)))
                .toList();

        List<String> full = new ArrayList<>(rows.size() + 1);
        full.add(header);
        full.addAll(rows);
        String complete = String.join("\n", full);
        if(complete.length() <= MAX_MESSAGE_LENGTH) return complete;

        List<String> visible = new ArrayList<>();
        for(String row : rows){
            int hidden = count - visible.size() - 1;
            List<String> candidate = new ArrayList<>(visible.size() + 3);
            candidate.add(header);
            candidate.addAll(visible);
            candidate.add(row);
            if(hidden > 0) candidate.add(truncatedLine(formats, hidden, count));
            if(String.join("\n", candidate).length() > MAX_MESSAGE_LENGTH) break;
            visible.add(row);
        }

        int hidden = count - visible.size();
        while(true){
            String suffix = truncatedLine(formats, hidden, count);
            List<String> result = new ArrayList<>(visible.size() + 2);
            result.add(header);
            result.addAll(visible);
            result.add(suffix);
            String rendered = String.join("\n", result);
            if(rendered.length() <= MAX_MESSAGE_LENGTH) return rendered;
            if(!visible.isEmpty()){
                visible.removeLast();
                hidden++;
                continue;
            }

            suffix = suffix.length() < MAX_MESSAGE_LENGTH ? suffix : "… +" + hidden;
            int headerLimit = Math.max(0, MAX_MESSAGE_LENGTH - suffix.length() - 1);
            return truncate(header, headerLimit) + "\n" + suffix;
        }
    }

    @Nonnull
    private static String truncatedLine(
            @Nonnull DiscordConfig.PlayerListFormats formats,
            int hidden,
            int count
    ) {
        return formats.truncated()
                .replace("{hidden}", Integer.toString(hidden))
                .replace("{count}", Integer.toString(count));
    }

    @Nonnull
    private static String truncate(@Nonnull String value, int limit) {
        if(limit <= 0) return "";
        if(value.length() <= limit) return value;
        if(limit == 1) return "…";
        return value.substring(0, limit - 1) + "…";
    }
}
