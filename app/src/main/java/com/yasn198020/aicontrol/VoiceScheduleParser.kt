package com.yasn198020.aicontrol

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class VoiceSchedule(
    val commandText: String,
    val executeAtMillis: Long,
    val spokenTime: String
)

object VoiceScheduleParser {
    private val numberWords = mapOf(
        "ноль" to 0L,
        "один" to 1L,
        "одна" to 1L,
        "одно" to 1L,
        "одну" to 1L,
        "два" to 2L,
        "две" to 2L,
        "три" to 3L,
        "четыре" to 4L,
        "пять" to 5L,
        "шесть" to 6L,
        "семь" to 7L,
        "восемь" to 8L,
        "девять" to 9L,
        "десять" to 10L,
        "одиннадцать" to 11L,
        "двенадцать" to 12L,
        "тринадцать" to 13L,
        "четырнадцать" to 14L,
        "пятнадцать" to 15L,
        "шестнадцать" to 16L,
        "семнадцать" to 17L,
        "восемнадцать" to 18L,
        "девятнадцать" to 19L,
        "двадцать" to 20L,
        "тридцать" to 30L,
        "сорок" to 40L,
        "пятьдесят" to 50L,
        "шестьдесят" to 60L
    )

    private val relativePattern = Regex(
        """\bчерез\s+([0-9]+|[а-яё]+(?:\s+[а-яё]+)?)\s+(секунд\w*|сек\w*|минут\w*|мин\w*|час\w*|ч\b|дн\w*|день|дня|дней)\b""",
        RegexOption.IGNORE_CASE
    )

    private val tomorrowPattern = Regex(
        """\bзавтра\s+в\s+(\d{1,2})(?:(?::|\.)(\d{2}))?(?:\s+(утра|дня|вечера|ночи))?\b""",
        RegexOption.IGNORE_CASE
    )

    private val todayPattern = Regex(
        """\bв\s+(\d{1,2})(?:(?::|\.)(\d{2}))(?:\s*(утра|дня|вечера|ночи))?\b""",
        RegexOption.IGNORE_CASE
    )

    private val todayHourPattern = Regex(
        """\bв\s+(\d{1,2})\s+(утра|дня|вечера|ночи)\b""",
        RegexOption.IGNORE_CASE
    )

    private val simpleRelativePattern = Regex(
        """\bчерез\s+(час|минуту|минут|секунду|секунды|день)\b""",
        RegexOption.IGNORE_CASE
    )

    fun hasScheduleIntent(command: String): Boolean {
        val text = normalize(command)
        if (text.isBlank()) return false

        return Regex(
            """\bчерез\s+(?:[0-9]+|[а-я]+(?:\s+[а-я]+)?)\s+(?:секунд\w*|сек\w*|минут\w*|мин\w*|час\w*|ч\b|дн\w*|день|дня|дней)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text) ||
            Regex("""\bчерез\s+(?:полчаса|полтора\s+часа)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("""\bзавтра\s+в\s+\d{1,2}(?:(?::|\.)\d{2})?(?:\s+(?:утра|дня|вечера|ночи))?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("""\bв\s+\d{1,2}(?:(?::|\.)\d{2})(?:\s*(?:утра|дня|вечера|ночи))?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("""\bв\s+\d{1,2}\s+(?:утра|дня|вечера|ночи)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    fun parse(command: String, nowMillis: Long = System.currentTimeMillis()): VoiceSchedule? {
        val normalized = normalize(command)
        if (normalized.isBlank()) return null

        parseRelative(normalized, nowMillis)?.let { return it }
        parseTomorrow(normalized, nowMillis)?.let { return it }
        parseClock(normalized, nowMillis)?.let { return it }

        return null
    }

    private fun parseRelative(text: String, nowMillis: Long): VoiceSchedule? {
        val specialHalfHour = Regex("""\bчерез\s+полчаса\b""", RegexOption.IGNORE_CASE).find(text)
        if (specialHalfHour != null) {
            return buildRelative(text, specialHalfHour.range, 30L * 60L * 1000L, "через полчаса", nowMillis)
        }

        val specialHalf = Regex("""\bчерез\s+полтора\s+часа\b""", RegexOption.IGNORE_CASE).find(text)
        if (specialHalf != null) {
            return buildRelative(text, specialHalf.range, 90L * 60L * 1000L, "через полтора часа", nowMillis)
        }

        val simple = simpleRelativePattern.find(text)
        if (simple != null) {
            val delay = when (simple.groupValues[1].lowercase()) {
                "час" -> 60L * 60L * 1000L
                "день" -> 24L * 60L * 60L * 1000L
                "минуту", "минут" -> 60L * 1000L
                "секунду", "секунды" -> 1000L
                else -> return null
            }
            return buildRelative(text, simple.range, delay, "через " + simple.groupValues[1], nowMillis)
        }

        val match = relativePattern.find(text) ?: return null
        val amount = parseNumber(match.groupValues[1]) ?: return null
        if (amount <= 0L) return null

        val unit = match.groupValues[2]
        val multiplier = when {
            unit.startsWith("сек") -> 1000L
            unit.startsWith("мин") -> 60L * 1000L
            unit.startsWith("час") || unit == "ч" -> 60L * 60L * 1000L
            unit.startsWith("дн") || unit == "день" || unit == "дня" || unit == "дней" ->
                24L * 60L * 60L * 1000L
            else -> return null
        }

        val delay = amount.coerceAtMost(365L * 24L) * multiplier
        return buildRelative(
            text,
            match.range,
            delay,
            "через ${amount}${unitForSpeech(unit)}",
            nowMillis
        )
    }

    private fun parseTomorrow(text: String, nowMillis: Long): VoiceSchedule? {
        val match = tomorrowPattern.find(text) ?: return null
        val time = parseClockValues(
            match.groupValues[1],
            match.groupValues[2],
            match.groupValues[3]
        ) ?: return null

        val now = LocalDateTime.now()
        var target = LocalDateTime.of(LocalDate.now().plusDays(1), time)
        if (target.isBefore(now)) {
            target = target.plusDays(1)
        }

        return buildAbsolute(
            text,
            match.range,
            target,
            "завтра в ${formatTime(time)}",
            nowMillis
        )
    }

    private fun parseClock(text: String, nowMillis: Long): VoiceSchedule? {
        val withMinutes = todayPattern.find(text)
        val hourOnly = if (withMinutes == null) todayHourPattern.find(text) else null
        val match = withMinutes ?: hourOnly ?: return null

        val minuteRaw = if (withMinutes != null) {
            match.groupValues.getOrNull(2).orEmpty()
        } else {
            ""
        }

        val periodRaw = if (withMinutes != null) {
            match.groupValues.getOrNull(3).orEmpty()
        } else {
            match.groupValues.getOrNull(2).orEmpty()
        }

        val time = parseClockValues(
            match.groupValues[1],
            minuteRaw,
            periodRaw
        ) ?: return null

        val now = LocalDateTime.now()
        var target = LocalDateTime.of(LocalDate.now(), time)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }

        return buildAbsolute(
            text,
            match.range,
            target,
            "в ${formatTime(time)}",
            nowMillis
        )
    }

    private fun parseClockValues(hourRaw: String, minuteRaw: String, periodRaw: String): LocalTime? {
        val hour = hourRaw.toIntOrNull() ?: return null
        val minute = if (minuteRaw.isBlank()) 0 else minuteRaw.toIntOrNull() ?: return null
        var adjustedHour = hour

        if (hour !in 0..23 || minute !in 0..59) return null

        when (periodRaw.lowercase()) {
            "утра" -> {
                if (hour !in 0..12) return null
                if (hour == 12) adjustedHour = 0
            }
            "дня", "вечера" -> {
                if (hour !in 0..12) return null
                if (hour != 12) adjustedHour = hour + 12
            }
            "ночи" -> {
                if (hour !in 0..12) return null
                if (hour == 12) adjustedHour = 0
            }
        }

        return LocalTime.of(adjustedHour, minute)
    }

    private fun buildRelative(
        text: String,
        range: IntRange,
        delayMillis: Long,
        spokenTime: String,
        nowMillis: Long
    ): VoiceSchedule? =
        build(text, range, nowMillis + delayMillis.coerceAtLeast(1000L), spokenTime)

    private fun buildAbsolute(
        text: String,
        range: IntRange,
        target: LocalDateTime,
        spokenTime: String,
        nowMillis: Long
    ): VoiceSchedule? {
        val executeAt = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (executeAt <= nowMillis) return null
        return build(text, range, executeAt, spokenTime)
    }

    private fun build(
        text: String,
        range: IntRange,
        executeAtMillis: Long,
        spokenTime: String
    ): VoiceSchedule? {
        val base = text.removeRange(range).trim()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""^\s*(,|и)\s+"""), "")
            .replace(Regex("""\s+(,|\.)$"""), "")
            .trim()

        if (base.isBlank()) return null

        return VoiceSchedule(
            commandText = base,
            executeAtMillis = executeAtMillis,
            spokenTime = spokenTime
        )
    }

    private fun parseNumber(value: String): Long? {
        val normalized = value.lowercase().trim()
        normalized.toLongOrNull()?.let { return it }
        numberWords[normalized]?.let { return it }
        val parts = normalized.split(Regex("\\s+"))
        if (parts.size == 2) {
            val tens = numberWords[parts[0]]
            val ones = numberWords[parts[1]]
            if (tens != null && tens >= 20L && tens % 10L == 0L && ones != null && ones in 1L..9L) {
                return tens + ones
            }
        }
        return null
    }

    private fun unitForSpeech(unit: String): String = when {
        unit.startsWith("сек") -> " секунд"
        unit.startsWith("мин") -> " минут"
        unit.startsWith("час") || unit == "ч" -> " часов"
        else -> " дней"
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9:., ]+"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun formatTime(time: LocalTime): String =
        String.format("%02d:%02d", time.hour, time.minute)
}
