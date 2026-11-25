package io.github.axon07.channelbox.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.util.Calendar

/**
 * ZarkTV: ícone de datas comemorativas (Natal, Ano Novo, São João e Carnaval), trocado sozinho
 * conforme a data. O painel pode fixar um (campo "icone" do config.json: auto, padrao, natal,
 * anonovo, junina ou carnaval); sem nada no painel vale "auto".
 *
 * Cada ícone é um apelido (activity-alias) no manifesto de cada versão; só um fica ligado.
 * A troca é feita quando o app sai da tela, para não atrapalhar quem está assistindo.
 */
object IconeSazonal {
    private val APELIDOS = linkedMapOf(
        "padrao" to "IconePadrao",
        "natal" to "IconeNatal",
        "anonovo" to "IconeAnoNovo",
        "junina" to "IconeJunina",
        "carnaval" to "IconeCarnaval",
    )

    /** Data comemorativa de hoje (ou "padrao"). */
    fun daData(hoje: Calendar = Calendar.getInstance()): String {
        val mes = hoje.get(Calendar.MONTH) + 1
        val dia = hoje.get(Calendar.DAY_OF_MONTH)
        val mmdd = mes * 100 + dia
        return when {
            mmdd in 1201..1226 -> "natal"                 // 1º a 26 de dezembro
            mmdd >= 1227 || mmdd <= 106 -> "anonovo"      // 27 de dezembro a 6 de janeiro
            mmdd in 601..715 -> "junina"                  // 1º de junho a 15 de julho
            noCarnaval(hoje) -> "carnaval"                // sexta antes até a Quarta-feira de Cinzas
            else -> "padrao"
        }
    }

    /** Carnaval: Páscoa menos 47 dias é a terça; vale da sexta anterior até a quarta de cinzas. */
    private fun noCarnaval(hoje: Calendar): Boolean {
        val ano = hoje.get(Calendar.YEAR)
        val pascoa = pascoa(ano)
        val inicio = (pascoa.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -51) }
        val fim = (pascoa.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -46) }
        val d = (hoje.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }
        return !d.before(inicio) && !d.after(fim)
    }

    /** Domingo de Páscoa (algoritmo de Meeus/Jones/Butcher). */
    private fun pascoa(ano: Int): Calendar {
        val a = ano % 19; val b = ano / 100; val c = ano % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val mes = (h + l - 7 * m + 114) / 31; val dia = ((h + l - 7 * m + 114) % 31) + 1
        return Calendar.getInstance().apply { clear(); set(ano, mes - 1, dia, 12, 0) }
    }

    /** O que o painel mandou (campo "icone"); "auto" quando não tem ou não é conhecido. */
    private fun doPainel(c: Context): String {
        val txt = c.getSharedPreferences("zarktv", Context.MODE_PRIVATE).getString("config_json", null) ?: return "auto"
        val v = Regex("\"icone\"\\s*:\\s*\"([a-z]+)\"").find(txt)?.groupValues?.get(1) ?: return "auto"
        return if (v in APELIDOS) v else "auto"
    }

    /** Liga o ícone certo (e desliga os outros). Não faz nada se já estiver certo. */
    fun aplicar(c: Context) = runCatching {
        val painel = doPainel(c)
        val alvo = if (painel == "auto") daData() else painel
        val pm = c.packageManager
        val componentes = APELIDOS.mapValues { (_, nome) -> ComponentName(c, "io.github.axon07.channelbox.$nome") }
        fun ligado(cn: ComponentName, padrao: Boolean) = when (pm.getComponentEnabledSetting(cn)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> padrao
        }
        val atual = componentes.entries.firstOrNull { (k, cn) -> ligado(cn, k == "padrao") }?.key
        if (atual == alvo) return@runCatching
        // Liga o novo antes de desligar o antigo: o app nunca fica sem ícone.
        pm.setComponentEnabledSetting(componentes.getValue(alvo), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        componentes.forEach { (k, cn) ->
            if (k != alvo) pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
