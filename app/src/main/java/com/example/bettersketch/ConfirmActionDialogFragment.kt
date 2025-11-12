package com.example.bettersketch

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class ConfirmActionDialogFragment : DialogFragment() {

    internal interface Listener {
        fun onConfirmDiscardRedo()
        fun onConfirmInsertStroke()
        fun onCancel()
    }

    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setMessage("You have a redo history. What would you like to do?")
            .setPositiveButton("Discard Redo & Draw") { _, _ -> listener?.onConfirmDiscardRedo() }
            .setNeutralButton("Insert Stroke") { _, _ -> listener?.onConfirmInsertStroke() }
            .setNegativeButton("Cancel") { _, _ -> listener?.onCancel() }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        listener?.onCancel()
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}
