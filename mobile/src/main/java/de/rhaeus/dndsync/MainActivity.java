package de.rhaeus.dndsync;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity 
        extends AppCompatActivity {
        
    @Override
    protected void onCreate(
            Bundle savedInstanceState) {
            
        super.onCreate(savedInstanceState);
        setContentView(
            R.layout.activity_main
        );

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(
                    android.R.id.content, 
                    new MainFragment()
                )
                .commit();
        }
    }
}
