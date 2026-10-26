package com.example.sunwakeup.audio

import com.example.sunwakeup.data.SoundType
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

const val SAMPLE_RATE = 44100

/** Produces mono float samples in roughly -1..1. */
fun interface Generator {
    fun next(): Float
}

fun generatorFor(type: SoundType): Generator = when (type) {
    SoundType.WHITE_NOISE -> WhiteNoise()
    SoundType.PINK_NOISE -> PinkNoise()
    SoundType.BROWN_NOISE -> BrownNoise()
    SoundType.RAIN -> Rain()
    SoundType.OCEAN -> Ocean()
    SoundType.FAN -> Fan()
    SoundType.CHIMES -> Chimes()
    SoundType.BIRDS -> Birds()
    SoundType.SYSTEM_ALARM, SoundType.CUSTOM -> error("$type is not synthesized")
}

private val rnd = Random(System.nanoTime())
private fun white() = rnd.nextFloat() * 2f - 1f

class WhiteNoise : Generator {
    override fun next() = white() * 0.25f
}

/** Paul Kellet's refined pink noise filter. */
class PinkNoise : Generator {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var b3 = 0f; private var b4 = 0f; private var b5 = 0f; private var b6 = 0f

    override fun next(): Float {
        val w = white()
        b0 = 0.99886f * b0 + w * 0.0555179f
        b1 = 0.99332f * b1 + w * 0.0750759f
        b2 = 0.96900f * b2 + w * 0.1538520f
        b3 = 0.86650f * b3 + w * 0.3104856f
        b4 = 0.55000f * b4 + w * 0.5329522f
        b5 = -0.7616f * b5 - w * 0.0168980f
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
        b6 = w * 0.115926f
        return out * 0.06f
    }
}

class BrownNoise : Generator {
    private var last = 0f
    override fun next(): Float {
        last = (last + 0.02f * white()) / 1.02f
        return last * 2.2f
    }
}

/** A soft bed of filtered noise with individual drops landing on top. */
class Rain : Generator {
    private val bed = PinkNoise()
    private var lp = 0f
    private var hpPrev = 0f
    private var drop = 0f
    private var dropDecay = 0.999f

    override fun next(): Float {
        val p = bed.next()
        val hp = p - hpPrev * 0.97f
        hpPrev = p
        lp += (hp - lp) * 0.35f
        if (rnd.nextFloat() < 40f / SAMPLE_RATE) {
            drop = 0.25f + rnd.nextFloat() * 0.5f
            dropDecay = 0.990f + rnd.nextFloat() * 0.008f
        }
        drop *= dropDecay
        return lp * 0.9f + drop * white() * 0.35f
    }
}

/** Brown noise breathing in and out like waves on a beach. */
class Ocean : Generator {
    private val noise = BrownNoise()
    private val hiss = PinkNoise()
    private var phase = 0.0
    private var period = 9.0

    override fun next(): Float {
        phase += 1.0 / (SAMPLE_RATE * period)
        if (phase >= 1.0) {
            phase -= 1.0
            period = 7.0 + rnd.nextDouble() * 6.0
        }
        // Fast build-up, long draw-back.
        val shape = if (phase < 0.35) sin(phase / 0.35 * PI / 2) else exp(-(phase - 0.35) * 4.0)
        val swell = (0.15 + 0.85 * shape * shape).toFloat()
        return noise.next() * swell + hiss.next() * swell * swell * 0.5f
    }
}

class Fan : Generator {
    private val noise = BrownNoise()
    private val air = PinkNoise()
    private var t = 0.0

    override fun next(): Float {
        t += 1.0 / SAMPLE_RATE
        val flutter = 1f + 0.04f * sin(2 * PI * 18 * t).toFloat()
        val hum = 0.02f * sin(2 * PI * 100 * t).toFloat()
        return (noise.next() * 0.8f + air.next() * 0.25f) * flutter + hum
    }
}

/** Random pentatonic bell tones that ring out and decay. */
class Chimes : Generator {
    private class Voice(val freq: Double, var amp: Float) {
        var phase = 0.0
    }

    private val voices = ArrayList<Voice>()
    private var untilNext = 0
    private val scale = doubleArrayOf(0.0, 2.0, 4.0, 7.0, 9.0, 12.0, 14.0, 16.0)
    private val decay = exp(-1.0 / (SAMPLE_RATE * 2.2)).toFloat()

    override fun next(): Float {
        if (--untilNext <= 0) {
            val semitone = scale[rnd.nextInt(scale.size)]
            voices += Voice(392.0 * 2.0.pow(semitone / 12.0), 0.18f + rnd.nextFloat() * 0.1f)
            untilNext = (SAMPLE_RATE * (1.2 + rnd.nextDouble() * 2.8)).toInt()
            if (voices.size > 8) voices.removeAt(0)
        }
        var out = 0f
        // Indexed and backwards, so nothing is allocated per sample and removing is safe.
        for (i in voices.lastIndex downTo 0) {
            val v = voices[i]
            v.phase += 2 * PI * v.freq / SAMPLE_RATE
            val s = sin(v.phase) + 0.35 * sin(2.76 * v.phase) + 0.15 * sin(5.4 * v.phase)
            out += (s * v.amp).toFloat()
            v.amp *= decay
            if (v.amp < 0.0005f) voices.removeAt(i)
        }
        return out
    }
}

/** Chirps made of short frequency sweeps over a quiet breeze. */
class Birds : Generator {
    private class Syllable(
        val start: Int, val length: Int, val f0: Double, val f1: Double, val amp: Float,
    )

    private class Bird(val syllables: List<Syllable>) {
        var pos = 0
        var phase = 0.0
        val end = syllables.last().start + syllables.last().length
    }

    private val birds = ArrayList<Bird>()
    private val breeze = PinkNoise()
    private var untilNext = SAMPLE_RATE / 2

    private fun newBird(): Bird {
        val base = 2200.0 + rnd.nextDouble() * 2400.0
        val count = 1 + rnd.nextInt(6)
        val sylLen = (SAMPLE_RATE * (0.04 + rnd.nextDouble() * 0.09)).toInt()
        val gap = (SAMPLE_RATE * (0.03 + rnd.nextDouble() * 0.08)).toInt()
        val sweep = (rnd.nextDouble() - 0.3) * 1800.0
        val amp = 0.08f + rnd.nextFloat() * 0.12f
        return Bird(List(count) { i ->
            val jitter = 1.0 + (rnd.nextDouble() - 0.5) * 0.08
            Syllable(i * (sylLen + gap), sylLen, base * jitter, (base + sweep) * jitter, amp)
        })
    }

    override fun next(): Float {
        if (--untilNext <= 0) {
            if (birds.size < 4) birds += newBird()
            untilNext = (SAMPLE_RATE * (0.4 + rnd.nextDouble() * 2.5)).toInt()
        }
        var out = breeze.next() * 0.12f
        // Indexed and backwards, so nothing is allocated per sample and removing is safe.
        for (i in birds.lastIndex downTo 0) {
            val b = birds[i]
            out += sample(b)
            if (++b.pos >= b.end) birds.removeAt(i)
        }
        return out
    }

    private fun sample(b: Bird): Float {
        var syl: Syllable? = null
        for (k in 0 until b.syllables.size) {
            val candidate = b.syllables[k]
            if (b.pos >= candidate.start && b.pos < candidate.start + candidate.length) {
                syl = candidate
                break
            }
        }
        if (syl == null) return 0f
        val x = (b.pos - syl.start).toDouble() / syl.length
        val freq = syl.f0 + (syl.f1 - syl.f0) * x + 120 * sin(2 * PI * 35 * x)
        b.phase += 2 * PI * freq / SAMPLE_RATE
        val env = sin(PI * x)
        return (sin(b.phase) * env * env * syl.amp).toFloat()
    }
}
