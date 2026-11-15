package com.example.aimassist;

/** Логика наведения: как именно камера догоняет цель. */
public enum AimMode {
    SMOOTH("Плавная"),            // каждый кадр проходим долю оставшегося пути
    LINEAR("Линейная"),           // постоянная скорость поворота
    EASE("Мягкая доводка"),       // быстро издалека, медленно возле цели
    SNAP("Мгновенная");           // сразу на цель

    public final String label;

    AimMode(String label) {
        this.label = label;
    }
}
