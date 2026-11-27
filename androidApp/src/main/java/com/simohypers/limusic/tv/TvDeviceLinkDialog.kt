package com.simohypers.limusic.tv

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import com.simohypers.limusic.R
import com.simohypers.limusic.domain.data.model.listentogether.PairResult
import com.simohypers.limusic.domain.repository.DeviceLinkRepository
import com.simohypers.limusic.viewModel.ListenTogetherViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class TvDeviceLinkDialog(
    private val activity: Activity,
    private val repository: DeviceLinkRepository,
    private val scope: CoroutineScope,
) {
    fun show() {
        val link = repository.link.value
        val actions =
            buildList {
                add(R.string.tv_show_pairing_code to ::showCode)
                if (link.paired) {
                    add(R.string.tv_unlink_this_tv to ::confirmUnlink)
                } else {
                    add(R.string.tv_enter_pairing_code to ::enterCode)
                }
            }
        val devices = link.devices.joinToString(", ") { it.name }
        val title =
            if (devices.isEmpty()) activity.getString(R.string.tv_link_devices)
            else activity.getString(R.string.tv_linked_devices, devices)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(actions.map { activity.getString(it.first) }.toTypedArray()) { _, index ->
                actions[index].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCode() {
        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(R.string.tv_show_pairing_code)
                .setMessage(R.string.tv_requesting_code)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        val job =
            scope.launch {
                try {
                    val invite = repository.createPairingInvite()
                    if (invite == null) {
                        dialog.setMessage(activity.getString(R.string.tv_link_unreachable))
                        return@launch
                    }
                    val code = ListenTogetherViewModel.makeInvite(invite.server, invite.code)
                    dialog.setMessage(activity.getString(R.string.tv_pairing_code_message, code, invite.expiresInSeconds))
                    delay(invite.expiresInSeconds.coerceAtLeast(0) * 1000L)
                    dialog.setMessage(activity.getString(R.string.tv_pairing_code_expired))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    dialog.setMessage(activity.getString(R.string.tv_link_unreachable))
                }
            }
        dialog.setOnDismissListener { job.cancel() }
    }

    private fun enterCode() {
        val input =
            EditText(activity).apply {
                setSingleLine()
                hint = activity.getString(R.string.tv_pairing_code_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(R.string.tv_enter_pairing_code)
                .setView(input)
                .setPositiveButton(R.string.tv_link_devices, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        submit.setOnClickListener {
            val (server, code) = ListenTogetherViewModel.parseInvite(input.text.toString()) ?: ("" to "")
            if (!Regex("[A-Z0-9]{6}").matches(code)) {
                input.error = activity.getString(R.string.tv_invalid_pairing_code)
                return@setOnClickListener
            }
            submit.isEnabled = false
            val job =
                scope.launch {
                    val result =
                        try {
                            repository.pair(if (server == ListenTogetherViewModel.DEFAULT_SERVER_URL) "" else server, code)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            PairResult.Unreachable
                        }
                    if (result == PairResult.Linked) {
                        dialog.dismiss()
                    } else {
                        val error =
                            when (result) {
                                PairResult.InvalidCode -> R.string.tv_invalid_pairing_code
                                PairResult.Unsupported -> R.string.tv_link_unsupported
                                else -> R.string.tv_link_unreachable
                            }
                        input.error = activity.getString(error)
                        submit.isEnabled = true
                    }
                }
            dialog.setOnDismissListener { job.cancel() }
        }
    }

    private fun confirmUnlink() {
        AlertDialog.Builder(activity)
            .setMessage(R.string.tv_unlink_this_tv_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.tv_unlink_this_tv) { _, _ -> repository.unlinkThisPhone() }
            .show()
    }
}
