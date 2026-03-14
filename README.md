# eBikeMonitor for Bosch Gen4 Smart System Data Collection

<img src="images/IconVersion2.png" width="160">

## Desciption
This android app gets data from eBike and pushes values via MQTT to a broker.

## Connecting to eBike
- ensure no Flow app or eBikeMonitor app is active on your smartphone
- turn on eBike
- launch eBikeMonitor app
-- the eBikeMonitor app will automatically (if enabled in settings)  BLE connection to ebike and launch the Flow app
-- please keep Flow app open until main screen is refreshed with eBike data before switching to other app or closing any screen
- the MQTT connection will be enabled as soon as broker is available (i start this every time, as soon as i am getting home, connection is established and latest sensor data is sent to broker automatically)
- if MQTT connection is not available the sensor values are slightly grayed out and will get solid black once MQTT data has been successfully transferred.

- for monitoring the charging process the above sequence has to be run and afterwards the charger is connected to the bike. Battery must be charged mounted in the eBike. Also the smartphone has to have a BLE connection to the eBike during the entire charge process.

# How this works
The eBike Monitor listens to the BLE traffic between eBike and Bosch Flow app.
To do so the eBikeMonitor app must be started and connected to bonded smart Ebike _before_ the Flow app is started.
Running eBikeMonitor on another Android device than the one with Bosch Flow app does not work!

## TODO list
- 
### UI
- allow swaping of sensor boxes in GUI for personal preferences
### Settings
- assist mode levels should be defined by user or taken from eBike

