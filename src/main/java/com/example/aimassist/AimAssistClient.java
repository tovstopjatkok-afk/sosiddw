package com.example.aimassist;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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

    // Результат rotationTo, чтобы не создавать массив на каждый вызов
    private float rotYaw, rotPitch;

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
        target = findTarget(client, me, cfg);
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

            Vec3d point = aimPoint(p, cfg, 1.0f);
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
        rotationTo(eye, aimPoint(t, cfg, td));

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
        lastStepYaw = stepYaw;
        lastStepPitch = stepPitch;

        // changeLookDirection сам обновляет prevYaw/prevPitch, поэтому камера не дрожит.
        // Внутри он умножает на 0.15, поэтому делим на это число.
        me.changeLookDirection(stepYaw / 0.15, stepPitch / 0.15);
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
        if (!cfg.autoAttack) return;

        ClientPlayerEntity me = client.player;
        if (me == null || client.world == null || client.interactionManager == null) return;
        if (client.currentScreen != null || !me.isAlive() || me.isSpectator()) return;

        if (!(client.crosshairTarget instanceof EntityHitResult hit)) return;
        if (!(hit.getEntity() instanceof PlayerEntity victim) || !victim.isAlive()) return;

        // Не бить, пока ешь / пьёшь / блокируешь щитом / тянешь лук
        if (cfg.noHitWhileUsing && me.isUsingItem()) return;

        // Заряд оружия: полный взмах или заданный минимум
        float needCharge = cfg.fullChargeOnly ? 1.0f : (float) cfg.attackCharge;
        if (me.getAttackCooldownProgress(0.5f) < needCharge) return;

        // Только крит: падаем, не на земле, не в воде, не на лестнице, не в спринте
        if (cfg.critOnly && !canCrit(me)) return;

        client.interactionManager.attackEntity(me, victim);
        me.swingHand(Hand.MAIN_HAND);
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

    private static Vec3d aimPoint(PlayerEntity target, AimConfig cfg, float tickDelta) {
        double h = cfg.aimAtHead ? target.getEyeHeight(target.getPose()) : target.getHeight() * 0.5;
        return target.getLerpedPos(tickDelta).add(0, h, 0);
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
}
