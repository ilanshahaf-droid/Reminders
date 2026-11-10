package com.ilan.reminders

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** The spoken text split into the reminder title and (when a date or time was said) the time of the alert. */
data class VoiceResult(val title: String, val at: ZonedDateTime?)

/**
 * Understands Hebrew spoken dates and times: "מחר ב-9", "ביום ראשון בשעה 7:30", "בעוד שעתיים",
 * "ב-15 לחודש בערב", "רבע ל-10", "ב-20.11 בצהריים". What it does not recognise stays in the title.
 */
object VoiceParse {
    private const val HB = "֐-׿"

    // longest phrases first, so "אחת עשרה" is read before "אחת"
    private val NUMW = linkedMapOf(
        "אחת עשרה" to 11, "שתים עשרה" to 12, "שתיים עשרה" to 12, "אחד עשר" to 11, "שנים עשר" to 12,
        "אחת" to 1, "אחד" to 1, "שתיים" to 2, "שתים" to 2, "שניים" to 2, "שלוש" to 3, "ארבע" to 4,
        "חמש" to 5, "שש" to 6, "שבע" to 7, "שמונה" to 8, "תשע" to 9, "עשר" to 10
    )
    private val NUM = "(\\d{1,2}|" + NUMW.keys.joinToString("|") + ")"
    private val MONTHS = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")
    private val DOW = mapOf(
        "ראשון" to DayOfWeek.SUNDAY, "שני" to DayOfWeek.MONDAY, "שלישי" to DayOfWeek.TUESDAY, "רביעי" to DayOfWeek.WEDNESDAY,
        "חמישי" to DayOfWeek.THURSDAY, "שישי" to DayOfWeek.FRIDAY, "שבת" to DayOfWeek.SATURDAY,
        "א" to DayOfWeek.SUNDAY, "ב" to DayOfWeek.MONDAY, "ג" to DayOfWeek.TUESDAY, "ד" to DayOfWeek.WEDNESDAY,
        "ה" to DayOfWeek.THURSDAY, "ו" to DayOfWeek.FRIDAY, "ש" to DayOfWeek.SATURDAY
    )

    private enum class Period { AM, NOON, PM, EVE, NIGHT, EARLY }

    private fun num(s: String): Int? = s.trim().replace(Regex("\\s+"), " ").let { it.toIntOrNull() ?: NUMW[it] }

    private val RE_REL = Regex("בעוד\\s+(?:(חצי שעה|שעה וחצי|שעתיים|שעה|דקה|יומיים|יום|שבועיים|שבוע|חודש)|(\\d{1,3})\\s+(דקות|שעות|ימים|שבועות|חודשים))")
    private val RE_DATE_NUM = Regex("(?<![\\d./:])(?:[בל]-?\\s?)?(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?(?!\\d)")
    private val RE_DATE_MONTH = Regex("(?<!\\d)(?:[בל]-?\\s?)?(\\d{1,2})\\s+(?:[בל])?(" + MONTHS.joinToString("|") + ")(?![$HB])")
    private val RE_DAY_OF_MONTH = Regex("(?<!\\d)(?:[בל]-?\\s?)?(\\d{1,2})\\s+לחודש(?![$HB])")
    private val RE_WEEKDAY = Regex("(?<![$HB])[בל]?יום\\s+(ראשון|שני|שלישי|רביעי|חמישי|שישי|שבת|[א-ו]['׳])(?![$HB])")
    private val RE_SHABBAT = Regex("(?<![$HB])[בל]שבת(?![$HB])")
    private val RE_DAYWORD = Regex("(?<![$HB])[בל]?(מחרתיים|מחר|היום|הערב|הלילה)(?![$HB])")
    private val RE_QUARTER_TO = Regex("(?:בשעה\\s+|ב-?\\s?)?רבע\\s+ל-?\\s?" + NUM + "(?![$HB\\d])")
    private val RE_TIME_COLON = Regex("(?<![\\d:.])(?:בשעה\\s+|לשעה\\s+|ב-?\\s?)?(\\d{1,2}):(\\d{2})(?!\\d)")
    private val RE_TIME_HOUR = Regex("(?<![$HB\\d])(?:בשעה\\s+|ב-?\\s?)" + NUM + "(?:\\s+(וחצי|ורבע))?(?![$HB\\d:./])")
    private val RE_P_PM = Regex("(?:אחר|אחרי)\\s+הצהריים|אחר\\s+הצהרים|אחה[\"״]צ")
    private val RE_P_EARLY = Regex("לפנות\\s+בוקר")
    private val RE_P_AM = Regex("(?<![$HB])[בה]בוקר(?![$HB])|(?<![$HB])בבוקר(?![$HB])")
    private val RE_P_NOON = Regex("(?<![$HB])בצהר(?:יי|י)ם(?![$HB])")
    private val RE_P_EVE = Regex("(?<![$HB])[בה]ערב(?![$HB])")
    private val RE_P_NIGHT = Regex("(?<![$HB])[בה]לילה(?![$HB])")
    private val RE_LEAD = Regex("^(?:(?:תזכיר|תזכירי|תזכור|תוסיף|הוסף|הוסיפי|צור|תיצור)\\s+(?:לי\\s+)?(?:תזכורת\\s+)?(?:חדשה\\s+)?|(?:להזכיר|הזכר)\\s+לי\\s+|תזכורת\\s+(?:חדשה\\s+)?)")
    private val RE_EDGE_START = Regex("^(?:בשעה|ביום|בעוד|ב|-|,)\\s+")
    private val RE_EDGE_END = Regex("\\s+(?:בשעה|ביום|בעוד|ב|ל|-|,)$")

    fun parse(text: String, now: ZonedDateTime, defaultTime: LocalTime): VoiceResult {
        val original = text.trim()
        var s = " " + original.replace('־', '-').replace('–', '-') + " "

        fun take(re: Regex): MatchResult? {
            val m = re.find(s) ?: return null
            s = s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1)
            return m
        }

        val today = now.toLocalDate()
        var date: LocalDate? = null
        var weekday: DayOfWeek? = null
        var hour = -1
        var minute = 0
        var period: Period? = null

        // "in two hours" / "in 3 days"
        take(RE_REL)?.let { m ->
            val w = m.groupValues[1]
            val n = m.groupValues[2].toIntOrNull() ?: 0
            val u = m.groupValues[3]
            var minutes = 0L
            var days = 0L
            var months = 0L
            when (w) {
                "חצי שעה" -> minutes = 30
                "שעה וחצי" -> minutes = 90
                "שעתיים" -> minutes = 120
                "שעה" -> minutes = 60
                "דקה" -> minutes = 1
                "יומיים" -> days = 2
                "יום" -> days = 1
                "שבועיים" -> days = 14
                "שבוע" -> days = 7
                "חודש" -> months = 1
                else -> when (u) {
                    "דקות" -> minutes = n.toLong()
                    "שעות" -> minutes = n * 60L
                    "ימים" -> days = n.toLong()
                    "שבועות" -> days = n * 7L
                    "חודשים" -> months = n.toLong()
                }
            }
            if (minutes > 0) {
                val at = now.plusMinutes(minutes).withSecond(0).withNano(0)
                return VoiceResult(cleanTitle(s, original), at)
            }
            date = today.plusDays(days).plusMonths(months)
        }

        // explicit dates: 20.11 / 20/11/2027 / "20 בנובמבר" / "ב-15 לחודש"
        if (date == null) {
            var m = RE_DATE_NUM.find(s)
            while (m != null) {
                val d = m.groupValues[1].toInt()
                val mo = m.groupValues[2].toInt()
                if (d in 1..31 && mo in 1..12) {
                    s = s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1)
                    var y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: today.year
                    date = safeDate(y, mo, d)
                    if (date != null && m.groupValues[3].isEmpty() && date!!.isBefore(today)) date = safeDate(y + 1, mo, d)
                    break
                }
                m = m.next()
            }
        }
        if (date == null) take(RE_DATE_MONTH)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = MONTHS.indexOf(m.groupValues[2]) + 1
            date = safeDate(today.year, mo, d)?.let { if (it.isBefore(today)) safeDate(today.year + 1, mo, d) else it }
        }
        if (date == null) take(RE_DAY_OF_MONTH)?.let { m ->
            val d = m.groupValues[1].toInt()
            var cand = safeDate(today.year, today.monthValue, d)
            if (cand == null || cand.isBefore(today)) {
                val nm = today.withDayOfMonth(1).plusMonths(1)
                cand = safeDate(nm.year, nm.monthValue, d)
            }
            date = cand
        }
        if (date == null) {
            val m = take(RE_WEEKDAY)
            if (m != null) {
                val k = m.groupValues[1].trimEnd('\'', '׳')
                weekday = DOW[k]
            } else if (take(RE_SHABBAT) != null) weekday = DayOfWeek.SATURDAY
        }
        if (date == null && weekday == null) take(RE_DAYWORD)?.let { m ->
            when (m.groupValues[1]) {
                "מחר" -> date = today.plusDays(1)
                "מחרתיים" -> date = today.plusDays(2)
                "היום" -> date = today
                "הערב" -> { date = today; period = Period.EVE }
                "הלילה" -> { date = today; period = Period.NIGHT }
            }
        }

        // the time of day
        var ambiguous = false
        val q = take(RE_QUARTER_TO)
        if (q != null) {
            val n = num(q.groupValues[1]) ?: 12
            hour = if (n == 1) 12 else n - 1
            minute = 45
        } else {
            val c = take(RE_TIME_COLON)
            if (c != null) {
                hour = c.groupValues[1].toInt()
                minute = c.groupValues[2].toInt()
                if (hour > 23 || minute > 59) { hour = -1; minute = 0 }
            } else {
                val h = take(RE_TIME_HOUR)
                if (h != null) {
                    val n = num(h.groupValues[1])
                    if (n != null && n in 0..23) {
                        hour = n
                        minute = when (h.groupValues[2]) { "וחצי" -> 30; "ורבע" -> 15; else -> 0 }
                    }
                }
            }
        }
        if (take(RE_P_PM) != null) period = Period.PM
        else if (take(RE_P_EARLY) != null) period = Period.EARLY
        else if (take(RE_P_AM) != null) period = Period.AM
        else if (take(RE_P_NOON) != null) period = Period.NOON
        else if (take(RE_P_EVE) != null) period = Period.EVE
        else if (take(RE_P_NIGHT) != null) period = Period.NIGHT

        val per = period
        var time: LocalTime? = null
        if (hour >= 0) {
            var h = hour
            when (per) {
                Period.AM, Period.EARLY -> h %= 12
                Period.NOON -> if (h in 1..5) h += 12
                Period.PM, Period.EVE -> if (h in 1..11) h += 12
                Period.NIGHT -> if (h == 12) h = 0 else if (h in 6..11) h += 12
                null -> if (h in 1..6) h += 12 else if (h in 7..11) ambiguous = true
            }
            time = LocalTime.of(h % 24, minute)
        } else if (per != null) {
            time = when (per) {
                Period.AM, Period.EARLY -> LocalTime.of(8, 0)
                Period.NOON -> LocalTime.of(12, 0)
                Period.PM -> LocalTime.of(16, 0)
                Period.EVE -> LocalTime.of(20, 0)
                Period.NIGHT -> LocalTime.of(22, 0)
            }
        }

        val title = cleanTitle(s, original)
        if (date == null && weekday == null && time == null) return VoiceResult(title, null)

        val timeGiven = time != null
        var t = time ?: defaultTime
        val nowZ = now.withSecond(0).withNano(0)
        fun isFuture(d: LocalDate, tt: LocalTime) = d.atTime(tt).atZone(now.zone).isAfter(nowZ)

        var d: LocalDate
        if (weekday != null) {
            var diff = (weekday.value - today.dayOfWeek.value + 7) % 7
            if (diff == 0 && !(timeGiven && isFuture(today, t))) diff = 7
            d = today.plusDays(diff.toLong())
        } else if (date != null) {
            d = date!!
        } else {
            d = today
            if (!isFuture(d, t)) {
                if (ambiguous && t.hour < 12 && isFuture(d, t.plusHours(12))) t = t.plusHours(12)
                else d = today.plusDays(1)
            }
        }
        if (ambiguous && d == today && !isFuture(d, t) && isFuture(d, t.plusHours(12))) t = t.plusHours(12)
        return VoiceResult(title, d.atTime(t).atZone(now.zone))
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    private fun cleanTitle(rest: String, original: String): String {
        var t = rest.replace(Regex("\\s+"), " ").trim().trim(',', '.', ' ')
        t = RE_LEAD.replaceFirst(t, "").trim()
        var changed = true
        while (changed) {
            changed = false
            val a = RE_EDGE_START.replaceFirst(t, "")
            val b = RE_EDGE_END.replaceFirst(a, "")
            if (b != t) { t = b.trim().trim(',', '.', ' '); changed = true }
        }
        if (t == "ב" || t == "ל" || t == "-") t = ""
        return t.ifBlank { RE_LEAD.replaceFirst(original, "").trim().ifBlank { original } }
    }
}
