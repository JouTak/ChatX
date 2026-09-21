package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.ChatX;
import com.velocitypowered.api.proxy.Player;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

public class DiscordMessageListener extends ListenerAdapter {
    private static final TextColor DISCORD_COLOR = TextColor.color(0x5865F2);

    private final DiscordManager manager;

    public DiscordMessageListener(@Nonnull DiscordManager manager) {
        this.manager = manager;
    }

    @Override
    public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
        DiscordConfig config = manager.getConfig();
        if(!event.isFromGuild() || !event.getGuild().getId().equals(config.getGuildId())) return;

        DiscordRoute route = manager.getRouter().routeDiscord(event.getChannel().getId()).orElse(null);
        if(route == null) return;
        if(event.getAuthor().getIdLong() == event.getJDA().getSelfUser().getIdLong()) return;
        if(event.isWebhookMessage() && manager.isChatXWebhook(event.getAuthor().getId())) return;
        if(config.isIgnoreBots() && event.getAuthor().isBot()) return;

        Component message = render(event, config);
        if(message == null) return;
        ChatX.getProxy().getScheduler().buildTask(ChatX.getInstance(), () ->
                getReceivers(route).stream()
                        .filter(player -> player.getCurrentServer().isPresent())
                        .forEach(player -> player.sendMessage(message))
        ).schedule();
    }

    @Nonnull
    private Collection<Player> getReceivers(@Nonnull DiscordRoute route) {
        if(route.type() == DiscordRoute.Type.GLOBAL) return ChatX.getProxy().getAllPlayers();
        return ChatX.getProxy().getServer(route.backend())
                .<Collection<Player>>map(server -> server.getPlayersConnected())
                .orElse(List.of());
    }

    @Nullable
    private static Component render(
            @Nonnull MessageReceivedEvent event,
            @Nonnull DiscordConfig config
    ) {
        Message message = event.getMessage();
        if(message.getContentDisplay().isBlank() && message.getAttachments().isEmpty()) return null;

        Member member = event.getMember();
        String name = member == null ? event.getAuthor().getName() : member.getEffectiveName();
        Component result = Component.empty();
        Message referenced = message.getReferencedMessage();
        if(referenced != null){
            Member referencedMember = referenced.getMember();
            String referencedName = referencedMember == null
                    ? referenced.getAuthor().getName()
                    : referencedMember.getEffectiveName();
            result = Component.text("↪ " + referencedName + ": " + referenced.getContentDisplay(), NamedTextColor.DARK_GRAY)
                    .appendNewline();
        }

        result = result
                .append(Component.text("[Discord] ", DISCORD_COLOR))
                .append(Component.text(name, nicknameColor(member, config)))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(message.getContentDisplay(), NamedTextColor.WHITE));

        for(Message.Attachment attachment : message.getAttachments()){
            result = result.appendNewline().append(
                    Component.text("[" + attachment.getFileName() + "]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.openUrl(attachment.getUrl()))
                            .hoverEvent(HoverEvent.showText(Component.text(attachment.getUrl())))
            );
        }
        return result;
    }

    @Nonnull
    private static TextColor nicknameColor(
            @Nullable Member member,
            @Nonnull DiscordConfig config
    ) {
        if(member != null){
            int color = member.getColors().getPrimaryRaw();
            if(color != Role.DEFAULT_COLOR_RAW) return TextColor.color(color);
        }
        String configured = config.getDefaultNicknameColor();
        if(!configured.matches("^#[0-9A-Fa-f]{6}$")) return TextColor.color(0x47BFFB);
        return TextColor.color(Integer.parseInt(configured.substring(1), 16));
    }
}
