package org.fossify.contacts.extensions

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.FileProvider
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.BuildConfig
import org.fossify.contacts.R
import org.fossify.contacts.helpers.AUTOMATIC_BACKUP_REQUEST_CODE
import org.fossify.contacts.helpers.BACKUP_ON_CHANGE_DELAY_MS
import org.fossify.contacts.helpers.BACKUP_ON_CHANGE_FILE_NAME
import org.fossify.contacts.helpers.BACKUP_ON_CHANGE_JOB_ID
import org.fossify.contacts.helpers.GithubBackup
import org.fossify.contacts.helpers.TeeOutputStream
import org.fossify.contacts.helpers.Config
import org.fossify.contacts.helpers.VcfExporter
import org.fossify.contacts.helpers.getNextAutoBackupTime
import org.fossify.contacts.helpers.getPreviousAutoBackupTime
import org.fossify.contacts.receivers.AutomaticBackupReceiver
import org.fossify.contacts.services.ContactChangeBackupService
import org.joda.time.DateTime
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

val Context.config: Config get() = Config.newInstance(applicationContext)
fun Context.getCachePhotoUri(file: File = getCachePhoto()) = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.provider", file)

@SuppressLint("UseCompatLoadingForDrawables")
fun Context.getPackageDrawable(packageName: String): Drawable {
    return resources.getDrawable(
        when (packageName) {
            TELEGRAM_PACKAGE -> R.drawable.ic_telegram_rect_vector
            SIGNAL_PACKAGE -> R.drawable.ic_signal_rect_vector
            WHATSAPP_PACKAGE -> R.drawable.ic_whatsapp_rect_vector
            VIBER_PACKAGE -> R.drawable.ic_viber_rect_vector
            else -> R.drawable.ic_threema_rect_vector
        }, theme
    )
}

fun Context.getAutomaticBackupIntent(): PendingIntent {
    val intent = Intent(this, AutomaticBackupReceiver::class.java)
    return PendingIntent.getBroadcast(this, AUTOMATIC_BACKUP_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.scheduleNextAutomaticBackup() {
    if (config.autoBackup) {
        val backupAtMillis = getNextAutoBackupTime().millis
        val pendingIntent = getAutomaticBackupIntent()
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            AlarmManagerCompat.setAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, backupAtMillis, pendingIntent)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun Context.cancelScheduledAutomaticBackup() {
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.cancel(getAutomaticBackupIntent())
}

fun Context.checkAndBackupContactsOnBoot() {
    if (config.autoBackup) {
        val previousRealBackupTime = config.lastAutoBackupTime
        val previousScheduledBackupTime = getPreviousAutoBackupTime().millis
        val missedPreviousBackup = previousRealBackupTime < previousScheduledBackupTime
        if (missedPreviousBackup) {
            // device was probably off at the scheduled time so backup now
            backupContacts()
        }
    }
}

/**
 * Schedules a debounced backup after the user added, edited or deleted a contact.
 * Calling it again before the job runs replaces the pending job, so a burst of changes produces one backup.
 * Does nothing unless automatic backups and "back up on change" are both enabled.
 */
fun Context.scheduleBackupOnContactChange() {
    if (!isRPlus() || !config.autoBackup || !config.autoBackupOnChange) {
        return
    }

    try {
        val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobInfo = JobInfo.Builder(BACKUP_ON_CHANGE_JOB_ID, ComponentName(this, ContactChangeBackupService::class.java))
            .setMinimumLatency(BACKUP_ON_CHANGE_DELAY_MS)
            .build()
        jobScheduler.schedule(jobInfo)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.cancelBackupOnContactChange() {
    val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    jobScheduler.cancel(BACKUP_ON_CHANGE_JOB_ID)
}

/**
 * @param changeTriggered true when called because a contact changed. Such a backup always overwrites one stable file
 * ([BACKUP_ON_CHANGE_FILE_NAME]) instead of creating a new timestamped file, and leaves the daily schedule untouched.
 * @param onFinished called once on every exit path, after the work is done.
 */
fun Context.backupContacts(changeTriggered: Boolean = false, onFinished: () -> Unit = {}) {
    require(isRPlus())
    ensureBackgroundThread {
        val config = config
        ContactsHelper(this).getContactsToExport(selectedContactSources = config.autoBackupContactSources) { contactsToBackup ->
            if (contactsToBackup.isEmpty()) {
                if (!changeTriggered) {
                    toast(org.fossify.commons.R.string.no_entries_for_exporting)
                    config.lastAutoBackupTime = DateTime.now().millis
                    scheduleNextAutomaticBackup()
                }
                onFinished()
                return@getContactsToExport
            }

            val filename = if (changeTriggered) {
                BACKUP_ON_CHANGE_FILE_NAME
            } else {
                val now = DateTime.now()
                val year = now.year.toString()
                val month = now.monthOfYear.ensureTwoDigits()
                val day = now.dayOfMonth.ensureTwoDigits()
                val hours = now.hourOfDay.ensureTwoDigits()
                val minutes = now.minuteOfHour.ensureTwoDigits()
                val seconds = now.secondOfMinute.ensureTwoDigits()

                config.autoBackupFilename
                    .replace("%Y", year, false)
                    .replace("%M", month, false)
                    .replace("%D", day, false)
                    .replace("%h", hours, false)
                    .replace("%m", minutes, false)
                    .replace("%s", seconds, false)
            }

            val outputFolder = File(config.autoBackupFolder).apply {
                mkdirs()
            }

            var exportFile = File(outputFolder, "$filename.vcf")
            var exportFilePath = exportFile.absolutePath
            val outputStream = try {
                if (hasProperStoredFirstParentUri(exportFilePath)) {
                    val exportFileUri = createDocumentUriUsingFirstParentTreeUri(exportFilePath)
                    if (!getDoesFilePathExist(exportFilePath)) {
                        createSAFFileSdk30(exportFilePath)
                    }
                    applicationContext.contentResolver.openOutputStream(exportFileUri, "wt") ?: FileOutputStream(exportFile)
                } else {
                    var num = 0
                    while (getDoesFilePathExist(exportFilePath) && !exportFile.canWrite()) {
                        num++
                        exportFile = File(outputFolder, "${filename}_${num}.vcf")
                        exportFilePath = exportFile.absolutePath
                    }
                    FileOutputStream(exportFile)
                }
            } catch (e: Exception) {
                showErrorToast(e)
                if (!changeTriggered) {
                    scheduleNextAutomaticBackup()
                }
                onFinished()
                return@getContactsToExport
            }

            // when GitHub is enabled, keep a copy of the exported bytes so they can be uploaded after the local write
            val githubCopy = if (config.githubBackupEnabled) ByteArrayOutputStream() else null
            val exportStream: OutputStream = if (githubCopy != null) TeeOutputStream(outputStream, githubCopy) else outputStream
            var exportSucceeded = true

            VcfExporter().exportContacts(
                context = this,
                outputStream = exportStream,
                contacts = contactsToBackup.toMutableList() as ArrayList<Contact>,
                showExportingToast = false
            ) { exportResult ->
                if (exportResult == VcfExporter.ExportResult.EXPORT_FAIL) {
                    exportSucceeded = false
                    toast(org.fossify.commons.R.string.exporting_failed)
                }
            }

            if (githubCopy != null && exportSucceeded) {
                uploadBackupToGithub(config, githubCopy.toByteArray())
            }

            if (!changeTriggered) {
                config.lastAutoBackupTime = DateTime.now().millis
                scheduleNextAutomaticBackup()
            }
            onFinished()
        }
    }
}

private fun Context.uploadBackupToGithub(config: Config, content: ByteArray) {
    val target = GithubBackup.Target(
        repo = config.githubRepo,
        branch = config.githubBranch,
        folder = config.githubFolder,
        token = config.githubToken,
    )

    if (target.repo.isEmpty() || target.branch.isEmpty() || target.token.isEmpty()) {
        toast(getString(R.string.github_not_configured))
        return
    }

    try {
        GithubBackup.upload(target, content, "Contacts backup ${DateTime.now()}")
    } catch (e: Exception) {
        toast(getString(R.string.github_upload_failed, e.message.orEmpty()), android.widget.Toast.LENGTH_LONG)
    }
}

fun Context.copyUriToTempFile(uri: Uri, name: String): File? {
    val tempFile = getTempFile(name)
    contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
        }
    }

    return tempFile
}
