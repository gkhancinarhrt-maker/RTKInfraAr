package com.tusaga.rtkinfra.ui.ar

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.tusaga.rtkinfra.ar.ArRenderer
import com.tusaga.rtkinfra.ar.InfrastructureAnchorManager
import com.tusaga.rtkinfra.databinding.FragmentArBinding
import com.tusaga.rtkinfra.ui.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class ArFragment : Fragment() {

    @Inject lateinit var anchorManager: InfrastructureAnchorManager
    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentArBinding? = null
    private val binding get() = _binding!!
    private lateinit var arRenderer: ArRenderer

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRenderer()
        observeData()
    }

    private fun setupRenderer() {
        arRenderer = ArRenderer(requireContext(), anchorManager)

        val callback: (com.google.ar.core.Frame) -> Unit = { frame ->
            val dataset = viewModel.infraDataset.value
            if (dataset != null) {
                arRenderer.features = dataset.features
            }
            arRenderer.currentRtkState = viewModel.rtkState.value
        }
        arRenderer.onSessionUpdated = callback

        binding.glSurfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(arRenderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rtkState.collect { state ->
                binding.rtkOverlayText.text =
                    "${state.fixType.label} | ${state.accuracyDisplay} | ${state.usedSatellites} sats"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.glSurfaceView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
