package dev.lucxs.revo

import android.content.Context
import android.content.SharedPreferences

/**
 * One shared-prefs file for the whole app. OverlayService listens for
 * changes on this same file (see OverlayService.onSharedPreferenceChanged)
 * so the sliders in MainActivity retune the running overlay live, without
 * any manual broadcast plumbing between the activity and the service.
 */
object Prefs {
    private const val NOME_ARQUIVO = "revo_prefs"

    const val CHAVE_ATIVO = "ativo"
    const val CHAVE_SENSIBILIDADE = "sensibilidade"
    const val CHAVE_OPACIDADE = "opacidade"
    const val CHAVE_QUANTIDADE_PONTOS = "quantidade_pontos"

    const val PADRAO_SENSIBILIDADE = 50
    const val PADRAO_OPACIDADE = 55
    const val PADRAO_QUANTIDADE_PONTOS = 28

    fun arquivo(context: Context): SharedPreferences =
        context.getSharedPreferences(NOME_ARQUIVO, Context.MODE_PRIVATE)

    fun ativo(context: Context): Boolean =
        arquivo(context).getBoolean(CHAVE_ATIVO, false)

    fun setAtivo(context: Context, valor: Boolean) {
        arquivo(context).edit().putBoolean(CHAVE_ATIVO, valor).apply()
    }

    fun sensibilidade(context: Context): Int =
        arquivo(context).getInt(CHAVE_SENSIBILIDADE, PADRAO_SENSIBILIDADE)

    fun setSensibilidade(context: Context, valor: Int) {
        arquivo(context).edit().putInt(CHAVE_SENSIBILIDADE, valor).apply()
    }

    fun opacidade(context: Context): Int =
        arquivo(context).getInt(CHAVE_OPACIDADE, PADRAO_OPACIDADE)

    fun setOpacidade(context: Context, valor: Int) {
        arquivo(context).edit().putInt(CHAVE_OPACIDADE, valor).apply()
    }

    fun quantidadePontos(context: Context): Int =
        arquivo(context).getInt(CHAVE_QUANTIDADE_PONTOS, PADRAO_QUANTIDADE_PONTOS)

    fun setQuantidadePontos(context: Context, valor: Int) {
        arquivo(context).edit().putInt(CHAVE_QUANTIDADE_PONTOS, valor).apply()
    }
}
