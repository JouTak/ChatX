package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.util.PlaceholderUtil;
import com.velocitypowered.api.proxy.Player;
import io.github.miniplaceholders.api.MiniPlaceholders;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.tablist.SortingManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only loaded when the optional TAB plugin is present. */
final class DiscordTabIntegration {
    private static final String FIELD_SEPARATOR = "\uF8FF";
    private static final long PLACEHOLDER_TIMEOUT_MILLIS = 1_000;
    private static final Pattern LEGACY_FORMAT = Pattern.compile("(?i)§x(?:§[0-9a-f]){6}|[§&][0-9a-fk-or]");
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .build();

    private DiscordTabIntegration() {}

    @Nonnull
    static CompletableFuture<DiscordTabValues> values(@Nonnull Player player) {
        TabAPI api = TabAPI.getInstance();
        UUID uuid = player.getUniqueId();
        TabPlayer tabPlayer = api.getPlayer(uuid);
        if(tabPlayer == null || !tabPlayer.isLoaded()) return CompletableFuture.completedFuture(DiscordTabValues.EMPTY);

        TabListFormatManager format = api.getTabListFormatManager();
        SortingManager sorting = api.getSortingManager();
        String prefix = "";
        String name = player.getUsername();
        String suffix = "";
        String originalPrefix = "";
        String originalName = name;
        String originalSuffix = "";
        String rawPrefix = "";
        String rawName = name;
        String rawSuffix = "";
        if(format != null){
            originalPrefix = value(format.getOriginalReplacedPrefix(tabPlayer));
            originalName = value(format.getOriginalReplacedName(tabPlayer));
            if(originalName.isBlank()) originalName = name;
            originalSuffix = value(format.getOriginalReplacedSuffix(tabPlayer));
            String customPrefix = format.getCustomPrefix(tabPlayer);
            String customName = format.getCustomName(tabPlayer);
            String customSuffix = format.getCustomSuffix(tabPlayer);
            rawPrefix = applied(customPrefix, value(format.getOriginalRawPrefix(tabPlayer)));
            rawName = applied(customName, value(format.getOriginalRawName(tabPlayer)));
            rawSuffix = applied(customSuffix, value(format.getOriginalRawSuffix(tabPlayer)));
            prefix = applied(customPrefix, originalPrefix);
            name = applied(customName, originalName);
            suffix = applied(customSuffix, originalSuffix);
            try{
                TagResolver resolver = MiniPlaceholders.audienceGlobalPlaceholders();
                String resolvedPrefix = resolved(player, resolver, "<tab_tabprefix>");
                String resolvedName = resolved(player, resolver, "<tab_customtabname>");
                String resolvedSuffix = resolved(player, resolver, "<tab_tabsuffix>");
                if(!resolvedName.isBlank() && !resolvedName.contains("<tab_")){
                    prefix = resolvedPrefix;
                    name = resolvedName;
                    suffix = resolvedSuffix;
                }
            }catch (RuntimeException | LinkageError ignored){
                // MiniPlaceholders is optional. Keep the TAB API values as fallback.
            }
        }
        String sortKey = "";
        if(sorting != null){
            sortKey = value(applied(sorting.getForcedTeamName(tabPlayer), sorting.getOriginalTeamName(tabPlayer)));
        }
        DiscordTabValues values = new DiscordTabValues(prefix, name, suffix, value(tabPlayer.getGroup()), sortKey, value(tabPlayer.getWorld()),
                originalPrefix, originalName, originalSuffix);
        return resolvePlaceholders(player, values, rawPrefix, rawName, rawSuffix);
    }

    private static String applied(String custom, String original) {
        return custom == null ? original : custom;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String resolved(Player player, TagResolver resolver, String placeholder) {
        Component component = MiniMessage.miniMessage().deserialize(placeholder, player, resolver);
        return LEGACY_SERIALIZER.serialize(component);
    }

    private static CompletableFuture<DiscordTabValues> resolvePlaceholders(
            Player player,
            DiscordTabValues values,
            String rawPrefix,
            String rawName,
            String rawSuffix
    ) {
        String joined = String.join(FIELD_SEPARATOR,
                values.prefix(), values.name(), values.suffix(), rawPrefix, rawName, rawSuffix);
        DiscordTabValues fallback = withTerminalFormat(values, values.prefix(), values.name(), values.suffix(),
                rawPrefix, rawName, rawSuffix);
        if(!joined.contains("%")) return CompletableFuture.completedFuture(fallback);
        try{
            return PlaceholderUtil.replacePlaceholders(joined, player).handle((resolved, error) -> {
                String[] fields = error == null && resolved != null
                        ? resolved.split(FIELD_SEPARATOR, -1) : new String[0];
                if(fields.length != 6) return fallback;
                return withTerminalFormat(values, fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]);
            }).completeOnTimeout(fallback, PLACEHOLDER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }catch (RuntimeException | LinkageError ignored){
            return CompletableFuture.completedFuture(fallback);
        }
    }

    private static DiscordTabValues withTerminalFormat(
            DiscordTabValues values,
            String prefix,
            String name,
            String suffix,
            String rawPrefix,
            String rawName,
            String rawSuffix
    ) {
        return new DiscordTabValues(
                prefix + terminalFormat(rawPrefix),
                name + terminalFormat(rawName),
                suffix + terminalFormat(rawSuffix),
                values.group(), values.sortKey(), values.world(),
                values.originalPrefix(), values.originalName(), values.originalSuffix()
        );
    }

    private static String terminalFormat(String value) {
        Matcher matcher = LEGACY_FORMAT.matcher(value);
        String color = "";
        boolean reset = false;
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        boolean strikethrough = false;
        boolean obfuscated = false;
        while(matcher.find()){
            String code = matcher.group();
            char type = Character.toLowerCase(code.charAt(1));
            if(type == 'x' || "0123456789abcdef".indexOf(type) >= 0){
                color = code;
                reset = false;
                bold = italic = underlined = strikethrough = obfuscated = false;
            }else if(type == 'r'){
                color = "";
                reset = true;
                bold = italic = underlined = strikethrough = obfuscated = false;
            }else{
                if(type == 'l') bold = true;
                if(type == 'o') italic = true;
                if(type == 'n') underlined = true;
                if(type == 'm') strikethrough = true;
                if(type == 'k') obfuscated = true;
            }
        }
        return (reset ? "§r" : color)
                + (obfuscated ? "§k" : "")
                + (bold ? "§l" : "")
                + (strikethrough ? "§m" : "")
                + (underlined ? "§n" : "")
                + (italic ? "§o" : "");
    }

}
