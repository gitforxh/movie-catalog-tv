package com.moviecatalog.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.moviecatalog.tv.R
import com.moviecatalog.tv.data.Prefs

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = Prefs(this)
        val host = findViewById<EditText>(R.id.host).apply { setText(prefs.host) }
        val username = findViewById<EditText>(R.id.username).apply { setText(prefs.username) }
        val password = findViewById<EditText>(R.id.password).apply { setText(prefs.password) }
        val domain = findViewById<EditText>(R.id.domain).apply { setText(prefs.domain) }
        val shareMap = findViewById<EditText>(R.id.shareMap).apply { setText(prefs.shareMapRaw) }
        val catalogRoot = findViewById<EditText>(R.id.catalogRoot).apply { setText(prefs.catalogRoot) }

        val showPassword = findViewById<Button>(R.id.showPassword)
        showPassword.setOnClickListener {
            val revealed = password.inputType == InputType.TYPE_CLASS_TEXT
            password.inputType = if (revealed) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                                  else InputType.TYPE_CLASS_TEXT
            password.setSelection(password.text?.length ?: 0)  // typeface change resets the cursor otherwise
            showPassword.text = if (revealed) "Show" else "Hide"
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.host = host.text.toString().trim()
            prefs.username = username.text.toString().trim()
            prefs.password = password.text.toString()
            prefs.domain = domain.text.toString().trim()
            prefs.shareMapRaw = shareMap.text.toString().trim()
            prefs.catalogRoot = catalogRoot.text.toString().trim()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
