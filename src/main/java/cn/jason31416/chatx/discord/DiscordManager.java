package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.ChatX;
import cn.jason31416.chatx.util.Config;
import cn.jason31416.chatx.util.Logger;
import com.velocitypowered.api.proxy.Player;
import lombok.AccessLevel;
import lombok.Getter;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IncomingWebhookClient;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.WebhookClient;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.ExceptionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.FileUpload;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import okhttp3.OkHttpClient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Getter
public class DiscordManager {
    private static final long PENDING_MINECRAFT_TTL_MILLIS = 10_000L;
    private static final int MAX_PENDING_MINECRAFT_PER_PLAYER = 8;
    private static final long SHUTDOWN_GRACE_SECONDS = 3L;
    private static final long STARTUP_TIMEOUT_SECONDS = 30L;

    private final JDA jda;
    private final DiscordConfig config;
    private final boolean lifecycleEvents;
    private final DiscordMessageListener messageListener;
    private final ListenerAdapter connectionListener;
    private final ThreadPoolExecutor deliveryExecutor;
    private final Semaphore inFlight;
    private final AtomicBoolean startupFinished = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final Consumer<DiscordManager> readyHandler;
    private final Consumer<Throwable> failureHandler;
    private volatile List<DiscordRoute> availableRoutes = List.of();
    private volatile DiscordRouter router = new DiscordRouter(List.of());
    @Getter(AccessLevel.NONE)
    private final Map<String, IncomingWebhookClient> webhookClients = new ConcurrentHashMap<>();
    @Getter(AccessLevel.NONE)
    private final Set<String> unavailableWebhooks = ConcurrentHashMap.newKeySet();
    @Getter(AccessLevel.NONE)
    private final Map<UUID, Queue<DiscordEvent>> pendingMinecraft = new ConcurrentHashMap<>();
    @Getter(AccessLevel.NONE)
    private final Map<String, Long> lastErrorLogs = new ConcurrentHashMap<>();

    private DiscordManager(
            @Nonnull DiscordConfig config,
            boolean lifecycleEvents,
            @Nonnull Consumer<DiscordManager> readyHandler,
            @Nonnull Consumer<Throwable> failureHandler
    ) {
        this.config = config;
        this.lifecycleEvents = lifecycleEvents;
        this.readyHandler = readyHandler;
        this.failureHandler = failureHandler;
        this.deliveryExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.getDelivery().queueCapacity()),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.inFlight = new Semaphore(config.getDelivery().maxInFlight());
        this.messageListener = new DiscordMessageListener(this);
        this.connectionListener = new ListenerAdapter() {
            @Override
            public void onReady(@Nonnull ReadyEvent event) {
                if(validateConnection(event.getJDA())) completeStartup();
                else failStartup(new IllegalStateException("Discord routes failed validation"));
            }

            @Override
            public void onShutdown(@Nonnull ShutdownEvent event) {
                if(closed.get()) return;
                if(!startupFinished.get()){
                    failStartup(new IllegalStateException("Discord connection closed during startup: " + event.getCode()));
                    return;
                }
                reportFailure("connection-closed", "Discord connection closed", new IllegalStateException(
                        event.getCloseCode() == null ? Integer.toString(event.getCode()) : event.getCloseCode().toString()
                ));
                shutdown(false);
            }

            @Override
            public void onSessionDisconnect(@Nonnull SessionDisconnectEvent event) {
                if(!closed.get() && startupFinished.get()){
                    reportFailure("connection-disconnected", "Discord disconnected; delivery remains isolated", new IllegalStateException(
                            event.getCloseCode() == null ? "network connection lost" : event.getCloseCode().toString()
                    ));
                }
            }

            @Override
            public void onSessionResume(@Nonnull SessionResumeEvent event) {
                lastErrorLogs.remove("connection-disconnected");
            }

            @Override
            public void onException(@Nonnull ExceptionEvent event) {
                if(!closed.get()) reportFailure("connection-exception", "Discord connection error", event.getCause());
            }
        };
        try{
            this.jda = JDABuilder.createDefault(config.getToken(), config.getIntents())
                    .addEventListeners(messageListener, connectionListener)
                    .build();
        }catch (RuntimeException error){
            deliveryExecutor.shutdownNow();
            messageListener.shutdown();
            throw error;
        }
        try{
            jda.getRateLimitPool().schedule(() -> {
                if(!startupFinished.get()) failStartup(new TimeoutException("Discord connection timed out"));
            }, STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }catch (RejectedExecutionException error){
            failStartup(error);
        }
    }

    @Nullable
    public static DiscordManager start(
            @Nonnull DiscordConfig config,
            boolean lifecycleEvents,
            @Nonnull Consumer<DiscordManager> readyHandler,
            @Nonnull Consumer<Throwable> failureHandler
    ) {
        if(!config.isEnabled()){
            failureHandler.accept(new IllegalArgumentException("Discord integration is disabled"));
            return null;
        }
        if(!config.isConnectionValid() || !config.isValid()){
            failureHandler.accept(new IllegalArgumentException("discord.yml is invalid"));
            return null;
        }
        try{
            return new DiscordManager(config, lifecycleEvents, readyHandler, failureHandler);
        }catch (Throwable error){
            failureHandler.accept(error);
            return null;
        }
    }

    public void activate() {
        if(closed.get() || !active.compareAndSet(false, true)) return;
        updatePresence();
        if(lifecycleEvents && config.getEvents().start()){
            publishEvent(router.routeGlobal().orElse(null), config.getFormats().start(), config.getColors().join(), null);
        }
    }

    public boolean isActive() {
        return active.get() && !closed.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public boolean publish(@Nonnull String destinationId, @Nonnull MessageCreateData message) {
        return dispatch("channel:" + destinationId, (success, failure) -> {
            MessageChannel channel = jda.getChannelById(MessageChannel.class, destinationId);
            if(channel == null) throw new DeliveryException("destination channel is unavailable");
            channel.sendMessage(message).queue(ignored -> success.run(), failure);
        });
    }

    public boolean publishCommandResponse(
            @Nonnull String destinationId,
            @Nonnull String content,
            int deleteAfterSeconds
    ) {
        return dispatch("player-list-command:" + destinationId, (success, failure) -> {
            MessageChannel channel = jda.getChannelById(MessageChannel.class, destinationId);
            if(channel == null) throw new DeliveryException("destination channel is unavailable");
            channel.sendMessage(MessageCreateData.fromContent(content))
                    .setAllowedMentions(List.of())
                    .queue(
                            sent -> {
                                success.run();
                                scheduleCommandResponseDeletion(sent, deleteAfterSeconds);
                            },
                            failure
                    );
        });
    }

    public boolean publishCommandImageResponse(
            @Nonnull String destinationId,
            @Nonnull List<byte[]> pngPages,
            @Nonnull String fallback,
            int deleteAfterSeconds
    ) {
        return dispatch("player-list-image:" + destinationId, (success, failure) -> {
            MessageChannel channel = jda.getChannelById(MessageChannel.class, destinationId);
            if(channel == null) throw new DeliveryException("destination channel is unavailable");
            if(pngPages.isEmpty()) throw new DeliveryException("TAB renderer produced no images");
            sendCommandImagePage(channel, destinationId, pngPages, 0, new ArrayList<>(),
                    fallback, deleteAfterSeconds, success, failure);
        });
    }

    private void sendCommandImagePage(MessageChannel channel, String destinationId, List<byte[]> pngPages,
                                      int index, List<Message> sentPages, String fallback, int deleteAfterSeconds,
                                      Runnable success, Consumer<Throwable> failure) {
        FileUpload upload = null;
        try{
            upload = FileUpload.fromData(pngPages.get(index), "tab-" + (index + 1) + ".png");
            channel.sendFiles(upload).setAllowedMentions(List.of()).queue(
                    sent -> {
                        sentPages.add(sent);
                        if(index + 1 < pngPages.size()){
                            sendCommandImagePage(channel, destinationId, pngPages, index + 1, sentPages,
                                    fallback, deleteAfterSeconds, success, failure);
                        }else{
                            success.run();
                            sentPages.forEach(page -> scheduleCommandResponseDeletion(page, deleteAfterSeconds));
                        }
                    },
                    error -> failCommandImagePages(destinationId, sentPages, fallback,
                            deleteAfterSeconds, error, failure)
            );
        }catch (RuntimeException error){
            if(upload != null){
                try{
                    upload.close();
                }catch (IOException ignored){
                    // Preserve the delivery failure that caused cleanup.
                }
            }
            failCommandImagePages(destinationId, sentPages, fallback, deleteAfterSeconds, error, failure);
        }
    }

    private void failCommandImagePages(String destinationId, List<Message> sentPages, String fallback,
                                       int deleteAfterSeconds, Throwable error, Consumer<Throwable> failure) {
        sentPages.forEach(page -> {
            try{
                page.delete().queue(ignored -> {}, ignored -> {});
            }catch (RuntimeException ignored){
                // A failed cleanup must not suppress the text fallback.
            }
        });
        failure.accept(error);
        publishCommandResponse(destinationId, fallback, deleteAfterSeconds);
    }

    public void publishNetworkJoin(@Nonnull Player player, @Nonnull String backend) {
        if(!config.getEvents().join()) return;
        publishEvent(router.routeGlobal().orElse(null), formatNetworkEvent(config.getFormats().join(), player, backend), config.getColors().join(), avatarUrl(player));
    }

    public void publishBackendJoin(@Nonnull Player player, @Nonnull String backend) {
        if(!config.getEvents().join()) return;
        publishEvent(router.routeLocal(backend).orElse(null), formatPlayer(config.getFormats().serverJoin(), player, "", backend), config.getColors().join(), avatarUrl(player));
    }

    public void publishNetworkLeave(@Nonnull Player player, @Nullable String backend) {
        if(!config.getEvents().leave()) return;
        publishEvent(router.routeGlobal().orElse(null), formatNetworkEvent(config.getFormats().leave(), player, backend == null ? "" : backend), config.getColors().leave(), avatarUrl(player));
        if(backend != null){
            publishEvent(router.routeLocal(backend).orElse(null), formatPlayer(config.getFormats().serverLeave(), player, backend, backend), config.getColors().leave(), avatarUrl(player));
        }
    }

    public void publishServerSwitch(@Nonnull Player player, @Nonnull String previousBackend, @Nonnull String backend) {
        if(!config.getEvents().serverSwitch()) return;
        String message = formatPlayer(config.getFormats().serverSwitch(), player, previousBackend, backend);
        publishEvent(router.routeGlobal().orElse(null), message, config.getColors().serverSwitch(), avatarUrl(player));
        publishEvent(router.routeLocal(previousBackend).orElse(null), formatPlayer(config.getFormats().serverLeave(), player, previousBackend, previousBackend), config.getColors().leave(), avatarUrl(player));
        publishEvent(router.routeLocal(backend).orElse(null), formatPlayer(config.getFormats().serverJoin(), player, previousBackend, backend), config.getColors().join(), avatarUrl(player));
    }

    public void updatePresence() {
        updatePresence(ChatX.getProxy().getAllPlayers().size());
    }

    public void updatePresence(int online) {
        execute("presence", () -> {
            String presence = config.getFormats().presence().replace("{online}", Integer.toString(online));
            jda.getPresence().setActivity(Activity.customStatus(presence));
        });
    }

    public boolean queueMinecraft(@Nonnull DiscordEvent event) {
        if(!isActive() || getWebhookRoute(event) == null) return false;
        Queue<DiscordEvent> events = pendingMinecraft.computeIfAbsent(event.playerUuid(), key -> new ConcurrentLinkedQueue<>());
        while(events.size() >= MAX_PENDING_MINECRAFT_PER_PLAYER) events.poll();
        events.add(event);
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
        if(route == null || unavailableWebhooks.contains(route.webhookUrl())) return false;
        String routeName = routeName(route);
        return dispatch("webhook:" + routeName, (success, failure) -> {
            String content = PlainTextComponentSerializer.plainText().serialize(message);
            IncomingWebhookClient webhook = webhookClients.computeIfAbsent(route.webhookUrl(), url -> WebhookClient.createClient(jda, url));
            var action = webhook.sendMessage(content)
                    .setUsername(event.playerName())
                    .setAvatarUrl(config.getAvatarUrl().replace("{uuid}", event.playerUuid().toString()))
                    .setAllowedMentions(List.of());
            if(!route.threadId().isBlank()) action.setThreadId(route.threadId());
            action.queue(
                    ignored -> success.run(),
                    error -> {
                        webhookClients.remove(route.webhookUrl(), webhook);
                        if(isInvalidWebhook(error)) unavailableWebhooks.add(route.webhookUrl());
                        failure.accept(error);
                    }
            );
        });
    }

    public void shutdown(boolean publishStop) {
        if(!closed.compareAndSet(false, true)) return;
        active.set(false);
        messageListener.shutdown();
        pendingMinecraft.clear();
        deliveryExecutor.shutdownNow();
        if(publishStop && config.getEvents().stop() && queueStopAndClose()){
            try{
                jda.getRateLimitPool().schedule(this::closeResources, SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
                return;
            }catch (RejectedExecutionException ignored){
                // JDA is already shutting down.
            }
        }
        closeResources();
    }

    @Nullable
    private DiscordRoute getWebhookRoute(@Nonnull DiscordEvent event) {
        return router.routeMinecraft(event).filter(route -> !route.webhookUrl().isBlank()).orElse(null);
    }

    private static String routeName(@Nonnull DiscordRoute route) {
        return route.type() == DiscordRoute.Type.GLOBAL ? "GLOBAL" : "LOCAL:" + route.backend();
    }

    private void publishEvent(@Nullable DiscordRoute route, @Nonnull String content, int color, @Nullable String authorIconUrl) {
        if(route == null) return;
        dispatch("event:" + routeName(route), (success, failure) -> {
            MessageChannel channel = getMessageChannel(route);
            EmbedBuilder builder = new EmbedBuilder().setColor(color);
            if(authorIconUrl == null) builder.setDescription(normalizeEmoji(content));
            else builder.setAuthor(authorText(content), null, authorIconUrl);
            channel.sendMessageEmbeds(builder.build()).setAllowedMentions(List.of()).queue(ignored -> success.run(), failure);
        });
    }

    @Nonnull
    private MessageChannel getMessageChannel(@Nonnull DiscordRoute route) {
        MessageChannel channel = jda.getChannelById(MessageChannel.class, route.effectiveDestinationId());
        if(channel == null) throw new DeliveryException("destination channel is unavailable");
        if(channel instanceof ThreadChannel thread && (thread.isArchived() || thread.isLocked() || !thread.canTalk())){
            throw new DeliveryException("destination thread is closed");
        }
        return channel;
    }

    private boolean queueStopAndClose() {
        DiscordRoute route = router.routeGlobal().orElse(null);
        if(route == null) return false;
        try{
            MessageChannel channel = getMessageChannel(route);
            EmbedBuilder builder = new EmbedBuilder().setColor(config.getColors().leave()).setDescription(normalizeEmoji(config.getFormats().stop()));
            channel.sendMessageEmbeds(builder.build()).setAllowedMentions(List.of()).queue(
                    ignored -> closeResources(),
                    error -> {
                        reportFailure("shutdown-event", "Failed to publish Discord stop event", error);
                        closeResources();
                    }
            );
            return true;
        }catch (Throwable error){
            reportFailure("shutdown-event", "Failed to prepare Discord stop event", error);
            return false;
        }
    }

    private void scheduleCommandResponseDeletion(@Nonnull Message message, int delaySeconds) {
        if(delaySeconds <= 0 || closed.get()) return;
        try{
            jda.getRateLimitPool().schedule(() -> dispatch(
                    "player-list-command-delete:" + message.getChannel().getId(),
                    (success, failure) -> message.delete().queue(ignored -> success.run(), failure)
            ), delaySeconds, TimeUnit.SECONDS);
        }catch (RejectedExecutionException ignored){
            // The manager is shutting down, so no delayed work should survive it.
        }
    }

    private boolean dispatch(@Nonnull String operation, @Nonnull AsyncRequest request) {
        if(!isActive()) return false;
        try{
            deliveryExecutor.execute(() -> {
                if(!isActive()) return;
                try{
                    inFlight.acquire();
                }catch (InterruptedException e){
                    Thread.currentThread().interrupt();
                    return;
                }
                if(!isActive()){
                    inFlight.release();
                    return;
                }
                AtomicBoolean completed = new AtomicBoolean();
                Runnable success = () -> release(completed);
                Consumer<Throwable> failure = error -> {
                    release(completed);
                    if(!closed.get()) reportFailure(operation, "Discord delivery failed for " + operation, error);
                };
                try{
                    request.start(success, failure);
                }catch (Throwable error){
                    failure.accept(error);
                }
            });
            return true;
        }catch (RejectedExecutionException e){
            reportFailure("queue-full", "Discord delivery queue is full; dropping messages", e);
            return false;
        }
    }

    private boolean execute(@Nonnull String operation, @Nonnull Runnable action) {
        if(!isActive()) return false;
        try{
            deliveryExecutor.execute(() -> {
                if(!isActive()) return;
                try{
                    action.run();
                }catch (Throwable error){
                    reportFailure(operation, "Discord operation failed for " + operation, error);
                }
            });
            return true;
        }catch (RejectedExecutionException e){
            reportFailure("queue-full", "Discord delivery queue is full; dropping messages", e);
            return false;
        }
    }

    private void release(@Nonnull AtomicBoolean completed) {
        if(completed.compareAndSet(false, true)) inFlight.release();
    }

    private void completeStartup() {
        if(!startupFinished.compareAndSet(false, true)) return;
        try{
            readyHandler.accept(this);
        }catch (Throwable error){
            try{
                failureHandler.accept(error);
            }finally {
                shutdown(false);
            }
        }
    }

    private void failStartup(@Nonnull Throwable error) {
        if(!startupFinished.compareAndSet(false, true)) return;
        try{
            failureHandler.accept(error);
        }finally {
            shutdown(false);
        }
    }

    private void reportFailure(@Nonnull String key, @Nonnull String message, @Nonnull Throwable error) {
        long now = System.currentTimeMillis();
        long interval = TimeUnit.SECONDS.toMillis(config.getDelivery().errorLogIntervalSeconds());
        AtomicBoolean shouldLog = new AtomicBoolean();
        lastErrorLogs.compute(key, (ignored, previous) -> {
            if(previous == null || now - previous >= interval){
                shouldLog.set(true);
                return now;
            }
            return previous;
        });
        if(!shouldLog.get()) return;
        Logger.warn(message + ": " + describe(error) + ".");
    }

    @Nonnull
    private static String describe(@Nonnull Throwable error) {
        DeliveryException delivery = findCause(error, DeliveryException.class);
        if(delivery != null) return delivery.getMessage();
        RateLimitedException limited = findCause(error, RateLimitedException.class);
        if(limited != null) return "rate limited for " + limited.getRetryAfter() + " ms";
        ErrorResponseException response = findCause(error, ErrorResponseException.class);
        if(response != null){
            ErrorResponse code = response.getErrorResponse();
            if(code == ErrorResponse.UNKNOWN_WEBHOOK || code == ErrorResponse.INVALID_WEBHOOK_TOKEN) return "webhook is invalid or was deleted";
            if(code == ErrorResponse.UNKNOWN_CHANNEL) return "channel is unavailable";
            if(code == ErrorResponse.ILLEGAL_OPERATION_ARCHIVED_THREAD || code == ErrorResponse.THREAD_LOCKED) return "thread is archived or locked";
            if(code == ErrorResponse.MISSING_ACCESS || code == ErrorResponse.MISSING_PERMISSIONS) return "channel access is missing";
            if(response.isServerError()) return "Discord service is unavailable";
            return response.getMeaning();
        }
        if(findCause(error, IOException.class) != null) return "Discord connection is unavailable";
        Throwable cause = error;
        while(cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static boolean isInvalidWebhook(@Nonnull Throwable error) {
        ErrorResponseException response = findCause(error, ErrorResponseException.class);
        if(response == null) return false;
        return response.getErrorResponse() == ErrorResponse.UNKNOWN_WEBHOOK
                || response.getErrorResponse() == ErrorResponse.INVALID_WEBHOOK_TOKEN;
    }

    @Nullable
    private static <T extends Throwable> T findCause(@Nonnull Throwable error, @Nonnull Class<T> type) {
        Throwable current = error;
        while(current != null){
            if(type.isInstance(current)) return type.cast(current);
            if(current == current.getCause()) break;
            current = current.getCause();
        }
        return null;
    }

    private void closeResources() {
        if(!resourcesClosed.compareAndSet(false, true)) return;
        messageListener.shutdown();
        webhookClients.clear();
        unavailableWebhooks.clear();
        lastErrorLogs.clear();
        try{
            jda.removeEventListener(messageListener, connectionListener);
            jda.cancelRequests();
            jda.shutdownNow();
        }catch (RuntimeException ignored){
            // Continue closing the owned HTTP resources.
        }
        try{
            OkHttpClient client = jda.getHttpClient();
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            if(client.cache() != null) client.cache().close();
        }catch (Exception ignored){
            // JDA may already have closed its client.
        }
    }

    private boolean validateConnection(@Nonnull JDA readyJda) {
        if(!readyJda.getGatewayIntents().containsAll(config.getIntents())){
            Logger.error("Discord config: JDA is missing one or more configured intents.");
            return false;
        }
        Guild guild = readyJda.getGuildById(config.getGuildId());
        if(guild == null){
            Logger.error("Discord config: the configured guild is unavailable.");
            return false;
        }
        List<DiscordRoute> routes = new ArrayList<>();
        boolean valid = true;
        for(DiscordRoute route : config.getRoutes()){
            if(validateRoute(guild, route)) routes.add(route);
            else valid = false;
        }
        if(!valid || routes.size() != config.getRoutes().size()) return false;
        availableRoutes = List.copyOf(routes);
        router = new DiscordRouter(availableRoutes);
        return true;
    }

    private boolean validateRoute(@Nonnull Guild guild, @Nonnull DiscordRoute route) {
        String name = routeName(route);
        GuildChannel destination = guild.getJDA().getChannelById(GuildChannel.class, route.destinationId());
        if(destination == null || !destination.getGuild().equals(guild)){
            Logger.error("Discord config: route " + name + " points to an unavailable destination.");
            return false;
        }
        GuildMessageChannel messageChannel;
        if(!route.threadId().isBlank()){
            ThreadChannel thread = guild.getJDA().getChannelById(ThreadChannel.class, route.threadId());
            if(thread == null || !thread.getGuild().equals(guild) || !thread.getParentChannel().getId().equals(destination.getId()) || thread.isArchived() || thread.isLocked()){
                Logger.error("Discord config: route " + name + " points to an unavailable or closed thread.");
                return false;
            }
            messageChannel = thread;
        }else if(destination instanceof GuildMessageChannel channel){
            messageChannel = channel;
        }else{
            Logger.error("Discord config: route " + name + " destination cannot receive messages.");
            return false;
        }
        if(!messageChannel.canTalk()){
            Logger.error("Discord config: bot cannot send messages to route " + name + ".");
            return false;
        }
        return true;
    }

    @Nonnull
    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "chatx-discord-delivery");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String formatPlayer(@Nonnull String format, @Nonnull Player player, @Nonnull String previousBackend, @Nonnull String backend) {
        return format.replace("{name}", player.getUsername()).replace("{previous-server}", serverDisplayName(previousBackend)).replace("{server}", serverDisplayName(backend));
    }

    @Nonnull
    private static String formatNetworkEvent(@Nonnull String format, @Nonnull Player player, @Nonnull String backend) {
        String message = formatPlayer(format, player, "", backend);
        if(backend.isBlank() || format.contains("{server}")) return message;
        return message + " · **" + serverDisplayName(backend) + "**";
    }

    @Nonnull
    private String avatarUrl(@Nonnull Player player) {
        return config.getAvatarUrl().replace("{uuid}", player.getUniqueId().toString());
    }

    @Nonnull
    private static String serverDisplayName(@Nonnull String backend) {
        return backend.isBlank() ? "" : Config.getServerDisplayName(backend);
    }

    @Nonnull
    private static String normalizeEmoji(@Nonnull String content) {
        return content.replace(":arrow_right:", "➡️").replace(":arrow_left:", "⬅️").replace(":left_right_arrow:", "↔️").replace(":white_check_mark:", "✅").replace(":octagonal_sign:", "🛑");
    }

    @Nonnull
    private static String authorText(@Nonnull String content) {
        return normalizeEmoji(content).replace("**", "");
    }

    boolean isChatXWebhook(@Nonnull String authorId) {
        String webhookPath = "/webhooks/" + authorId + "/";
        return config.getRoutes().stream().map(DiscordRoute::webhookUrl).anyMatch(url -> url.contains(webhookPath));
    }

    @FunctionalInterface
    private interface AsyncRequest {
        void start(Runnable success, Consumer<Throwable> failure);
    }

    private static final class DeliveryException extends RuntimeException {
        private DeliveryException(String message) {
            super(message);
        }
    }
}
