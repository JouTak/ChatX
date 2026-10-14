package cn.jason31416.chatx.discord;

import javax.annotation.Nonnull;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

final class DiscordTabImageRenderer {
    private static final int SCALE = 2;
    private static final int COLUMN_WIDTH = 205;
    private static final int ROW_HEIGHT = 16;
    private static final int PADDING = 10;
    private static final int MAX_HEAD_BYTES = 128_000;
    private static final long HEAD_TTL = 60L * 60L * 1000L;
    private static final long FAILED_HEAD_TTL = 5L * 60L * 1000L;
    private static final Map<Character, Color> COLORS = legacyColors();
    private static final Pattern LEGACY_RGB = Pattern.compile("(?i)§x§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])");

    private final Font font;
    private final Map<UUID, CachedHead> heads = new HashMap<>();

    DiscordTabImageRenderer() throws IOException {
        try(InputStream input = DiscordTabImageRenderer.class.getResourceAsStream("/fonts/Monocraft.ttf")){
            if(input == null) throw new IOException("Monocraft font resource is missing");
            font = Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(10f);
        }catch (java.awt.FontFormatException error){
            throw new IOException("Monocraft font cannot be loaded", error);
        }
    }

    byte[] render(
            @Nonnull DiscordTabSnapshot snapshot,
            @Nonnull DiscordConfig.PlayerListImage config,
            long deadlineMillis
    ) throws IOException {
        List<DiscordTabSnapshot.Entry> players = snapshot.players();
        int columns = Math.max(1, (players.size() + 19) / 20);
        int rows = Math.max(1, Math.min(20, players.size()));
        String header = DiscordTabSnapshot.apply(config.header(), snapshot.placeholders());
        String footer = DiscordTabSnapshot.apply(config.footer(), snapshot.placeholders());
        List<String> headerLines = lines(header);
        List<String> footerLines = lines(footer);
        int headerHeight = Math.max(24, headerLines.size() * 14 + 10);
        int footerHeight = Math.max(20, footerLines.size() * 14 + 6);
        int width = columns * COLUMN_WIDTH + PADDING * 2;
        int height = PADDING * 2 + headerHeight + rows * ROW_HEIGHT + footerHeight;
        BufferedImage base = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = base.createGraphics();
        try{
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setFont(font);
            g.setColor(new Color(10, 10, 10, 226));
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(110, 110, 110, 225));
            g.drawRect(0, 0, width - 1, height - 1);
            g.setColor(new Color(40, 40, 40, 170));
            g.fillRect(PADDING, PADDING, width - PADDING * 2, headerHeight - 2);
            drawCenteredLines(g, headerLines, PADDING + 13, width);

            int bodyTop = PADDING + headerHeight;
            for(int index = 0; index < players.size(); index++){
                if(Thread.currentThread().isInterrupted() || System.currentTimeMillis() >= deadlineMillis){
                    throw new IOException("TAB render timed out");
                }
                int column = index / 20;
                int row = index % 20;
                int x = PADDING + column * COLUMN_WIDTH;
                int y = bodyTop + row * ROW_HEIGHT;
                g.setColor(new Color(255, 255, 255, row % 2 == 0 ? 25 : 15));
                g.fillRect(x, y, COLUMN_WIDTH - 3, ROW_HEIGHT - 1);
                DiscordTabSnapshot.Entry entry = players.get(index);
                int nameX = x + 5;
                if(config.showHeads()){
                    drawHead(g, entry.uuid(), x + 4, y + 3, deadlineMillis);
                    nameX += 14;
                }
                String name = DiscordTabSnapshot.apply(config.nameFormat(), entry.placeholders(snapshot));
                int nameLimit = COLUMN_WIDTH - (nameX - x) - (config.showPing() ? 66 : 9);
                Graphics2D clipped = (Graphics2D) g.create();
                try{
                    clipped.clipRect(nameX, y, Math.max(0, nameLimit), ROW_HEIGHT);
                    drawStyled(clipped, name, nameX, y + 11);
                }finally{
                    clipped.dispose();
                }
                if(config.showPing()){
                    String ping = DiscordTabSnapshot.apply(config.pingFormat(), entry.placeholders(snapshot));
                    Graphics2D pingText = (Graphics2D) g.create();
                    try{
                        pingText.clipRect(x + COLUMN_WIDTH - 62, y, 46, ROW_HEIGHT);
                        drawStyled(pingText, ping, x + COLUMN_WIDTH - 61, y + 11);
                    }finally{
                        pingText.dispose();
                    }
                    drawPingBars(g, entry.ping(), x + COLUMN_WIDTH - 14, y + 4);
                }
            }
            if(players.isEmpty()) drawCenteredLines(g, List.of("Никого нет в сети"), bodyTop + 12, width);
            drawCenteredLines(g, footerLines, bodyTop + rows * ROW_HEIGHT + 13, width);
        }finally{
            g.dispose();
        }

        BufferedImage scaled = new BufferedImage(width * SCALE, height * SCALE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D output = scaled.createGraphics();
        try{
            output.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            output.drawImage(base, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        }finally{
            output.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if(!ImageIO.write(scaled, "png", bytes)) throw new IOException("PNG encoder is unavailable");
        return bytes.toByteArray();
    }

    private void drawHead(Graphics2D g, UUID uuid, int x, int y, long deadlineMillis) {
        BufferedImage head = head(uuid, deadlineMillis);
        if(head != null){
            g.drawImage(head, x, y, 10, 10, null);
        }else{
            g.setColor(new Color(113, 137, 150));
            g.fillRect(x, y, 10, 10);
            g.setColor(new Color(68, 88, 99));
            g.fillRect(x + 2, y + 7, 6, 2);
        }
    }

    private BufferedImage head(UUID uuid, long deadlineMillis) {
        long now = System.currentTimeMillis();
        CachedHead cached = heads.get(uuid);
        if(cached != null && cached.expiresAt() > now) return cached.image();
        if(now >= deadlineMillis - 500) return null;
        if(heads.size() >= 512) heads.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        if(heads.size() >= 512) heads.clear();
        BufferedImage image = null;
        HttpURLConnection connection = null;
        try{
            connection = (HttpURLConnection) URI.create("https://mc-heads.net/avatar/" + uuid + "/8").toURL().openConnection();
            int timeout = (int) Math.max(200, Math.min(1_500, deadlineMillis - now - 200));
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            if(connection.getResponseCode() == 200){
                try(InputStream input = connection.getInputStream()){
                    byte[] data = input.readNBytes(MAX_HEAD_BYTES + 1);
                    if(data.length <= MAX_HEAD_BYTES) image = ImageIO.read(new ByteArrayInputStream(data));
                }
            }
        }catch (IOException | RuntimeException ignored){
            // A missing skin only affects this icon.
        }finally{
            if(connection != null) connection.disconnect();
        }
        heads.put(uuid, new CachedHead(image, now + (image == null ? FAILED_HEAD_TTL : HEAD_TTL)));
        return image;
    }

    private void drawCenteredLines(Graphics2D g, List<String> lines, int firstBaseline, int width) {
        for(int i = 0; i < lines.size(); i++){
            String line = lines.get(i);
            int textWidth = visibleWidth(g.getFontMetrics(), line);
            Graphics2D clipped = (Graphics2D) g.create();
            try{
                clipped.clipRect(PADDING, firstBaseline + i * 14 - 11, width - PADDING * 2, 14);
                drawStyled(clipped, line, Math.max(PADDING, (width - textWidth) / 2), firstBaseline + i * 14);
            }finally{
                clipped.dispose();
            }
        }
    }

    private static void drawPingBars(Graphics2D g, long ping, int x, int y) {
        int bars = ping < 0 ? 0 : ping < 150 ? 5 : ping < 300 ? 4 : ping < 600 ? 3 : ping < 1_000 ? 2 : 1;
        for(int i = 0; i < 5; i++){
            g.setColor(i < bars ? new Color(112, 221, 102) : new Color(78, 78, 78));
            g.fillRect(x + i * 2, y + 8 - i * 2, 1, 2 + i * 2);
        }
    }

    private static List<String> lines(String text) {
        String[] parts = text.replace("\\n", "\n").split("\n", -1);
        List<String> result = new ArrayList<>();
        for(int i = 0; i < Math.min(5, parts.length); i++){
            result.add(parts[i].length() > 160 ? parts[i].substring(0, 160) : parts[i]);
        }
        return result;
    }

    private static int visibleWidth(FontMetrics metrics, String text) {
        return metrics.stringWidth(stripStyles(text));
    }

    private static String stripStyles(String value) {
        return normalizeRgb(value).replaceAll("(?i)[&§][0-9a-fk-or]", "")
                .replaceAll("(?i)(?:&|<)#([0-9a-f]{6})>?", "");
    }

    private static void drawStyled(Graphics2D g, String text, int x, int baseline) {
        text = normalizeRgb(text);
        Color color = Color.WHITE;
        boolean bold = false;
        StringBuilder part = new StringBuilder();
        int cursor = x;
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            Color next = null;
            if((c == '&' || c == '§') && i + 1 < text.length()){
                char code = Character.toLowerCase(text.charAt(i + 1));
                next = COLORS.get(code);
                if(next != null || "klmnor".indexOf(code) >= 0){
                    cursor = drawPart(g, part, cursor, baseline, color, bold);
                    if(next != null){
                        color = next;
                        bold = false;
                    }
                    if(code == 'r'){
                        color = Color.WHITE;
                        bold = false;
                    }
                    if(code == 'l') bold = true;
                    i++;
                    continue;
                }
                if(i + 7 < text.length() && text.charAt(i + 1) == '#'){
                    next = hexColor(text.substring(i + 2, i + 8));
                    if(next != null){
                        cursor = drawPart(g, part, cursor, baseline, color, bold);
                        color = next;
                        bold = false;
                        i += 7;
                        continue;
                    }
                }
            }
            if(c == '<' && i + 8 < text.length() && text.charAt(i + 1) == '#' && text.charAt(i + 8) == '>'){
                next = hexColor(text.substring(i + 2, i + 8));
                if(next != null){
                    cursor = drawPart(g, part, cursor, baseline, color, bold);
                    color = next;
                    bold = false;
                    i += 8;
                    continue;
                }
            }
            part.append(c);
        }
        drawPart(g, part, cursor, baseline, color, bold);
    }

    private static String normalizeRgb(String text) {
        return LEGACY_RGB.matcher(text).replaceAll("&#$1$2$3$4$5$6");
    }

    private static int drawPart(Graphics2D g, StringBuilder text, int x, int baseline, Color color, boolean bold) {
        if(text.isEmpty()) return x;
        String value = text.toString();
        g.setColor(new Color(0, 0, 0, 190));
        g.drawString(value, x + 1, baseline + 1);
        g.setColor(color);
        g.drawString(value, x, baseline);
        if(bold) g.drawString(value, x + 1, baseline);
        text.setLength(0);
        return x + g.getFontMetrics().stringWidth(value);
    }

    private static Color hexColor(String hex) {
        try{
            return new Color(Integer.parseInt(hex, 16));
        }catch (NumberFormatException error){
            return null;
        }
    }

    private static Map<Character, Color> legacyColors() {
        String[] colors = {
                "000000", "0000AA", "00AA00", "00AAAA", "AA0000", "AA00AA", "FFAA00", "AAAAAA",
                "555555", "5555FF", "55FF55", "55FFFF", "FF5555", "FF55FF", "FFFF55", "FFFFFF"
        };
        Map<Character, Color> result = new HashMap<>();
        for(int i = 0; i < colors.length; i++){
            result.put(Character.forDigit(i, 16), new Color(Integer.parseInt(colors[i], 16)));
        }
        return Map.copyOf(result);
    }

    private record CachedHead(BufferedImage image, long expiresAt) {}
}
