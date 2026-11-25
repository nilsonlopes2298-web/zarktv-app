package io.github.axon07.channelbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VencimentoZpTest {
    private val agora = 1_795_000_000_000L
    private val hora = 3_600_000L
    private val dia = 24 * hora

    @Test fun marcos() {
        assertEquals("Faltam 5 dias para o seu acesso vencer.", textoVencimentoZp(agora + 5 * dia + hora, agora))
        assertNull(textoVencimentoZp(agora + 4 * dia + hora, agora))
        assertEquals("Faltam 3 dias para o seu acesso vencer.", textoVencimentoZp(agora + 3 * dia + hora, agora))
        assertEquals("Falta menos de 1 hora para o seu acesso vencer.", textoVencimentoZp(agora + 30 * 60_000L, agora))
        assertEquals("Seu acesso venceu. Renove para continuar assistindo.", textoVencimentoZp(agora - hora, agora))
        assertNull(textoVencimentoZp(agora + 10 * dia, agora))
        assertNull(textoVencimentoZp(null, agora))
    }
}
