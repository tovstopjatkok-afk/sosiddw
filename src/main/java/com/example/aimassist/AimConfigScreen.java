package com.example.aimassist;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Меню в стиле «модульного» клиента: боковая панель с разделами, карточки модулей,
 * поиск по настройкам и настройка цвета оформления.
 */
public class AimConfigScreen extends Screen {
    // Цвета (ARGB). Акцент берётся из конфига.
    private static final int CARD = 0xFF16181F;
    private static final int BORDER = 0xFF262A35;
    private static final int FIELD = 0xFF1E222B;
    private static final int TRACK = 0xFF2A2F3A;
    private static final int OFF = 0xFF3A3F4B;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFC3C8D2;
    private static final int MUTED = 0xFF8A93A3;

    // Размеры
    private static final int SIDEBAR_W = 44;
    private static final int HEADER_H = 48;
    private static final int CARD_HEAD = 30;
    private static final int GAP = 8;

    private static final String[] TAB_TITLES = {"Combat", "Visual"};
    private static final String[] TAB_SHORT = {"AIM", "UI"};

    private static final int[] SWATCHES = {
            0x8B5CF6, 0x4FC3F7, 0x66BB6A, 0xEF5350, 0xFFA726, 0xEC407A
    };

    // Запоминаем вкладку между открытиями меню
    private static int currentTab = 0;

    private final Screen parent;

    private final List<Card> modules = new ArrayList<>();
    private TextFieldWidget searchField;
    private String query = "";
    private double scroll = 0;
    private SliderRow dragging;

    // Геометрия, считается в init()
    private int winX, winY, winW, winH;
    private int cx0, cy0, cw, ch; // область с карточками

    public AimConfigScreen(Screen parent) {
        super(Text.literal("Aim Assist"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ построение

    @Override
    protected void init() {
        AimConfig cfg = AimConfig.INSTANCE;

        winW = Math.min(470, this.width - 16);
        winH = Math.min(330, this.height - 24);
        winX = (this.width - winW) / 2;
        winY = (this.height - winH) / 2;
        cx0 = winX + SIDEBAR_W + 12;
        cy0 = winY + HEADER_H;
        cw = winW - SIDEBAR_W - 24;
        ch = winH - HEADER_H - 10;

        modules.clear();

        // ---- Combat ----
        Card aim = new Card(0, "Aim Assist", "Плавно наводит камеру на ближайшую цель",
                () -> cfg.enabled, v -> cfg.enabled = v);
        aim.rows.add(new CycleRow<>("Логика", Arrays.asList(AimMode.values()),
                () -> cfg.mode, v -> cfg.mode = v, m -> m.label));
        aim.rows.add(new SliderRow("Дальность", 3, 60, 0, " бл.", () -> cfg.range, v -> cfg.range = v));
        aim.rows.add(new SliderRow("Угол обзора (FOV)", 10, 360, 0, "°", () -> cfg.fov, v -> cfg.fov = v));
        aim.rows.add(new SliderRow("Скорость X (влево-вправо)", 0.01, 1.0, 2, "", () -> cfg.speedX, v -> cfg.speedX = v));
        aim.rows.add(new SliderRow("Скорость Y (вверх-вниз)", 0.01, 1.0, 2, "", () -> cfg.speedY, v -> cfg.speedY = v));
        aim.rows.add(new SliderRow("Плавность разгона", 0.05, 1.0, 2, "", () -> cfg.accel, v -> cfg.accel = v));
        aim.rows.add(new SliderRow("Макс. поворот за тик", 1, 90, 0, "°", () -> cfg.maxStep, v -> cfg.maxStep = v));
        aim.rows.add(new CycleRow<>("Целиться в", Arrays.asList(true, false),
                () -> cfg.aimAtHead, v -> cfg.aimAtHead = v, v -> v ? "Голова" : "Тело"));
        aim.rows.add(new ToggleRow("Только видимые цели", () -> cfg.visibleOnly, v -> cfg.visibleOnly = v));
        aim.rows.add(new ToggleRow("Только при зажатой ЛКМ", () -> cfg.holdAttackOnly, v -> cfg.holdAttackOnly = v));
        modules.add(aim);

        Card atk = new Card(0, "Auto Attack", "Бьёт, когда прицел на хитбоксе игрока",
                () -> cfg.autoAttack, v -> cfg.autoAttack = v);
        atk.rows.add(new ToggleRow("Только полный взмах (100%)", () -> cfg.fullChargeOnly, v -> cfg.fullChargeOnly = v));
        atk.rows.add(new SliderRow("Мин. заряд (если взмах выкл.)", 0.1, 1.0, 2, "", () -> cfg.attackCharge, v -> cfg.attackCharge = v));
        atk.rows.add(new ToggleRow("Только криты", () -> cfg.critOnly, v -> cfg.critOnly = v));
        atk.rows.add(new ToggleRow("Не бить при еде / щите / луке", () -> cfg.noHitWhileUsing, v -> cfg.noHitWhileUsing = v));
        modules.add(atk);

        // ---- Visual ----
        Card theme = new Card(1, "Оформление", "Цвет и прозрачность меню", null, null);
        theme.rows.add(new SwatchRow("Цвет акцента"));
        theme.rows.add(new SliderRow("Красный", 0, 255, 0, "", () -> (cfg.accent >> 16) & 255,
                v -> cfg.accent = (cfg.accent & 0x00FFFF) | ((int) v << 16)));
        theme.rows.add(new SliderRow("Зелёный", 0, 255, 0, "", () -> (cfg.accent >> 8) & 255,
                v -> cfg.accent = (cfg.accent & 0xFF00FF) | ((int) v << 8)));
        theme.rows.add(new SliderRow("Синий", 0, 255, 0, "", () -> cfg.accent & 255,
                v -> cfg.accent = (cfg.accent & 0xFFFF00) | (int) v));
        theme.rows.add(new SliderRow("Прозрачность окна", 0.3, 1.0, 2, "", () -> cfg.windowOpacity, v -> cfg.windowOpacity = v));
        modules.add(theme);

        // ---- Поиск ----
        int sw = 140;
        int sx = winX + winW - 12 - sw;
        int sy = winY + 14;
        searchField = new TextFieldWidget(this.textRenderer, sx + 6, sy + 4, sw - 12, 12, Text.literal("Поиск"));
        searchField.setDrawsBackground(false);
        searchField.setMaxLength(32);
        searchField.setPlaceholder(Text.literal("Поиск настроек..."));
        searchField.setText(query);
        searchField.setChangedListener(s -> {
            query = s;
            scroll = 0;
        });
        addDrawableChild(searchField);
    }

    // ------------------------------------------------------------------ отрисовка

    private int accent() {
        return 0xFF000000 | (AimConfig.INSTANCE.accent & 0xFFFFFF);
    }

    private int accentAlpha(int alpha) {
        return (alpha << 24) | (AimConfig.INSTANCE.accent & 0xFFFFFF);
    }

    /** Какие модули показывать сейчас (с учётом вкладки и поиска). */
    private List<Card> visibleModules() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Card> out = new ArrayList<>();
        for (Card m : modules) {
            if (q.isEmpty()) {
                if (m.tab != currentTab) continue;
                m.shown = m.rows;
                out.add(m);
            } else {
                boolean nameMatch = m.name.toLowerCase(Locale.ROOT).contains(q);
                List<Row> rows = new ArrayList<>();
                for (Row r : m.rows) {
                    if (nameMatch || r.label.toLowerCase(Locale.ROOT).contains(q)) rows.add(r);
                }
                if (rows.isEmpty()) continue;
                m.shown = rows;
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Фон рисуем сами в render(), стандартное размытие не нужно
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        AimConfig cfg = AimConfig.INSTANCE;
        int alpha = (int) (MathHelper.clamp(cfg.windowOpacity, 0.3, 1.0) * 255);

        // Затемнение и окно
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        ctx.fill(winX, winY, winX + winW, winY + winH, (alpha << 24) | 0x0F1014);
        ctx.fill(winX, winY, winX + SIDEBAR_W, winY + winH, (alpha << 24) | 0x0A0B0E);
        ctx.drawBorder(winX, winY, winW, winH, BORDER);
        ctx.fill(winX + SIDEBAR_W, winY, winX + SIDEBAR_W + 1, winY + winH, BORDER);

        // Боковая панель
        for (int i = 0; i < TAB_TITLES.length; i++) {
            int bx = winX + 6, by = winY + 12 + i * 36, bw = SIDEBAR_W - 12, bh = 30;
            boolean active = i == currentTab && query.isEmpty();
            boolean hover = mx >= bx && mx < bx + bw && my >= by && my < by + bh;
            if (active) {
                ctx.fill(bx, by, bx + bw, by + bh, accentAlpha(0x33));
                ctx.fill(bx, by, bx + 2, by + bh, accent());
            } else if (hover) {
                ctx.fill(bx, by, bx + bw, by + bh, 0x22FFFFFF);
            }
            ctx.drawCenteredTextWithShadow(this.textRenderer, TAB_SHORT[i], bx + bw / 2, by + 11,
                    active ? TEXT : MUTED);
        }

        // Шапка
        List<Card> vis = visibleModules();
        String title = query.trim().isEmpty() ? TAB_TITLES[currentTab] : "Поиск";
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(title).formatted(Formatting.BOLD),
                winX + SIDEBAR_W + 14, winY + 12, TEXT);
        ctx.drawTextWithShadow(this.textRenderer, vis.size() + " " + plural(vis.size()),
                winX + SIDEBAR_W + 14, winY + 25, MUTED);

        int sw = 140, sx = winX + winW - 12 - sw, sy = winY + 14;
        ctx.fill(sx, sy, sx + sw, sy + 18, FIELD);
        ctx.drawBorder(sx, sy, sw, 18, searchField.isFocused() ? accent() : BORDER);

        // Раскладка карточек в две колонки
        int colW = (cw - GAP) / 2;
        int[] colH = {0, 0};
        for (Card m : vis) {
            int col = colH[0] <= colH[1] ? 0 : 1;
            m.px = col * (colW + GAP);
            m.py = colH[col];
            m.pw = colW;
            colH[col] += m.height() + GAP;
        }
        int contentH = Math.max(colH[0], colH[1]) - GAP;
        double maxScroll = Math.max(0, contentH - ch);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);

        // Карточки (с обрезкой по области контента)
        ctx.enableScissor(cx0, cy0, cx0 + cw + 4, cy0 + ch);
        for (Card m : vis) {
            drawModule(ctx, m, cx0 + m.px, cy0 + m.py - (int) scroll, mx, my);
        }
        ctx.disableScissor();

        if (vis.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, "Ничего не найдено",
                    cx0 + cw / 2, cy0 + 30, MUTED);
        }

        // Полоса прокрутки
        if (maxScroll > 0) {
            int barH = Math.max(16, (int) (ch * (ch / (double) contentH)));
            int barY = cy0 + (int) ((ch - barH) * (scroll / maxScroll));
            ctx.fill(cx0 + cw + 3, barY, cx0 + cw + 5, barY + barH, accentAlpha(0xAA));
        }

        // Поле поиска поверх своей подложки
        super.render(ctx, mx, my, delta);

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                "R — вкл/выкл · Right Shift — это меню · колесо — прокрутка",
                this.width / 2, winY + winH + 6, MUTED);
    }

    private static String plural(int n) {
        int a = n % 10, b = n % 100;
        if (a == 1 && b != 11) return "модуль";
        if (a >= 2 && a <= 4 && (b < 12 || b > 14)) return "модуля";
        return "модулей";
    }

    private void drawModule(DrawContext ctx, Card m, int x, int y, int mx, int my) {
        int w = m.pw;
        int h = m.height();
        ctx.fill(x, y, x + w, y + h, CARD);
        ctx.drawBorder(x, y, w, h, BORDER);

        ctx.drawTextWithShadow(this.textRenderer, Text.literal(m.name).formatted(Formatting.BOLD),
                x + 8, y + 6, TEXT);
        int descW = w - 16 - (m.on != null ? 28 : 0);
        ctx.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(m.desc, descW),
                x + 8, y + 17, MUTED);

        if (m.on != null) {
            m.tx = x + w - 8 - 20;
            m.ty = y + 9;
            drawSwitch(ctx, m.tx, m.ty, m.on.getAsBoolean());
        }

        int ry = y + CARD_HEAD;
        for (Row r : m.shown) {
            r.x = x + 8;
            r.y = ry;
            r.w = w - 16;
            r.draw(ctx, mx, my);
            ry += r.height();
        }
    }

    private void drawSwitch(DrawContext ctx, int x, int y, boolean on) {
        ctx.fill(x, y, x + 20, y + 10, on ? accent() : OFF);
        int kx = on ? x + 20 - 8 : x + 2;
        ctx.fill(kx, y + 2, kx + 6, y + 8, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ ввод

    private boolean inContent(double mx, double my) {
        return mx >= cx0 && mx < cx0 + cw && my >= cy0 && my < cy0 + ch;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        searchField.setFocused(false);

        // Боковая панель
        for (int i = 0; i < TAB_TITLES.length; i++) {
            int bx = winX + 6, by = winY + 12 + i * 36, bw = SIDEBAR_W - 12, bh = 30;
            if (mx >= bx && mx < bx + bw && my >= by && my < by + bh && button == 0) {
                currentTab = i;
                scroll = 0;
                searchField.setText("");
                return true;
            }
        }

        if (!inContent(mx, my)) return false;

        for (Card m : visibleModules()) {
            if (m.on != null && button == 0
                    && mx >= m.tx - 2 && mx < m.tx + 22 && my >= m.ty - 2 && my < m.ty + 12) {
                m.setOn.accept(!m.on.getAsBoolean());
                return true;
            }
            for (Row r : m.shown) {
                if (r.click(mx, my, button)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            dragging.apply(mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (inContent(mx, my)) {
            scroll -= vertical * 20;
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public void removed() {
        AimConfig.save(); // сохраняем при любом выходе из меню
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false; // игра не ставится на паузу, можно настраивать на ходу
    }

    // ------------------------------------------------------------------ модели

    private final class Card {
        final int tab;
        final String name, desc;
        final BooleanSupplier on;      // null, если у модуля нет главного переключателя
        final Consumer<Boolean> setOn;
        final List<Row> rows = new ArrayList<>();
        List<Row> shown = rows;
        int px, py, pw;                // положение в области контента
        int tx, ty;                    // положение переключателя на экране

        Card(int tab, String name, String desc, BooleanSupplier on, Consumer<Boolean> setOn) {
            this.tab = tab;
            this.name = name;
            this.desc = desc;
            this.on = on;
            this.setOn = setOn;
        }

        int height() {
            int h = CARD_HEAD + 4;
            for (Row r : shown) h += r.height();
            return h;
        }
    }

    private abstract class Row {
        final String label;
        int x, y, w;

        Row(String label) {
            this.label = label;
        }

        abstract int height();

        abstract void draw(DrawContext ctx, int mx, int my);

        boolean click(double mx, double my, int button) {
            return false;
        }

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + height() && inContent(mx, my);
        }
    }

    private final class ToggleRow extends Row {
        final BooleanSupplier get;
        final Consumer<Boolean> set;

        ToggleRow(String label, BooleanSupplier get, Consumer<Boolean> set) {
            super(label);
            this.get = get;
            this.set = set;
        }

        @Override
        int height() {
            return 20;
        }

        @Override
        void draw(DrawContext ctx, int mx, int my) {
            ctx.drawTextWithShadow(textRenderer, label, x, y + 6, hit(mx, my) ? TEXT : TEXT_DIM);
            drawSwitch(ctx, x + w - 20, y + 5, get.getAsBoolean());
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button == 0 && hit(mx, my)) {
                set.accept(!get.getAsBoolean());
                return true;
            }
            return false;
        }
    }

    private final class SliderRow extends Row {
        final double min, max;
        final int decimals;
        final String suffix;
        final DoubleSupplier get;
        final DoubleConsumer set;

        SliderRow(String label, double min, double max, int decimals, String suffix,
                  DoubleSupplier get, DoubleConsumer set) {
            super(label);
            this.min = min;
            this.max = max;
            this.decimals = decimals;
            this.suffix = suffix;
            this.get = get;
            this.set = set;
        }

        @Override
        int height() {
            return 26;
        }

        @Override
        void draw(DrawContext ctx, int mx, int my) {
            double v = get.getAsDouble();
            String value = (decimals == 0 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%." + decimals + "f", v)) + suffix;

            ctx.drawTextWithShadow(textRenderer, label, x, y + 3, hit(mx, my) || dragging == this ? TEXT : TEXT_DIM);
            ctx.drawTextWithShadow(textRenderer, value, x + w - textRenderer.getWidth(value), y + 3, TEXT);

            double t = MathHelper.clamp((v - min) / (max - min), 0, 1);
            int ty = y + 17;
            int fw = (int) (w * t);
            ctx.fill(x, ty, x + w, ty + 2, TRACK);
            ctx.fill(x, ty, x + fw, ty + 2, accent());
            ctx.fill(x + fw - 2, ty - 2, x + fw + 2, ty + 4, 0xFFFFFFFF);
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button == 0 && hit(mx, my) && my >= y + 9) {
                dragging = this;
                apply(mx);
                return true;
            }
            return false;
        }

        void apply(double mx) {
            double t = MathHelper.clamp((mx - x) / w, 0, 1);
            double v = min + (max - min) * t;
            double p = Math.pow(10, decimals);
            set.accept(Math.round(v * p) / p);
        }
    }

    private final class CycleRow<T> extends Row {
        final List<T> values;
        final Supplier<T> get;
        final Consumer<T> set;
        final Function<T, String> name;

        CycleRow(String label, List<T> values, Supplier<T> get, Consumer<T> set, Function<T, String> name) {
            super(label);
            this.values = values;
            this.get = get;
            this.set = set;
            this.name = name;
        }

        @Override
        int height() {
            return 20;
        }

        @Override
        void draw(DrawContext ctx, int mx, int my) {
            ctx.drawTextWithShadow(textRenderer, label, x, y + 6, hit(mx, my) ? TEXT : TEXT_DIM);
            String s = name.apply(get.get());
            int bw = Math.max(64, textRenderer.getWidth(s) + 16);
            int bx = x + w - bw;
            ctx.fill(bx, y + 2, x + w, y + 18, FIELD);
            ctx.drawBorder(bx, y + 2, bw, 16, BORDER);
            ctx.drawCenteredTextWithShadow(textRenderer, s, bx + bw / 2, y + 6, TEXT);
        }

        @Override
        boolean click(double mx, double my, int button) {
            if ((button == 0 || button == 1) && hit(mx, my)) {
                int i = values.indexOf(get.get());
                int step = button == 0 ? 1 : -1;
                set.accept(values.get(Math.floorMod(i + step, values.size())));
                return true;
            }
            return false;
        }
    }

    private final class SwatchRow extends Row {
        SwatchRow(String label) {
            super(label);
        }

        @Override
        int height() {
            return 24;
        }

        private int swatchX(int i) {
            return x + w - (SWATCHES.length - i) * 16 + 4;
        }

        @Override
        void draw(DrawContext ctx, int mx, int my) {
            ctx.drawTextWithShadow(textRenderer, label, x, y + 8, TEXT_DIM);
            for (int i = 0; i < SWATCHES.length; i++) {
                int sx = swatchX(i);
                ctx.fill(sx, y + 6, sx + 12, y + 18, 0xFF000000 | SWATCHES[i]);
                if ((AimConfig.INSTANCE.accent & 0xFFFFFF) == SWATCHES[i]) {
                    ctx.drawBorder(sx - 1, y + 5, 14, 14, 0xFFFFFFFF);
                }
            }
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button != 0 || !hit(mx, my)) return false;
            for (int i = 0; i < SWATCHES.length; i++) {
                int sx = swatchX(i);
                if (mx >= sx && mx < sx + 12) {
                    AimConfig.INSTANCE.accent = SWATCHES[i];
                    return true;
                }
            }
            return false;
        }
    }
}
