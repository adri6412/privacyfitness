package com.chinonso.wearos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import android.graphics.Bitmap;
import android.widget.ImageView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import android.graphics.Color;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity implements MonitoringService.UIUpdateCallback {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_BODY_SENSORS = 1;
    private static final int PERMISSION_REQUEST_LOCATION = 2;
    private static final int PERMISSION_REQUEST_ALL = 3;
    private ImageView qrCodeImageView;
    private TextView heartRateTextView, stepCountTextView, caloriesTextView, gpsTextView, altitudeTextView, timerTextView;
    private Spinner workoutSpinner;
    private Button startButton, userIdButton;

    private String userId;
    private int weight;
    private int height;
    private String gender;

    private boolean isMonitoringStarted = false;

    private MonitoringService monitoringService;
    private boolean isBound = false;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MonitoringService.LocalBinder binder = (MonitoringService.LocalBinder) service;
            monitoringService = binder.getService();
            isBound = true;
            monitoringService.registerUIUpdateCallback(MainActivity.this);

            String selectedWorkout = workoutSpinner.getSelectedItem().toString();
            monitoringService.setCurrentWorkout(selectedWorkout);

            // Check if monitoring is already started
            if (monitoringService.isMonitoring()) {
                isMonitoringStarted = true;
                updateUI();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            monitoringService.unregisterUIUpdateCallback(MainActivity.this);
        }
    };

    @SuppressLint("SetTextI18n")
    @Override
    public void onHeartRateUpdate(int heartRate) {
        heartRateTextView.setText("Heart Rate: " + heartRate + " bpm");
        Log.d(TAG, "Heart Rate updated: " + heartRate);
    }

    @Override
    public void onStepCountUpdate(int stepCount) {
        stepCountTextView.setText("Steps: " + stepCount);
        Log.d(TAG, "Step Count updated: " + stepCount);
    }

    @Override
    public void onCaloriesUpdate(double calories) {
        caloriesTextView.setText("Calories: " + String.format("%.2f", calories) + " kcal");
        Log.d(TAG, "Calories updated: " + calories);
    }

    @Override
    public void onAltitudeUpdate(double altitude) {
        altitudeTextView.setText("Altitude: " + String.format("%.2f", altitude) + " m");
        Log.d(TAG, "Altitude updated: " + altitude);
    }

    @Override
    public void onGPSUpdate(String gpsInfo) {
        gpsTextView.setText("GPS: " + gpsInfo);
        Log.d(TAG, "GPS updated: " + gpsInfo);
    }

    @Override
    public void onTimerUpdate(String timerText) {
        timerTextView.setText(timerText);
        Log.d(TAG, "Timer updated: " + timerText);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.round_activity_main);
        qrCodeImageView = findViewById(R.id.qrCodeImageView);
        Button showQRCodeButton = findViewById(R.id.showQRCodeButton);
        showQRCodeButton.setOnClickListener(v -> showQRCode());
        SharedPreferences sharedPreferences = getSharedPreferences("UserData", MODE_PRIVATE);
        if (!sharedPreferences.contains("userId")) {
            startActivity(new Intent(MainActivity.this, RegistrationActivity.class));
            finish();
            return;
        }

        userId = sharedPreferences.getString("userId", "");
        weight = sharedPreferences.getInt("weight", 0);
        height = sharedPreferences.getInt("height", 0);
        gender = sharedPreferences.getString("gender", "");

        timerTextView = findViewById(R.id.timerTextView);
        heartRateTextView = findViewById(R.id.heartRateTextView);
        stepCountTextView = findViewById(R.id.stepCountTextView);
        caloriesTextView = findViewById(R.id.caloriesTextView);
        gpsTextView = findViewById(R.id.gpsTextView);
        altitudeTextView = findViewById(R.id.altitudeTextView);
        workoutSpinner = findViewById(R.id.workoutSpinner);
        startButton = findViewById(R.id.startButton);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.workout_types, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        workoutSpinner.setAdapter(adapter);

        startButton.setOnClickListener(v -> toggleMonitoring());

        checkAndRequestPermissions();

        if (savedInstanceState != null) {
            isMonitoringStarted = savedInstanceState.getBoolean("isMonitoringStarted", false);
            updateUI();
        }
    }
    private void checkAndRequestPermissions() {
        boolean needBodySensors = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED;
        boolean needLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        boolean needActivityRecognition = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED;

        List<String> permissionsNeeded = new ArrayList<>();

        if (needBodySensors) {
            permissionsNeeded.add(Manifest.permission.BODY_SENSORS);
        }
        if (needLocation) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (needActivityRecognition) {
            permissionsNeeded.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_ALL);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case PERMISSION_REQUEST_ALL:
                boolean allGranted = true;
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    Log.d(TAG, "All permissions granted");
                    // Qui puoi abilitare le funzionalità che richiedono i permessi
                } else {
                    Log.d(TAG, "Some permissions were denied");
                    showPermissionExplanationDialog();
                }
                break;
        }
    }

    private void showPermissionExplanationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Permessi necessari")
                .setMessage("Questa app richiede l'accesso ai sensori del corpo e alla posizione per funzionare correttamente. Senza questi permessi, alcune funzionalità potrebbero non essere disponibili.")
                .setPositiveButton("Richiedi di nuovo", (dialog, which) -> checkAndRequestPermissions())
                .setNegativeButton("Annulla", null)
                .show();
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void toggleMonitoring() {
        if (isMonitoringStarted) {
            stopMonitoring();
            startButton.setText("Start");
        } else {
            if (checkPermissions()) {
                startMonitoring();
                startButton.setText("Stop");
            } else {
                checkAndRequestPermissions();
                return;
            }
        }
        isMonitoringStarted = !isMonitoringStarted;
    }
    private void startMonitoring() {
        String selectedWorkout = workoutSpinner.getSelectedItem().toString();
        Intent intent = new Intent(this, MonitoringService.class);
        intent.setAction("start");
        intent.putExtra("workoutType", selectedWorkout);
        startService(intent);
        bindService(intent, connection, BIND_AUTO_CREATE);
    }

    private void stopMonitoring() {
        Intent intent = new Intent(this, MonitoringService.class);
        intent.setAction("stop");
        startService(intent);
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }

    private void showUserIdDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("User ID");

        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        textView.setText(userId);
        textView.setPadding(20, 20, 20, 20);
        scrollView.addView(textView);

        builder.setView(scrollView);
        builder.setPositiveButton("OK", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.setOnKeyListener((dialogInterface, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                dialogInterface.dismiss();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            if (intent.getAction().equals(Intent.ACTION_MAIN)) {
                if (isMonitoringStarted) {
                    bindToMonitoringService();
                }
            }
        }
    }

    private void bindToMonitoringService() {
        Intent intent = new Intent(this, MonitoringService.class);
        bindService(intent, connection, BIND_AUTO_CREATE);
    }
    private void showQRCode() {
        SharedPreferences sharedPreferences = getSharedPreferences("UserData", MODE_PRIVATE);
        String userId = sharedPreferences.getString("userId", "");

        try {
            // Ottieni le dimensioni dello schermo
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            int screenWidth = displayMetrics.widthPixels;
            int screenHeight = displayMetrics.heightPixels;

            // Calcola la dimensione ottimale per il QR code (80% della dimensione minima dello schermo)
            int qrCodeSize = (int) (Math.min(screenWidth, screenHeight) * 0.8);

            Bitmap qrCodeBitmap = generateQRCode(userId, qrCodeSize);

            AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_qr_code, null);
            ImageView qrCodeImageView = dialogView.findViewById(R.id.qrCodeImageView);
            qrCodeImageView.setImageBitmap(qrCodeBitmap);

            builder.setView(dialogView);

            AlertDialog dialog = builder.create();
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.show();

            // Aggiungi un listener per chiudere il dialog quando l'utente tocca l'immagine
            qrCodeImageView.setOnClickListener(v -> dialog.dismiss());

        } catch (Exception e) {
            Log.e("MainActivity", "Error generating QR code", e);
            Toast.makeText(this, "Errore nella generazione del QR code", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap generateQRCode(String text, int size) throws Exception {
        BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }

        return bitmap;
    }
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isMonitoringStarted", isMonitoringStarted);
    }

    private void updateUI() {
        startButton.setText(isMonitoringStarted ? "Stop" : "Start");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isMonitoringStarted) {
            bindToMonitoringService();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
}