package com.example.aimassist;

/** Режим автоудара. */
public enum AttackMode {
    NORMAL("Обычный"),   // бьёт, как только выполнены условия из настроек
    SMART("Умный");      // сам понимает: комбо на земле или крит с прыжка

    public final String label;

    AttackMode(String label) {
        this.label = label;
    }
}
