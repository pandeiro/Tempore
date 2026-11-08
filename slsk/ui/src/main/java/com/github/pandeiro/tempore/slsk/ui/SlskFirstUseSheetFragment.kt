package com.github.pandeiro.tempore.slsk.ui

import com.github.pandeiro.tempore.slsk.ui.databinding.*

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** First-use notice (spec §5.2): expectation-setting copy, shown once. */
class SlskFirstUseSheetFragment : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val bind = SlskSheetFirstUseBinding.inflate(inflater, container, false)
        bind.slskFirstUseGotIt.setOnClickListener { dismiss() }
        return bind.root
    }
}
