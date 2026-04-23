package com.tusaga.rtkinfra.ui.map

import android.graphics.Color
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.tusaga.rtkinfra.data.model.GeometryType
import com.tusaga.rtkinfra.databinding.FragmentMapBinding
import com.tusaga.rtkinfra.ui.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MapFragment : Fragment(), OnMapReadyCallback {

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private var googleMap: GoogleMap? = null
    private var userMarker: Marker? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mapFragment = childFragmentManager
            .findFragmentById(binding.mapContainer.id) as? SupportMapFragment
            ?: SupportMapFragment.newInstance().also {
                childFragmentManager.beginTransaction()
                    .replace(binding.mapContainer.id, it).commit()
            }
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.mapType = GoogleMap.MAP_TYPE_SATELLITE
        map.uiSettings.apply {
            isCompassEnabled = true
            isMyLocationButtonEnabled = false
        }

        observeData()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rtkState.collect { state ->
                if (state.latitude == 0.0) return@collect
                val latLng = LatLng(state.latitude, state.longitude)

                userMarker?.remove()
                userMarker = googleMap?.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("RTK ${state.fixType.label}")
                        .icon(BitmapDescriptorFactory.defaultMarker(
                            when (state.fixType.name) {
                                "FIX"    -> BitmapDescriptorFactory.HUE_GREEN
                                "FLOAT"  -> BitmapDescriptorFactory.HUE_ORANGE
                                "DGPS"   -> BitmapDescriptorFactory.HUE_BLUE
                                else     -> BitmapDescriptorFactory.HUE_RED
                            }
                        ))
                )
                googleMap?.moveCamera(CameraUpdateFactory.newLatLng(latLng))

                // Draw accuracy circle
                googleMap?.addCircle(
                    CircleOptions()
                        .center(latLng)
                        .radius(state.accuracyM)
                        .fillColor(Color.argb(40, 76, 175, 80))
                        .strokeColor(Color.argb(200, 76, 175, 80))
                        .strokeWidth(2f)
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.infraDataset.collect { dataset ->
                dataset ?: return@collect
                googleMap?.clear()
                userMarker = null

                dataset.features.forEach { feature ->
                    val color = when (feature.type?.lowercase()) {
                        "water"    -> Color.BLUE
                        "sewer"    -> Color.rgb(139, 69, 19)
                        "gas"      -> Color.YELLOW
                        "telecom"  -> Color.GREEN
                        "electric" -> Color.RED
                        else       -> Color.GRAY
                    }

                    when (feature.geometryType) {
                        GeometryType.POINT -> googleMap?.addMarker(
                            MarkerOptions()
                                .position(LatLng(feature.latitude, feature.longitude))
                                .title(feature.label ?: feature.type)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
                        )
                        GeometryType.LINE -> googleMap?.addPolyline(
                            PolylineOptions()
                                .add(LatLng(feature.latitude, feature.longitude))
                                .color(color)
                                .width(4f)
                                .pattern(listOf(
                                    com.google.android.gms.maps.model.Dash(20f),
                                    com.google.android.gms.maps.model.Gap(10f)
                                ))
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
