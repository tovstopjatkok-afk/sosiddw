package com.example.aimassist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class AimConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("aimassist.json");

    public static AimConfig INSTANCE = new AimConfig();

    public boolean enabled = false;
    public double range = 20.0;        // блоки
    public double fov = 90.0;          // градусы (полный угол обзора, в котором ищем цель)
    public AimMode mode = AimMode.SMOOTH; // логика наведения
    public double accel = 0.60;        // плавность разгона камеры 0.05 .. 1.0 (1.0 = без сглаживания)
    public double speedX = 0.30;       // скорость по горизонтали (влево-вправо), 0.01 .. 1.0
    public double speedY = 0.30;       // скорость по вертикали (вверх-вниз), 0.01 .. 1.0
    public double maxStep = 20.0;      // максимум градусов за тик
    public boolean visibleOnly = true; // только цели в прямой видимости
    public boolean holdAttackOnly = false; // наводиться только при зажатой ЛКМ
    public boolean autoAttack = false;     // бить, когда прицел на хитбоксе игрока
    public WeaponFilter weapon = WeaponFilter.ANY; // с каким оружием в руке бить
    public boolean fullChargeOnly = true;  // только полностью заряженный удар (полный взмах)
    public double attackCharge = 0.9;      // мин. заряд, если fullChargeOnly выключен (0.1 .. 1.0)
    public boolean critOnly = false;       // бить только критическим ударом (падение, не на земле)
    public boolean noHitWhileUsing = true; // не бить, пока ешь / пьёшь / держишь щит / натягиваешь лук

    // Обводка цели (ESP)
    public boolean espEnabled = true;
    public EspStyle espStyle = EspStyle.PULSE;
    public boolean espFrame = true;        // базовая рамка вокруг цели
    public double espSpeed = 1.0;          // скорость эффекта
    public int espBolts = 6;               // количество молний (для стиля «Молнии»)
    public int espColor = 0x4FC3F7;        // RGB

    // Оформление меню
    public int accent = 0x8B5CF6;          // цвет акцента RGB
    public double windowOpacity = 0.92;    // прозрачность окна 0.3 .. 1.0

    public static void load() {
        if (!Files.exists(FILE)) {
            save();
            return;
        }
        try (Reader r = Files.newBufferedReader(FILE)) {
            AimConfig loaded = GSON.fromJson(r, AimConfig.class);
            if (loaded != null) INSTANCE = loaded;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(FILE)) {
            GSON.toJson(INSTANCE, w);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
