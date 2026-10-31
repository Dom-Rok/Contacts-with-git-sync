package org.fossify.contacts.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.contacts.R
import org.fossify.contacts.activities.SimpleActivity
import org.fossify.contacts.databinding.DialogGithubBackupBinding
import org.fossify.contacts.extensions.config
import org.fossify.contacts.helpers.GithubBackup

/**
 * Lets the user choose a GitHub repository as an additional backup destination.
 * The connection is checked before anything is saved.
 */
class GithubBackupDialog(private val activity: SimpleActivity, private val onSuccess: () -> Unit) {
    private val binding = DialogGithubBackupBinding.inflate(activity.layoutInflater)
    private val config = activity.config
    private var ignoreClicks = false

    init {
        binding.apply {
            githubBackupEnabled.isChecked = config.githubBackupEnabled
            githubRepo.setText(config.githubRepo)
            githubBranch.setText(config.githubBranch)
            githubFolder.setText(config.githubFolder)
            if (config.githubToken.isNotEmpty()) {
                githubTokenHint.hint = activity.getString(R.string.github_token_saved)
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.github_backup) { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (!ignoreClicks) {
                            save(dialog)
                        }
                    }
                }
            }
    }

    private fun save(dialog: AlertDialog) {
        if (!binding.githubBackupEnabled.isChecked) {
            config.githubBackupEnabled = false
            dialog.dismiss()
            onSuccess()
            return
        }

        val repo = binding.githubRepo.value.trim().removePrefix("https://github.com/").trim('/').removeSuffix(".git")
        val branch = binding.githubBranch.value.trim()
        val folder = binding.githubFolder.value.trim().trim('/')
        val token = binding.githubToken.value.trim().ifEmpty { config.githubToken }

        when {
            !REPO_PATTERN.matches(repo) -> activity.toast(R.string.github_invalid_repository)
            branch.isEmpty() || token.isEmpty() -> activity.toast(R.string.github_missing_fields)
            else -> {
                ignoreClicks = true
                ensureBackgroundThread {
                    try {
                        GithubBackup.verify(GithubBackup.Target(repo, branch, folder, token))
                        config.apply {
                            githubRepo = repo
                            githubBranch = branch
                            githubFolder = folder
                            githubToken = token
                            githubBackupEnabled = true
                        }

                        activity.runOnUiThread {
                            activity.toast(R.string.github_connection_ok)
                            dialog.dismiss()
                            onSuccess()
                        }
                    } catch (e: Exception) {
                        ignoreClicks = false
                        activity.runOnUiThread {
                            activity.toast(activity.getString(R.string.github_connection_failed, e.message.orEmpty()))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    }
}
