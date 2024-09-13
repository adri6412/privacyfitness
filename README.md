# WearOS Native Android App

This native Android wearable app is designed for Wear OS by Google, specifically targeting the [TicWatch E3] device. The app leverages hardware sensors to collect vital data and utilizes GPS for location tracking and distance measurement.

## Features

- Utilizes hardware sensors for vital data collection
- GPS integration for location and distance tracking
- Privacy-focused design
- Optional 24/7 monitoring feature

## Privacy

We take your privacy seriously. By default, this app does not collect any data unless manually activated by the user. Your information is not collected from sensors until you choose to initiate data collection and transmission by starting a workout.

### 24/7 Monitoring

The app includes an optional 24/7 monitoring feature that can be enabled within the app settings. When activated, this feature will continuously monitor and send your data to the server. Important notes about this feature:

- It is categorized as a workout type within the app
- You can start and stop this feature at any time
- No data is collected or transmitted unless this feature is explicitly enabled by you

## Server-Side Software

The companion server-side software for this application can be found at https://github.com/adri6412/privacyfitness-server

# Additional Info


This app is for android wear and does not have a companion app so the data is sent to the server directly from the smartwatch. So make sure that either via bluetooth from your phone or via wifi the watch has internet access.
