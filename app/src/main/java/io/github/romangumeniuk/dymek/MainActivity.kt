package io.github.romangumeniuk.dymek

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Ekran ustawien: klucz API, model, wyglad i wlaczanie plywajacego przycisku. */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var claudeKeyInput: EditText
    private lateinit var geminiKeyInput: EditText
    private lateinit var modelGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "Dymek"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            text = "Pływający przycisk nad innymi aplikacjami. Stuknij go, a AI (Claude albo darmowy Gemini) " +
                "przeczyta pytanie z ekranu " +
                "i pokaże krótką odpowiedź w dymku. Przytrzymaj przycisk, żeby go wyłączyć, " +
                "albo przeciągnij go na środek ekranu, żeby zamknąć całą aplikację."
            setPadding(0, dp(8), 0, dp(20))
        })

        column.addView(label("Model"))
        modelGroup = RadioGroup(this)
        ModelChoice.entries.forEachIndexed { i, choice ->
            modelGroup.addView(RadioButton(this).apply {
                id = i + 1
                text = choice.label
                isChecked = choice == settings.model
            })
        }
        column.addView(modelGroup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(20) })

        // obie opcje zapisuja sie od razu, a wlaczony dymek od razu zmienia wyglad
        column.addView(label("Przycisk"))
        column.addView(CheckBox(this).apply {
            text = "Mniej widoczny (mały, szary i półprzezroczysty)"
            isChecked = settings.quietButton
            setOnCheckedChangeListener { _, checked -> settings.quietButton = checked }
        })
        column.addView(CheckBox(this).apply {
            text = "Przeciągnięcie na środek ekranu zamyka całą aplikację"
            isChecked = settings.centerQuits
            setOnCheckedChangeListener { _, checked -> settings.centerQuits = checked }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(20) })

        claudeKeyInput = addKeySection(
            column,
            title = "Klucz API Claude (płatny)",
            hint = "sk-ant-...",
            value = settings.claudeKey,
            button = "Pobierz klucz z konsoli Claude",
            url = "https://platform.claude.com/settings/keys",
            note = "Idzie wyłącznie do api.anthropic.com. Zapytania są płatne z Twojego konta Claude.",
        )
        geminiKeyInput = addKeySection(
            column,
            title = "Klucz API Gemini (darmowy)",
            hint = "AIza...",
            value = settings.geminiKey,
            button = "Pobierz darmowy klucz z Google AI Studio",
            url = "https://aistudio.google.com/apikey",
            note = "Idzie wyłącznie do generativelanguage.googleapis.com. Darmowy plan ma limit zapytań na minutę " +
                "i na dzień, a Google może używać przesłanych zrzutów do ulepszania swoich usług.",
        )
        column.addView(TextView(this).apply {
            text = "Wystarczy klucz do tego dostawcy, którego model wybrałeś. Klucze zostają tylko na tym telefonie."
            textSize = 12f
        })

        column.addView(Button(this).apply {
            text = "Włącz dymek"
            setOnClickListener { start() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        column.addView(Button(this).apply {
            text = "Wyłącz dymek"
            setOnClickListener {
                startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(TextView(this).apply {
            text = "Uwaga: aplikacje, które blokują zrzuty ekranu (np. bankowe), dadzą czarny obraz."
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        })

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        }
    }

    private fun addKeySection(
        column: LinearLayout,
        title: String,
        hint: String,
        value: String,
        button: String,
        url: String,
        note: String,
    ): EditText {
        column.addView(label(title))
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setText(value)
        }
        column.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(Button(this).apply {
            text = button
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        })
        column.addView(TextView(this).apply {
            text = note
            textSize = 12f
            setPadding(0, 0, 0, dp(16))
        })
        return input
    }

    private fun saveForm() {
        settings.claudeKey = claudeKeyInput.text.toString()
        settings.geminiKey = geminiKeyInput.text.toString()
        val index = modelGroup.checkedRadioButtonId - 1
        ModelChoice.entries.getOrNull(index)?.let { settings.model = it }
    }

    override fun onPause() {
        super.onPause()
        saveForm()
    }

    private fun start() {
        saveForm()
        when (settings.model.provider) {
            Provider.CLAUDE -> if (!settings.claudeKey.startsWith("sk-ant-")) {
                toast("Wklej klucz API Claude (zaczyna się od sk-ant-) albo wybierz darmowy model Gemini.")
                return
            }
            Provider.GEMINI -> if (settings.geminiKey.isEmpty()) {
                toast("Wklej darmowy klucz Gemini z Google AI Studio.")
                return
            }
        }
        if (!SystemSettings.canDrawOverlays(this)) {
            toast("Zezwól Dymkowi na wyświetlanie nad innymi aplikacjami, potem wróć i stuknij jeszcze raz.")
            startActivity(
                Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // powiadomienie nie jest konieczne, ale bez niego nie widac przycisku "Wylacz"
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        askForScreenCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) askForScreenCapture()
    }

    private fun askForScreenCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // caly ekran, a nie jedna aplikacja: dymek ma dzialac nad dowolnym quizem
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Deprecated("Activity bez AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Bez zgody na zrzut ekranu dymek nie zadziała.")
            return
        }
        val service = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data)
        startForegroundService(service)
        toast("Dymek włączony. Otwórz quiz i stuknij przycisk ?.")
        moveTaskToBack(true)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIFICATIONS = 2
    }
}
