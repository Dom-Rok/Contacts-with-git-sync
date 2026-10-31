# Backup / Export / Import analysis

Scope: how this app (a Fossify Contacts fork, package `org.fossify.contacts`) creates and imports backups.
Based on a read of the code at branch `claude/eager-pasteur-wdpscn`. Nothing was run or tested.

## 1. Summary

- There is **one backup format: vCard (`.vcf`, v4.0 by default)**, read and written with the `ez-vcard` library (0.12.2). There is no proprietary format, no database dump, no compression, no encryption.
- There are **three ways to produce a `.vcf`** and **three ways to consume one**. They share a single exporter (`VcfExporter`) and a single importer (`VcfImporter`).
- "Automatic backups" are just a scheduled call to the same exporter that writes a timestamped `.vcf` into a user-chosen folder. Android 11+ only.
- **There is no git sync in the code.** Despite the repo name, a search for git/sync logic found nothing. The only "sync" hit is README marketing text. Any git integration would have to be added; the natural hook is `Context.backupContacts()` (see section 6).

## 2. Components

| Concern | File |
|---|---|
| vCard write | `helpers/VcfExporter.kt` |
| vCard read | `helpers/VcfImporter.kt` |
| Manual export UI | `dialogs/ExportContactsDialog.kt`, `activities/SettingsActivity.kt` (`tryExportContacts`, `exportContactsTo`) |
| Manual import UI | `dialogs/ImportContactsDialog.kt`, `extensions/Activity.kt` (`tryImportContactsFromFile`) |
| Automatic backup logic | `extensions/Context.kt` (`backupContacts`, `scheduleNextAutomaticBackup`, `checkAndBackupContactsOnBoot`) |
| Automatic backup config UI | `dialogs/ManageAutoBackupsDialog.kt` |
| Triggers | `receivers/AutomaticBackupReceiver.kt`, `receivers/BootCompletedReceiver.kt`, `AndroidManifest.xml` |
| Schedule / constants | `helpers/Constants.kt` (`getNextAutoBackupTime`, `AUTO_BACKUP_INTERVAL_IN_DAYS = 1`) |
| Settings persisted | `helpers/Config.kt`: `autoBackup`, `autoBackupFolder`, `autoBackupFilename`, `autoBackupContactSources`, `lastAutoBackupTime`, `lastExportPath` |

## 3. Creating backups

### 3.1 Manual export (Settings > Export contacts)
1. `ExportContactsDialog` loads all contact sources and contacts, shows source checkboxes with counts, and asks for a filename (default `contacts_<datetime>`).
2. Unchecked sources become an `ignoredContactSources` set.
3. **Android 10+ (Q+)**: the dialog hides the folder field, then `Intent.ACTION_CREATE_DOCUMENT` (`text/x-vcard`) opens the system file picker. The result URI is opened with `contentResolver.openOutputStream`. The ignore set is held in an activity field (`ignoredExportContactSources`), so it is lost if the process dies while the picker is open.
4. **Below Q**: asks for `WRITE_EXTERNAL_STORAGE`, shows the folder picker, refuses if the file already exists (`name_taken`), and writes via `getFileOutputStream`.
5. `exportContactsTo` calls `ContactsHelper.getContacts(..., ignoredSources)` and passes the result to `VcfExporter.exportContacts`, then toasts OK / partial / failed.

### 3.2 Share
`shareContacts()` exports selected contacts to a temp file and fires a share intent, so it uses the same exporter.

### 3.3 Automatic backup (Android 11+ only: `require(isRPlus())`)
- **Enable**: Settings > Enable automatic backups opens `ManageAutoBackupsDialog` (folder, filename pattern, which sources). On OK it saves config and calls `scheduleNextAutomaticBackup()`.
- **Schedule**: `AlarmManagerCompat.setAndAllowWhileIdle(RTC_WAKEUP, ...)`. This is an inexact, one-shot alarm for **06:00 local time**, hardcoded, daily (`getNextAutoBackupTime`).
- **Fire**: `AutomaticBackupReceiver.onReceive` takes a 3-second partial wakelock and calls `Context.backupContacts()`.
- **`backupContacts()`**:
  1. On a background thread, loads contacts via `ContactsHelper.getContactsToExport(selectedContactSources)`.
  2. If there are none: toast, update `lastAutoBackupTime`, reschedule, stop.
  3. Builds the filename from the template. Tokens: `%Y %M %D %h %m %s` (default `Contacts_%Y%M%D_%h%m%s`).
  4. `mkdirs()` on the target folder, then opens an output stream. If a SAF tree URI has been granted for the path, it creates the document and opens it with mode `"wt"` (truncate). Otherwise it uses a plain `FileOutputStream`, adding a `_N` suffix only if the file exists **and** is not writable.
  5. Runs `VcfExporter.exportContacts`, sets `lastAutoBackupTime = now`, and reschedules for the next 06:00.
- **Boot catch-up**: `BootCompletedReceiver` (BOOT_COMPLETED, QUICKBOOT_POWERON, MY_PACKAGE_REPLACED) runs `checkAndBackupContactsOnBoot()`. If `lastAutoBackupTime` is before the most recent scheduled 06:00, it backs up immediately.

### 3.4 What gets written (`VcfExporter`)
One vCard per contact: FN, N (prefix/given/additional/family/suffix), nickname, phones (type + `PREF=1`), emails, birthday/anniversary events (full date or `--MM-DD` partial), addresses, IMPP (known protocols mapped, else custom label), notes, ORG + TITLE, URLs, PHOTO (read from `photoUri`, always labelled JPEG), and CATEGORIES (the contact's group names).

Not exported: starred/favorite flag, ringtone, custom event types (only birthday and anniversary), contact source/account, custom phone/email/address labels beyond a plain TYPE string.

## 4. Importing backups

### 4.1 Entry points
1. **Settings > Import contacts**. On Q+ it uses `ACTION_GET_CONTENT` (`text/x-vcard`); below Q it asks for storage permission and shows a file picker.
2. **Open or share a `.vcf` into the app**. `MainActivity.handleExternalIntent` handles `ACTION_VIEW` and `ACTION_SEND` (manifest filters for `text/vcard` and `text/x-vcard`). Both then refresh the contact list on success.

### 4.2 Flow
1. `tryImportContactsFromFile(uri)`: a `file://` URI is used directly. A `content://` URI is copied to a temp file `import-<millis>-<DEFAULT_FILE_NAME>`. Anything else shows "invalid file format". The temp file is never deleted by this code.
2. `ImportContactsDialog` asks which **target contact source** to import into. The default is the last used source, falling back to the private/local source `SMT_PRIVATE`.
3. On OK, `VcfImporter.importContacts(path, targetSource)` runs on a background thread.

### 4.3 What `VcfImporter` does
For each parsed card it builds a `Contact` (id 0, so always a new row) and inserts it with `ContactsHelper.insertContact`.
- Names, nickname, phones, emails, addresses, events, URLs, IMs, and the first photo (re-encoded to JPEG at quality 100 into the cache).
- Categories become groups. A group is matched by exact title, else created in `groupsDB`.
- A card with only FN (no N, no ORG) is treated as an organization contact, and `isABusinessContact()` also forces the organization mimetype.
- Legacy handling: a birthday year of 1900 is converted to `--MM-DD`.
- It keeps only the first note, first ORG value, first TITLE, and first photo. Phone and email types use only the first TYPE (phones also read the second as a fax subtype).
- Starred is hard-coded to 0 and ringtone to null.

Result mapping: 0 imported = FAIL; any `contactsFailed` = PARTIAL; otherwise OK.

## 5. Issues and risks found

Ordered by impact. These are from code reading, not from running the app.

1. **Automatic backups can stop after a reboot (likely bug).** `AlarmManager` alarms are cleared on reboot. `checkAndBackupContactsOnBoot()` only calls `backupContacts()` (which reschedules) when a backup was *missed*. If the phone reboots after the day's 06:00 backup, nothing calls `scheduleNextAutomaticBackup()`. The only other callers are the settings dialogs and the backup itself, so no alarm exists until the user toggles the setting. The next day's backup is then skipped. The following boot would catch up only if it lands after a 06:00 that was missed.
2. **A failed backup is recorded as successful.** In `backupContacts()` the exporter result only triggers a toast. `lastAutoBackupTime` is updated and the next run scheduled regardless, so the boot catch-up will not retry.
3. **Importer aborts on the first bad card.** The try/catch wraps the entire loop, so one exception (for example `getPreferredValue` calling `toInt()` on a malformed `PREF=`, or a bad photo) stops the import. Contacts already inserted stay inserted. `contactsFailed` is only ever incremented in that outer catch, and a `false` from `insertContact` is not counted as a failure. "Partial" therefore effectively means "stopped part-way".
4. **Exporter never reports partial failure.** `contactsFailed` is never incremented in `VcfExporter`, so `EXPORT_PARTIAL` is unreachable. `contactsExported` is incremented while cards are only being built, before `Ezvcard.write` runs. A write error is caught, a toast shown, and the callback may still report `EXPORT_OK`.
5. **No de-duplication on import.** Contact id is always 0 and nothing is matched against existing data. Importing the same backup twice duplicates every contact, so a `.vcf` is a data dump, not a restore that can be safely re-applied.
6. **Lossy round trip.** Starred/favorites, ringtones, non-birthday/anniversary events, multiple notes, and the source account are not preserved (see 3.4 and 4.3). Photos are always re-encoded as JPEG.
7. **Backups accumulate without limit.** The default template includes a timestamp to the second, so every day adds a new file with no rotation or retention setting. If the user's template has no tokens, the file is silently overwritten on the SAF path (`"wt"`).
8. **Address export condition looks inverted.** The structured-address branch is taken when *any* of the seven fields is empty (a `fold` with `||` over `isEmpty()`), and the flat `value` string is used only when all seven are non-empty. The intent was probably the opposite or "all empty". Worth confirming against real exports.
9. **Wakelock is shorter than the work.** The receiver holds a 3 s wakelock, but the export runs on a separate thread and can easily take longer with many contacts or photos. The device may sleep mid-backup.
10. **Resource handling.** The importer's `InputStream` is never closed, and temp import files are never deleted. The exporter does not explicitly close the output stream and relies on ez-vcard's behavior (not verified here).
11. **Security / privacy.** Backups are plaintext vCards (including photos and notes) in a user-chosen folder. There is no encryption. `android:allowBackup="true"` also lets Android's own backup include app data.
12. **Platform limits.** Automatic backup is hidden below Android 11 (`isRPlus()`), and the time is fixed at 06:00 with a 1-day interval.

## 6. Notes for adding git sync

- The cleanest hook is the end of `Context.backupContacts()`: after the `.vcf` is written, commit and push the backup folder. The output file path is already known there (`exportFilePath`).
- Because the filename defaults to a timestamp, a git history would be full of one-file-per-day additions. For useful diffs, use a stable filename (no tokens) and let git hold the history. This also removes the rotation problem in item 7.
- `VcfExporter` writes contacts in the order returned by `ContactsHelper`, with no explicit sort. Stable ordering would be needed for clean diffs.
- Run-time constraints to design for: this work runs from a receiver, so needs a proper background mechanism (see items 1, 2, 9), plus credential storage and network handling, which the app has none of today.
- Fix items 1 and 2 first; otherwise a sync layer built on top inherits unreliable scheduling.
