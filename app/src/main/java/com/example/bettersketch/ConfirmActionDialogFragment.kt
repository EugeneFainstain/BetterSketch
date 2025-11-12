package com.example.bettersketch

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class ConfirmActionDialogFragment : DialogFragment() {

    internal fun interface Listener {
        fun onConfirm()
    }

    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setMessage("This will clear the redo history. Are you sure?")
            .setPositiveButton("Yes") { _, _ -> listener?.onConfirm() }
            .setNegativeButton("No", null)
            .create()
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}
