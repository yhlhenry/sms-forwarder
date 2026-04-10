package com.example.smsforwarder

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.smsforwarder.databinding.ActivityTutorialBinding

class TutorialActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStartUsing.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }
}
