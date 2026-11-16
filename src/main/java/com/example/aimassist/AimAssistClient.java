package com.example.aimassist;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.Random;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

public class AimAssistClient implements ClientModInitializer {
    private static final String CATEGORY = "category.aimassist";

    private static KeyBinding openGuiKey;
    private static KeyBinding toggleKey;

    // Нужен, чтобы скорость наведения не зависела от FPS
    private long lastFrameNanos = System.nanoTime();

    // Цель ищется раз в тик (20 раз/сек) и кэшируется, а каждый кадр считается только поворот.
    // Это главная оптимизация: поиск и raycast'ы по всем игрокам не выполняются на каждом кадре.
    private PlayerEntity target;

    // Шаг поворота предыдущего кадра, для сглаживания разгона и торможения камеры
    private float lastStepYaw, lastStepPitch;

    // Умный режим автоудара: сколько тиков уже ждём падения ради крита, и пауза между подсказками
    private int smartWait;
    private int statusCooldown;

    // Результат rotationTo, чтобы не создавать массив на каждый вызов
    private float rotYaw, rotPitch;

    // Внутри этого угла (градусы) камеру уже не двигаем: прицел в центре цели
    private static final float DEADZONE = 0.15f;

    @Override
    public void onInitializeClient() {
        AimConfig.load();

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimassist.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimassist.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new AimConfigScreen(null));
                }
            }
            while (toggleKey.wasPressed()) {
                AimConfig cfg = AimConfig.INSTANCE;
                cfg.enabled = !cfg.enabled;
                AimConfig.save();
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("Aim Assist: ")
                                    .append(Text.literal(cfg.enabled ? "ON" : "OFF")
                                            .formatted(cfg.enabled ? Formatting.GREEN : Formatting.RED)),
                            true);
                }
            }
            autoAttack(client);
            updateTarget(client);
        });

        // Поворот камеры каждый кадр, а не каждый тик, иначе камера дёргается
        WorldRenderEvents.START.register(context -> onFrame(MinecraftClient.getInstance()));

        // Обводка цели рисуется после сущностей
        WorldRenderEvents.AFTER_ENTITIES.register(this::renderEsp);
    }

    // ---------------------------------------------------------------- цель (раз в тик)

    private void updateTarget(MinecraftClient client) {
        AimConfig cfg = AimConfig.INSTANCE;
        ClientPlayerEntity me = client.player;
        if (!cfg.enabled || me == null || client.world == null || client.currentScreen != null
                || !me.isAlive() || me.isSpectator()) {
            target = null;
            return;
        }

        // Цель «залипает»: пока она жива, в зоне и видима, аим не перескакивает на другого игрока,
        // даже если тот встал ближе к прицелу. Новая цель ищется только когда старая потеряна.
        if (target != null && stillValid(client, me, cfg, target)) return;
        target = findTarget(client, me, cfg);
    }

    private boolean stillValid(MinecraftClient client, ClientPlayerEntity me, AimConfig cfg, PlayerEntity t) {
        if (t.isRemoved() || !t.isAlive() || t.isSpectator()) return false;

        // Небольшой запас (гистерезис), чтобы цель не терялась на границе зоны
        double range = cfg.range * 1.1;
        if (me.squaredDistanceTo(t) > range * range) return false;

        Vec3d eye = me.getEyePos();
        Vec3d point = aimPoint(t, 1.0f);
        rotationTo(eye, point);
        float yawDiff = MathHelper.wrapDegrees(rotYaw - me.getYaw());
        float pitchDiff = rotPitch - me.getPitch();
        if (Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff) > cfg.fov / 2.0 * 1.25) return false;

        return !cfg.visibleOnly || canSee(client, me, eye, point);
    }

    private PlayerEntity findTarget(MinecraftClient client, ClientPlayerEntity me, AimConfig cfg) {
        Vec3d eye = me.getEyePos();
        double rangeSq = cfg.range * cfg.range;
        double halfFov = cfg.fov / 2.0;

        PlayerEntity best = null;
        double bestAngle = Double.MAX_VALUE;

        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == me || !p.isAlive() || p.isSpectator()) continue;
            if (me.squaredDistanceTo(p) > rangeSq) continue; // дешёвая проверка идёт первой

            Vec3d point = aimPoint(p, 1.0f);
            rotationTo(eye, point);
            float yawDiff = MathHelper.wrapDegrees(rotYaw - me.getYaw());
            float pitchDiff = rotPitch - me.getPitch();
            double angle = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);

            if (angle > halfFov || angle >= bestAngle) continue;
            // raycast - самая дорогая проверка, поэтому только для лучшего кандидата
            if (cfg.visibleOnly && !canSee(client, me, eye, point)) continue;

            bestAngle = angle;
            best = p;
        }
        return best;
    }

    private boolean canSee(MinecraftClient client, ClientPlayerEntity me, Vec3d from, Vec3d to) {
        HitResult hit = client.world.raycast(new RaycastContext(
                from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                me));
        return hit.getType() == HitResult.Type.MISS;
    }

    // ---------------------------------------------------------------- наведение (каждый кадр)

    private void onFrame(MinecraftClient client) {
        long now = System.nanoTime();
        double dt = Math.min((now - lastFrameNanos) / 1_000_000_000.0, 0.1); // секунды, не больше 0.1
        lastFrameNanos = now;

        AimConfig cfg = AimConfig.INSTANCE;
        ClientPlayerEntity me = client.player;
        PlayerEntity t = target;

        if (!cfg.enabled || me == null || t == null || !t.isAlive() || t.isRemoved()
                || client.currentScreen != null || !me.isAlive() || me.isSpectator()
                || (cfg.holdAttackOnly && !client.options.attackKey.isPressed())) {
            lastStepYaw = 0;
            lastStepPitch = 0;
            return;
        }

        // Интерполированные позиции: камера движется плавно между тиками, а не рывками
        float td = client.getRenderTickCounter().getTickDelta(false);
        Vec3d eye = me.getCameraPosVec(td);
        rotationTo(eye, aimPoint(t, td));

        float yawDiff = MathHelper.wrapDegrees(rotYaw - me.getYaw());
        float pitchDiff = rotPitch - me.getPitch();

        float stepYaw = axisStep(yawDiff, cfg.speedX, dt, cfg.maxStep, cfg.mode);
        float stepPitch = axisStep(pitchDiff, cfg.speedY, dt, cfg.maxStep, cfg.mode);

        // Сглаживание разгона/торможения: шаг не прыгает, а плавно меняется от кадра к кадру
        if (cfg.mode != AimMode.SNAP) {
            double a = 1.0 - Math.pow(1.0 - MathHelper.clamp(cfg.accel, 0.05, 1.0), dt * 20.0);
            stepYaw = lastStepYaw + (stepYaw - lastStepYaw) * (float) a;
            stepPitch = lastStepPitch + (stepPitch - lastStepPitch) * (float) a;
        }

        // Доводим ровно до центра и не дальше: шаг никогда не больше остатка пути
        stepYaw = clampToward(stepYaw, yawDiff);
        stepPitch = clampToward(stepPitch, pitchDiff);

        lastStepYaw = stepYaw;
        lastStepPitch = stepPitch;

        // changeLookDirection сам обновляет prevYaw/prevPitch, поэтому камера не дрожит.
        // Внутри он умножает на 0.15, поэтому делим на это число.
        me.changeLookDirection(stepYaw / 0.15, stepPitch / 0.15);
    }

    /** Не даёт перелететь цель: шаг в ту же сторону и не больше оставшегося угла. */
    private static float clampToward(float step, float diff) {
        if (Math.abs(diff) < DEADZONE) return 0f;
        return diff > 0 ? MathHelper.clamp(step, 0f, diff) : MathHelper.clamp(step, diff, 0f);
    }

    /** Шаг поворота по одной оси за этот кадр. diff - сколько градусов осталось до цели. */
    private static float axisStep(float diff, double speed, double dt, double maxStep, AimMode mode) {
        if (mode == AimMode.SNAP) return diff;

        double ticks = dt * 20.0; // скорость не зависит от FPS
        double s = MathHelper.clamp(speed, 0.01, 1.0);
        double limit = Math.max(maxStep, 0.1) * ticks;
        double abs = Math.abs(diff);
        double step;

        switch (mode) {
            case LINEAR -> step = Math.signum(diff) * Math.min(abs, s * limit);
            case EASE -> {
                double factor = 1.0 - Math.pow(1.0 - s, ticks);
                double far = abs / (abs + 8.0); // 0 возле цели, ближе к 1 издалека
                step = diff * factor * (0.25 + 0.75 * far);
            }
            default -> step = diff * (1.0 - Math.pow(1.0 - s, ticks)); // SMOOTH
        }
        return (float) MathHelper.clamp(step, -limit, limit);
    }

    // ---------------------------------------------------------------- автоудар (раз в тик)

    /**
     * Автоудар: если прицел (реальный crosshairTarget, то есть хитбокс) смотрит на игрока
     * и выполнены условия из настроек, бьём.
     */
    private void autoAttack(MinecraftClient client) {
        AimConfig cfg = AimConfig.INSTANCE;
        if (statusCooldown > 0) statusCooldown--;
        if (!cfg.autoAttack) return;

        ClientPlayerEntity me = client.player;
        if (me == null || client.world == null || client.interactionManager == null) return;
        if (client.currentScreen != null || !me.isAlive() || me.isSpectator()) return;

        if (!(client.crosshairTarget instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof PlayerEntity victim) || !victim.isAlive()) {
            smartWait = 0;
            if (target != null) {
                status(me, String.format(java.util.Locale.ROOT,
                        "цель в %.1f бл., но не в прицеле или дальше досягаемости (~3 бл.)", me.distanceTo(target)));
            }
            return;
        }

        // Только с нужным оружием в руке
        if (!weaponAllowed(me, cfg)) {
            status(me, "в руке не то оружие");
            return;
        }

        // Не бить, пока ешь / пьёшь / блокируешь щитом / тянешь лук
        if (cfg.noHitWhileUsing && me.isUsingItem()) {
            status(me, "использую предмет (еда, щит, лук)");
            return;
        }

        // Заряд оружия: полный взмах или заданный минимум
        float needCharge = cfg.fullChargeOnly ? 1.0f : (float) cfg.attackCharge;
        float charge = me.getAttackCooldownProgress(0.5f);
        if (charge < needCharge) {
            status(me, "заряд удара " + Math.round(charge * 100) + "%");
            return;
        }

        // Умный режим: решает, бить сразу (комбо) или подождать падения (крит)
        if (cfg.attackMode == AttackMode.SMART && !smartAllows(client, me, cfg)) return;

        // Только крит: падаем, не на земле, не в воде, не на лестнице, не в спринте
        if (cfg.critOnly && !canCrit(me)) {
            status(me, "жду крит (только криты)");
            return;
        }

        client.interactionManager.attackEntity(me, victim);
        me.swingHand(Hand.MAIN_HAND);
        smartWait = 0;

        // Если это сообщение видно, а урона нет, значит мод ударил, но сервер удар не принял
        statusCooldown = 0;
        status(me, "удар отправлен: " + victim.getName().getString());
    }

    /**
     * Умный режим. Намерение игрока определяется по его действиям:
     *  - на земле без прыжка, или в спринте: идёт комбо, бьём сразу, как зарядился взмах;
     *  - зажат прыжок или игрок в воздухе (не в спринте): идёт крит, ждём падения
     *    (удар на подъёме не критует и зря сбрасывает заряд). Если крит не дождались за
     *    заданное число тиков, бьём обычным ударом.
     */
    private boolean smartAllows(MinecraftClient client, ClientPlayerEntity me, AimConfig cfg) {
        boolean jumpHeld = client.options.jumpKey.isPressed();
        boolean airborne = !me.isOnGround() && !me.isClimbing() && !me.isTouchingWater() && !me.hasVehicle();
        boolean wantsCrit = (jumpHeld || airborne) && !me.isSprinting();

        if (!wantsCrit || canCrit(me)) {
            smartWait = 0;
            return true; // комбо или крит уже готов
        }

        smartWait++;
        if (smartWait > Math.max(cfg.smartMaxWait, 1)) {
            smartWait = 0;
            status(me, "крит не дождался, бью обычным");
            return true;
        }
        status(me, "умный режим: жду падения для крита");
        return false;
    }

    /** Короткое сообщение над хотбаром о том, почему автоудар сейчас не бьёт (раз в полсекунды). */
    private void status(ClientPlayerEntity me, String message) {
        if (!AimConfig.INSTANCE.attackDebug || statusCooldown > 0) return;
        statusCooldown = 10;
        me.sendMessage(Text.literal("Автоудар: " + message).formatted(Formatting.GRAY), true);
    }

    private static boolean weaponAllowed(ClientPlayerEntity me, AimConfig cfg) {
        ItemStack stack = me.getMainHandStack();
        return switch (cfg.weapon) {
            case ANY -> true;
            case SWORD -> stack.isIn(ItemTags.SWORDS);
            case SWORD_AXE -> stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES);
        };
    }

    /** Условия критического удара в ванильной механике. */
    private static boolean canCrit(ClientPlayerEntity me) {
        return me.fallDistance > 0.0F
                && !me.isOnGround()
                && !me.isClimbing()
                && !me.isTouchingWater()
                && !me.hasVehicle()
                && !me.isSprinting()
                && !me.hasStatusEffect(StatusEffects.BLINDNESS);
    }

    // ---------------------------------------------------------------- геометрия

    /** Точка прицеливания: всегда центр хитбокса цели. */
    private static Vec3d aimPoint(PlayerEntity target, float tickDelta) {
        return target.getLerpedPos(tickDelta).add(0, target.getHeight() * 0.5, 0);
    }

    /** Считает yaw/pitch в градусах (система координат Minecraft) в поля rotYaw/rotPitch. */
    private void rotationTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        rotYaw = MathHelper.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        rotPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -90f, 90f);
    }

    // ---------------------------------------------------------------- обводка цели

    private void renderEsp(WorldRenderContext context) {
        AimConfig cfg = AimConfig.INSTANCE;
        PlayerEntity t = target;
        if (!cfg.enabled || !cfg.espEnabled || t == null || t.isRemoved()) return;
        if (context.consumers() == null || context.matrixStack() == null) return;

        float td = MinecraftClient.getInstance().getRenderTickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        Vec3d shift = t.getLerpedPos(td).subtract(t.getPos());
        Box box = t.getBoundingBox().offset(shift);

        float r = ((cfg.espColor >> 16) & 255) / 255f;
        float g = ((cfg.espColor >> 8) & 255) / 255f;
        float b = (cfg.espColor & 255) / 255f;

        MatrixStack ms = context.matrixStack();
        VertexConsumer lines = context.consumers().getBuffer(RenderLayer.getLines());
        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);

        double seconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0;
        double speed = Math.max(cfg.espSpeed, 0.05);

        // Основная рамка
        if (cfg.espFrame) {
            VertexRendering.drawBox(ms, lines, box.expand(0.01), r, g, b, 0.95f);
        }

        switch (cfg.espStyle) {
            case PULSE -> {
                // От рамки расходится затухающая волна
                float phase = (float) ((seconds * speed) % 1.0);
                VertexRendering.drawBox(ms, lines, box.expand(0.02 + 0.30 * phase), r, g, b, 0.9f * (1f - phase));
            }
            case WAVE -> {
                // Кольцо поднимается от ног к голове, за ним тянется короткий след
                double phase = (seconds * speed * 0.8) % 1.0;
                double height = box.getLengthY();
                for (int k = 0; k < 4; k++) {
                    double p = phase - k * 0.05;
                    if (p < 0) continue;
                    double y = box.minY + p * height;
                    float a = (float) (Math.sin(p * Math.PI) * (1.0 - k * 0.25));
                    Box ring = new Box(box.minX - 0.04, y, box.minZ - 0.04, box.maxX + 0.04, y + 0.03, box.maxZ + 0.04);
                    VertexRendering.drawBox(ms, lines, ring, r, g, b, Math.max(a, 0f));
                }
            }
            case LIGHTNING -> drawLightning(ms.peek(), lines, box, cfg);
            default -> { }
        }

        ms.pop();
    }

    // ---------------------------------------------------------------- молнии

    /**
     * Мелкие молнии «бьют током» по поверхности тела. Рисунок молний обновляется несколько раз
     * в секунду (скорость задаёт настройка), поэтому они мерцают и трещат.
     */
    private static void drawLightning(MatrixStack.Entry entry, VertexConsumer lines, Box box, AimConfig cfg) {
        long bucket = (long) (System.currentTimeMillis() * Math.max(cfg.espSpeed, 0.2) / 70.0);
        Random rnd = new Random(bucket * 1_000_003L + 17L);

        int rgb = cfg.espColor & 0xFFFFFF;
        int bright = mixWhite(rgb, 0.6f);
        double e = 0.04; // молнии чуть снаружи модели, чтобы их не закрывало тело
        double[] lo = {box.minX - e, box.minY - e, box.minZ - e};
        double[] hi = {box.maxX + e, box.maxY + e, box.maxZ + e};

        int bolts = Math.max(1, cfg.espBolts);
        for (int i = 0; i < bolts; i++) {
            // Выбираем грань бокса и две точки на ней: молния ползёт по поверхности
            int axis = rnd.nextInt(3);
            double fixed = rnd.nextBoolean() ? hi[axis] : lo[axis];
            Vec3d a = facePoint(rnd, lo, hi, axis, fixed);
            Vec3d b = facePoint(rnd, lo, hi, axis, fixed);
            if (a.distanceTo(b) < 0.35) b = facePoint(rnd, lo, hi, axis, fixed);

            int color = 0xFF000000 | (rnd.nextFloat() < 0.4f ? bright : rgb);
            int segments = 5 + rnd.nextInt(3);
            Vec3d prev = a;
            for (int sgm = 1; sgm <= segments; sgm++) {
                Vec3d next = a.lerp(b, sgm / (double) segments);
                if (sgm < segments) next = jitter(rnd, next, lo, hi, axis, 0.11);
                line(entry, lines, prev, next, color);

                // Иногда от излома отходит короткая веточка
                if (sgm < segments && rnd.nextFloat() < 0.25f) {
                    Vec3d tip = jitter(rnd, next, lo, hi, axis, 0.22);
                    line(entry, lines, next, tip, color);
                }
                prev = next;
            }
        }
    }

    private static Vec3d facePoint(Random rnd, double[] lo, double[] hi, int axis, double fixed) {
        double[] v = new double[3];
        for (int k = 0; k < 3; k++) {
            v[k] = k == axis ? fixed : lo[k] + (hi[k] - lo[k]) * rnd.nextDouble();
        }
        return new Vec3d(v[0], v[1], v[2]);
    }

    /** Сдвигает точку вдоль плоскости грани (не отрываясь от неё). */
    private static Vec3d jitter(Random rnd, Vec3d p, double[] lo, double[] hi, int axis, double amount) {
        double[] v = {p.x, p.y, p.z};
        for (int k = 0; k < 3; k++) {
            if (k == axis) continue;
            v[k] = MathHelper.clamp(v[k] + (rnd.nextDouble() - 0.5) * 2.0 * amount, lo[k], hi[k]);
        }
        return new Vec3d(v[0], v[1], v[2]);
    }

    private static int mixWhite(int rgb, float t) {
        int r = (int) (((rgb >> 16) & 255) * (1 - t) + 255 * t);
        int g = (int) (((rgb >> 8) & 255) * (1 - t) + 255 * t);
        int b = (int) ((rgb & 255) * (1 - t) + 255 * t);
        return (r << 16) | (g << 8) | b;
    }

    /** Один отрезок линии (RenderLayer.getLines требует нормаль вдоль линии). */
    private static void line(MatrixStack.Entry entry, VertexConsumer vc, Vec3d a, Vec3d b, int argb) {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6) return;
        float nx = (float) (dx / len), ny = (float) (dy / len), nz = (float) (dz / len);
        vc.vertex(entry, (float) a.x, (float) a.y, (float) a.z).color(argb).normal(entry, nx, ny, nz);
        vc.vertex(entry, (float) b.x, (float) b.y, (float) b.z).color(argb).normal(entry, nx, ny, nz);
    }
}
