package com.example.aimassist;

/** Стиль эффекта на цели. */
public enum EspStyle {
    STATIC("Статичная"),   // только рамка
    PULSE("Pulse"),        // от рамки расходится затухающая волна
    WAVE("Волна"),         // кольцо-сканер поднимается от ног к голове
    LIGHTNING("Молнии");   // мелкие молнии бьют током по телу

    public final String label;

    EspStyle(String label) {
        this.label = label;
    }
}
