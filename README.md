# eBikeMonitor


## TODO list

### UI
- arrange in UI the buttons for MQTT, BLE and FLOW frome left to right
- MQTT and BLE button 
-- shall be red if discinnected, green when connected and yellow when unknown
-- push a button will trigger disconnect when in connected state and connect when in disconnected state
  if state is unknown the push button will trigger disconnect.

### MQTT
- DONE: check the Qos state of the MQTT connection to match old app settings -> all use Qos = 0
- MQTT connection is still present even after app is closed -> fix this to close MQTT connection when app is closed
