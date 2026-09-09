package cn.jason31416.chatx;

import cn.jason31416.chatx.handler.DatabaseHandler;
import cn.jason31416.chatx.handler.EventListener;
import cn.jason31416.chatx.handler.PacketEventListener;
import cn.jason31416.chatx.handler.RedisRemoteManager;
import cn.jason31416.chatx.util.RateLimiter;
import com.github.retrooper.packetevents.PacketEvents;
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
import cn.jason31416.chatx.handler.*;
import cn.jason31416.chatx.message.MessageLoader;
import cn.jason31416.chatx.module.PatternModule;
import cn.jason31416.chatx.util.Config;
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

@Plugin(
        id = "chatx",
        name = "ChatXJT",
        version = "1.3.1",
        authors = {
                "EnderDissa"
        },
        dependencies = {
                @Dependency(id = "packetevents", optional = true),
                @Dependency(id = "papiproxybridge", optional = true),
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

    public static void reload() {
        Config.reload();
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
        if(RedisRemoteManager.getInstance()!=null) RedisRemoteManager.getInstance().shutdown();
        PacketEvents.getAPI().terminate();
    }
}
