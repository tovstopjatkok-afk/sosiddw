package com.example.aimassist;

/** С каким оружием в руке разрешён автоудар. */
public enum WeaponFilter {
    ANY("Любое"),
    SWORD("Только меч"),
    SWORD_AXE("Меч и топор");

    public final String label;

    WeaponFilter(String label) {
        this.label = label;
    }
}
