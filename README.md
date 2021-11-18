# Navigation App

This app was created to practice android development aswell as integrating location tracking and mapping, and sending data to an arduino via bluetooth

## Description

A Simple UI Android App made using the google maps API. The start up screen is a ListView used to show all possible bluetooth devices to connect to. Once choosing and successfully connecting to a bluetooth
device, the user is moved to the google map activity and a foreground service is started. THe Foreground Service is used to update user location whilist the app is active or Pasued.
The User also has the ability to use the Search Bar which implements the autocomplete service to auto detect what the user is typing and provide recommendations on places.
After the user has chosen a Place, a URL is generated to be sent to the Web to recieve a JSON formatted response in which there is an encoded polyline to give directions from the 
users current location to his selected destination. The encoded polyline is then decoded to be drawn onto the UI, the UI is updated every ~5 sec to ensure most up to date information is being displayed on the Map. Each time
the UI is updated, information about the direction of the polyline is sent to an arduino that is connected to a circuit to enable LED's depending on the direction the user is
facing and the direction the user needs to go. The Circuit consists of 4 LED's, an ardunio UNO, a HC-05 Bluetooth Module and a MPU 9150 (I only use the magnetometer).

## Issues

There seems to be a memory leak whenever the map Activity is destroyed. I tried my best to elimnate all variables that are being referenced prior to the activity being destroyed.
However I believe it has something to do with the location updates. I do remove location updates once the activity is destroyed however there still seems to be retained references within the code.

## Authors

Contributors names and contact info

Paul Kokhanov
  [@PaulKokhanov](https://www.linkedin.com/in/paulkokhanov/)

## Version History

* 0.1
    * Initial Release
