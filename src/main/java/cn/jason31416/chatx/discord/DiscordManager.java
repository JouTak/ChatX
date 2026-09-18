package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.util.Logger;
import lombok.AccessLevel;
import lombok.Getter;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IncomingWebhookClient;
import net.dv8tion.jda.api.entities.WebhookClient;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Getter
public class DiscordManager {
    private static final long PENDING_MINECRAFT_TTL_MILLIS = 10_000L;

    private final JDA jda;
    private final DiscordConfig config;
    private volatile List<DiscordRoute> availableRoutes = List.of();
    private volatile DiscordRouter router = new DiscordRouter(List.of());
    @Getter(AccessLevel.NONE)
    private final Map<String, IncomingWebhookClient> webhookClients = new ConcurrentHashMap<>();
    @Getter(AccessLevel.NONE)
    private final Map<UUID, Queue<DiscordEvent>> pendingMinecraft = new ConcurrentHashMap<>();

    public DiscordManager(@Nonnull JDA jda) {
        this(jda, null);
    }

    private DiscordManager(@Nonnull JDA jda, DiscordConfig config) {
        this.jda = jda;
        this.config = config;
        if(config != null){
            jda.addEventListener(new ListenerAdapter() {
                @Override
                public void onReady(@Nonnull ReadyEvent event) {
                    validateConnection();
                }
            });
        }
    }

    public static DiscordManager create(@Nonnull DiscordConfig config) {
        if(!config.isEnabled()) return null;
        if(!config.isConnectionValid()){
            Logger.error("Discord integration is disabled because discord.yml is invalid.");
            return null;
        }
        if(!config.isValid()){
            Logger.warn("Discord config contains invalid values; valid routes will remain available.");
        }

        String token = System.getenv(config.getTokenEnvironment());
        if(token == null || token.isBlank()){
            Logger.error("Discord integration is disabled because environment variable "
                    + config.getTokenEnvironment() + " is empty.");
            return null;
        }

        try{
            JDA jda = JDABuilder.createDefault(token, config.getIntents())
                    .build();
            return new DiscordManager(jda, config);
        }catch (Exception e){
            Logger.error("Failed to start Discord integration.");
            return null;
        }
    }

    public boolean publish(@Nonnull String destinationId, @Nonnull MessageCreateData message) {
        MessageChannel channel = jda.getChannelById(MessageChannel.class, destinationId);
        if(channel == null) return false;
        channel.sendMessage(message).queue();
        return true;
    }

    public boolean queueMinecraft(@Nonnull DiscordEvent event) {
        if(getWebhookRoute(event) == null) return false;
        pendingMinecraft.computeIfAbsent(event.playerUuid(), key -> new ConcurrentLinkedQueue<>()).add(event);
        return true;
    }

    @Nullable
    public DiscordEvent pollMinecraft(@Nonnull UUID playerUuid) {
        Queue<DiscordEvent> events = pendingMinecraft.get(playerUuid);
        if(events == null) return null;
        long expiredBefore = System.currentTimeMillis() - PENDING_MINECRAFT_TTL_MILLIS;
        DiscordEvent event;
        do{
            event = events.poll();
        }while (event != null && event.createdAt() < expiredBefore);
        if(events.isEmpty()) pendingMinecraft.remove(playerUuid, events);
        return event;
    }

    public void discardMinecraft(@Nonnull UUID playerUuid) {
        pendingMinecraft.remove(playerUuid);
    }

    public boolean publishMinecraft(@Nonnull DiscordEvent event, @Nonnull Component message) {
        DiscordRoute route = getWebhookRoute(event);
        if(route == null) return false;

        try{
            String content = PlainTextComponentSerializer.plainText().serialize(message);
            IncomingWebhookClient webhook = webhookClients.computeIfAbsent(
                    route.webhookUrl(),
                    url -> WebhookClient.createClient(jda, url)
            );
            var action = webhook.sendMessage(content)
                    .setUsername(event.playerName())
                    .setAvatarUrl(config.getAvatarUrl().replace("{uuid}", event.playerUuid().toString()))
                    .setAllowedMentions(List.of());
            if(!route.threadId().isBlank()) action.setThreadId(route.threadId());
            action.queue(
                    ignored -> {},
                    exception -> Logger.warn("Failed to publish Minecraft chat to Discord route " + routeName(route) + ".")
            );
            return true;
        }catch (Exception e){
            Logger.warn("Failed to prepare Minecraft chat for Discord route " + routeName(route) + ".");
            return false;
        }
    }

    public void shutdown() {
        pendingMinecraft.clear();
        webhookClients.clear();
        jda.shutdownNow();
    }

    @Nullable
    private DiscordRoute getWebhookRoute(@Nonnull DiscordEvent event) {
        return router.routeMinecraft(event)
                .filter(route -> !route.webhookUrl().isBlank())
                .orElse(null);
    }

    private static String routeName(@Nonnull DiscordRoute route) {
        return route.type() == DiscordRoute.Type.GLOBAL ? "GLOBAL" : "LOCAL:" + route.backend();
    }

    private void validateConnection() {
        if(!jda.getGatewayIntents().containsAll(config.getIntents())){
            Logger.error("Discord config: JDA is missing one or more configured intents.");
            availableRoutes = List.of();
            router = new DiscordRouter(List.of());
            return;
        }

        Guild guild = jda.getGuildById(config.getGuildId());
        if(guild == null){
            Logger.error("Discord config: the configured guild is unavailable.");
            availableRoutes = List.of();
            router = new DiscordRouter(List.of());
            return;
        }

        List<DiscordRoute> routes = new ArrayList<>();
        for(DiscordRoute route : config.getRoutes()){
            if(validateRoute(guild, route)) routes.add(route);
        }
        availableRoutes = List.copyOf(routes);
        router = new DiscordRouter(availableRoutes);
    }

    private boolean validateRoute(
            @Nonnull Guild guild,
            @Nonnull DiscordRoute route
    ) {
        String routeName = route.type() == DiscordRoute.Type.GLOBAL
                ? "GLOBAL"
                : "LOCAL:" + route.backend();
        GuildChannel destination = guild.getJDA().getChannelById(GuildChannel.class, route.destinationId());
        if(destination == null || !destination.getGuild().equals(guild)){
            Logger.error("Discord config: route " + routeName + " points to an unavailable destination.");
            return false;
        }

        GuildMessageChannel messageChannel;
        if(!route.threadId().isBlank()){
            ThreadChannel thread = guild.getJDA().getChannelById(ThreadChannel.class, route.threadId());
            if(thread == null
                    || !thread.getGuild().equals(guild)
                    || !thread.getParentChannel().getId().equals(destination.getId())){
                Logger.error("Discord config: route " + routeName + " points to an unavailable thread.");
                return false;
            }
            messageChannel = thread;
        }else if(destination instanceof GuildMessageChannel channel){
            messageChannel = channel;
        }else{
            Logger.error("Discord config: route " + routeName + " destination cannot receive messages.");
            return false;
        }

        if(!messageChannel.canTalk()){
            Logger.error("Discord config: bot cannot send messages to route " + routeName + ".");
            return false;
        }
        return true;
    }
}
