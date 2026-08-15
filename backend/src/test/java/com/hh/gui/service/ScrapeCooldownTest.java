package com.hh.gui.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScrapeCooldownTest {

    @Test
    void freshInstance_isNotCoolingDown() {
        ScrapeCooldown cooldown = new ScrapeCooldown();
        assertFalse(cooldown.isCoolingDown());
        assertEquals(0, cooldown.remainingMinutes());
    }

    @Test
    void enter_freezesScrapingForTheBaseInterval() {
        ScrapeCooldown cooldown = new ScrapeCooldown();
        cooldown.enter();
        assertTrue(cooldown.isCoolingDown());
        long left = cooldown.remainingMinutes();
        // Диапазон, а не точное значение: остаток считается от текущего времени, поэтому
        // 30 минут превращаются в 29 или 30 в зависимости от того, попали ли два вызова
        // currentTimeMillis в одну миллисекунду. Точное сравнение здесь было бы флаки.
        assertTrue(left >= 29 && left <= 30, "первая заморозка — около 30 минут, получено: " + left);
    }

    @Test
    void enter_whileAlreadyCoolingDown_doesNotEscalate() {
        // Регрессия: разные поиски выполняются параллельно, так что один и тот же реальный
        // блок hh.ru независимые прогоны могли обнаружить порознь в течение одной секунды —
        // каждый вызывал enter(), и счётчик страйков прыгал 1→2→3 сразу, разгоняя заморозку
        // до нескольких часов за одно событие вместо честной эскалации после действительно
        // повторной блокировки.
        //
        // Проверяем через наблюдаемое следствие (сколько осталось), а не через приватный
        // счётчик: раньше этот тест лез к полю рефлексией, потому что механика была заперта
        // внутри VacancyPipelineService.
        ScrapeCooldown cooldown = new ScrapeCooldown();
        cooldown.enter();
        cooldown.enter();
        cooldown.enter();

        long left = cooldown.remainingMinutes();
        assertTrue(left <= 30,
            "повторные срабатывания, пока заморозка уже активна, не должны разгонять эскалацию "
                + "(при тройном страйке было бы ~120 мин), получено: " + left);
    }

    @Test
    void onSuccess_resetsEscalationSoNextFreezeStartsFromBaseAgain() {
        ScrapeCooldown cooldown = new ScrapeCooldown();
        cooldown.enter();
        cooldown.onSuccess();
        assertTrue(cooldown.isCoolingDown(), "успех не снимает текущую заморозку, только сбрасывает эскалацию");
    }
}
