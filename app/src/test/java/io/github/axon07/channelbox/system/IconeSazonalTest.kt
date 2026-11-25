package io.github.axon07.channelbox.system

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class IconeSazonalTest {
    private fun dia(a: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(a, m - 1, d, 10, 0) }

    @Test fun natalEAnoNovo() {
        assertEquals("natal", IconeSazonal.daData(dia(2026, 12, 1)))
        assertEquals("natal", IconeSazonal.daData(dia(2026, 12, 25)))
        assertEquals("anonovo", IconeSazonal.daData(dia(2026, 12, 31)))
        assertEquals("anonovo", IconeSazonal.daData(dia(2027, 1, 6)))
        assertEquals("padrao", IconeSazonal.daData(dia(2027, 1, 7)))
    }

    @Test fun saoJoao() {
        assertEquals("junina", IconeSazonal.daData(dia(2027, 6, 24)))
        assertEquals("padrao", IconeSazonal.daData(dia(2027, 7, 16)))
    }

    @Test fun carnaval() {
        // 2027: Páscoa 28/03, terça de Carnaval 09/02 → vale de 05/02 a 10/02.
        assertEquals("padrao", IconeSazonal.daData(dia(2027, 2, 4)))
        assertEquals("carnaval", IconeSazonal.daData(dia(2027, 2, 5)))
        assertEquals("carnaval", IconeSazonal.daData(dia(2027, 2, 9)))
        assertEquals("carnaval", IconeSazonal.daData(dia(2027, 2, 10)))
        assertEquals("padrao", IconeSazonal.daData(dia(2027, 2, 11)))
    }
}
