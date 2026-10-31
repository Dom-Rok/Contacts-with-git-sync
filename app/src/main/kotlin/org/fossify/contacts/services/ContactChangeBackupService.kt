package org.fossify.contacts.services

import android.app.job.JobParameters
import android.app.job.JobService
import org.fossify.contacts.extensions.backupContacts
import org.fossify.contacts.extensions.config

/**
 * Runs the backup scheduled by [org.fossify.contacts.extensions.scheduleBackupOnContactChange].
 */
class ContactChangeBackupService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        if (!config.autoBackup || !config.autoBackupOnChange) {
            return false
        }

        backupContacts(changeTriggered = true) {
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
