package com.privacystudio.calculator;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static volatile boolean visible=false;
    public static boolean isShowing(){return visible;}
    @Override public void onCreate(Bundle savedInstanceState){
        registerPlugin(SecureRecorderPlugin.class);
        super.onCreate(savedInstanceState);
    }
    @Override public void onStart(){super.onStart();visible=true;RecorderService.appVisible(true);}
    @Override public void onStop(){visible=false;if(!PreviewActivity.isShowing())RecorderService.appVisible(false);super.onStop();}
}
