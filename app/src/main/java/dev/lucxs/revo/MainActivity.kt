package dev.lucxs.revo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusTexto: TextView
    private lateinit var permissaoBotao: Button
    private lateinit var ativarSwitch: Switch
    private lateinit var sensibilidadeBarra: SeekBar
    private lateinit var opacidadeBarra: SeekBar
    private lateinit var quantidadeBarra: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTexto = findViewById(R.id.statusTexto)
        permissaoBotao = findViewById(R.id.permissaoBotao)
        ativarSwitch = findViewById(R.id.ativarSwitch)
        sensibilidadeBarra = findViewById(R.id.sensibilidadeBarra)
        opacidadeBarra = findViewById(R.id.opacidadeBarra)
        quantidadeBarra = findViewById(R.id.quantidadeBarra)

        permissaoBotao.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
        }

        sensibilidadeBarra.progress = Prefs.sensibilidade(this)
        opacidadeBarra.progress = Prefs.opacidade(this)
        quantidadeBarra.progress = Prefs.quantidadePontos(this)

        sensibilidadeBarra.setOnSeekBarChangeListener(onChange { Prefs.setSensibilidade(this, it) })
        opacidadeBarra.setOnSeekBarChangeListener(onChange { Prefs.setOpacidade(this, it) })
        quantidadeBarra.setOnSeekBarChangeListener(
            onChange { Prefs.setQuantidadePontos(this, it.coerceAtLeast(12)) },
        )
    }

    override fun onResume() {
        super.onResume()
        atualizarEstado()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) atualizarEstado()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ligarOverlay()
        } else {
            ativarSwitch.isChecked = false
        }
    }

    private fun atualizarEstado() {
        val temPermissao = Settings.canDrawOverlays(this)
        permissaoBotao.visibility = if (temPermissao) View.GONE else View.VISIBLE

        val ativo = temPermissao && Prefs.ativo(this)
        ativarSwitch.setOnCheckedChangeListener(null)
        ativarSwitch.isChecked = ativo
        ativarSwitch.isEnabled = temPermissao
        ativarSwitch.setOnCheckedChangeListener { _, ligado ->
            if (ligado) {
                tentarLigar()
            } else {
                OverlayService.stop(this)
                Prefs.setAtivo(this, false)
            }
        }

        statusTexto.text = when {
            !temPermissao -> getString(R.string.main_status_permission_missing)
            ativo -> getString(R.string.main_status_active)
            else -> getString(R.string.main_status_inactive)
        }
    }

    private fun tentarLigar() {
        if (!Settings.canDrawOverlays(this)) {
            ativarSwitch.isChecked = false
            Toast.makeText(this, R.string.main_permission_explanation, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        ligarOverlay()
    }

    private fun ligarOverlay() {
        OverlayService.start(this)
        Prefs.setAtivo(this, true)
        statusTexto.text = getString(R.string.main_status_active)
    }

    private fun onChange(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onValue(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
    }
}
