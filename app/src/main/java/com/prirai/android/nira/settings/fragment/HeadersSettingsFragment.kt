package com.prirai.android.nira.settings.fragment

import android.content.Intent
import android.os.Bundle
import com.prirai.android.nira.R
import com.prirai.android.nira.addons.AddonsActivity
import com.prirai.android.nira.devtools.DeveloperToolsActivity

class SettingsFragment : BaseSettingsFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, s: String?) {
        addPreferencesFromResource(R.xml.preferences_headers)

        // Wire Add-on settings to open AddonsActivity
        findPreference<androidx.preference.Preference>("addons_settings")?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), AddonsActivity::class.java)
            startActivity(intent)
            true
        }

        // Wire Developer tools to open DeveloperToolsActivity
        findPreference<androidx.preference.Preference>("developer_tools")?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), DeveloperToolsActivity::class.java)
            startActivity(intent)
            true
        }
    }
}