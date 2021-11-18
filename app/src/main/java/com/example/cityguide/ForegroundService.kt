package com.example.cityguide

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class ForegroundService : Service() {


    //LocationRequest objects are used to request a quality of service
    //for location updates from the FusedLocationProviderApi
    private lateinit var locationRequest: LocationRequest

    private val CHANNEL_ID = "ForegroundService Kotlin"

    //FusedLocationProviderClient is used to get the last known location of the user
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    companion object {

        lateinit var lastLocation: Location
        lateinit var locationCallback: LocationCallback

        //we create a MutableLiveData so that we can observe this val from other activities
        val location = MutableLiveData<Location>()


        fun startService(context: Context, message: String) {
            val startIntent = Intent(context, ForegroundService::class.java)
            startIntent.putExtra("inputExtra", message)
            ContextCompat.startForegroundService(context, startIntent)
        }

        fun stopService(context: Context) {
            val stopIntent = Intent(context, ForegroundService::class.java)
            context.stopService(stopIntent)
        }


        //function to create the URL which will return a JSON formatted file that reads out direction
        //and other information
        fun getDirectionURL(origin: LatLng, dest: LatLng, secret: String): String {
            val url: String =
                "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude}," +
                        "${origin.longitude}&destination=${dest.latitude},${dest.longitude}" +
                        "&sensor=false" +
                        "&mode=driving" +
                        "&key=$secret"
            println(url)
            return url
        }

        //https://developers.google.com/maps/documentation/utilities/polylinealgorithm
        fun decodePolyline(encoded: String): List<LatLng> {
            //the encoded string is comprised of firstly lat then lng, and there is multiple sets of
            //latlng points in an encoded string
            val poly = ArrayList<LatLng>()
            var index = 0
            val len = encoded.length
            var lat = 0
            var lng = 0
            //the while loop is to make sure we go through the entire JSON formatted code
            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                //essentially we reverse the steps to encode a latlng point
                do {
                    //convert each character to decimal from ascii and remove 63 from the value
                    b = encoded[index++].code - 63
                    println("b is: $b")
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lat += dlat
                shift = 0
                result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lng += dlng
                val latLng = LatLng((lat.toDouble() / 1E5), (lng.toDouble() / 1E5))
                poly.add(latLng)
            }
            //we return a list of latlng points for each straight section of the polyline
            return poly
        }



        //this function will send data to arduino
        fun sendCommand(input: String){
            if(MapsActivity.m_bluetoothSocket != null){
                try {
                    //write to transmitter
                    MapsActivity.m_bluetoothSocket!!.outputStream.write(input.toByteArray())
                } catch (e: IOException){
                    e.printStackTrace()
                }
            }
        }
    }


        //this function is used to create an encoded string from the JSON code for the decodepolyline fun to read and return
        //a latlng list which this function will draw to the map
        //I used a library to convert form AsyncTask to coroutines whilist maintaing the AsyncTask Syntax
        //https://github.com/ladrahul25/CoroutineAsyncTask/blob/master/app/src/main/java/com/example/background/CoroutinesAsyncTask.kt
        class GetDirection(val url: String) : CoroutinesAsyncTask<Void, Void, List<List<LatLng>>>("MysAsyncTask"){
            //will preform the following actions in a background thread
            override fun doInBackground(vararg p0: Void?): List<List<LatLng>> {
                //OkHttp is used for sending and recieving HTTP-based network requests
                //initialize the OkHttpClient
                val client = OkHttpClient()
                //request calls a GET method on the previously made url
                val request = Request.Builder().url(url).build()
                //we make a synchronous network call on the background thread since you can't make
                //a synchronous call on the main thread, im guessing cause the synchronous call will block the main thread
                //A synchronous function means it will block the caller of it and will return only after it finishes
                //its task. On the other hand an asynchronous function starts its task in the
                // background but returns immediately to the caller.
                val response = client.newCall(request).execute()
                //this holds the resposne data, converting it to a string allows us to read entire payload
                val data = response.body!!.string()

                var x_value = 0.0
                var y_value = 0.0
                var angle = 0.0

                val result =  ArrayList<List<LatLng>>()
                try{
                    //here we convert JSON string to a class object using Gson()
                    val respObj = Gson().fromJson(data,MapData::class.java)
                    val path =  ArrayList<LatLng>()
                    //respObj is the class object, but for easier reference consider the data value which
                    //holds the JSON string, there is only one "routes" that holds everything, one "legs" that
                    // holds everything and then within "steps" we have seperate directions and latlngs, so
                    //we want to add each latlng within steps so that is how our for loop is limited
                    for (i in 0 until respObj.routes[0].legs[0].steps.size) {
                        //here same as what we mentioned in creating our for loop but we call decodepolyline
                        //for each latlng and the encoded string within each step, which is given by "points"
                        //NOTE: "polyline" contains a single "points" object that holds an encoded polyline
                        // representation of the step. This polyline is an approximate (smoothed) path of the step
                        path.addAll(decodePolyline(respObj.routes[0].legs[0].steps[i].polyline.points))
                    }

                    //here I convert the first straightaway of the polyline to an angle (E being 0 degrees)
                    //this is to be sent to the bluetooth module to notify which led on the longboard to light up
                    x_value = path[1].longitude- path[0].longitude
                    y_value = path[1].latitude - path[0].latitude
                    if(x_value != 0.0 && x_value > 0 && y_value >0) {
                        var div = y_value/x_value
                        angle = Math.toDegrees(Math.atan(div))
                    } else if (x_value != 0.0 && x_value > 0 && y_value <0) {
                        var div = y_value/x_value
                        angle = Math.toDegrees(Math.atan(div))
                    }
                    else if(x_value != 0.0 && x_value <0){
                        var div = y_value/x_value
                        angle = Math.toDegrees(Math.atan(div))
                        angle += 180
                    } else if(y_value > 0.0){
                        angle = 90.0
                    } else if(y_value < 0.0){
                        angle = 270.0
                    } else{
                        //shouldn't ever get to this point
                        angle = 34404.0
                    }
                    println("---------------------------------------angle: ${angle}")
                    //send value to arduino
                    sendCommand(angle.toString())
                    result.add(path)
                }catch (e:Exception){
                    e.printStackTrace()
                }

                return result
            }

            //here for each two consecutive latlng point we connect with a polyline and draw it to the map
            override fun onPostExecute(result: List<List<LatLng>>?) {
                MapsActivity.drawPolylines(result, MapsActivity.map)
            }

        }




    override fun onCreate() {
        super.onCreate()


        //the fused location provider is a location API that combines different signals for
        //location information
        //we create a new instance of FusedLocationProviderclient to be used in this activity
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createLocationRequest()

        // Fetching API_KEY which we wrapped
        val ai: ApplicationInfo = applicationContext.packageManager
            .getApplicationInfo(applicationContext.packageName, PackageManager.GET_META_DATA)
        val value = ai.metaData["com.google.android.geo.API_KEY"]
        val apiKey = value.toString()


        locationCallback = object : LocationCallback() {
            //whenever we have a successful location data retrieval
            override fun onLocationResult(p0: LocationResult) {
                super.onLocationResult(p0)

                //here we give the lastLocation var the actual data (latLng) of the user's last location
                lastLocation = p0.lastLocation

                //update the MutableLiveData
                location.postValue(lastLocation)

                createLocationRequest()

                    if (MapsActivity.enableTracking) {
                        try {
                            //set up latLng values for origin and destination
                            val originLocation = LatLng(lastLocation.latitude, lastLocation.longitude)
                            val destinationLocation = LatLng(MapsActivity.destinationPlace.latitude, MapsActivity.destinationPlace.longitude)
                            //create value to create the url for an HTTP request to return a JSON formatted directions
                            val urll = getDirectionURL(originLocation, destinationLocation, apiKey)
                            //essentially this call will create the polylines
                            GetDirection(urll).execute()

                        } catch (e: IOException) {
                            //printing to logcat the stack trace
                            e.printStackTrace()
                        }
                    }

            }
        }
        startLocationUpdates()

    }




    private fun startLocationUpdates() {

        val hasLocationPermission = ActivityCompat.checkSelfPermission(this,
            android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED


        //we check if Location permissions have been granted and if not request them otherwise return
        //we used our own request code, defined above

        if(hasLocationPermission) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback, Looper.getMainLooper()
            )
        }

    }

    private fun createLocationRequest(){
        /*
        Before you start requesting for location updates, you need to
        check the state of the user’s location settings
         */

        locationRequest = LocationRequest.create().apply {
            //interval specifies rate at which app will like to receive updates
            interval = 10000
            //fastest interval states the absolute fastest your app can receive updates
            //essentially it just sets a limit on how fast you app will receive updates
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            maxWaitTime = 10000
        }

    }









    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //do heavy work on a background thread
        val input = intent?.getStringExtra("inputExtra")
        createNotificationChannel()
        val notificationIntent = Intent(this, MapsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, 0
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Foreground Service is accessing user location")
            .setContentText(input)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(1, notification)
        //stopSelf();
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent): IBinder? {
        return null
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(NotificationManager::class.java)
            manager!!.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }


}