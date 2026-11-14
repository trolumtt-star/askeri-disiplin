package com.askeri.disiplin

import kotlin.random.Random

data class MathQuestion(val text: String, val answer: Int)

/**
 * Kolay zihinden matematik: çarpım tablosu (5 × 5, 7 × 9) ve basit toplama.
 * Sonuç her zaman pozitif tamsayı. Yanlış cevapta doğru cevap gösterilir, yeni soru gelir.
 */
class MathEngine(private val rnd: Random = Random.Default) {

    fun next(): MathQuestion = if (rnd.nextInt(10) < 7) {
        val a = rnd.nextInt(2, 10); val b = rnd.nextInt(2, 10)
        MathQuestion("$a × $b", a * b)
    } else {
        val a = rnd.nextInt(5, 21); val b = rnd.nextInt(2, 10)
        MathQuestion("$a + $b", a + b)
    }
}
