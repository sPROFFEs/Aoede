package com.aoede.twa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class SetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_setup)

        val firstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)
        val prefs    = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

        val titleView    = findViewById<TextView>(R.id.setup_title)
        val subtitleView = findViewById<TextView>(R.id.setup_subtitle)
        val urlInput     = findViewById<EditText>(R.id.url_input)
        val saveBtn      = findViewById<Button>(R.id.btn_save)
        val cancelBtn    = findViewById<Button>(R.id.btn_cancel)

        if (firstRun) {
            titleView.setText(R.string.setup_welcome_title)
            subtitleView.setText(R.string.setup_welcome_subtitle)
            cancelBtn.visibility = View.GONE
        } else {
            titleView.setText(R.string.setup_change_title)
            subtitleView.setText(R.string.setup_change_subtitle)
            urlInput.setText(prefs.getString(MainActivity.KEY_URL, ""))
            cancelBtn.visibility = View.VISIBLE
        }

        cancelBtn.setOnClickListener { finish() }

        saveBtn.setOnClickListener {
            var url = urlInput.text.toString().trim()

            if (url.isBlank()) {
                urlInput.error = getString(R.string.setup_error_empty_url)
                return@setOnClickListener
            }

            // Add https:// if the user forgot it
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            url = url.trimEnd('/')

            // Basic validation: must have a host
            val uri = Uri.parse(url)
            if (ServerOrigin.from(uri) == null || uri.query != null || uri.fragment != null) {
                urlInput.error = getString(R.string.setup_error_invalid_url)
                return@setOnClickListener
            }

            prefs.edit().putString(MainActivity.KEY_URL, url).apply()

            Toast.makeText(this, R.string.setup_toast_saved, Toast.LENGTH_SHORT).show()

            // Restart MainActivity with the new URL
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }
}
