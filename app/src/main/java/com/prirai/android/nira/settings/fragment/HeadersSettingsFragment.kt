package com.prirai.android.nira.settings.fragment

import android.content.Intent
import android.os.Bundle
import com.prirai.android.nira.R
import com.prirai.android.nira.addons.AddonsActivity
import com.prirai.android.nira.devtools.DeveloperToolsActivity
import com.prirai.android.nira.preferences.UserPreferences

class SettingsFragment : BaseSettingsFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, s: String?) {
        addPreferencesFromResource(R.xml.preferences_headers)

        // Wire Add-on settings to open AddonsActivity
        findPreference<androidx.preference.Preference>("addons_settings")?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), AddonsActivity::class.java)
            startActivity(intent)
            true
        }

        // Wire Developer tools to open DeveloperToolsActivity. Hidden when the Developer
        // tools toggle (Settings -> Advanced) is off — otherwise it opens an empty panel.
        findPreference<androidx.preference.Preference>("developer_tools")?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), DeveloperToolsActivity::class.java)
            startActivity(intent)
            true
        }
        applyDeveloperToolsVisibility()
    }

    override fun onResume() {
        super.onResume()
        applyDeveloperToolsVisibility()
    }

    private fun applyDeveloperToolsVisibility() {
        findPreference<androidx.preference.Preference>("developer_tools")?.isVisible =
            UserPreferences(requireContext()).devtoolsEnabled
    }
}