package cn.jason31416.chatx.channel.type;

import cn.jason31416.chatx.ChatX;
import cn.jason31416.chatx.channel.Channel;
import cn.jason31416.chatx.channel.ChannelHandler;
import cn.jason31416.chatx.discord.DiscordManager;
import cn.jason31416.chatx.handler.ChatHistoryManager;
import cn.jason31416.chatx.handler.InteractiveChatBridge;
import cn.jason31416.chatx.handler.InteractiveChatHook;
import cn.jason31416.chatx.discord.DiscordEvent;
import cn.jason31416.chatx.message.Message;
import cn.jason31416.chatx.module.PatternModule;
import cn.jason31416.chatx.util.PlaceholderUtil;
import cn.jason31416.chatx.util.SimplePlayer;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public abstract class ServerWideChannelHandler implements ChannelHandler {
    public abstract List<SimplePlayer> getReceivers(SimplePlayer sender);
    public abstract Component getPrefix(String text, SimplePlayer sender);
    public abstract Channel getChannel();

    @Override
    public void logToConsole(@Nonnull SimplePlayer player, @Nonnull String message){
        PlaceholderUtil.replacePlaceholders(getChannel().getConfig(player.getCurrentServer()).getFormat(), player.getPlayer())
                .thenAccept(text->{
                    Component cmp = PatternModule.handleMessage(player.getPlayer(), message, List.of());
                    Component component = getPrefix(text, player).append(cmp);

                    ChatX.getProxy().getConsoleCommandSource().sendMessage(component);
                });
    }

    @Override
    public void handle(@Nonnull SimplePlayer player, @Nonnull String message) {
        DiscordEvent discordEvent = player.getPlayer() == null
                ? null
                : DiscordEvent.fromMinecraft(
                        getChannel(),
                        player.getPlayer(),
                        player.getCurrentServer()
                ).orElse(null);
        handle(player, message, discordEvent);
    }

    @Override
    public void handle(
            @Nonnull SimplePlayer player,
            @Nonnull String message,
            @Nullable DiscordEvent discordEvent
    ) {
        PlaceholderUtil.replacePlaceholders(getChannel().getConfig(player.getCurrentServer()).getFormat(), player.getPlayer())
                .thenAccept(text->{
                    List<SimplePlayer> receivers = getReceivers(player);
                    Component cmp = PatternModule.handleMessage(player.getPlayer(), message, receivers);
                    Component component = getPrefix(text, player).append(cmp);

                    for(SimplePlayer receiver : receivers) {
                        if(getChannel().getConfig(player.getCurrentServer()).getReceivePermission() != null&&!receiver.hasPermission(Objects.requireNonNull(getChannel().getConfig(player.getCurrentServer()).getReceivePermission())))
                            continue;
                        if (InteractiveChatHook.containsPlaceholder(message)) {
                            ChatX.getInteractiveChatBridge().process(player.getPlayer(), receiver.getPlayer(), component, processed -> {
                                Component marked = InteractiveChatBridge.markProcessed(processed);
                                sendMessage(receiver, marked);
                            });
                        } else {
                            sendMessage(receiver, component);
                        }
                    }
                    DiscordManager discordManager = ChatX.getDiscordManager();
                    if(discordEvent != null && discordManager != null){
                        discordManager.publishMinecraft(discordEvent, cmp);
                    }
                    if(getChannel().getConfig(player.getCurrentServer()).isLogToConsole()) ChatX.getProxy().getConsoleCommandSource().sendMessage(component);
                });
    }

    @Override
    public void handleProcessed(@Nonnull SimplePlayer player, @Nonnull Component component) {
        PlaceholderUtil.replacePlaceholders(getChannel().getConfig(player.getCurrentServer()).getFormat(), player.getPlayer())
                .thenAccept(text -> {
            List<SimplePlayer> receivers = getReceivers(player);
            Component message = getPrefix(text, player).append(component);
            PatternModule.notifyMentions(
                    player.getPlayer(),
                    PlainTextComponentSerializer.plainText().serialize(component),
                    receivers
            );
            for(SimplePlayer receiver : receivers) {
                if(getChannel().getConfig(player.getCurrentServer()).getReceivePermission() != null
                        && !receiver.hasPermission(Objects.requireNonNull(getChannel().getConfig(player.getCurrentServer()).getReceivePermission()))) {
                    continue;
                }
                if (InteractiveChatHook.containsPlaceholder(PlainTextComponentSerializer.plainText().serialize(component))) {
                    ChatX.getInteractiveChatBridge().process(player.getPlayer(), receiver.getPlayer(), message, processed -> {
                        Component marked = InteractiveChatBridge.markProcessed(processed);
                        sendMessage(receiver, marked);
                    });
                } else {
                    sendMessage(receiver, message);
                }
            }
            if(getChannel().getConfig(player.getCurrentServer()).isLogToConsole()) {
                ChatX.getProxy().getConsoleCommandSource().sendMessage(message);
            }
        });
    }

    private void sendMessage(@Nonnull SimplePlayer receiver, @Nonnull Component message) {
        if("GLOBAL".equalsIgnoreCase(getChannel().getType())){
            receiver.getPlayer().sendMessage(message);
            return;
        }
        receiver.getPlayer().sendMessage(message, ChatType.CHAT.bind(message));
    }
}
