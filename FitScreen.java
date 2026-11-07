package net.fit.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fit.client.FitClient;
import net.fit.client.module.Category;
import net.fit.client.module.Module;
import net.fit.client.module.ModuleManager;
import net.fit.client.module.Setting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The Fit menu: tab row, category sidebar, search, two-column module cards. */
public final class FitScreen extends Screen {
    private static final int BG = 0xF0150F1C;
    private static final int BORDER = 0xFF3B2B4A;
    private static final int PINK = 0xFFFF6FB5;
    private static final int PINK_SOFT = 0xFFFF9CCB;
    private static final int CARD = 0xFF211928;
    private static final int CARD_HOVER = 0xFF2A2034;
    private static final int TEXT = 0xFFEDE7F3;
    private static final int DIM = 0xFF8E8099;
    private static final int TRACK_OFF = 0xFF3A3342;

    private static final String[] TABS = {"MODULES", "HUD", "EMOTES", "CONFIGS", "PROFILE", "SETTINGS"};

    private record Region(int x, int y, int w, int h, Runnable left, Runnable right) {
        boolean hit(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<Region> regions = new ArrayList<>();
    private Category selected = Category.RENDER;
    private int tab = 0;
    private String search = "";
    private Module popup;

    public FitScreen() {
        super(Component.literal(FitClient.NAME));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x70000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        regions.clear();

        int w = Math.min(480, width - 16);
        int h = Math.min(290, height - 16);
        int x0 = (width - w) / 2;
        int y0 = (height - h) / 2;

        // window
        box(g, x0 + 3, y0 + 4, w, h, 0x66000000);
        box(g, x0 - 1, y0 - 1, w + 2, h + 2, BORDER);
        box(g, x0, y0, w, h, BG);
        g.fill(x0 + 1, y0 + 30, x0 + w - 1, y0 + 31, 0xFF2A1F35);

        // header: logo + tabs + close
        drawLogo(g, x0 + 10, y0 + 6, 2);
        int tx = x0 + 46;
        for (int i = 0; i < TABS.length; i++) {
            int tw = font.width(TABS[i]) + 14;
            final int index = i;
            boolean active = i == tab;
            boolean hover = inside(mouseX, mouseY, tx, y0 + 7, tw, 18);
            box(g, tx, y0 + 7, tw, 18, active ? 0xFF2E2238 : hover ? 0xFF251B2E : 0xFF1C1424);
            g.drawString(font, TABS[i], tx + 7, y0 + 12, active ? 0xFFFFFFFF : DIM, false);
            if (active) g.fill(tx + 4, y0 + 28, tx + tw - 4, y0 + 30, PINK);
            regions.add(new Region(tx, y0 + 7, tw, 18, () -> tab = index, null));
            tx += tw + 5;
        }
        int cx = x0 + w - 20;
        g.drawString(font, "x", cx + 5, y0 + 11, hoverColor(mouseX, mouseY, cx, y0 + 6, 16, 16, DIM, 0xFFFFFFFF), false);
        regions.add(new Region(cx, y0 + 6, 16, 16, this::onClose, null));

        if (tab != 0) {
            String label = TABS[tab] + " - coming soon";
            g.drawString(font, label, x0 + (w - font.width(label)) / 2, y0 + h / 2, DIM, false);
            return;
        }

        // sidebar
        int sy = y0 + 40;
        for (Category category : Category.values()) {
            boolean active = category == selected;
            boolean hover = inside(mouseX, mouseY, x0 + 8, sy, 96, 20);
            box(g, x0 + 8, sy, 96, 20, active ? 0xFF33243D : hover ? 0xFF271D30 : 0xFF1D1525);
            if (active) g.fill(x0 + 8, sy + 3, x0 + 10, sy + 17, PINK);
            g.drawString(font, category.label, x0 + 20, sy + 6, active ? 0xFFFFFFFF : DIM, false);
            regions.add(new Region(x0 + 8, sy, 96, 20, () -> selected = category, null));
            sy += 26;
        }

        // content
        int cx0 = x0 + 114;
        int cw = w - 122;
        List<Module> modules = filtered();

        box(g, cx0, y0 + 38, Math.min(170, cw - 70), 14, 0xFF1D1525);
        String searchText = search.isEmpty() ? "SEARCH MODULES" : search + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        g.drawString(font, searchText, cx0 + 6, y0 + 41, search.isEmpty() ? DIM : TEXT, false);
        String count = modules.size() + (modules.size() == 1 ? " MODULE" : " MODULES");
        g.drawString(font, count, cx0 + cw - font.width(count), y0 + 41, DIM, false);

        int gap = 6;
        int colW = (cw - gap) / 2;
        int cardH = 26;
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            int col = i % 2;
            int row = i / 2;
            int cardX = cx0 + col * (colW + gap);
            int cardY = y0 + 60 + row * (cardH + gap);
            if (cardY + cardH > y0 + h - 18) break;

            boolean hover = popup == null && inside(mouseX, mouseY, cardX, cardY, colW, cardH);
            box(g, cardX, cardY, colW, cardH, hover ? CARD_HOVER : CARD);
            if (module.isEnabled()) g.fill(cardX, cardY + 3, cardX + 2, cardY + cardH - 3, PINK);

            g.drawString(font, module.name.toUpperCase(Locale.ROOT), cardX + 8, cardY + 4,
                    module.isEnabled() ? PINK_SOFT : TEXT, false);
            String desc = font.plainSubstrByWidth(module.description, colW - 56);
            g.drawString(font, desc, cardX + 8, cardY + 15, DIM, false);
            drawToggle(g, cardX + colW - 30, cardY + 8, module.isEnabled());

            regions.add(new Region(cardX, cardY, colW, cardH, module::toggle, () -> popup = module));
        }

        g.drawString(font, "Right-click a module for settings   |   Right Shift opens this menu",
                x0 + 12, y0 + h - 12, 0xFF5E516B, false);

        if (popup != null) drawPopup(g, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ popup

    private void drawPopup(GuiGraphics g, int mouseX, int mouseY) {
        Module module = popup;
        int rows = module.settings.size();
        int pw = 200;
        int ph = 26 + rows * 16 + 8;
        int px = (width - pw) / 2;
        int py = (height - ph) / 2;

        regions.add(new Region(0, 0, width, height, () -> popup = null, () -> popup = null));
        g.fill(0, 0, width, height, 0x55000000);
        box(g, px - 1, py - 1, pw + 2, ph + 2, BORDER);
        box(g, px, py, pw, ph, 0xFF1A1222);
        regions.add(new Region(px, py, pw, ph, () -> { }, () -> { }));
        g.drawString(font, module.name.toUpperCase(Locale.ROOT), px + 8, py + 7, PINK_SOFT, false);

        int ry = py + 24;
        for (Setting setting : module.settings) {
            g.drawString(font, setting.name, px + 8, ry + 2, TEXT, false);
            if (setting instanceof Setting.Bool bool) {
                drawToggle(g, px + pw - 30, ry, bool.value);
                regions.add(new Region(px + 6, ry - 2, pw - 12, 14, bool::toggle, bool::toggle));
            } else if (setting instanceof Setting.Num num) {
                String value = num.value == Math.floor(num.value)
                        ? String.valueOf((int) num.value) : String.format(Locale.ROOT, "%.1f", num.value);
                int vx = px + pw - 52;
                g.drawString(font, "<", vx - 6, ry + 2, hoverColor(mouseX, mouseY, vx - 10, ry - 2, 14, 14, DIM, PINK), false);
                g.drawString(font, value, vx + (28 - font.width(value)) / 2 + 6, ry + 2, PINK_SOFT, false);
                g.drawString(font, ">", vx + 42, ry + 2, hoverColor(mouseX, mouseY, vx + 38, ry - 2, 14, 14, DIM, PINK), false);
                regions.add(new Region(vx - 10, ry - 2, 14, 14, () -> num.add(-1), () -> num.add(-1)));
                regions.add(new Region(vx + 38, ry - 2, 14, 14, () -> num.add(1), () -> num.add(1)));
            }
            ry += 16;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region region = regions.get(i);
            if (!region.hit(mx, my)) continue;
            Runnable action = button == 0 ? region.left() : button == 1 ? region.right() : null;
            if (action != null) action.run();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (popup != null) {
                popup = null;
                return true;
            }
            return super.keyPressed(event);
        }
        if (tab == 0 && popup == null) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
                return true;
            }
            if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z && search.length() < 24) {
                search += (char) (key + 32);
                return true;
            }
            if (key == GLFW.GLFW_KEY_SPACE && search.length() < 24 && !search.isEmpty()) {
                search += " ";
                return true;
            }
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------ helpers

    private List<Module> filtered() {
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<Module> out = new ArrayList<>();
        for (Module module : ModuleManager.in(selected)) {
            if (needle.isEmpty() || module.name.toLowerCase(Locale.ROOT).contains(needle)) out.add(module);
        }
        return out;
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int hoverColor(int mx, int my, int x, int y, int w, int h, int normal, int hover) {
        return inside(mx, my, x, y, w, h) ? hover : normal;
    }

    /** Rectangle with the corner pixels cut off. */
    private static void box(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 2, y, x + w - 2, y + h, color);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
        g.fill(x, y + 2, x + w, y + h - 2, color);
    }

    private static void drawToggle(GuiGraphics g, int x, int y, boolean on) {
        g.fill(x + 1, y, x + 21, y + 10, on ? PINK : TRACK_OFF);
        g.fill(x, y + 1, x + 22, y + 9, on ? PINK : TRACK_OFF);
        int knobX = on ? x + 13 : x + 1;
        g.fill(knobX, y + 1, knobX + 8, y + 9, on ? 0xFFFFFFFF : 0xFF8E8099);
    }

    /** The slanted pink F, drawn from rectangles. */
    private static void drawLogo(GuiGraphics g, int x, int y, int s) {
        int rows = 10;
        for (int r = 0; r < rows; r++) {
            int shear = (rows - 1 - r) * s / 3;
            int top = y + r * s;
            int color = r < 5 ? 0xFFFFA8D3 : PINK;
            g.fill(x + shear, top, x + shear + 2 * s, top + s, color);
            if (r < 2) g.fill(x + shear, top, x + shear + 7 * s, top + s, color);
            if (r == 4 || r == 5) g.fill(x + shear, top, x + shear + 5 * s, top + s, color);
        }
    }
}
