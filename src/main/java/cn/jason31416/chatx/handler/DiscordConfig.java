package cn.jason31416.chatx.handler;

import cn.jason31416.chatx.util.Logger;
import cn.jason31416.chatx.util.MapTree;
import lombok.Getter;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.requests.GatewayIntent;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Getter
public class DiscordConfig {
    private static final Pattern DISCORD_ID = Pattern.compile("^[0-9]{17,20}$");
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern WEBHOOK_PATH = Pattern.compile("^/api(?:/v[0-9]+)?/webhooks/[0-9]{17,20}/[^/]+/?$");

    private final boolean enabled;
    private final String tokenEnvironment;
    private final String guildId;
    private final Set<GatewayIntent> intents;
    private final Set<Permission> permissions;
    private final List<Route> routes;
    private final Formats formats;
    private final Events events;
    private final String avatarUrl;
    private final String defaultNicknameColor;
    private final boolean valid;

    private DiscordConfig(
            boolean enabled,
            String tokenEnvironment,
            String guildId,
            Set<GatewayIntent> intents,
            Set<Permission> permissions,
            List<Route> routes,
            Formats formats,
            Events events,
            String avatarUrl,
            String defaultNicknameColor,
            boolean valid
    ) {
        this.enabled = enabled;
        this.tokenEnvironment = tokenEnvironment;
        this.guildId = guildId;
        this.intents = Set.copyOf(intents);
        this.permissions = Set.copyOf(permissions);
        this.routes = List.copyOf(routes);
        this.formats = formats;
        this.events = events;
        this.avatarUrl = avatarUrl;
        this.defaultNicknameColor = defaultNicknameColor;
        this.valid = valid;
    }

    public static DiscordConfig load(@Nonnull MapTree tree) {
        boolean enabled = tree.getBoolean("enabled", false);
        String tokenEnvironment = tree.getString("token-env", "CHATX_DISCORD_TOKEN").trim();
        String guildId = tree.getString("guild-id", "").trim();
        boolean valid = true;

        if(enabled && !ENVIRONMENT_VARIABLE.matcher(tokenEnvironment).matches()){
            Logger.error("Discord config: token-env is not a valid environment variable name.");
            valid = false;
        }
        if(enabled && !isDiscordId(guildId)){
            Logger.error("Discord config: guild-id is not a valid Discord ID.");
            valid = false;
        }

        ParsedEnums<GatewayIntent> parsedIntents = parseEnums(
                tree.get("intents"),
                GatewayIntent.class,
                "intent",
                enabled
        );
        EnumSet<GatewayIntent> intents = parsedIntents.values();
        valid &= parsedIntents.valid();
        if(enabled && intents.isEmpty()){
            Logger.error("Discord config: at least one intent is required.");
            valid = false;
        }

        ParsedEnums<Permission> parsedPermissions = parseEnums(
                tree.get("required-permissions"),
                Permission.class,
                "permission",
                enabled
        );
        EnumSet<Permission> permissions = parsedPermissions.values();
        valid &= parsedPermissions.valid();
        if(enabled && permissions.isEmpty()){
            Logger.error("Discord config: at least one required permission is required.");
            valid = false;
        }

        List<Route> routes = parseRoutes(tree.get("routes"), enabled);
        if(enabled && routes.stream().noneMatch(route -> route.type() == RouteType.GLOBAL)){
            Logger.error("Discord config: one GLOBAL route is required.");
        }

        MapTree formatTree = tree.getSection("formats");
        Formats formats = new Formats(
                formatTree.getString("chat", "**{name}**: {message}"),
                formatTree.getString("reply", "**{name}** replied to **{reply-name}**: {message}"),
                formatTree.getString("attachments", "{url}"),
                formatTree.getString("join", ":arrow_right: **{name}** joined the network"),
                formatTree.getString("leave", ":arrow_left: **{name}** left the network"),
                formatTree.getString("switch", ":left_right_arrow: **{name}** moved to {server}"),
                formatTree.getString("start", ":white_check_mark: Network started"),
                formatTree.getString("stop", ":octagonal_sign: Network stopped"),
                formatTree.getString("presence", "{online} players online")
        );

        MapTree eventTree = tree.getSection("events");
        Events events = new Events(
                eventTree.getBoolean("join", true),
                eventTree.getBoolean("leave", true),
                eventTree.getBoolean("switch", true),
                eventTree.getBoolean("start", true),
                eventTree.getBoolean("stop", true)
        );

        String avatarUrl = tree.getString("avatar-url", "https://mc-heads.net/avatar/{uuid}/64");
        if(enabled && !avatarUrl.contains("{uuid}")){
            Logger.error("Discord config: avatar-url must contain {uuid}.");
            valid = false;
        }

        String defaultNicknameColor = tree.getString("default-nickname-color", "#47BFFB");
        if(enabled && !defaultNicknameColor.matches("^#[0-9A-Fa-f]{6}$")){
            Logger.error("Discord config: default-nickname-color must be a hex color.");
            valid = false;
        }

        return new DiscordConfig(
                enabled,
                tokenEnvironment,
                guildId,
                intents,
                permissions,
                routes,
                formats,
                events,
                avatarUrl,
                defaultNicknameColor,
                valid
        );
    }

    private static <E extends Enum<E>> ParsedEnums<E> parseEnums(
            Object rawValues,
            Class<E> enumClass,
            String valueName,
            boolean required
    ) {
        EnumSet<E> result = EnumSet.noneOf(enumClass);
        if(!(rawValues instanceof List<?> values)){
            if(required) Logger.error("Discord config: " + valueName + "s must be a list.");
            return new ParsedEnums<>(result, !required);
        }

        boolean valid = true;
        for(Object rawValue : values){
            if(!(rawValue instanceof String value)){
                Logger.error("Discord config: " + valueName + " values must be strings.");
                valid = false;
                continue;
            }
            try{
                result.add(Enum.valueOf(enumClass, value.toUpperCase(Locale.ROOT)));
            }catch (IllegalArgumentException e){
                Logger.error("Discord config: unknown " + valueName + " " + value + ".");
                valid = false;
            }
        }
        return new ParsedEnums<>(result, valid);
    }

    @SuppressWarnings("unchecked")
    private static List<Route> parseRoutes(Object rawRoutes, boolean enabled) {
        if(!(rawRoutes instanceof List<?> routeList)){
            if(enabled) Logger.error("Discord config: routes must be a list.");
            return List.of();
        }

        List<Route> routes = new ArrayList<>();
        Set<String> routeKeys = new HashSet<>();
        Set<String> destinations = new HashSet<>();
        Set<String> webhooks = new HashSet<>();
        int index = 0;
        for(Object rawRoute : routeList){
            index++;
            if(!(rawRoute instanceof Map<?, ?> rawMap)){
                Logger.error("Discord config: route " + index + " must be a section.");
                continue;
            }

            MapTree routeTree = new MapTree((Map<String, Object>) rawMap);
            RouteType type;
            try{
                type = RouteType.valueOf(routeTree.getString("type").toUpperCase(Locale.ROOT));
            }catch (IllegalArgumentException e){
                Logger.error("Discord config: route " + index + " has an unknown type.");
                continue;
            }

            String backend = routeTree.getString("backend", "").trim();
            String destinationId = routeTree.getString("destination-id", "").trim();
            String webhookUrl = routeTree.getString("webhook-url", "").trim();
            String threadId = routeTree.getString("thread-id", "").trim();
            String routeKey = type == RouteType.GLOBAL ? "GLOBAL" : "LOCAL:" + backend;
            String destinationKey = threadId.isBlank() ? destinationId : threadId;
            String webhookKey = webhookUrl + "#" + threadId;

            if(type == RouteType.LOCAL && backend.isBlank()){
                Logger.error("Discord config: route " + index + " needs a backend ID.");
                continue;
            }
            if(!isDiscordId(destinationId)){
                Logger.error("Discord config: route " + routeKey + " has an invalid destination ID.");
                continue;
            }
            if(!threadId.isBlank() && !isDiscordId(threadId)){
                Logger.error("Discord config: route " + routeKey + " has an invalid thread ID.");
                continue;
            }
            if(!webhookUrl.isBlank() && !isWebhookUrl(webhookUrl)){
                Logger.error("Discord config: route " + routeKey + " has an invalid webhook URL.");
                continue;
            }
            if(routeKeys.contains(routeKey)){
                Logger.error("Discord config: duplicate route " + routeKey + ".");
                continue;
            }
            if(destinations.contains(destinationKey)){
                Logger.error("Discord config: route " + routeKey + " duplicates a destination.");
                continue;
            }
            if(!webhookUrl.isBlank() && webhooks.contains(webhookKey)){
                Logger.error("Discord config: route " + routeKey + " duplicates a webhook.");
                continue;
            }

            routeKeys.add(routeKey);
            destinations.add(destinationKey);
            if(!webhookUrl.isBlank()) webhooks.add(webhookKey);
            routes.add(new Route(type, backend, destinationId, webhookUrl, threadId));
        }
        return routes;
    }

    private static boolean isDiscordId(String value) {
        return DISCORD_ID.matcher(value).matches();
    }

    private static boolean isWebhookUrl(String value) {
        try{
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && isDiscordHost(host)
                    && WEBHOOK_PATH.matcher(uri.getPath()).matches();
        }catch (IllegalArgumentException e){
            return false;
        }
    }

    private static boolean isDiscordHost(String host) {
        return host.equalsIgnoreCase("discord.com")
                || host.endsWith(".discord.com")
                || host.equalsIgnoreCase("discordapp.com")
                || host.endsWith(".discordapp.com");
    }

    private record ParsedEnums<E extends Enum<E>>(EnumSet<E> values, boolean valid) {}

    public enum RouteType {
        GLOBAL,
        LOCAL
    }

    public record Route(
            RouteType type,
            String backend,
            String destinationId,
            String webhookUrl,
            String threadId
    ) {}

    public record Formats(
            String chat,
            String reply,
            String attachments,
            String join,
            String leave,
            String serverSwitch,
            String start,
            String stop,
            String presence
    ) {}

    public record Events(
            boolean join,
            boolean leave,
            boolean serverSwitch,
            boolean start,
            boolean stop
    ) {}
}
