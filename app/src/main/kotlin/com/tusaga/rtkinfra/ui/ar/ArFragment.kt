package com.tusaga.rtkinfra.ui.ar

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
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

    private var arSession: Session? = null
    private lateinit var arRenderer: ArRenderer

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupArSession()
        setupGlSurface()
        observeInfraData()
    }

    private fun setupArSession() {
        if (!ArCoreApk.getInstance().checkAvailability(requireContext()).isSupported) {
            Toast.makeText(requireContext(), "ARCore not supported on this device",
                Toast.LENGTH_LONG).show()
            return
        }

        try {
            arSession = Session(requireContext()).also { session ->
                val config = Config(session).apply {
                    // Use LATEST_CAMERA_IMAGE for low-latency AR
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    // Enable plane finding for surface detection
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    // Enable depth API if available
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
                    // Use highest accuracy camera pose
                    focusMode = Config.FocusMode.AUTO
                }
                session.configure(config)
                anchorManager.setArSession(session)
                arRenderer.arSession = session
            }
        } catch (e: UnavailableArcoreNotInstalledException) {
            Timber.e(e, "ARCore not installed")
            Toast.makeText(requireContext(), "Please install ARCore", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Timber.e(e, "AR Session setup failed")
        }
    }

    private fun setupGlSurface() {
        arRenderer = ArRenderer(requireContext(), anchorManager)
        arRenderer.onSessionUpdated = { _ ->
            // Update AR anchors on each frame on GL thread
            val dataset = viewModel.infraDataset.value ?: return@onSessionUpdated
            arRenderer.features = dataset.features
            arRenderer.currentRtkState = viewModel.rtkState.value
        }

        binding.glSurfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(arRenderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    private fun observeInfraData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rtkState.collect { state ->
                binding.rtkOverlayText.text = buildString {
                    append("${state.fixType.label} | ${state.accuracyDisplay}\n")
                    append("${state.usedSatellites} sats")
                    if (state.dualFrequencyAvailable) append(" | L1+L5")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            arSession?.resume()
            binding.glSurfaceView.onResume()
        } catch (e: CameraNotAvailableException) {
            Timber.e(e, "AR: Camera not available on resume")
        }
    }

    override fun onPause() {
        super.onPause()
        binding.glSurfaceView.onPause()
        arSession?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        arSession?.close()
        arSession = null
        _binding = null
    }
}
