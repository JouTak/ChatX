package cn.jason31416.chatx.channel;

import cn.jason31416.chatx.channel.type.RedisChannelHandler;
import cn.jason31416.chatx.util.Config;
import cn.jason31416.chatx.util.Logger;
import cn.jason31416.chatx.util.MapTree;
import cn.jason31416.chatx.util.SimplePlayer;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import cn.jason31416.chatx.ChatX;
import cn.jason31416.chatx.channel.type.serverwide.GlobalChannelHandler;
import cn.jason31416.chatx.channel.type.serverwide.LocalChannelHandler;
import cn.jason31416.chatx.channel.type.serverwide.RoomChannelHandler;
import cn.jason31416.chatx.util.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import net.kyori.adventure.text.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@SuppressWarnings("unchecked")
@Getter
public class Channel {

    @Getter
    private static final Set<CommandMeta> registeredChannelCommands = new HashSet<>();

    public static Map<String, Channel> channelPrefixes = new ConcurrentHashMap<>();

    public static Channel defaultChannel = null;
    @Getter
    private static final Map<UUID, Channel> playerChannels = new ConcurrentHashMap<>();

    @Getter
    private static final Map<String, Channel> channels = new ConcurrentHashMap<>();

    @Getter
    private static final Map<String, Function<Channel, ChannelHandler>> channelTypes = new ConcurrentHashMap<>();

    public static enum HandleMode {
        IGNORE_BACKEND,
        NOTIFY_BACKEND,
        RESPECT_BACKEND,
        PASSTHROUGH
    }

    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Builder @Getter
    public static class ChannelConfig{
        private String displayName;
        @Builder.Default
        private HandleMode handleMode = HandleMode.IGNORE_BACKEND;
        @Builder.Default
        private boolean logToConsole = true;
        @Builder.Default
        @Nullable
        private String sendPermission = null;
        @Builder.Default
        @Nullable
        private String receivePermission = null;
        private String format;
        @Builder.Default
        private int rateLimitTime = 0;
        @Builder.Default
        private int rateLimitCount = 0;
        @Builder.Default
        private RateLimiter rateLimiter=null;
    }

    public static void registerChannelType(@Nonnull String id, @Nullable Function<Channel, ChannelHandler> handler) {
        channelTypes.put(id.toLowerCase(Locale.ROOT), handler);
    }

    static {
        registerChannelType("local", LocalChannelHandler::new);
        registerChannelType("global", GlobalChannelHandler::new);
        registerChannelType("redis", RedisChannelHandler::new);
        registerChannelType("room", RoomChannelHandler::new);
        registerChannelType("noop", LocalChannelHandler::new);
    }

    private final ChannelConfig defaultConfig;
    private final Map<String, ChannelConfig> serverConfig = new ConcurrentHashMap<>();

    public ChannelConfig getConfig(String serverid){
        if(serverConfig.containsKey(serverid)){
            return serverConfig.get(serverid);
        }
        return getDefaultConfig();
    }
    public ChannelConfig getConfig(Player player){
        if(player.getCurrentServer().isEmpty()) return getDefaultConfig();
        return getConfig(player.getCurrentServer().get().getServerInfo().getName());
    }
    public ChannelConfig getConfig(CommandSource player){
        if(player instanceof Player pl) return getConfig(pl);
        return getDefaultConfig();
    }

    @Getter
    private String id;
    @Getter
    private ChannelHandler handler;
    @Getter
    private List<String> restrictedServers = new ArrayList<>();

    public Channel(ChannelConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public MapTree getRawConfig() {
        return Config.getChannelTree().getSection(id);
    }
    @Nullable
    public static Channel getPlayerChannel(SimplePlayer player) {
        return playerChannels.get(player.getPlayerUUID());
    }
    @Nullable
    public static Channel getChannel(String id) {
        return channels.get(id.toLowerCase(Locale.ROOT));
    }
    @NotNull
    public static Channel getPlayerChannel(Player player){
        if(!playerChannels.containsKey(player.getUniqueId())){
            Logger.debug("Player "+player.getUsername()+" channel is null");
            return defaultChannel;
        }
        return playerChannels.get(player.getUniqueId());
    }

    public static void loadChannels(){
        defaultChannel = null;
        channelPrefixes.clear();
        for(String i: Config.getChannelTree().getKeys()){
            try {
                // Load default config
                ChannelConfig channelConfig = ChannelConfig.builder()
                        .displayName(Config.getChannelTree().getString(i + ".name"))
                        .logToConsole(Config.getChannelTree().getBoolean(i + ".log-console", true))
                        .format(Config.getChannelTree().getString(i + ".format"))
                        .receivePermission(Config.getChannelTree().getString(i + ".receive-permission", null))
                        .sendPermission(Config.getChannelTree().getString(i + ".send-permission", null))
                        .rateLimitTime(Config.getChannelTree().getInt(i + ".rate-limit.time", 0))
                        .rateLimitCount(Config.getChannelTree().getInt(i + ".rate-limit.count", 0))
                        .handleMode(HandleMode.valueOf(Config.getChannelTree().getString(i + ".handle-mode", "ignore_backend").toUpperCase(Locale.ROOT)))
                        .build();
                if(channelConfig.rateLimitTime!=0)
                    channelConfig.rateLimiter = new RateLimiter(channelConfig.rateLimitTime*1000L, channelConfig.rateLimitCount);
                Channel channel = new Channel(channelConfig);
                channel.id = i;
                channel.handler = channelTypes.get(Config.getChannelTree().getString(i + ".type").toLowerCase(Locale.ROOT)).apply(channel);
                if(Config.getChannelTree().contains(i + ".restricted-servers")) {
                    channel.restrictedServers = Config.getChannelTree().getStringList(i + ".restricted-servers");
                }

                //todo: Apply non-default configs

                // Register channel
                channels.put(i.toLowerCase(Locale.ROOT), channel);

                for(String prefix: Config.getChannelTree().getStringList(i + ".prefixes")){
                    channelPrefixes.put(prefix.toLowerCase(Locale.ROOT), channel);
                }

                // Register command
                List<String> commands = (List<String>) Config.getChannelTree().get(i + ".commands");
                if(!commands.isEmpty()){
                    CommandMeta meta = ChatX.getProxy().getCommandManager()
                            .metaBuilder(commands.get(0))
                                    .aliases(commands.subList(1, commands.size()).toArray(String[]::new))
                                    .build();
                    registeredChannelCommands.add(meta);
                    ChatX.getProxy().getCommandManager().register(meta, channel.handler.getCommand(channel));
                }
                if(defaultChannel == null) defaultChannel = channel;
            } catch (Exception e) {
                Logger.error("Failed to load channel " + i);
                // noinspection CallToPrintStackTrace
                e.printStackTrace();
            }
        }
    }
    public static void handleChat(Player player, Channel channel, String message){
        channel.getHandler().handle(new SimplePlayer(player), message);
    }

    public static void handleProcessedChat(Player player, Channel channel, Component message){
        channel.getHandler().handleProcessed(new SimplePlayer(player), message);
    }
}
