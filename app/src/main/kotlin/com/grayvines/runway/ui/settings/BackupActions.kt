package com.grayvines.runway.ui.settings

/** Saving the launcher to a file the user picks, and reading one back. */
data class BackupActions(val save: () -> Unit, val restore: () -> Unit)
