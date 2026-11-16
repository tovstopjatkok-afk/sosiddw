package com.example.aimassist;

/** Как работает клавиша автоудара. */
public enum BindMode {
    TOGGLE("Переключатель"),   // нажал - включил, нажал ещё раз - выключил
    HOLD("Удержание");         // бьёт, только пока клавиша зажата

    public final String label;

    BindMode(String label) {
        this.label = label;
    }
}
