package com.chinonso.wearos;

import static android.hardware.Sensor.TYPE_HEART_RATE;
import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MonitoringService extends Service implements SensorEventListener, LocationListener {
    private static final String TAG = "MonitoringService";
    private static final String SERVER_URL = "https://fitapi.adrianofrongillo.ovh/api/bulk-data";
    private static final String CHANNEL_ID = "FitnessTrackingChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long DAILY_MONITORING_INTERVAL = 5 * 60 * 1000; // 5 minuti
    private static final int MAX_DATA_POINTS = 100;
    private static final long SEND_INTERVAL = 60 * 60 * 1000; // 1 ora in millisecondi
    private static final long LOCATION_UPDATE_INTERVAL = 10000; // 10 secondi
    private static final long LOCATION_UPDATE_FASTEST_INTERVAL = 5000; // 5 secondi
    private static final long LOCATION_UPDATE_TIMEOUT = 30000; // 30 secondi
    private static final long FORCE_UPDATE_INTERVAL = 5 * 60 * 1000; // 5 minuti

    private String currentWorkout = "";
    private int heartRate = 0;
    private int stepCount = 0;
    private double calories = 0;
    private double altitude = 0;
    private String gpsInfo = "";
    private String timerText = "00:00:00";
    private int initialStepCount = -1;
    private boolean isMonitoring = false;

    private SensorManager sensorManager;
    private Sensor heartRateSensor, stepCountSensor, pressureSensor;
    private LocationManager locationManager;

    private String userId;
    private int weight;
    private int height;
    private String gender;

    private OkHttpClient client = new OkHttpClient();
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private long startTime;
    private Handler handler = new Handler();
    private Handler sendDataHandler = new Handler();
    private Handler forceUpdateHandler = new Handler();

    private PowerManager.WakeLock wakeLock;

    private List<DataPoint> dataPoints = new ArrayList<>();
    private Location lastKnownLocation;
    private long lastLocationUpdateTime;
    private float lastPressureReading = 0;

    public class LocalBinder extends Binder {
        MonitoringService getService() {
            return MonitoringService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    private Runnable sendDataRunnable = new Runnable() {
        @Override
        public void run() {
            sendDataToServer();
            sendDataHandler.postDelayed(this, SEND_INTERVAL);
        }
    };

    private Runnable forceUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            forceLocationUpdate();
            forceUpdateHandler.postDelayed(this, FORCE_UPDATE_INTERVAL);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MonitoringService::WakeLock");

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        heartRateSensor = sensorManager.getDefaultSensor(TYPE_HEART_RATE);
        stepCountSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        Log.d(TAG, "Heart Rate Sensor: " + (heartRateSensor != null ? "Available" : "Not available"));
        Log.d(TAG, "Step Count Sensor: " + (stepCountSensor != null ? "Available" : "Not available"));
        Log.d(TAG, "Pressure Sensor: " + (pressureSensor != null ? "Available" : "Not available"));

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        SharedPreferences sharedPreferences = getSharedPreferences("UserData", MODE_PRIVATE);
        userId = sharedPreferences.getString("userId", "");
        weight = sharedPreferences.getInt("weight", 0);
        height = sharedPreferences.getInt("height", 0);
        gender = sharedPreferences.getString("gender", "");

        SharedPreferences prefs = getSharedPreferences("MonitoringServicePrefs", MODE_PRIVATE);
        isMonitoring = prefs.getBoolean("isMonitoring", false);
        if (isMonitoring) {
            resumeMonitoring();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.d(TAG, "Service restarted with null intent");
            if (isMonitoring) {
                resumeMonitoring();
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action != null) {
            if (action.equals("start")) {
                String workoutType = intent.getStringExtra("workoutType");
                startMonitoring(workoutType);
            } else if (action.equals("stop")) {
                stopMonitoring();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopMonitoring();
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    private void startMonitoring(String workoutType) {
        if (!isMonitoring) {
            isMonitoring = true;
            initialStepCount = -1;
            this.stepCount = 0;
            Log.d(TAG, "Step count reset to 0 at the start of monitoring");
            SharedPreferences.Editor editor = getSharedPreferences("MonitoringServicePrefs", MODE_PRIVATE).edit();
            editor.putBoolean("isMonitoring", true);
            editor.apply();

            if (!isGpsEnabled()) {
                Log.e(TAG, "GPS is not enabled");
                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                startLocationUpdates();
                forceLocationUpdate();
            }

            if (heartRateSensor != null) {
                boolean hrRegistered = sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
                Log.d(TAG, "Heart rate sensor registered: " + hrRegistered);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }

            currentWorkout = workoutType;

            if ("Monitoraggio Giornaliero".equals(currentWorkout)) {
                startDailyMonitoring();
            } else {
                startRegularMonitoring();
            }

            startTime = System.currentTimeMillis();
            handler.post(updateTimerRunnable);
            startForeground(NOTIFICATION_ID, createNotification());

            addDataPoint("start", currentWorkout);

            sendDataHandler.postDelayed(sendDataRunnable, SEND_INTERVAL);
            forceUpdateHandler.postDelayed(forceUpdateRunnable, FORCE_UPDATE_INTERVAL);
        }
    }

    private void startDailyMonitoring() {
        sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, stepCountSensor, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
        startLocationUpdates();
    }

    private void startRegularMonitoring() {
        sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, stepCountSensor, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_FASTEST);
        startLocationUpdates();
    }

    private void resumeMonitoring() {
        if (!isMonitoring) {
            isMonitoring = true;
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
            sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_FASTEST);
            sensorManager.registerListener(this, stepCountSensor, SensorManager.SENSOR_DELAY_FASTEST);
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_FASTEST);
            startLocationUpdates();
            handler.post(updateTimerRunnable);
            startForeground(NOTIFICATION_ID, createNotification());
            sendDataHandler.postDelayed(sendDataRunnable, SEND_INTERVAL);
            forceUpdateHandler.postDelayed(forceUpdateRunnable, FORCE_UPDATE_INTERVAL);
        }
    }

    private void stopMonitoring() {
        if (isMonitoring) {
            isMonitoring = false;
            SharedPreferences.Editor editor = getSharedPreferences("MonitoringServicePrefs", MODE_PRIVATE).edit();
            editor.putBoolean("isMonitoring", false);
            editor.apply();

            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            sensorManager.unregisterListener(this);
            stopLocationUpdates();
            handler.removeCallbacks(updateTimerRunnable);
            sendDataHandler.removeCallbacks(sendDataRunnable);
            forceUpdateHandler.removeCallbacks(forceUpdateRunnable);

            addDataPoint("stop", currentWorkout);

            sendDataToServer();
            dataPoints.clear();
            stopForeground(true);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String contentText = "Monitoraggio Giornaliero".equals(currentWorkout)
                ? "Monitoraggio Giornaliero Attivo"
                : "Monitoraggio " + currentWorkout + " Attivo";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Fitness Tracking")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Fitness Tracking Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    private static final long GPS_TIMEOUT = 30000;
    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted");
            return;
        }
        if (!hasGPSHardware()) {
            Log.e(TAG, "Device does not have GPS hardware.");
            return;
        }
        try {
            // Request updates from both GPS and Network providers
            List<String> providers = locationManager.getProviders(true);
            for (String provider : providers) {
                locationManager.requestLocationUpdates(
                        provider,
                        LOCATION_UPDATE_INTERVAL,
                        5, // Minimum distance in meters
                        this
                );
                Log.d(TAG, provider + " location updates requested successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting location updates: " + e.getMessage());
        }
    }

    private void stopLocationUpdates() {
        locationManager.removeUpdates(this);
    }

    private void forceLocationUpdate() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted");
            return;
        }

        Location lastKnownLocationGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location lastKnownLocationNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        if (lastKnownLocationGPS != null) {
            updateLocationInfo(lastKnownLocationGPS);
            Log.d(TAG, "Last known GPS location obtained");
        } else if (lastKnownLocationNetwork != null) {
            updateLocationInfo(lastKnownLocationNetwork);
            Log.d(TAG, "Last known network location obtained");
        } else {
            Log.e(TAG, "No last known location available");
        }
    }

    private void updateLocationInfo(Location location) {
        if (location == null) {
            Log.e(TAG, "Received null location");
            return;
        }

        Log.d(TAG, "Location update received");
        Log.d(TAG, "Provider: " + location.getProvider());
        Log.d(TAG, "Accuracy: " + location.getAccuracy());
        Log.d(TAG, "Time: " + new Date(location.getTime()).toString());

        lastKnownLocation = location;
        String gpsInfo = String.format("Lat: %.6f, Lon: %.6f", location.getLatitude(), location.getLongitude());
        this.gpsInfo = gpsInfo;
        Log.d(TAG, "GPS Info: " + gpsInfo);
        addDataPoint("gps", gpsInfo);

        updateAltitude(location);

        notifyUIUpdates();
    }

    private void updateAltitude(Location location) {
        if (location != null && location.hasAltitude()) {
            this.altitude = location.getAltitude();
            Log.d(TAG, "Altitude from GPS: " + this.altitude);
        } else if (lastPressureReading != 0) {
            this.altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, lastPressureReading);
            Log.d(TAG, "Altitude from pressure sensor: " + this.altitude);
        } else {
            Log.d(TAG, "No altitude data available");
        }
        addDataPoint("altitude", this.altitude);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            updateLocationInfo(location);
        } else {
            Log.e(TAG, "Received null location");
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    public void onSensorChanged(SensorEvent event) {
        Log.d(TAG, "Sensor event received: " + event.sensor.getType());
        if (event.sensor.getType() == TYPE_HEART_RATE) {
            this.heartRate = (int) event.values[0];
            Log.d(TAG, "Heart Rate: " + this.heartRate);
            Log.d(TAG, "Raw heart rate values: " + java.util.Arrays.toString(event.values));
            addDataPoint("heart_rate", heartRate);
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            int totalSteps = (int) event.values[0];
            if (initialStepCount == -1) {
                initialStepCount = totalSteps;
                Log.d(TAG, "Initial step count set to: " + initialStepCount);
            }
            this.stepCount = totalSteps - initialStepCount;
            this.calories = calculateCalories(this.stepCount);
            Log.d(TAG, "Step Count: " + this.stepCount + ", Calories: " + this.calories);
            addDataPoint("step_count", stepCount);
            addDataPoint("calories", calories);
        } else if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            lastPressureReading = event.values[0];
            updateAltitude(null);
        }

        notifyUIUpdates();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Accuracy changed for sensor: " + sensor.getName() + " to " + accuracy);
    }

    public void setCurrentWorkout(String workout) {
        this.currentWorkout = workout;
    }

    private void addDataPoint(String dataType, Object value) {
        dataPoints.add(new DataPoint(dataType, value, System.currentTimeMillis()));

        if (dataPoints.size() >= MAX_DATA_POINTS) {
            sendDataToServer();
        }
    }

    private void sendDataToServer() {
        if (dataPoints.isEmpty()) {
            return;
        }

        JSONArray jsonArray = new JSONArray();
        for (DataPoint dp : dataPoints) {
            try {
                JSONObject json = new JSONObject();
                json.put("userId", userId);
                json.put("type", dp.type);
                json.put("value", dp.value);
                json.put("timestamp", dp.timestamp);
                json.put("workout", currentWorkout);
                jsonArray.put(json);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating JSON", e);
            }
        }

        Log.d(TAG, "Sending data to server: " + jsonArray.toString());

        RequestBody body = RequestBody.create(jsonArray.toString(), JSON);
        Request request = new Request.Builder()
                .url(SERVER_URL)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Error sending data to server", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Unexpected code " + response);
                } else {
                    Log.d(TAG, "Data sent successfully");
                    dataPoints.clear();
                }
            }
        });
    }

    private double calculateCalories(int stepCount) {
        double caloriesBurned = 0;
        double met = 0;

        switch (currentWorkout) {
            case "Walking":
                met = 3.5;
                break;
            case "Running":
                met = 7.0;
                break;
            case "Cycling":
                met = 8.0;
                break;
            case "Monitoraggio Giornaliero":
                met = 1.5; // MET medio per attività leggere durante il giorno
                break;
            default:
                met = 3.5;
        }

        double weightInKg = weight * 0.45359237;
        double heightInCm = height * 2.54;

        if (gender.equalsIgnoreCase("male")) {
            caloriesBurned = (met * 3.5 * weightInKg) / 200;
        } else if (gender.equalsIgnoreCase("female")) {
            caloriesBurned = (met * 3.5 * weightInKg) / 200 * 0.9;
        }

        long activityDuration = (System.currentTimeMillis() - startTime) / 1000;
        caloriesBurned *= (stepCount / 100.0) * (activityDuration / 3600.0);

        return caloriesBurned;
    }

    private List<UIUpdateCallback> uiUpdateCallbacks = new ArrayList<>();

    public interface UIUpdateCallback {
        void onHeartRateUpdate(int heartRate);
        void onStepCountUpdate(int stepCount);
        void onCaloriesUpdate(double calories);
        void onAltitudeUpdate(double altitude);
        void onGPSUpdate(String gpsInfo);
        void onTimerUpdate(String timerText);
    }

    public void registerUIUpdateCallback(UIUpdateCallback callback) {
        uiUpdateCallbacks.add(callback);
    }

    public void unregisterUIUpdateCallback(UIUpdateCallback callback) {
        uiUpdateCallbacks.remove(callback);
    }

    private void notifyUIUpdates() {
        for (UIUpdateCallback callback : uiUpdateCallbacks) {
            callback.onHeartRateUpdate(heartRate);
            callback.onStepCountUpdate(stepCount);
            callback.onCaloriesUpdate(calories);
            callback.onAltitudeUpdate(altitude);
            callback.onGPSUpdate(gpsInfo);
            callback.onTimerUpdate(timerText);
        }
    }

    private Runnable updateTimerRunnable = new Runnable() {
        @Override
        public void run() {
            long currentTime = System.currentTimeMillis();
            long elapsedTime = currentTime - startTime;

            int seconds = (int) (elapsedTime / 1000) % 60;
            int minutes = (int) ((elapsedTime / (1000 * 60)) % 60);
            int hours = (int) ((elapsedTime / (1000 * 60 * 60)) % 24);
            timerText = String.format("%02d:%02d:%02d", hours, minutes, seconds);
            addDataPoint("timer", timerText);

            handler.postDelayed(this, 1000);
        }
    };

    public boolean isMonitoring() {
        return isMonitoring;
    }

    private boolean isGpsEnabled() {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }
    private boolean hasGPSHardware() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
    }
}