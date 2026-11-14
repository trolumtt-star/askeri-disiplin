package com.askeri.disiplin

import kotlin.random.Random

data class MathQuestion(val text: String, val answer: Int)

/**
 * Orta-zor zihinden matematik. Sonuç her zaman pozitif tamsayı.
 * Yanlış cevapta aynı soru tekrar gelmez (tahminle geçilemez) — yeni soru üretilir.
 */
class MathEngine(private val rnd: Random = Random.Default) {

    fun next(): MathQuestion = when (rnd.nextInt(6)) {
        0 -> { // 47 × 3 − 15
            val a = rnd.nextInt(23, 80); val b = rnd.nextInt(3, 10)
            val c = rnd.nextInt(11, minOf(99, a * b))
            MathQuestion("$a × $b − $c", a * b - c)
        }
        1 -> { // 17 × 14
            val a = rnd.nextInt(12, 30); val b = rnd.nextInt(11, 20)
            MathQuestion("$a × $b", a * b)
        }
        2 -> { // 7 × 8 + 6 × 12
            val a = rnd.nextInt(4, 10); val b = rnd.nextInt(6, 10)
            val c = rnd.nextInt(3, 10); val d = rnd.nextInt(7, 14)
            MathQuestion("$a × $b + $c × $d", a * b + c * d)
        }
        3 -> { // (38 + 27) × 4
            val a = rnd.nextInt(15, 61); val b = rnd.nextInt(15, 61); val c = rnd.nextInt(3, 8)
            MathQuestion("($a + $b) × $c", (a + b) * c)
        }
        4 -> { // 19² − 87
            val a = rnd.nextInt(13, 26); val b = rnd.nextInt(20, 151)
            MathQuestion("$a² − $b", a * a - b)
        }
        else -> { // 384 ÷ 6 + 29
            val d = rnd.nextInt(4, 10); val q = rnd.nextInt(24, 99); val c = rnd.nextInt(11, 60)
            MathQuestion("${d * q} ÷ $d + $c", q + c)
        }
    }
}
