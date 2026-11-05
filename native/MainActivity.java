package com.privacystudio.calculator;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override public void onCreate(Bundle savedInstanceState){
        registerPlugin(SecureRecorderPlugin.class);
        super.onCreate(savedInstanceState);
    }
    @Override protected void onStart(){super.onStart();RecorderService.appVisible(true);}
    @Override protected void onStop(){RecorderService.appVisible(false);super.onStop();}
}
