package eu.kanade.tachiyomi.ui.more

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.AppUpdateResult
import eu.kanade.tachiyomi.data.updater.AppUpdateService
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import io.noties.markwon.Markwon

class NewUpdateDialogController(bundle: Bundle? = null) : DialogController(bundle) {

    constructor(update: AppUpdateResult.NewUpdate) : this(
        bundleOf(BODY_KEY to update.release.info, URL_KEY to update.release.getDownloadLink()),
    )

    override fun onCreateDialog(savedViewState: Bundle?): Dialog {
        val releaseBody = (args.getString(BODY_KEY) ?: "")
            .replace("""---(\R|.)*Checksums(\R|.)*""".toRegex(), "")
        val info = Markwon.create(activity!!).toMarkdown(releaseBody)

        return MaterialAlertDialogBuilder(activity!!)
            .setTitle(R.string.update_check_notification_update_available)
            .setMessage(info)
            .setPositiveButton(R.string.update_check_confirm) { _, _ ->
                applicationContext?.let { context ->
                    // Start download
                    val url = args.getString(URL_KEY) ?: ""
                    AppUpdateService.start(context, url)
                }
            }
            .setNegativeButton(R.string.update_check_ignore, null)
            .create()
    }
}

private const val BODY_KEY = "NewUpdateDialogController.body"
private const val URL_KEY = "NewUpdateDialogController.key"
