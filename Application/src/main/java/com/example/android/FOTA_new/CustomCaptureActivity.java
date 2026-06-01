package com.example.android.FOTA_new;


import android.os.Bundle;

import android.widget.RelativeLayout;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.ViewfinderView;


public class CustomCaptureActivity extends CaptureActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Find the ViewfinderView which is the scanning area
        ViewfinderView viewfinderView = findViewById(R.id.viewfinder_view);

        if (viewfinderView != null) {
            // Modify the size of the scanning area (e.g., 200dp x 200dp)
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                    (int) getResources().getDimension(android.R.dimen.app_icon_size), // Convert dp to px
                    (int) getResources().getDimension(android.R.dimen.app_icon_size)  // Convert dp to px
            );

            params.addRule(RelativeLayout.CENTER_IN_PARENT);  // Center the scanning area
            viewfinderView.setLayoutParams(params);  // Apply the new layout parameters
        }

    }



}
