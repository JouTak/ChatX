package cn.jason31416.chatx;

import cn.jason31416.chatx.handler.DatabaseHandler;
import cn.jason31416.chatx.handler.EventListener;
import cn.jason31416.chatx.handler.PacketEventListener;
import cn.jason31416.chatx.handler.RedisRemoteManager;
import cn.jason31416.chatx.util.RateLimiter;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import cn.jason31416.chatx.channel.Channel;
import cn.jason31416.chatx.command.ChatHistoryCommand;
import cn.jason31416.chatx.command.DirectMessageCommand;
import cn.jason31416.chatx.command.OnlineCommand;
import cn.jason31416.chatx.command.ChatXCommand;
import cn.jason31416.chatx.discord.DiscordConfig;
import cn.jason31416.chatx.discord.DiscordManager;
import cn.jason31416.chatx.discord.DiscordNetworkListener;
import cn.jason31416.chatx.handler.*;
import cn.jason31416.chatx.message.MessageLoader;
import cn.jason31416.chatx.module.PatternModule;
import cn.jason31416.chatx.util.Config;
import cn.jason31416.chatx.util.MapTree;
import cn.jason31416.chatx.util.PlaceholderUtil;
import io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder;
import lombok.Getter;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Plugin(
        id = "chatx",
        name = "ChatXJT",
        version = "1.4.3",
        authors = {
                "EnderDissa"
        },
        dependencies = {
                @Dependency(id = "packetevents", optional = true),
                @Dependency(id = "miniplaceholders", optional = true),
                @Dependency(id = "papiproxybridge", optional = true),
                @Dependency(id = "tab", optional = true),
                @Dependency(id = "interactivechatvelocity", optional = true)
        }
)
public class ChatX {
    @Getter
    private static ChatX instance;
    @Getter
    private static ProxyServer proxy;
    @Getter
    private static Logger logger;
    @Getter
    private static File dataDirectory;
    @Getter
    private static InteractiveChatBridge interactiveChatBridge;
    @Getter
    private static volatile DiscordConfig discordConfig;
    @Getter
    private static volatile DiscordManager discordManager;
    private static volatile DiscordManager pendingDiscordManager;
    private static final Object DISCORD_LOCK = new Object();
    private static final AtomicLong DISCORD_GENERATION = new AtomicLong();

    @Inject
    public ChatX(@Nonnull ProxyServer proxy, @Nonnull Logger logger, @Nonnull @DataDirectory Path dataDirectory) {
        instance = this;
        ChatX.proxy = proxy;
        ChatX.logger = logger;
        ChatX.dataDirectory = dataDirectory.toFile();
    }

    @SuppressWarnings("UnstableApiUsage")
    @Subscribe
    public void onProxyInitialization(@Nonnull ProxyInitializeEvent event) {
        proxy.getEventManager().register(this, new EventListener());
        interactiveChatBridge = new InteractiveChatBridge();
        proxy.getEventManager().register(this, interactiveChatBridge);
        Config.init();
        stageDiscordConfig(Config.getDiscordTree(), DiscordConfig.load(Config.getDiscordTree()), true);
        proxy.getEventManager().register(this, new DiscordNetworkListener());
        MessageLoader.initialize();

        if(Config.getConfigTree().getBoolean("redis.enabled", false)){
            RedisRemoteManager.setInstance(new RedisRemoteManager());
        }

        getProxy().getScheduler().buildTask(this, ()->{
            for(var i: RateLimiter.limiters){
                i.checkCache();
            }
        }).repeat(10, TimeUnit.MINUTES).schedule();

        PlaceholderUtil.init();

        PacketEvents.setAPI(VelocityPacketEventsBuilder.build(proxy, proxy.getPluginManager().fromInstance(this).orElseThrow(), logger, dataDirectory.toPath()));
        PacketEvents.getAPI().getSettings().checkForUpdates(false);
        PacketEvents.getAPI().getSettings().kickOnPacketException(false);

        PacketEvents.getAPI().load();
        registerCustomParticleTypes();
//        PacketEvents.getAPI().getEventManager().registerListener(new PlayerInventoryListener());
        PacketEvents.getAPI().getEventManager().registerListener(new PacketEventListener());
        PacketEvents.getAPI().getEventManager().registerListener(new ChatPacketListener());
        PacketEvents.getAPI().init();

        PatternModule.registerDefaults();

        Channel.loadChannels();

        DirectMessageCommand.registerCommand();

        getProxy().getCommandManager().register(getProxy().getCommandManager().metaBuilder("chatx").build(), new ChatXCommand());
        if(Config.getBoolean("online-command.enabled")) getProxy().getCommandManager().register(getProxy().getCommandManager().metaBuilder(Config.getString("online-command.command")).build(), new OnlineCommand());
//        getProxy().getCommandManager().register(getProxy().getCommandManager().metaBuilder("chathistory").aliases("chathist", "ch").build(), new ChatHistoryCommand());

        DatabaseHandler.init();

        // For Luckperms to load the nodes
        getProxy().getConsoleCommandSource().hasPermission("chatx.chathistory");
        getProxy().getConsoleCommandSource().hasPermission("chatx.admin");
        getProxy().getConsoleCommandSource().hasPermission("chatx.channel");
    }

    private void registerCustomParticleTypes() {
        for(String particleType : Config.getConfigTree().getStringList("packetevents.custom-particle-types")){
            if(particleType.isBlank() || ParticleTypes.getByName(particleType) != null) continue;
            try{
                ParticleTypes.define(particleType);
            }catch (RuntimeException e){
                logger.warn("Cannot register custom particle type {}: {}", particleType, e.getMessage());
            }
        }
    }

    public static void reload() {
        MapTree candidateTree = null;
        DiscordConfig candidateConfig = null;
        try{
            candidateTree = Config.loadDiscordCandidate();
            candidateConfig = DiscordConfig.load(candidateTree);
        }catch (Exception e){
            logger.error("Discord reload rejected: cannot read discord.yml: {}", e.getMessage());
            DISCORD_GENERATION.incrementAndGet();
            cancelPendingDiscordCandidate();
        }

        Config.reload();
        if(candidateTree != null && candidateConfig != null){
            stageDiscordConfig(candidateTree, candidateConfig, false);
        }
        MessageLoader.initialize();

        if(Config.getConfigTree().getBoolean("redis.enabled", false)){
            if(RedisRemoteManager.getInstance()!=null) RedisRemoteManager.getInstance().shutdown();
            RedisRemoteManager.setInstance(new RedisRemoteManager());
        }

        DatabaseHandler.getInstance().getDatasource().close();
        DatabaseHandler.init();
        for(CommandMeta commandMetas: Channel.getRegisteredChannelCommands()){
            getProxy().getCommandManager().unregister(commandMetas);
        }
        for(Channel channel : Channel.getChannels().values()){
            channel.getHandler().destroy();
        }
        Channel.getChannels().clear();
        Channel.loadChannels();
        DirectMessageCommand.registerCommand();
        for(UUID uuid : new HashSet<>(Channel.getPlayerChannels().keySet())){
            Channel.getPlayerChannels().put(uuid, Channel.defaultChannel);
        }
        logger.info("ChatX reloaded!");
    }

    @Subscribe
    public void onProxyReload(@Nonnull ProxyReloadEvent event) {
        reload();
    }

    @Subscribe
    public void onProxyShutdown(@Nonnull ProxyShutdownEvent event) {
        DISCORD_GENERATION.incrementAndGet();
        if(RedisRemoteManager.getInstance()!=null) RedisRemoteManager.getInstance().shutdown();
        DiscordManager pending;
        DiscordManager active;
        synchronized (DISCORD_LOCK){
            pending = pendingDiscordManager;
            active = discordManager;
            pendingDiscordManager = null;
            discordManager = null;
        }
        if(pending != null) pending.shutdown(false);
        if(active != null) active.shutdown(true);
        PacketEvents.getAPI().terminate();
    }

    private static void stageDiscordConfig(
            @Nonnull MapTree candidateTree,
            @Nonnull DiscordConfig candidateConfig,
            boolean lifecycleEvents
    ) {
        long generation = DISCORD_GENERATION.incrementAndGet();
        cancelPendingDiscordCandidate();
        if(!candidateConfig.isEnabled()){
            installDiscordManager(generation, candidateTree, candidateConfig, null);
            return;
        }
        if(!candidateConfig.isConnectionValid() || !candidateConfig.isValid()){
            logger.error("Discord reload rejected: discord.yml is invalid; the current connection remains active.");
            return;
        }

        getProxy().getScheduler().buildTask(getInstance(), () -> {
            DiscordManager started = DiscordManager.start(
                    candidateConfig,
                    lifecycleEvents,
                    candidate -> installDiscordManager(generation, candidateTree, candidateConfig, candidate),
                    error -> {
                        if(DISCORD_GENERATION.get() != generation) return;
                        logger.error("Discord reload rejected: {}; the current connection remains active.", errorMessage(error));
                    }
            );
            if(started == null || started.isClosed()) return;
            synchronized (DISCORD_LOCK){
                if(DISCORD_GENERATION.get() != generation){
                    started.shutdown(false);
                }else if(discordManager != started){
                    pendingDiscordManager = started;
                }
            }
        }).schedule();
    }

    private static void installDiscordManager(
            long generation,
            @Nonnull MapTree candidateTree,
            @Nonnull DiscordConfig candidateConfig,
            DiscordManager candidate
    ) {
        DiscordManager previous;
        synchronized (DISCORD_LOCK){
            if(DISCORD_GENERATION.get() != generation){
                if(candidate != null) candidate.shutdown(false);
                return;
            }
            previous = discordManager;
            if(pendingDiscordManager == candidate) pendingDiscordManager = null;
            discordManager = candidate;
            discordConfig = candidateConfig;
            Config.installDiscordTree(candidateTree);
            if(previous != null) previous.shutdown(false);
            if(candidate != null) candidate.activate();
        }
        logger.info(candidate == null ? "Discord integration disabled." : "Discord integration is ready.");
    }

    @Nonnull
    private static String errorMessage(@Nonnull Throwable error) {
        Throwable cause = error;
        while(cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static void cancelPendingDiscordCandidate() {
        DiscordManager candidate;
        synchronized (DISCORD_LOCK){
            candidate = pendingDiscordManager;
            pendingDiscordManager = null;
        }
        if(candidate != null) candidate.shutdown(false);
    }
}
