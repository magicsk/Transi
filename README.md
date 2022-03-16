# Transi

App with list of virtual tables and trip planner for MHD in Bratislava.

## Features

### Future  
- [ ] ŽSR table support if possible
- [ ] Map direction button
- [ ] Offline timetables
- [ ] IDSBK trip planner
- [ ] Virtual table on swipe gesture for notification and share

### 1.0.5
- [x] Material You redesign v3
- [x] Info in virtual table when there are no departures
- [x] Share trip or vehicle via button in notifications

### 1.0.0
- [x] Trip planner add time arrival departure switch
- [x] Material You redesign v2
- [x] Virtual table on long press notification
- [x] Trip planner trip notification
- [x] Table info on click launch web with more information

### 0.9.9
- [x] Material You dynamic colors support 
- [x] Material You redesign
- [x] Dark google map
- [x] New map marker
- [x] New regional stops icon color

### 0.9.8
- [x] Cache stops.json
- [x] Zoom map on actual location
- [x] Actual time in Table next to stop name

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
- [ ] Fix location working only each other start of application
- [ ] Fix table list item style if only one
- [ ] Fix disappearing tripplanner info

### 1.0.5
- [x] Fix landscape rotation when trip is searched
- [x] Fix sometimes table notification not removed after bus leave stop
- [x] Fix crash on notification create
- [x] Fix crash on onPause while trip planned
- [x] Fix trip notification title is "from Stop to null" if last step is walking 
- [x] Fix clickable area for trip notification

### 1.0.0
- [x] Fix duplicates in virtual table
- [x] Fix expand animation
- [x] Fix virtual table time remaining (not updating frequently enough)
- [x] Fix overlapping information about connection

### 0.9.8
- [x] Fix typeAhead search not recovering items
- [x] Fix duplicate stops in TripPlanner
- [x] Fix crash when back button is used in map

### 0.9.7
- [x] Fix crash on opening TypeAhead before table info loaded

### 0.9.4
- [X] Fix Table items duplication

### 0.9.3
- [x] Fix nearest switching icon change on stop selection

### 0.9.2
- [x] Fix onLocationChanged and stop changed crash
