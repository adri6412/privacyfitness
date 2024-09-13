package com.chinonso.wearos;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RegistrationActivity extends AppCompatActivity {
    private static final String TAG = "RegistrationActivity";
    private static final String SERVER_URL = "http://10.0.1.23:3000";

    private EditText nameEditText;
    private EditText weightEditText;
    private EditText heightEditText;
    private Spinner genderSpinner;
    private Button registerButton;

    private OkHttpClient client = new OkHttpClient();
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);

        nameEditText = findViewById(R.id.nameEditText);
        weightEditText = findViewById(R.id.weightEditText);
        heightEditText = findViewById(R.id.heightEditText);
        genderSpinner = findViewById(R.id.genderSpinner);
        registerButton = findViewById(R.id.registerButton);

        registerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                registerUser();
            }
        });
    }

    private void registerUser() {
        String userId = UUID.randomUUID().toString();
        String name = nameEditText.getText().toString();
        int weight = Integer.parseInt(weightEditText.getText().toString());
        int height = Integer.parseInt(heightEditText.getText().toString());
        String gender = genderSpinner.getSelectedItem().toString();

        SharedPreferences sharedPreferences = getSharedPreferences("UserData", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("userId", userId);
        editor.putString("name", name);
        editor.putInt("weight", weight);
        editor.putInt("height", height);
        editor.putString("gender", gender);
        editor.apply();

        sendUserDataToServer(userId, name, weight, height, gender);

        startActivity(new Intent(RegistrationActivity.this, MainActivity.class));
        finish();
    }

    private void sendUserDataToServer(String userId, String name, int weight, int height, String gender) {
        JSONObject json = new JSONObject();
        try {
            json.put("userId", userId);
            json.put("name", name);
            json.put("weight", weight);
            json.put("height", height);
            json.put("gender", gender);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON", e);
            return;
        }

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder()
                .url(SERVER_URL + "/api/register")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Error sending user data to server", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Unexpected code " + response);
                } else {
                    Log.d(TAG, "User data sent successfully");
                }
            }
        });
    }
}