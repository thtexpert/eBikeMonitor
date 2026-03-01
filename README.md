# eBikeMonitor

<img src="images/IconVersion2.png" width="160">

## TODO list

### locales
- prepare for multi language
-- EN, GE
-- sensor names
- map sensor names to MQTT topic at central location
-- e.g. *Ble Status* <-> *ebikemonitor/blestatus*
- 
### UI
- arrange in UI the buttons for MQTT, BLE and FLOW frome left to right
- MQTT and BLE button 
-- shall be toggled off if disconnected, toggled on when connected and yellow when unknown
-- ON state shoudl be indicated by green color, off by a red color
-- push a button will trigger disconnect when in connected state and connect when in disconnected state
  if state is unknown the push button will trigger disconnect.
- redefine grid for sensor display
-- table with 2 columns
--- left column has name of sensor and in smaller font size the unit (if available)
--- right column has value with one digit and unit
-- sequence
--- Speed
--- assist mode
--- power human
--- SoC
--- total distance
--- total energy
-- values that are supposed to be sent by MQTT should have colour coding for succesful transmission
--- black for success, gray for not transmitted
- Version Info
### Settings
- group the autostart features together
- consider for each sensor
-- UI visibility On/Off 
-- MQTT transfer On/Off (for supported sensors)
-- maybe another application comes up later in project (seperate BLE exposure)
- assist mode levels should be defined by user

### MQTT
- DONE: check the Qos state of the MQTT connection to match old app settings -> all use Qos = 0
- DONE: MQTT connection is still present even after app is closed -> fix this to close MQTT connection when app is closed
- DONE: send tiepstamp of latest succesful MQQT connection established
- define topic at one single location to "ebikemonitor" instead of many local definitions
- consider MQTT to send app version

### home assistant
- find out how MQTT sensor config can be retrieved from eBikeMonitor app

### App Icon
- design a specific icon for this app

### debug
- disable temporary the MQTT send of total distance, total energie and SoC. Keep assist mode
- 

