package com.example.documentscanner

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import nz.mega.documentscanner.DocumentScannerActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnPick).setOnClickListener {
            startActivity(Intent(this, DocumentScannerActivity::class.java))
        }
    }
}
