package com.example.aimassist;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Меню в стиле «модульного» клиента: боковая панель с иконками, карточки модулей,
 * выпадающие списки для режимов, поиск по настройкам и настройка цветов.
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
    private static final int DROP_ITEM_H = 16;

    private static final String[] TAB_TITLES = {"Combat", "Visual"};

    private static final int[] SWATCHES = {
            0x8B5CF6, 0x4FC3F7, 0x66BB6A, 0xEF5350, 0xFFA726, 0xEC407A
    };

    // Запоминаем вкладку между открытиями меню
    private static int currentTab = 0;

    // Свёрнутые карточки (запоминаем между открытиями меню)
    private static final Set<String> collapsed = new HashSet<>();

    private final Screen parent;

    // Время между кадрами для анимаций
    private long lastNanos = System.nanoTime();
    private float dt = 0.016f;

    private final List<Card> cards = new ArrayList<>();
    private TextFieldWidget searchField;
    private String query = "";
    private double scroll = 0;
    private SliderRow dragging;

    // Строка выбора клавиши, которая сейчас ждёт нажатия
    private BindRow listening;

    // Открытый выпадающий список
    private CycleRow<?> drop;
    private int dropX, dropY, dropW, dropH;

    // Иконки разделов
    private ItemStack[] tabIcons;

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

        tabIcons = new ItemStack[]{new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.ENDER_EYE)};
        drop = null;
        listening = null;

        winW = Math.min(470, this.width - 16);
        winH = Math.min(330, this.height - 24);
        winX = (this.width - winW) / 2;
        winY = (this.height - winH) / 2;
        cx0 = winX + SIDEBAR_W + 12;
        cy0 = winY + HEADER_H;
        cw = winW - SIDEBAR_W - 24;
        ch = winH - HEADER_H - 24;

        cards.clear();

        // ---- Combat ----
        Card aim = new Card(0, "Aim Assist", "Доводит камеру до центра цели",
                () -> cfg.enabled, v -> cfg.enabled = v);
        aim.rows.add(new CycleRow<>("Логика", Arrays.asList(AimMode.values()),
                () -> cfg.mode, v -> cfg.mode = v, m -> m.label));
        aim.rows.add(new SliderRow("Дальность", 3, 60, 0, " бл.", () -> cfg.range, v -> cfg.range = v));
        aim.rows.add(new SliderRow("Угол обзора (FOV)", 10, 360, 0, "°", () -> cfg.fov, v -> cfg.fov = v));
        aim.rows.add(new SliderRow("Скорость X (влево-вправо)", 0.01, 1.0, 2, "", () -> cfg.speedX, v -> cfg.speedX = v));
        aim.rows.add(new SliderRow("Скорость Y (вверх-вниз)", 0.01, 1.0, 2, "", () -> cfg.speedY, v -> cfg.speedY = v));
        aim.rows.add(new SliderRow("Плавность разгона", 0.05, 1.0, 2, "", () -> cfg.accel, v -> cfg.accel = v));
        aim.rows.add(new SliderRow("Макс. поворот за тик", 1, 90, 0, "°", () -> cfg.maxStep, v -> cfg.maxStep = v));
        aim.rows.add(new ToggleRow("Только видимые цели", () -> cfg.visibleOnly, v -> cfg.visibleOnly = v));
        aim.rows.add(new ToggleRow("Только при зажатой ЛКМ", () -> cfg.holdAttackOnly, v -> cfg.holdAttackOnly = v));
        cards.add(aim);

        Card atk = new Card(0, "Auto Attack", "Бьёт, когда прицел на хитбоксе игрока",
                () -> cfg.autoAttack, v -> cfg.autoAttack = v);
        atk.rows.add(new CycleRow<>("Режим", Arrays.asList(AttackMode.values()),
                () -> cfg.attackMode, v -> cfg.attackMode = v, m -> m.label));
        atk.rows.add(new BindRow("Клавиша"));
        atk.rows.add(new CycleRow<>("Режим клавиши", Arrays.asList(BindMode.values()),
                () -> cfg.attackBindMode, v -> cfg.attackBindMode = v, m -> m.label));
        atk.rows.add(new SliderRow("Ожидание крита (тики)", 2, 20, 0, "", () -> cfg.smartMaxWait, v -> cfg.smartMaxWait = (int) v));
        atk.rows.add(new CycleRow<>("Оружие в руке", Arrays.asList(WeaponFilter.values()),
                () -> cfg.weapon, v -> cfg.weapon = v, w -> w.label));
        atk.rows.add(new ToggleRow("Только полный взмах (100%)", () -> cfg.fullChargeOnly, v -> cfg.fullChargeOnly = v));
        atk.rows.add(new SliderRow("Мин. заряд (если взмах выкл.)", 0.1, 1.0, 2, "", () -> cfg.attackCharge, v -> cfg.attackCharge = v));
        atk.rows.add(new ToggleRow("Сбрасывать спринт ради крита", () -> cfg.critStopSprint, v -> cfg.critStopSprint = v));
        atk.rows.add(new ToggleRow("Только криты", () -> cfg.critOnly, v -> cfg.critOnly = v));
        atk.rows.add(new ToggleRow("Не бить при еде / щите / луке", () -> cfg.noHitWhileUsing, v -> cfg.noHitWhileUsing = v));
        atk.rows.add(new ToggleRow("Подсказки на экране (отладка)", () -> cfg.attackDebug, v -> cfg.attackDebug = v));
        cards.add(atk);

        // ---- Visual ----
        Card esp = new Card(1, "Target ESP", "Обводка игрока, на которого наведён аим",
                () -> cfg.espEnabled, v -> cfg.espEnabled = v);
        esp.rows.add(new CycleRow<>("Стиль", Arrays.asList(EspStyle.values()),
                () -> cfg.espStyle, v -> cfg.espStyle = v, e -> e.label));
        esp.rows.add(new ToggleRow("Рамка вокруг цели", () -> cfg.espFrame, v -> cfg.espFrame = v));
        esp.rows.add(new SliderRow("Скорость эффекта", 0.2, 3.0, 1, "", () -> cfg.espSpeed, v -> cfg.espSpeed = v));
        esp.rows.add(new SliderRow("Количество молний", 1, 14, 0, "", () -> cfg.espBolts, v -> cfg.espBolts = (int) v));
        esp.rows.add(new SwatchRow("Цвет обводки", () -> cfg.espColor, v -> cfg.espColor = v));
        cards.add(esp);

        Card theme = new Card(1, "Оформление", "Цвет и прозрачность меню", null, null);
        theme.rows.add(new SwatchRow("Цвет акцента", () -> cfg.accent, v -> cfg.accent = v));
        theme.rows.add(new SliderRow("Красный", 0, 255, 0, "", () -> (cfg.accent >> 16) & 255,
                v -> cfg.accent = (cfg.accent & 0x00FFFF) | ((int) v << 16)));
        theme.rows.add(new SliderRow("Зелёный", 0, 255, 0, "", () -> (cfg.accent >> 8) & 255,
                v -> cfg.accent = (cfg.accent & 0xFF00FF) | ((int) v << 8)));
        theme.rows.add(new SliderRow("Синий", 0, 255, 0, "", () -> cfg.accent & 255,
                v -> cfg.accent = (cfg.accent & 0xFFFF00) | (int) v));
        theme.rows.add(new SliderRow("Прозрачность окна", 0.3, 1.0, 2, "", () -> cfg.windowOpacity, v -> cfg.windowOpacity = v));
        cards.add(theme);

        // ---- Поиск ----
        int sw = 140;
        int sx = winX + winW - 12 - sw;
        int sy = winY + 12;
        searchField = new TextFieldWidget(this.textRenderer, sx + 6, sy + 4, sw - 12, 12, Text.literal("Поиск"));
        searchField.setDrawsBackground(false);
        searchField.setMaxLength(32);
        searchField.setPlaceholder(Text.literal("Поиск настроек..."));
        searchField.setText(query);
        searchField.setChangedListener(s -> {
            query = s;
            scroll = 0;
            drop = null;
        });
        addDrawableChild(searchField);
    }

    // ------------------------------------------------------------------ рисование примитивов

    private int accent() {
        return 0xFF000000 | (AimConfig.INSTANCE.accent & 0xFFFFFF);
    }

    private int accentAlpha(int alpha) {
        return (alpha << 24) | (AimConfig.INSTANCE.accent & 0xFFFFFF);
    }

    /** Прямоугольник со срезанными углами (выглядит скруглённым). */
    private static void rect(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x + 1, y, x + w - 1, y + h, color);
        c.fill(x, y + 1, x + 1, y + h - 1, color);
        c.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Скруглённый блок с рамкой. */
    private static void box(DrawContext c, int x, int y, int w, int h, int fill, int border) {
        rect(c, x, y, w, h, border);
        rect(c, x + 1, y + 1, w - 2, h - 2, fill);
    }

    /** t = 0 (выкл) .. 1 (вкл), плавно меняется со временем. */
    private void drawSwitch(DrawContext ctx, int x, int y, float t) {
        if (t > 0.02f) rect(ctx, x - 1, y - 1, 24, 12, accentAlpha((int) (0x30 * t)));
        rect(ctx, x, y, 22, 10, lerpColor(OFF, accent(), t));
        rect(ctx, x + 1 + Math.round(t * 12), y + 1, 8, 8, 0xFFFFFFFF);
    }

    private float approach(float current, float target) {
        return current + (target - current) * Math.min(1f, dt * 16f);
    }

    private static int lerpColor(int a, int b, float t) {
        int out = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            int ca = (a >> shift) & 255, cb = (b >> shift) & 255;
            out |= ((int) (ca + (cb - ca) * t) & 255) << shift;
        }
        return out;
    }

    private void drawChevronRight(DrawContext ctx, int x, int y, int color) {
        for (int i = 0; i < 3; i++) {
            ctx.fill(x + i, y + i, x + i + 1, y + 5 - i, color);
        }
    }

    private void drawChevron(DrawContext ctx, int x, int y, int color) {
        for (int i = 0; i < 3; i++) {
            ctx.fill(x + i, y + i, x + 5 - i, y + i + 1, color);
        }
    }

    // ------------------------------------------------------------------ отрисовка

    /** Какие карточки показывать сейчас (с учётом вкладки и поиска). */
    private List<Card> visibleCards() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Card> out = new ArrayList<>();
        for (Card m : cards) {
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

    private int tabY(int i) {
        return winY + 48 + i * 36;
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        AimConfig cfg = AimConfig.INSTANCE;
        long now = System.nanoTime();
        dt = Math.min((now - lastNanos) / 1_000_000_000f, 0.05f);
        lastNanos = now;

        int alpha = (int) (MathHelper.clamp(cfg.windowOpacity, 0.3, 1.0) * 255);

        // Затемнение, тень и окно
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        for (int i = 6; i >= 1; i--) {
            rect(ctx, winX - i, winY - i, winW + i * 2, winH + i * 2, 0x10000000);
        }
        box(ctx, winX, winY, winW, winH, (alpha << 24) | 0x0F1014, BORDER);
        ctx.fillGradient(winX + 1, winY + 1, winX + winW - 1, winY + 34, accentAlpha(0x22), 0x00000000);
        rect(ctx, winX + 1, winY + 1, SIDEBAR_W - 1, winH - 2, (alpha << 24) | 0x0A0B0E);
        ctx.fill(winX + SIDEBAR_W, winY + 1, winX + SIDEBAR_W + 1, winY + winH - 1, BORDER);

        // Логотип
        rect(ctx, winX + 8, winY + 10, 28, 28, accent());
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("A").formatted(Formatting.BOLD),
                winX + 22, winY + 20, TEXT);

        // Боковая панель с иконками
        int hoverTab = -1;
        for (int i = 0; i < TAB_TITLES.length; i++) {
            int bx = winX + 6, by = tabY(i), bw = SIDEBAR_W - 12, bh = 30;
            boolean active = i == currentTab && query.trim().isEmpty();
            boolean hover = mx >= bx && mx < bx + bw && my >= by && my < by + bh;
            if (hover) hoverTab = i;
            if (active) {
                rect(ctx, bx, by, bw, bh, accentAlpha(0x33));
                ctx.fill(bx, by + 4, bx + 2, by + bh - 4, accent());
            } else if (hover) {
                rect(ctx, bx, by, bw, bh, 0x22FFFFFF);
            }
            ctx.drawItem(tabIcons[i], bx + (bw - 16) / 2, by + 7);
        }

        // Шапка: крупный заголовок
        List<Card> vis = visibleCards();
        String title = query.trim().isEmpty() ? TAB_TITLES[currentTab] : "Поиск";
        ctx.getMatrices().push();
        ctx.getMatrices().translate(winX + SIDEBAR_W + 14, winY + 10, 0);
        ctx.getMatrices().scale(1.4f, 1.4f, 1f);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(title).formatted(Formatting.BOLD), 0, 0, TEXT);
        ctx.getMatrices().pop();
        ctx.drawTextWithShadow(this.textRenderer, vis.size() + " " + plural(vis.size()),
                winX + SIDEBAR_W + 14, winY + 27, MUTED);
        ctx.fill(winX + SIDEBAR_W + 1, winY + HEADER_H - 6, winX + winW - 1, winY + HEADER_H - 5, BORDER);

        int sw = 140, sx = winX + winW - 12 - sw, sy = winY + 12;
        box(ctx, sx, sy, sw, 20, FIELD, searchField.isFocused() ? accent() : BORDER);

        // Строка-подсказка внизу окна
        ctx.fill(winX + SIDEBAR_W + 1, winY + winH - 20, winX + winW - 1, winY + winH - 19, BORDER);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                "R — вкл/выкл · Right Shift — меню · клик по заголовку — свернуть",
                winX + SIDEBAR_W + (winW - SIDEBAR_W) / 2, winY + winH - 14, MUTED);

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
            drawCard(ctx, m, cx0 + m.px, cy0 + m.py - (int) scroll, mx, my);
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

        // Поле поиска
        super.render(ctx, mx, my, delta);

        // Подпись раздела при наведении на иконку
        if (hoverTab >= 0 && drop == null) {
            int tw = this.textRenderer.getWidth(TAB_TITLES[hoverTab]) + 12;
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0, 0, 300);
            box(ctx, winX + SIDEBAR_W + 6, tabY(hoverTab) + 7, tw, 16, 0xFF12141A, accent());
            ctx.drawTextWithShadow(this.textRenderer, TAB_TITLES[hoverTab],
                    winX + SIDEBAR_W + 12, tabY(hoverTab) + 11, TEXT);
            ctx.getMatrices().pop();
        }

        // Выпадающий список рисуем поверх всего
        if (drop != null) drawDropdown(ctx, mx, my);
    }

    private static String plural(int n) {
        int a = n % 10, b = n % 100;
        if (a == 1 && b != 11) return "модуль";
        if (a >= 2 && a <= 4 && (b < 12 || b > 14)) return "модуля";
        return "модулей";
    }

    private boolean isCollapsed(Card m) {
        return query.trim().isEmpty() && collapsed.contains(m.name);
    }

    private void drawCard(DrawContext ctx, Card m, int x, int y, int mx, int my) {
        int w = m.pw;
        int h = m.height();
        m.sx = x;
        m.sy = y;

        box(ctx, x, y, w, h, CARD, BORDER);
        boolean enabled = m.on == null || m.on.getAsBoolean();
        ctx.fillGradient(x + 1, y + 1, x + w - 1, y + CARD_HEAD - 2, accentAlpha(enabled ? 0x1E : 0x08), 0x00000000);
        if (m.on != null && enabled) {
            ctx.fill(x, y + 6, x + 2, y + CARD_HEAD - 6, accent()); // метка включённого модуля
        }

        ctx.drawTextWithShadow(this.textRenderer, Text.literal(m.name).formatted(Formatting.BOLD),
                x + 9, y + 6, TEXT);
        int descW = w - 18 - (m.on != null ? 52 : 18);
        ctx.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(m.desc, descW),
                x + 9, y + 17, MUTED);

        // Стрелка сворачивания
        int cxr = x + w - 12 - (m.on != null ? 30 : 0);
        if (isCollapsed(m)) drawChevronRight(ctx, cxr + 1, y + 11, MUTED);
        else drawChevron(ctx, cxr - 1, y + 12, MUTED);

        if (m.on != null) {
            m.t = m.t < 0 ? (enabled ? 1f : 0f) : approach(m.t, enabled ? 1f : 0f);
            m.tx = x + w - 8 - 22;
            m.ty = y + 9;
            drawSwitch(ctx, m.tx, m.ty, m.t);
        }

        if (isCollapsed(m)) return;

        ctx.fill(x + 8, y + CARD_HEAD - 2, x + w - 8, y + CARD_HEAD - 1, BORDER);
        int ry = y + CARD_HEAD;
        for (Row r : m.shown) {
            r.x = x + 8;
            r.y = ry;
            r.w = w - 16;
            if (r.hit(mx, my)) rect(ctx, r.x - 4, r.y, r.w + 8, r.height(), 0x0DFFFFFF);
            r.draw(ctx, mx, my);
            ry += r.height();
        }
    }

    private void drawDropdown(DrawContext ctx, int mx, int my) {
        List<String> labels = drop.labels();
        int selected = drop.index();

        int maxText = 0;
        for (String s : labels) maxText = Math.max(maxText, this.textRenderer.getWidth(s));
        dropW = Math.max(Math.max(100, drop.boxW()), maxText + 28);
        dropH = labels.size() * DROP_ITEM_H + 6;
        dropX = drop.x + drop.w - dropW;
        dropY = drop.y + 20;
        if (dropY + dropH > this.height - 4) dropY = drop.y + 2 - dropH; // открываем вверх, если не помещается

        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);

        for (int i = 4; i >= 1; i--) rect(ctx, dropX - i, dropY - i, dropW + i * 2, dropH + i * 2, 0x14000000);
        box(ctx, dropX, dropY, dropW, dropH, 0xFF12141A, accentAlpha(0xFF));
        for (int i = 0; i < labels.size(); i++) {
            int iy = dropY + 3 + i * DROP_ITEM_H;
            boolean hover = mx >= dropX + 3 && mx < dropX + dropW - 3 && my >= iy && my < iy + DROP_ITEM_H;
            boolean sel = i == selected;
            if (sel) {
                rect(ctx, dropX + 3, iy, dropW - 6, DROP_ITEM_H, accentAlpha(0x40));
                ctx.fill(dropX + 3, iy + 3, dropX + 5, iy + DROP_ITEM_H - 3, accent());
            } else if (hover) {
                rect(ctx, dropX + 3, iy, dropW - 6, DROP_ITEM_H, 0x22FFFFFF);
            }
            ctx.drawTextWithShadow(this.textRenderer, labels.get(i), dropX + 11, iy + 4,
                    sel || hover ? TEXT : TEXT_DIM);
        }

        ctx.getMatrices().pop();
    }

    // ------------------------------------------------------------------ ввод

    private boolean inContent(double mx, double my) {
        return mx >= cx0 && mx < cx0 + cw && my >= cy0 && my < cy0 + ch;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            AimConfig cfg = AimConfig.INSTANCE;
            if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                cfg.attackKeyCode = -1; // снять клавишу
                cfg.attackKeyMouse = false;
            } else if (keyCode != GLFW.GLFW_KEY_ESCAPE) { // Esc просто отменяет выбор
                cfg.attackKeyCode = keyCode;
                cfg.attackKeyMouse = false;
            }
            listening = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Ждём клавишу: боковые и средняя кнопки мыши привязываются, ЛКМ и ПКМ отменяют выбор
        if (listening != null) {
            if (button >= 2) {
                AimConfig.INSTANCE.attackKeyCode = button;
                AimConfig.INSTANCE.attackKeyMouse = true;
            }
            listening = null;
            return true;
        }

        // Открытый список перехватывает клик первым
        if (drop != null) {
            CycleRow<?> d = drop;
            drop = null;
            if (mx >= dropX && mx < dropX + dropW && my >= dropY && my < dropY + dropH) {
                int i = (int) ((my - dropY - 3) / DROP_ITEM_H);
                if (i >= 0 && i < d.labels().size()) d.select(i);
                return true;
            }
            if (d.hit(mx, my)) return true; // повторный клик по строке просто закрывает список
        }

        if (super.mouseClicked(mx, my, button)) return true;
        searchField.setFocused(false);

        // Боковая панель
        for (int i = 0; i < TAB_TITLES.length; i++) {
            int bx = winX + 6, by = tabY(i), bw = SIDEBAR_W - 12, bh = 30;
            if (mx >= bx && mx < bx + bw && my >= by && my < by + bh && button == 0) {
                currentTab = i;
                scroll = 0;
                searchField.setText("");
                return true;
            }
        }

        if (!inContent(mx, my)) return false;

        for (Card m : visibleCards()) {
            if (m.on != null && button == 0
                    && mx >= m.tx - 2 && mx < m.tx + 24 && my >= m.ty - 2 && my < m.ty + 12) {
                m.setOn.accept(!m.on.getAsBoolean());
                return true;
            }
            // Клик по заголовку сворачивает / разворачивает карточку
            if (button == 0 && query.trim().isEmpty()
                    && mx >= m.sx && mx < m.sx + m.pw && my >= m.sy && my < m.sy + CARD_HEAD) {
                if (!collapsed.remove(m.name)) collapsed.add(m.name);
                return true;
            }
            if (isCollapsed(m)) continue;
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
            drop = null;
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
        int sx, sy;                    // положение карточки на экране
        float t = -1f;                 // анимация переключателя

        Card(int tab, String name, String desc, BooleanSupplier on, Consumer<Boolean> setOn) {
            this.tab = tab;
            this.name = name;
            this.desc = desc;
            this.on = on;
            this.setOn = setOn;
        }

        int height() {
            if (isCollapsed(this)) return CARD_HEAD;
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
            return mx >= x && mx < x + w && my >= y && my < y + height() && inContent(mx, my)
                    && drop == null;
        }
    }

    private final class ToggleRow extends Row {
        final BooleanSupplier get;
        final Consumer<Boolean> set;
        float t = -1f;

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
            boolean on = get.getAsBoolean();
            t = t < 0 ? (on ? 1f : 0f) : approach(t, on ? 1f : 0f);
            ctx.drawTextWithShadow(textRenderer, label, x, y + 6, hit(mx, my) ? TEXT : TEXT_DIM);
            drawSwitch(ctx, x + w - 22, y + 5, t);
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
            String value = (decimals == 0 ? String.valueOf(Math.round(v))
                    : String.format(Locale.ROOT, "%." + decimals + "f", v)) + suffix;

            ctx.drawTextWithShadow(textRenderer, label, x, y + 3, hit(mx, my) || dragging == this ? TEXT : TEXT_DIM);
            int vw = textRenderer.getWidth(value) + 8;
            box(ctx, x + w - vw, y, vw, 13, FIELD, BORDER);
            ctx.drawTextWithShadow(textRenderer, value, x + w - vw + 4, y + 3, TEXT);

            double t = MathHelper.clamp((v - min) / (max - min), 0, 1);
            int ty = y + 17;
            int fw = (int) (w * t);
            boolean active = dragging == this || (hit(mx, my) && my >= y + 9);

            rect(ctx, x, ty, w, 4, TRACK);
            if (fw > 0) {
                rect(ctx, x, ty, Math.max(fw, 3), 4, accent());
                ctx.fill(x + 1, ty, x + Math.max(fw, 3) - 1, ty + 1, lerpColor(accent(), 0xFFFFFFFF, 0.35f));
            }
            if (active) rect(ctx, x + fw - 6, ty - 4, 12, 12, accentAlpha(0x40)); // свечение
            rect(ctx, x + fw - 3, ty - 2, 6, 8, 0xFFFFFFFF);
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

    /** Строка с выпадающим списком: по клику открывается список всех значений. */
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

        List<String> labels() {
            List<String> out = new ArrayList<>();
            for (T v : values) out.add(name.apply(v));
            return out;
        }

        int index() {
            return Math.max(0, values.indexOf(get.get()));
        }

        void select(int i) {
            set.accept(values.get(i));
        }

        int boxW() {
            return Math.max(72, textRenderer.getWidth(name.apply(get.get())) + 26);
        }

        @Override
        int height() {
            return 22;
        }

        @Override
        void draw(DrawContext ctx, int mx, int my) {
            boolean hover = hit(mx, my) || drop == this;
            ctx.drawTextWithShadow(textRenderer, label, x, y + 7, hover ? TEXT : TEXT_DIM);
            String s = name.apply(get.get());
            int bw = boxW();
            int bx = x + w - bw;
            box(ctx, bx, y + 2, bw, 18, FIELD, drop == this ? accent() : BORDER);
            ctx.drawTextWithShadow(textRenderer, s, bx + 7, y + 7, TEXT);
            drawChevron(ctx, bx + bw - 11, y + 9, drop == this ? accent() : MUTED);
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button == 0 && hit(mx, my)) {
                drop = this;
                return true;
            }
            return false;
        }
    }

    private final class SwatchRow extends Row {
        final IntSupplier get;
        final IntConsumer set;

        SwatchRow(String label, IntSupplier get, IntConsumer set) {
            super(label);
            this.get = get;
            this.set = set;
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
                rect(ctx, sx, y + 6, 12, 12, 0xFF000000 | SWATCHES[i]);
                if ((get.getAsInt() & 0xFFFFFF) == SWATCHES[i]) {
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
                    set.accept(SWATCHES[i]);
                    return true;
                }
            }
            return false;
        }
    }

    /** Строка выбора клавиши: клик, затем нажмите клавишу (Delete - снять, Esc - отмена). */
    private final class BindRow extends Row {
        BindRow(String label) {
            super(label);
        }

        @Override
        int height() {
            return 22;
        }

        private String keyName() {
            AimConfig cfg = AimConfig.INSTANCE;
            if (cfg.attackKeyCode < 0) return "Не задана";
            InputUtil.Key key = (cfg.attackKeyMouse ? InputUtil.Type.MOUSE : InputUtil.Type.KEYSYM)
                    .createFromCode(cfg.attackKeyCode);
            return key.getLocalizedText().getString();
        }

        @Override
        void draw(DrawContext ctx, int mx, int my) {
            boolean waiting = listening == this;
            ctx.drawTextWithShadow(textRenderer, label, x, y + 7, hit(mx, my) || waiting ? TEXT : TEXT_DIM);
            String s = waiting ? "Нажмите клавишу..." : keyName();
            int bw = Math.max(72, textRenderer.getWidth(s) + 16);
            int bx = x + w - bw;
            box(ctx, bx, y + 2, bw, 18, FIELD, waiting ? accent() : BORDER);
            ctx.drawCenteredTextWithShadow(textRenderer, s, bx + bw / 2, y + 7, waiting ? TEXT : TEXT_DIM);
        }

        @Override
        boolean click(double mx, double my, int button) {
            if (button == 0 && hit(mx, my)) {
                listening = this;
                return true;
            }
            return false;
        }
    }
}
