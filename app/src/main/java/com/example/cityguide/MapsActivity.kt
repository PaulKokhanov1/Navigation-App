package com.example.cityguide


import android.app.AlertDialog
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer


import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.example.cityguide.databinding.ActivityMapsBinding
import com.google.android.gms.common.api.Status
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import java.io.IOException
import java.util.*


class MapsActivity : AppCompatActivity(), OnMapReadyCallback, GoogleMap.OnMarkerClickListener {


    //enable view binding var
    private lateinit var binding: ActivityMapsBinding

    private lateinit var mapFragment: SupportMapFragment

    private lateinit var autocompleteFragment: AutocompleteSupportFragment





    companion object {
        //use this to animate camera on initial activity launch
        var FirstStart: Boolean = true

        //declare a google map type
        lateinit var map: GoogleMap

        //we initialize our own request code that will be used to request permissions later on
        const val LOCATION_PERMISSION_REQUEST_CODE = 1

        //used for autocomplete, to store user's destination
        lateinit var destinationPlace: LatLng


        //used for whether to allow route updating or not
        var enableTracking = false


        var m_myUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        //what we use to actually send data to bluetooth device
        var m_bluetoothSocket: BluetoothSocket? = null
        lateinit var m_progress: ProgressDialog
        lateinit var m_bluetoothAdapter : BluetoothAdapter
        var m_isConnected: Boolean = false
        lateinit var m_address: String


        //drawing polylines to the UI
        fun drawPolylines(result: List<List<LatLng>>?, map: GoogleMap){

            val lineoption = PolylineOptions()
            if (result != null) {
                for (i in result.indices){
                    lineoption.addAll(result[i])
                    lineoption.width(10f)
                    lineoption.color(Color.GREEN)
                    lineoption.geodesic(true)

                }
            }
            map.addPolyline(lineoption)

        }


        //this function will send data to arduino
        fun sendCommand(input: String){
            if(m_bluetoothSocket != null){
                try {
                    //write to transmitter
                    m_bluetoothSocket!!.outputStream.write(input.toByteArray())
                } catch (e: IOException){
                    e.printStackTrace()
                }
            }
        }


    }



    //is called when the system first creates the activity
    //savedInstanceState is of type Bundle, thus it contains the activities previously saved state
    //if the activity has not been called before then it is null
    override fun onCreate(savedInstanceState: Bundle?) {
        // call the super class onCreate to complete the creation of activity like
        // the view hierarchy
        super.onCreate(savedInstanceState)

        ForegroundService.startService(this,"Foreground Service is running...")

        //we create an instance of the ViewBinding
        binding = ActivityMapsBinding.inflate(layoutInflater)
        //we tell activity to use layout from the binding object
        setContentView(binding.root)

        //get mac address from extras that we had set on the selectdeviceactivity
        m_address = intent.getStringExtra(MainActivity.EXTRA_ADDRESS).toString()

        //initialize the bluetooth connection
        ConnectToDevice(this).execute()



        // Fetching API_KEY which we wrapped
        val ai: ApplicationInfo = applicationContext.packageManager
            .getApplicationInfo(applicationContext.packageName, PackageManager.GET_META_DATA)
        val value = ai.metaData["com.google.android.geo.API_KEY"]
        val apiKey = value.toString()

        //initializing SDK
        Places.initialize(applicationContext, apiKey )



        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        //a SupportMapFragment is used for managing the lifecycle of a GoogleMap object
        mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        //Sets a callback object which will be triggered when the GoogleMap instance is ready to be used.
        mapFragment.getMapAsync(this)


            //we observe for a change in location of the user so that we can call functions in the main thread
            ForegroundService.location.observe(this,
                Observer {
                    checkConnectivity()

                    println("------------------------ ${ForegroundService.lastLocation}")

                    //clears all elements on the map fragment
                    map.clear()

                    if (FirstStart){
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(ForegroundService.lastLocation.latitude, ForegroundService.lastLocation.longitude), 12.0f))
                        FirstStart = false
                    }

                    //places marker at origin
                    placeMarkerOnMap(LatLng(ForegroundService.lastLocation.latitude, ForegroundService.lastLocation.longitude))
                    //here we check if the user has selected a place to set up a route to
                    if (enableTracking) {
                        try {
                            placeMarkerOnMap(destinationPlace)
                        } catch (e: IOException) {
                            //printing to logcat the stack trace
                            e.printStackTrace()
                        }
                    }
                })


        //calling our autocomplete function which creates the hotbar to type in a location
        //that can autocomplete your location depending on related places to what you have typed
        //so far
        autocomplete(mapFragment, apiKey)

        //create a listener for a button used to clear the map of all elements and disable the route tracking
        binding.fab.setOnClickListener {
            map.clear()
            placeMarkerOnMap(LatLng(ForegroundService.lastLocation.latitude, ForegroundService.lastLocation.longitude))
            enableTracking = false
            sendCommand("off")
        }

        //to disconnect from paired bluetooth device
        binding.controlLedDisconnect.setOnClickListener {
            enableTracking = false
            disconnect()
        }


    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     */
    override fun onMapReady(googleMap: GoogleMap) {

        //initialize map variable
        map = googleMap

        map.clear()
        //so here we get the user interface setting and we enable zoom controls on the
        //map by calling setZoomCo..(true), you can see the controls in the bot right corner
        //of the app
        map.getUiSettings().setZoomControlsEnabled(true)
        //Here we declare MapsActivity as the callback triggered when user clicks on marker
        map.setOnMarkerClickListener(this)

        //this line of code refers to checking if the access fine location for this context has
        //not been allowed access by the user
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            //then the app will request permission
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                MapsActivity.LOCATION_PERMISSION_REQUEST_CODE
            )
        }
        setUpMap()
    }

    private fun placeMarkerOnMap(location: LatLng){
        //create MarkerOptions object and set user's current location as position for marker
        val markerOptions = MarkerOptions().position(location)

        //here we call the function we made getAddress and we receive the string of the address
        //then we make the markers title to that string
        val titleStr = getAddress(location)
        markerOptions.title(titleStr)

        //add that marker to the map
        map.addMarker(markerOptions)
    }

    //activated whenever a Marker is clicked or tapped
    override fun onMarkerClick(p0: Marker) = false

    private fun setUpMap() {

        val hasLocationPermission = ActivityCompat.checkSelfPermission(this,
            android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED


        //we check if Location permissions have been granted and if not request them otherwise return
        //we used our own request code, defined above

        if(hasLocationPermission){
            //enables my-location layer which draws a light blue dot on the user’s location. It
            // also adds a button to the map that, when tapped, centers the map on the user’s location
            map.isMyLocationEnabled = true


            //setting up different map types, MAP_TYPE_NORMAL,
            //MAP_TYPE_SATELLITE, MAP_TYPE_TERRAIN, MAP_TYPE_HYBRID
            map.mapType = GoogleMap.MAP_TYPE_TERRAIN

        } else{
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
    }


    //this function will be used to create the autocomplete feature in the location search dialog box
    private fun autocomplete(mapFragment: SupportMapFragment, secret: String){

        //check whether the Places API is initialized if not, initialize it using API_KEY
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, secret)
        }

        //we initialize the AutocompleteSupportFragment which is used to essentially use the already created
        //autocomplete fragment from our xml file
        autocompleteFragment = supportFragmentManager.findFragmentById(R.id.autocomplete_fragment)
                as AutocompleteSupportFragment

        // Information that we wish to fetch after typing
        // the location and clicking on one of the options
        autocompleteFragment.setPlaceFields(
            listOf(

                Place.Field.NAME,
                Place.Field.ADDRESS,
                Place.Field.LAT_LNG

            )
        )

        //we add an on click listener to when the user clicks the autocomplete fragment
        autocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            //Once the user has selected a location
            override fun onPlaceSelected(p0: Place) {

                //create value to hold chosen place's latlng
                //Also for some reason I tried using the double bang operator here to reduce the null checks
                //but for some reason it creates issues with the app so don't use it here
                val latlng = p0.latLng

                //to be null safe we check if the value if null (which it should never be)
                if(latlng != null) {
                    placeMarkerOnMap(LatLng(latlng.latitude, latlng.longitude))
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latlng, 12.0f))
                    destinationPlace = latlng

                    //using the Map Fragment (the fragment responsible for managing the google map)
                    //we set a callback object to be triggered when the googlemap instance is ready to be used
                    mapFragment.getMapAsync {
                        //redeclares the map variable to be of mapFragment
                        map = it
                        val originLocation = LatLng(ForegroundService.lastLocation.latitude, ForegroundService.lastLocation.longitude)
                        placeMarkerOnMap(originLocation)
                        val destinationLocation = LatLng(latlng.latitude, latlng.longitude)
                        placeMarkerOnMap(destinationLocation)
                        val urll = ForegroundService.getDirectionURL(originLocation, destinationLocation, secret)
                        ForegroundService.GetDirection(urll).execute()
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(originLocation, 14F))
                        //this is to allow for updating the route path
                        enableTracking = true
                    }

                }
            }

            //Inform the user a place has not been selected or an error occured
            override fun onError(p0: Status) {
                Toast.makeText(applicationContext,"Some error occurred", Toast.LENGTH_SHORT).show()
            }
        })
    }
    

    //Override onPause() to stop location update request
    //This is called as part of the activity lifecycle when the user no longer
    //actively interacts with the activity, but it is still visible on screen
    override fun onPause() {
        super.onPause()
        println("The Activity is being Paused")

        if(isFinishing){
            println("The Activity is being destroyed")
        }
    }



    //called when activity is destroyed, here is where I attempt to clean up any potential
    //memory leaks
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        ForegroundService.stopService(this)
        //clear all markers and drawings on the google map
        map.clear()
        //making sure the two fragments are destroyed
        mapFragment.onDestroy()
        //autocompleteFragment.onDestroy()
        //manually forcing a garbage collection, which attempts to remove
        //al unreferenced objects in the heap, this is usually run automatically
        //whenever the eden section of the heap is full, but I run it again cuz YOLO
        System.gc()

    }

    //class that returns a String that is an address of the given latLng
    private fun getAddress(latLng: LatLng) :String {
        //initialize the geocoder class
        val geocoder = Geocoder(this)
        //getFromLocation returns an array of Addresses that describe area around given latLng
        val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
        //getAddressLine returns a line of the address numbered by the given index
        return addresses[0].getAddressLine(0).toString()
    }




    //remove connection with bluetooth device
    private fun disconnect(){
        if(m_bluetoothSocket != null){
            try {
                //this closes connection
                sendCommand("off")
                m_bluetoothSocket!!.close()
                m_bluetoothSocket = null
                //set this to false so that page knows we disconnected
                m_isConnected = false
            } catch (e: IOException){
                e.printStackTrace()
            }
        }
        //this closes page and goes back to select device activity
        finish()
    }

    //function will check interent connection and send dialog if no internet is found
    private fun checkConnectivity() {
        val manager = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = manager.activeNetworkInfo

        if (null == activeNetwork) {
            val dialogBuilder = AlertDialog.Builder(this)
            val intent = Intent(this, MainActivity::class.java)
            // set message of alert dialog
            dialogBuilder.setMessage("Make sure that WI-FI or mobile data is turned on, then try again")
                // if the dialog is cancelable
                .setCancelable(false)
                // positive button text and action
                .setPositiveButton("Retry", DialogInterface.OnClickListener { dialog, id ->
                    finish()
                    startActivity(getIntent())
                })
                // negative button text and action
                .setNegativeButton("Cancel", DialogInterface.OnClickListener { dialog, id ->
                    finish()
                })

            // create dialog box
            val alert = dialogBuilder.create()
            // set title for alert dialog box
            alert.setTitle("No Internet Connection")
            alert.setIcon(R.mipmap.ic_launcher)
            // show alert dialog
            alert.show()
        }
    }


    //this class deals with connecting to the bluetooth device
    private class ConnectToDevice(c : Context) : CoroutinesAsyncTask<Void, Void, String?>("MysAsyncTask"){

        private var connectSuccess: Boolean = true
        private val context: Context

        init {
            this.context = c
        }

        override fun onPreExecute() {
            super.onPreExecute()
            m_progress = ProgressDialog.show(context, "Connecting...", "please wait")
        }

        override fun doInBackground(vararg p0: Void?): String? {
            try {
                if(m_bluetoothSocket == null || !m_isConnected){
                    m_bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                    //this will get the specific device we selected using the MAC address we made
                    val device: BluetoothDevice = m_bluetoothAdapter.getRemoteDevice(m_address)
                    //this sets up connection between phone and bluetooth device
                    m_bluetoothSocket = device.createInsecureRfcommSocketToServiceRecord(m_myUUID)
                    //this stops app from looking for more device trying to connect to, so that we save battery
                    BluetoothAdapter.getDefaultAdapter().cancelDiscovery()
                    //this actually connects us
                    m_bluetoothSocket!!.connect()

                }
            } catch (e: IOException){
                connectSuccess = false
                e.printStackTrace()
            }
            return null

        }

        override fun onPostExecute(result: String?) {
            super.onPostExecute(result)
            if (!connectSuccess){
                Log.i("data", "couldn't connect")
            } else {
                m_isConnected = true
            }
            //remove the progress
            m_progress.dismiss()
        }

    }


}
