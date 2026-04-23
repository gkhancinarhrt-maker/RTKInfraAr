package com.tusaga.rtkinfra.ui.settings

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.snackbar.Snackbar
import com.tusaga.rtkinfra.databinding.FragmentSettingsBinding
import com.tusaga.rtkinfra.ntrip.NtripConfig
import com.tusaga.rtkinfra.ui.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pre-fill with existing config
        val config = viewModel.getNtripConfig()
        binding.apply {
            editHost.setText(config.host)
            editPort.setText(config.port.toString())
            editMount.setText(config.mountpoint)
            editUsername.setText(config.username)
            editPassword.setText(config.password)
        }

        binding.btnSave.setOnClickListener {
            saveConfig()
        }

        // Common TUSAGA mountpoints quick-select
        setupMountpointChips()
    }

    private fun setupMountpointChips() {
        val mountpoints = mapOf(
            "VRS Turkey"   to "TUSK00TUR0",
            "Istanbul"     to "IST000TUR0",
            "Ankara"       to "ANK000TUR0",
            "Izmir"        to "IZM000TUR0",
            "Bursa"        to "BRS000TUR0",
            "Antalya"      to "ANT000TUR0",
            "Konya"        to "KON000TUR0"
        )

        mountpoints.forEach { (label, mount) ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = label
                isCheckable = true
                setOnClickListener {
                    binding.editMount.setText(mount)
                }
            }
            binding.mountpointChipGroup.addView(chip)
        }
    }

    private fun saveConfig() {
        val host  = binding.editHost.text.toString().trim()
        val port  = binding.editPort.text.toString().toIntOrNull() ?: 2101
        val mount = binding.editMount.text.toString().trim()
        val user  = binding.editUsername.text.toString().trim()
        val pass  = binding.editPassword.text.toString()

        if (user.isBlank() || pass.isBlank()) {
            Snackbar.make(binding.root,
                "TUSAGA-Aktif username and password are required",
                Snackbar.LENGTH_LONG).show()
            return
        }

        val config = NtripConfig(
            host = host, port = port,
            mountpoint = mount, username = user, password = pass
        )
        viewModel.saveNtripConfig(config)
        Snackbar.make(binding.root, "TUSAGA-Aktif config saved", Snackbar.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
