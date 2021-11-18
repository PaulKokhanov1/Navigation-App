package com.example.cityguide

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.ConnectivityManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.cityguide.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    //the bluetooth adapter can be null since that is what gets "assigned" to it if
    //no bluetooth support is found
    private var m_bluetoothAdapter : BluetoothAdapter? = null
    //A set in kotlin is similar to a list, but it is a collection of unique values
    //also it is not mutable
    private lateinit var m_pairedDevices : Set<BluetoothDevice>
    private val REQUEST_ENABLE_BLUETOOTH = 1

    private lateinit var binding: ActivityMainBinding


    //using the new version of getting results from activites instead of using onActivityResult...
    val resultContract = registerForActivityResult(ActivityResultContracts.StartActivityForResult()){ result: ActivityResult? ->
        if (result?.resultCode == REQUEST_ENABLE_BLUETOOTH){
            if (result.resultCode == Activity.RESULT_OK){
                if (m_bluetoothAdapter!!.isEnabled){
                    Toast.makeText(applicationContext,"Bluetooth has been enabled", Toast.LENGTH_SHORT).show()
                } else{
                    Toast.makeText(applicationContext,"Bluetooth has been disabled", Toast.LENGTH_SHORT).show()
                }
            } else if (result.resultCode == Activity.RESULT_CANCELED){
                Toast.makeText(applicationContext,"Bluetooth enabling has been canceled", Toast.LENGTH_SHORT).show()

            }
        }

    }


    //define variables in the companion object that you want access to from other classes
    //essentially companion objects allow you to call its members without having to declare
    //a class instance
    companion object{
        //we use this as a key for our intent extras, so when we move data from this page
        //to a different page we use this as a key to access the data
        val EXTRA_ADDRESS: String = "Device_address"
    }

    //run whenever activity has started
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkConnectivity()

        //initialize bluetooth adapter
        m_bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if(m_bluetoothAdapter == null){
            Toast.makeText(applicationContext,"This Device Doesn't Support Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        //we have to add double bang operator to ensure this adapter will not be null
        //since we checked this in the previous if statement
        if(!m_bluetoothAdapter!!.isEnabled){
            val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            //this will start an activity that will decide if we enable or disabled bluetooth
            startActivityForResult(enableBluetoothIntent, REQUEST_ENABLE_BLUETOOTH)
        }
        //if this button is click then run pairedDeviceList function
        binding.selectDeviceRefresh.setOnClickListener { pairedDeviceList() }
    }

    private fun pairedDeviceList(){
        //sets all bonded devices within bluetoothadapter to the variable
        //Bonded "device" is a synonym to a "paired" device
        m_pairedDevices = m_bluetoothAdapter!!.bondedDevices

        val list : ArrayList<BluetoothDevice> = ArrayList()

        //check if the list is empty
        if (!m_pairedDevices.isEmpty()){
            for (device: BluetoothDevice in m_pairedDevices){
                list.add(device)
                Log.i("device",""+device)
            }
        } else {
            Toast.makeText(applicationContext,"No Paired Bluetooth Devices Found", Toast.LENGTH_SHORT).show()
        }

        //(ArrayAdapter) You can use this adapter to provide views for an AdapterView,
        //Returns a view for each object in a collection of data objects you provide,
        //and can be used with list-based user interface widgets such as ListView or Spinner.
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,list)
        //Kinda like how we do in a recycleView
        //populates list view with data of paired devices
        binding.selectDeviceList.adapter = adapter
        //set on click listener for each item in list
        //we make first two and last arguments an _ because we don't use them
        //this onclicklistener is a lambda function so we get certain arguments passed in
        //and we do something with the arguments
        binding.selectDeviceList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val device: BluetoothDevice = list[position]
            val address: String = device.address

            //Create an intent to move us to a different screen
            val intent = Intent(this, MapsActivity::class.java)
            //put the address into the intent


            intent.putExtra(EXTRA_ADDRESS, address)
            resultContract.launch(intent)
        }
    }

    //function will check internet connection and send dialog if no internet is found
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
                    recreate()
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



}