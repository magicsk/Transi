# Transi

App with list of virtual tables and trip planner for MHD in Bratislava.

## Features

### Future  
- [ ] Cache stops.json
- [ ] Local tripplanner parser
- [ ] Trip planner trip notification
- [ ] Trip planner add time arrival departure switch
- [ ] Table on long press notification
- [ ] ŽSR table support if possible
- [ ] Add settings: home a address, default stop

### 0.9.7
- [x] Add Trip planner dismiss
- [x] TypeAhead add chose from map

### 0.9.6
- [x] Table expand animation
- [x] Table info dismiss properly with memory and animation (recycler view)
- [x] Tripplanner stops change to recycler view

### 0.9.5
- [x] TypeAhead on long press navigation just show planFragment
- [x] Tripplanner add time picker
- [x] Tripplanner stops - zone and request info
- [x] Table info dismiss

### 0.9.4
- [x] Redesign Trip planner
- [x] Trip planner expand stops
- [x] Now using last location on startup

### 0.9.3
- [x] Table expand remember
- [x] Table refresh only changed not all
- [x] Table info clickable links

### 0.9.2
- [x] Expandable Virtual table items for more details
- [x] Dynamic loading image of bus

### 0.9.0
- [x] Added Virtual table
- [x] Added List of stops
- [x] Added Actual location detection
- [x] Added Base Trip planner implementation
- [x] Faster location get
- [x] Continuous stop change on location change and button for reset back to searching


## Bugs

### 0.9.7
- [x] Fix crash on opening TypeAhead before table info loaded

### 0.9.4
- [X] Fix Table items duplication

### 0.9.3
- [x] Fix nearest switching icon change on stop selection

### 0.9.2
- [x] Fix onLocationChanged and stop changed crash
