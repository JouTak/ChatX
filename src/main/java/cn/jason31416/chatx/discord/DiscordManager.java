package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.util.Logger;
import lombok.Getter;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

@Getter
public class DiscordManager {
    private final JDA jda;
    private final DiscordConfig config;
    private volatile List<DiscordConfig.Route> availableRoutes = List.of();

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

    public void shutdown() {
        jda.shutdownNow();
    }

    private void validateConnection() {
        if(!jda.getGatewayIntents().containsAll(config.getIntents())){
            Logger.error("Discord config: JDA is missing one or more configured intents.");
            availableRoutes = List.of();
            return;
        }

        Guild guild = jda.getGuildById(config.getGuildId());
        if(guild == null){
            Logger.error("Discord config: the configured guild is unavailable.");
            availableRoutes = List.of();
            return;
        }

        List<DiscordConfig.Route> routes = new ArrayList<>();
        for(DiscordConfig.Route route : config.getRoutes()){
            if(validateRoute(guild, route)) routes.add(route);
        }
        availableRoutes = List.copyOf(routes);
    }

    private boolean validateRoute(
            @Nonnull Guild guild,
            @Nonnull DiscordConfig.Route route
    ) {
        String routeName = route.type() == DiscordConfig.RouteType.GLOBAL
                ? "GLOBAL"
                : "LOCAL:" + route.backend();
        GuildChannel destination = guild.getJDA().getChannelById(GuildChannel.class, route.destinationId());
        if(destination == null || !destination.getGuild().equals(guild)){
            Logger.error("Discord config: route " + routeName + " points to an unavailable destination.");
            return false;
        }

        GuildChannel permissionChannel = destination;
        if(!route.threadId().isBlank()){
            ThreadChannel thread = guild.getJDA().getChannelById(ThreadChannel.class, route.threadId());
            if(thread == null
                    || !thread.getGuild().equals(guild)
                    || !thread.getParentChannel().getId().equals(destination.getId())){
                Logger.error("Discord config: route " + routeName + " points to an unavailable thread.");
                return false;
            }
            permissionChannel = thread;
        }

        if(!guild.getSelfMember().hasPermission(permissionChannel, config.getRequiredPermissions())){
            Logger.error("Discord config: bot permissions are insufficient for route " + routeName + ".");
            return false;
        }
        return true;
    }
}
