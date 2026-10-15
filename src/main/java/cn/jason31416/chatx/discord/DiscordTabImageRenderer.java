package cn.jason31416.chatx.discord;

import javax.annotation.Nonnull;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
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
    private static final int FONT_SIZE = 20;
    private static final int LINE_HEIGHT = 27;
    private static final int ROW_HEIGHT = 32;
    private static final int ROW_GAP = 2;
    private static final int PADDING = 7;
    private static final int SECTION_GAP = 4;
    private static final int MIN_COLUMN_WIDTH = 260;
    private static final int ROWS_PER_COLUMN = 20;
    private static final int HEAD_SIZE = 24;
    private static final int PING_WIDTH = 20;
    private static final int PING_HEIGHT = 16;
    private static final int ROW_PADDING = 7;
    private static final int NAME_PING_GAP = 8;
    private static final int MAX_HEAD_BYTES = 128_000;
    private static final long HEAD_TTL = 60L * 60L * 1000L;
    private static final long FAILED_HEAD_TTL = 5L * 60L * 1000L;
    private static final Map<Character, Color> COLORS = legacyColors();
    private static final Pattern LEGACY_RGB = Pattern.compile("(?i)§x§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])");

    private final Font[] primaryFonts = new Font[4];
    private final Font[] fallbackFonts = new Font[4];
    private final BufferedImage[] pingIcons = new BufferedImage[6];
    private final Map<UUID, CachedHead> heads = new HashMap<>();

    DiscordTabImageRenderer() throws IOException {
        try(InputStream input = DiscordTabImageRenderer.class.getResourceAsStream("/fonts/MinecraftSeven.ttf")){
            if(input == null) throw new IOException("Minecraft Seven font resource is missing");
            Font primary = Font.createFont(Font.TRUETYPE_FONT, input);
            Font fallback = new Font(Font.DIALOG, Font.PLAIN, FONT_SIZE);
            for(int style = 0; style < 4; style++){
                primaryFonts[style] = primary.deriveFont(style, (float) FONT_SIZE);
                fallbackFonts[style] = fallback.deriveFont(style, (float) FONT_SIZE);
            }
        }catch (java.awt.FontFormatException error){
            throw new IOException("Minecraft Seven font cannot be loaded", error);
        }
        pingIcons[0] = loadPingIcon("ping_unknown");
        for(int strength = 1; strength <= 5; strength++){
            pingIcons[strength] = loadPingIcon("ping_" + strength);
        }
    }

    List<byte[]> render(
            @Nonnull DiscordTabSnapshot snapshot,
            @Nonnull DiscordConfig.PlayerListImage config,
            long deadlineMillis
    ) throws IOException {
        String nameFormat = snapshot.local() ? config.localNameFormat() : config.nameFormat();
        List<Row> rows = new ArrayList<>(snapshot.players().size());
        for(DiscordTabSnapshot.Entry entry : snapshot.players()){
            Map<String, String> placeholders = entry.placeholders(snapshot);
            rows.add(new Row(entry.uuid(), entry.ping(),
                    DiscordTabSnapshot.apply(nameFormat, placeholders),
                    config.showPing() ? DiscordTabSnapshot.apply(config.pingFormat(), placeholders) : ""));
        }
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D metrics = probe.createGraphics();
        int columnWidth;
        try{
            int nameWidth = 0;
            int pingWidth = 0;
            for(Row row : rows){
                nameWidth = Math.max(nameWidth, styledWidth(metrics, row.name()));
                pingWidth = Math.max(pingWidth, styledWidth(metrics, row.pingText()));
            }
            int left = ROW_PADDING + (config.showHeads() ? HEAD_SIZE + 5 : 0);
            int right = config.showPing()
                    ? pingWidth + PING_WIDTH + ROW_PADDING + 5 + NAME_PING_GAP
                    : ROW_PADDING;
            columnWidth = Math.max(MIN_COLUMN_WIDTH, left + nameWidth + right);
        }finally{
            metrics.dispose();
        }
        // Discord shrinks multiple columns and attachment mosaics. One page is
        // one full-width image; a long list is sent as consecutive messages.
        int pageSize = ROWS_PER_COLUMN;
        int pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        List<byte[]> images = new ArrayList<>(pages);
        for(int page = 0; page < pages; page++){
            if(Thread.currentThread().isInterrupted() || System.currentTimeMillis() >= deadlineMillis){
                throw new IOException("TAB render timed out");
            }
            int start = page * pageSize;
            int end = Math.min(rows.size(), start + pageSize);
            images.add(renderPage(snapshot, config, rows.subList(start, end),
                    columnWidth, page, pages, deadlineMillis));
        }
        return images;
    }

    private byte[] renderPage(DiscordTabSnapshot snapshot, DiscordConfig.PlayerListImage config,
                              List<Row> players, int columnWidth, int page, int pages,
                              long deadlineMillis) throws IOException {
        int rowCount = Math.max(1, Math.min(ROWS_PER_COLUMN, players.size()));
        List<String> header = page == 0
                ? lines(DiscordTabSnapshot.apply(config.header(), snapshot.placeholders()))
                : List.of("&7Продолжение " + (page + 1) + "/" + pages);
        String footerTemplate = snapshot.local() ? config.footer()
                : snapshot.hidden() > 0 ? config.globalFooter() : "";
        List<String> footer = new ArrayList<>();
        if(page == pages - 1){
            footer.addAll(lines(DiscordTabSnapshot.apply(footerTemplate, snapshot.placeholders())));
        }
        footer.add(DiscordTabSnapshot.apply("&7{generated_at} • {source}", snapshot.placeholders()));
        int headerHeight = header.isEmpty() ? 0 : header.size() * LINE_HEIGHT + 6;
        int footerHeight = footer.isEmpty() ? 0 : footer.size() * LINE_HEIGHT + 6;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D metrics = probe.createGraphics();
        int textWidth = 0;
        try{
            for(String line : header) textWidth = Math.max(textWidth, styledWidth(metrics, line));
            for(String line : footer) textWidth = Math.max(textWidth, styledWidth(metrics, line));
        }finally{
            metrics.dispose();
        }
        int width = Math.max(columnWidth + PADDING * 2, textWidth + PADDING * 4);
        int bodyHeight = rowCount * ROW_HEIGHT - ROW_GAP;
        int height = PADDING * 2 + headerHeight + bodyHeight + footerHeight
                + (headerHeight > 0 ? SECTION_GAP : 0) + (footerHeight > 0 ? SECTION_GAP : 0);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try{
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            if(headerHeight > 0){
                g.setColor(new Color(72, 72, 72, 232));
                g.fillRect(PADDING, PADDING, width - PADDING * 2, headerHeight);
                drawCenteredLines(g, header, PADDING + FONT_SIZE + 3, width);
            }

            int bodyTop = PADDING + headerHeight + (headerHeight > 0 ? SECTION_GAP : 0);
            int bodyLeft = (width - columnWidth) / 2;
            for(int index = 0; index < players.size(); index++){
                if(Thread.currentThread().isInterrupted() || System.currentTimeMillis() >= deadlineMillis){
                    throw new IOException("TAB render timed out");
                }
                int x = bodyLeft;
                int y = bodyTop + index * ROW_HEIGHT;
                g.setColor(new Color(54, 54, 54, 232));
                g.fillRect(x, y, columnWidth, ROW_HEIGHT - ROW_GAP);
                Row entry = players.get(index);
                int nameX = x + ROW_PADDING;
                if(config.showHeads()){
                    drawHead(g, entry.uuid(), x + ROW_PADDING, y + 3, deadlineMillis);
                    nameX += HEAD_SIZE + 5;
                }
                drawStyled(g, entry.name(), nameX, y + 23);
                if(config.showPing()){
                    int pingWidth = styledWidth(g, entry.pingText());
                    int iconX = x + columnWidth - ROW_PADDING - PING_WIDTH;
                    int pingX = iconX - 5 - pingWidth;
                    drawStyled(g, entry.pingText(), pingX, y + 23);
                    drawPingIcon(g, entry.ping(), iconX, y + 7);
                }
            }
            if(players.isEmpty()) drawCenteredLines(g, List.of("Никого нет в сети"), bodyTop + 23, width);
            if(!footer.isEmpty()){
                int footerTop = bodyTop + bodyHeight + SECTION_GAP;
                g.setColor(new Color(72, 72, 72, 232));
                g.fillRect(PADDING, footerTop, width - PADDING * 2, footerHeight);
                drawCenteredLines(g, footer, footerTop + FONT_SIZE + 3, width);
            }
        }finally{
            g.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if(!ImageIO.write(image, "png", bytes)) throw new IOException("PNG encoder is unavailable");
        return bytes.toByteArray();
    }

    private void drawHead(Graphics2D g, UUID uuid, int x, int y, long deadlineMillis) {
        BufferedImage head = head(uuid, deadlineMillis);
        if(head != null){
            g.drawImage(head, x, y, HEAD_SIZE, HEAD_SIZE, null);
        }else{
            g.setColor(new Color(113, 137, 150));
            g.fillRect(x, y, HEAD_SIZE, HEAD_SIZE);
            g.setColor(new Color(68, 88, 99));
            g.fillRect(x + 6, y + 21, HEAD_SIZE - 12, 5);
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
            drawStyled(g, line, (width - styledWidth(g, line)) / 2, firstBaseline + i * LINE_HEIGHT);
        }
    }

    private BufferedImage loadPingIcon(String name) throws IOException {
        try(InputStream input = DiscordTabImageRenderer.class.getResourceAsStream("/textures/gui/sprites/icon/" + name + ".png")){
            if(input == null) throw new IOException("Minecraft " + name + " texture is missing");
            BufferedImage image = ImageIO.read(input);
            if(image == null) throw new IOException("Minecraft " + name + " texture cannot be loaded");
            return image;
        }
    }

    private void drawPingIcon(Graphics2D g, long ping, int x, int y) {
        int strength = ping < 0 ? 0 : ping < 150 ? 5 : ping < 300 ? 4 : ping < 600 ? 3 : ping < 1_000 ? 2 : 1;
        g.drawImage(pingIcons[strength], x, y, PING_WIDTH, PING_HEIGHT, null);
    }

    private static List<String> lines(String text) {
        if(text.isBlank()) return List.of();
        String[] parts = text.replace("\\n", "\n").split("\n", -1);
        List<String> result = new ArrayList<>();
        for(int i = 0; i < Math.min(5, parts.length); i++){
            result.add(parts[i].length() > 160 ? parts[i].substring(0, 160) : parts[i]);
        }
        return result;
    }

    private int styledWidth(Graphics2D g, String text) {
        int width = 0;
        for(Glyph glyph : glyphs(text)){
            width += glyphWidth(g, glyph);
        }
        return width;
    }

    private void drawStyled(Graphics2D g, String text, int x, int baseline) {
        int cursor = x;
        for(Glyph glyph : glyphs(text)){
            int width = glyphWidth(g, glyph);
            if(isVectorDiamond(glyph)){
                drawDiamond(g, cursor + 1, baseline + 1, new Color(0, 0, 0, 190));
                drawDiamond(g, cursor, baseline, glyph.color());
            }else{
                Font face = face(glyph);
                String value = face.canDisplay(glyph.codePoint()) ? glyph.text() : "?";
                g.setFont(face);
                g.setColor(new Color(0, 0, 0, 190));
                g.drawString(value, cursor + 1, baseline + 1);
                g.setColor(glyph.color());
                g.drawString(value, cursor, baseline);
            }
            cursor += width;
        }
    }

    private int glyphWidth(Graphics2D g, Glyph glyph) {
        if(isVectorDiamond(glyph)) return FONT_SIZE;
        Font face = face(glyph);
        String value = face.canDisplay(glyph.codePoint()) ? glyph.text() : "?";
        return g.getFontMetrics(face).stringWidth(value);
    }

    private Font face(Glyph glyph) {
        Font primary = primaryFonts[glyph.style()];
        return primary.canDisplay(glyph.codePoint()) ? primary : fallbackFonts[glyph.style()];
    }

    private boolean isVectorDiamond(Glyph glyph) {
        int codePoint = glyph.codePoint();
        return !primaryFonts[glyph.style()].canDisplay(codePoint)
                && (codePoint == 0x2726 || codePoint == 0x25C6 || codePoint == 0x2666 || codePoint == 0x2756);
    }

    private static void drawDiamond(Graphics2D g, int x, int baseline, Color color) {
        int cx = x + FONT_SIZE / 2;
        int cy = baseline - FONT_SIZE / 2 + 2;
        Path2D path = new Path2D.Double();
        path.moveTo(cx, cy - 12);
        path.lineTo(cx + 4, cy - 4);
        path.lineTo(cx + 12, cy);
        path.lineTo(cx + 4, cy + 4);
        path.lineTo(cx, cy + 12);
        path.lineTo(cx - 4, cy + 4);
        path.lineTo(cx - 12, cy);
        path.lineTo(cx - 4, cy - 4);
        path.closePath();
        g.setColor(color);
        g.fill(path);
    }

    private static List<Glyph> glyphs(String raw) {
        String text = LEGACY_RGB.matcher(raw).replaceAll("&#$1$2$3$4$5$6");
        List<Glyph> result = new ArrayList<>(text.length());
        Color color = Color.WHITE;
        int style = Font.PLAIN;
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            if((c == '&' || c == '§') && i + 1 < text.length()){
                char code = Character.toLowerCase(text.charAt(i + 1));
                Color next = COLORS.get(code);
                if(next != null){
                    color = next;
                    style = Font.PLAIN;
                    i++;
                    continue;
                }
                if(code == 'r'){
                    color = Color.WHITE;
                    style = Font.PLAIN;
                    i++;
                    continue;
                }
                if(code == 'l' || code == 'o'){
                    style |= code == 'l' ? Font.BOLD : Font.ITALIC;
                    i++;
                    continue;
                }
                if("kmn".indexOf(code) >= 0){
                    i++;
                    continue;
                }
                if(code == '#' && i + 7 < text.length()){
                    next = hexColor(text.substring(i + 2, i + 8));
                    if(next != null){
                        color = next;
                        style = Font.PLAIN;
                        i += 7;
                        continue;
                    }
                }
            }
            if(c == '<' && i + 8 < text.length() && text.charAt(i + 1) == '#' && text.charAt(i + 8) == '>'){
                Color next = hexColor(text.substring(i + 2, i + 8));
                if(next != null){
                    color = next;
                    style = Font.PLAIN;
                    i += 8;
                    continue;
                }
            }
            int codePoint = text.codePointAt(i);
            result.add(new Glyph(codePoint, new String(Character.toChars(codePoint)), color, style));
            i += Character.charCount(codePoint) - 1;
        }
        return result;
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

    private record Row(UUID uuid, long ping, String name, String pingText) {}
    private record Glyph(int codePoint, String text, Color color, int style) {}
    private record CachedHead(BufferedImage image, long expiresAt) {}
}
