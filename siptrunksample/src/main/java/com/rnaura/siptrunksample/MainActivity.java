package com.rnaura.siptrunksample;

import static net.gotev.sipservice.SipServiceConstants.ACTION_MAKE_CALL;
import static net.gotev.sipservice.SipServiceConstants.ACTION_REMOVE_ACCOUNT;
import static net.gotev.sipservice.SipServiceConstants.ACTION_SET_ACCOUNT;
import static net.gotev.sipservice.SipServiceConstants.PARAM_ACCOUNT_DATA;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import net.gotev.sipservice.SipAccountData;
import net.gotev.sipservice.SipService;

public class MainActivity extends AppCompatActivity {

    EditText etUsername, etPassword, etDomain;
    Button btnRegister,btnCall,btnRemove;

    String username ="",password ="",domain="";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private boolean permissionToRecordAccepted = false;
    private final String[] permissions = {Manifest.permission.RECORD_AUDIO,Manifest.permission.MODIFY_AUDIO_SETTINGS};
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION);


        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        etDomain = findViewById(R.id.etDomain);
        btnRegister = findViewById(R.id.btnRegister);
        btnCall = findViewById(R.id.btnCall);
        btnRemove = findViewById(R.id.btnRemove);

        btnRegister.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                 username = etUsername.getText().toString();
                 password = etPassword.getText().toString();
                 domain = etDomain.getText().toString();

                registerSIP(username, password, domain);
            }
        });

        btnCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                makeSIPCall("918894268540");
            }
        });

        btnRemove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                removeAccount();
            }
        });
    }

    private void registerSIP(String username, String password, String domain) {

        SipAccountData sipAccount = new SipAccountData();
        sipAccount.setUsername(username);
        sipAccount.setPassword(password);
        sipAccount.setRealm(domain);
        sipAccount.setHost(domain);

        Intent intent = new Intent(this, net.gotev.sipservice.SipService.class);
        intent.setAction(ACTION_SET_ACCOUNT);
        intent.putExtra(PARAM_ACCOUNT_DATA, sipAccount);
        startService(intent);


    }

    private void makeSIPCall(String phoneNumber) {
        // Format examples:
        // String destination = "sip:919876543210@sip.yourdomain.com";
        // or E.164:           "sip:+919876543210@sip.yourdomain.com";

        String domain = "144.202.4.201"; // <-- set your actual SIP domain here
        String destination = "sip:" + phoneNumber + "@" + domain;

        String accountId = "sip:" + etUsername.getText().toString() + "@" + domain;
        Intent callIntent = new Intent(this, net.gotev.sipservice.SipService.class);
        callIntent.setAction(ACTION_MAKE_CALL);

        callIntent.putExtra(SipService.PARAM_ACCOUNT_ID, accountId);
        callIntent.putExtra(SipService.PARAM_NUMBER, destination);
        callIntent.putExtra(SipService.PARAM_IS_VIDEO, false);

        startService(callIntent);
    }
    private void removeAccount() {
        String accountId = "sip:" + etUsername.getText().toString() + "@" + etDomain.getText().toString();
        Intent callIntent = new Intent(this, net.gotev.sipservice.SipService.class);
        callIntent.setAction(ACTION_REMOVE_ACCOUNT);
        callIntent.putExtra("accountID", accountId);
        startService(callIntent);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            permissionToRecordAccepted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!permissionToRecordAccepted) {
                // Permission denied
                Toast.makeText(this, "Permission to record audio denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}