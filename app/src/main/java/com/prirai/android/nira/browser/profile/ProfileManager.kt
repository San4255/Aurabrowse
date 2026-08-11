package com.prirai.android.nira.browser.profile

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.prirai.android.nira.ext.components
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Manages browser profiles - creation, deletion, and persistence
 * Profiles are stored in SharedPreferences as JSON
 */
class ProfileManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val profileListType = Types.newParameterizedType(List::class.java, BrowserProfile::class.java)
    private val profileListAdapter = moshi.adapter<List<BrowserProfile>>(profileListType)
    
    companion object {
        private const val PREFS_NAME = "profile_manager"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_LAST_PRIVATE_PROFILE = "last_private_profile"
        private const val KEY_DEFAULT_PROFILE_NAME = "default_profile_name"
        
        @Volatile
        private var instance: ProfileManager? = null
        
        fun getInstance(context: Context): ProfileManager {
            return instance ?: synchronized(this) {
                instance ?: ProfileManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * Get all profiles (excluding private mode)
     * Always includes the default profile
     */
    fun getAllProfiles(): List<BrowserProfile> {
        val json = prefs.getString(KEY_PROFILES, null)
        val profiles = if (json != null) {
            profileListAdapter.fromJson(json) ?: emptyList()
        } else {
            emptyList()
        }
        
        // Always ensure default profile exists
        val defaultProfile = getDefaultProfile()
        return if (profiles.none { it.id == defaultProfile.id }) {
            listOf(defaultProfile) + profiles
        } else {
            profiles
        }
    }

    /**
     * Default profile with the user-renamed name applied (if any).
     */
    private fun getDefaultProfile(): BrowserProfile {
        val base = BrowserProfile.getDefaultProfile()
        val storedName = prefs.getString(KEY_DEFAULT_PROFILE_NAME, null)
        return if (storedName.isNullOrBlank()) base else base.copy(name = storedName)
    }
    
    /**
     * Get the currently active profile
     */
    fun getActiveProfile(): BrowserProfile {
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, "default")
        return getAllProfiles().find { it.id == activeId } ?: getDefaultProfile()
    }
    
    /**
     * Set the active profile
     */
    fun setActiveProfile(profile: BrowserProfile) {
        prefs.edit { putString(KEY_ACTIVE_PROFILE_ID, profile.id)}
    }
    
    /**
     * Create a new profile
     */
    fun createProfile(name: String, color: Int, emoji: String): BrowserProfile {
        val newProfile = BrowserProfile(
            name = name,
            color = color,
            emoji = emoji,
            isDefault = false
        )
        
        val profiles = getAllProfiles().toMutableList()
        profiles.add(newProfile)
        saveProfiles(profiles)
        
        return newProfile
    }
    
    /**
     * Update an existing profile
     */
    fun updateProfile(profile: BrowserProfile) {
        if (profile.isDefault) {
            // Default profile isn't stored in the profiles list, keep its name override separately
            prefs.edit { putString(KEY_DEFAULT_PROFILE_NAME, profile.name) }
            return
        }

        val profiles = getAllProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index != -1) {
            profiles[index] = profile
            saveProfiles(profiles)
        }
    }
    
    /**
     * Delete a profile (cannot delete default)
     */
    fun deleteProfile(profileId: String) {
        if (profileId == "default") {
            throw IllegalArgumentException("Cannot delete default profile")
        }
        
        val profiles = getAllProfiles().toMutableList()
        profiles.removeAll { it.id == profileId }
        saveProfiles(profiles)
        
        // If deleted profile was active, switch to default
        if (getActiveProfile().id == profileId) {
            setActiveProfile(getDefaultProfile())
        }
        
        // Clean up profile-specific storage
        cleanupProfileStorage(profileId)
    }
    
    /**
     * Check if we're in private browsing mode
     */
    fun isPrivateMode(): Boolean {
        return prefs.getBoolean(KEY_LAST_PRIVATE_PROFILE, false)
    }
    
    /**
     * Set private browsing mode
     */
    fun setPrivateMode(isPrivate: Boolean) {
        prefs.edit { putBoolean(KEY_LAST_PRIVATE_PROFILE, isPrivate) }
    }
    
    private fun saveProfiles(profiles: List<BrowserProfile>) {
        // Don't save default profile to prefs, it's generated
        val toSave = profiles.filter { !it.isDefault }
        val json = profileListAdapter.toJson(toSave)
        prefs.edit { putString(KEY_PROFILES, json) }
    }
    
    private fun cleanupProfileStorage(profileId: String) {
        // Delete profile-specific session storage
        val profileDir = context.getDir("profile_$profileId", Context.MODE_PRIVATE)
        profileDir.deleteRecursively()
    }

    /**
     * Migrate a tab to another profile by updating its contextId.
     * The tab is recreated with the target profile's contextId (GeckoView sessions
     * cannot change contextId in place), so the returned value is the NEW tab id.
     * @param tabId The ID of the tab to migrate
     * @param targetProfileId The ID of the target profile ("private" for private mode)
     * @return the id of the recreated tab, or null if migration failed / already in target profile
     */
    fun migrateTabToProfile(tabId: String, targetProfileId: String): String? {
        val store = context.components.store
        val tab = store.state.tabs.find { it.id == tabId } ?: return null

        // Don't migrate if already in the target profile
        val currentContextId = tab.contextId
        val targetContextId = if (targetProfileId == "private") "private" else "profile_$targetProfileId"
        if (currentContextId == targetContextId) return null

        val isTargetPrivate = targetProfileId == "private"

        // Always recreate the tab with the new context and privacy mode
        val url = tab.content.url
        val title = tab.content.title
        val isSelected = store.state.selectedTabId == tabId

        // Remove old tab
        context.components.tabsUseCases.removeTab(tabId)

        // Create new tab with correct context (returns the new tab id)
        return context.components.tabsUseCases.addTab(
            url = url,
            private = isTargetPrivate,
            contextId = targetContextId,
            selectTab = isSelected,
            title = title
        )
    }

    /**
     * Migrate multiple tabs to another profile
     * @param tabIds List of tab IDs to migrate
     * @param targetProfileId The ID of the target profile ("private" for private mode)
     * @return the new ids of the successfully migrated tabs (empty if none)
     */
    fun migrateTabsToProfile(tabIds: List<String>, targetProfileId: String): List<String> {
        return tabIds.mapNotNull { tabId ->
            migrateTabToProfile(tabId, targetProfileId)
        }
    }

    /**
     * Open a fresh copy of a tab in another profile, leaving the original tab in place.
     * The copy uses the target profile's storage context, so it starts with no cookies /
     * login state from the current profile.
     * @param tabId The ID of the tab to copy
     * @param targetProfileId The ID of the target profile ("private" for private mode)
     * @param selectNewTab Whether the new tab should become the selected tab
     * @return the id of the new tab, or null if the source tab wasn't found
     */
    fun copyTabToProfile(tabId: String, targetProfileId: String, selectNewTab: Boolean = true): String? {
        val store = context.components.store
        val tab = store.state.tabs.find { it.id == tabId } ?: return null

        val targetContextId = if (targetProfileId == "private") "private" else "profile_$targetProfileId"
        val isTargetPrivate = targetProfileId == "private"

        return context.components.tabsUseCases.addTab(
            url = tab.content.url,
            private = isTargetPrivate,
            contextId = targetContextId,
            selectTab = selectNewTab,
            title = tab.content.title
        )
    }

    /**
     * Switch the active profile and browsing mode so the target profile is visible.
     * Handles the "private" pseudo-profile and normal profiles.
     */
    fun activateProfileAndMode(targetProfileId: String, activity: com.prirai.android.nira.BrowserActivity) {
        if (targetProfileId == "private") {
            activity.browsingModeManager.mode = com.prirai.android.nira.browser.BrowsingMode.Private
        } else {
            val target = getAllProfiles().find { it.id == targetProfileId }
            if (target != null) {
                setActiveProfile(target)
                activity.browsingModeManager.mode = com.prirai.android.nira.browser.BrowsingMode.Normal
                activity.browsingModeManager.currentProfile = target
            }
        }
    }
}
