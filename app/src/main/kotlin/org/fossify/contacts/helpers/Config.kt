package org.fossify.contacts.helpers

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import org.fossify.commons.helpers.SHOW_TABS

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    var autoBackupContactSources: Set<String>
        get() = prefs.getStringSet(AUTO_BACKUP_CONTACT_SOURCES, setOf())!!
        set(autoBackupContactSources) = prefs.edit().remove(AUTO_BACKUP_CONTACT_SOURCES).putStringSet(AUTO_BACKUP_CONTACT_SOURCES, autoBackupContactSources)
            .apply()

    var autoBackupOnChange: Boolean
        get() = prefs.getBoolean(AUTO_BACKUP_ON_CHANGE, false)
        set(autoBackupOnChange) = prefs.edit().putBoolean(AUTO_BACKUP_ON_CHANGE, autoBackupOnChange).apply()

    var githubBackupEnabled: Boolean
        get() = prefs.getBoolean(GITHUB_BACKUP_ENABLED, false)
        set(githubBackupEnabled) = prefs.edit().putBoolean(GITHUB_BACKUP_ENABLED, githubBackupEnabled).apply()

    /** "owner/name" */
    var githubRepo: String
        get() = prefs.getString(GITHUB_BACKUP_REPO, "")!!
        set(githubRepo) = prefs.edit().putString(GITHUB_BACKUP_REPO, githubRepo).apply()

    var githubBranch: String
        get() = prefs.getString(GITHUB_BACKUP_BRANCH, "main")!!
        set(githubBranch) = prefs.edit().putString(GITHUB_BACKUP_BRANCH, githubBranch).apply()

    /** Folder inside the repository, empty for the repository root. */
    var githubFolder: String
        get() = prefs.getString(GITHUB_BACKUP_FOLDER, "contacts-backup")!!
        set(githubFolder) = prefs.edit().putString(GITHUB_BACKUP_FOLDER, githubFolder).apply()

    /** Stored encrypted with a Keystore key, see [SecretStore]. Empty when unset or undecryptable. */
    var githubToken: String
        get() = prefs.getString(GITHUB_BACKUP_TOKEN, "")!!.let { if (it.isEmpty()) "" else SecretStore.decrypt(it).orEmpty() }
        set(githubToken) = prefs.edit().putString(GITHUB_BACKUP_TOKEN, if (githubToken.isEmpty()) "" else SecretStore.encrypt(githubToken)).apply()
}
