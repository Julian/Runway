package com.grayvines.runway.fixture

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

/** A widget that insists on its setup screen before it can be shown; the screen is all it has. */
class FixtureSetupWidget : android.appwidget.AppWidgetProvider()

/** The setup screen: Done finishes with the id, as a real one would; Cancel finishes without. */
class FixtureWidgetSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        setResult(RESULT_CANCELED)
        // Mid-screen, clear of the system bars the window extends under: a test must be able to
        // tap the buttons, not just find them.
        val buttons =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                fitsSystemWindows = true
            }
        buttons.addView(
            Button(this).apply {
                setText(R.string.fixture_setup_done)
                setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                }
            }
        )
        buttons.addView(
            Button(this).apply {
                setText(R.string.fixture_setup_cancel)
                setOnClickListener { finish() }
            }
        )
        setContentView(buttons)
    }
}
